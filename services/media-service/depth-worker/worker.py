#!/usr/bin/env python3
"""Local authenticated depth executor. Java's durable media tasks own the waiting queue."""
import argparse
import hmac
import json
import math
import os
import re
import shutil
import subprocess
import threading
import time
from fractions import Fraction
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MAX_BYTES = 200 * 1024 * 1024
IDENTIFIER = re.compile(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\Z')
IDENTITY = ('owner', 'sourceFileId', 'resolution')


def validate_selection(duration, resolution):
    if not math.isfinite(duration) or duration <= 0 or resolution not in ('480p', '720p'):
        raise ValueError('invalid depth selection')
    if duration > 30 and resolution != '480p':
        raise ValueError('videos over 30 seconds require 480p')


def inspect_video(path):
    result = subprocess.run([os.environ.get('DEPTH_FFPROBE', 'ffprobe'), '-v', 'error',
        '-protocol_whitelist', 'file', '-select_streams', 'v:0', '-show_streams', '-of', 'json', str(path)],
        capture_output=True, check=True, timeout=30)
    streams = json.loads(result.stdout)['streams']
    if not streams: raise ValueError('video stream missing')
    stream = streams[0]
    duration = float(stream['duration'])
    width, height = int(stream['width']), int(stream['height'])
    if min(width, height) < 2 or max(width, height) > 8192 or width * height > 36_000_000:
        raise ValueError('video dimensions are unsupported')
    rate = Fraction(stream['avg_frame_rate'])
    if rate <= 0: raise ValueError('video frame rate is unavailable')
    rotation = next((float(item['rotation']) for item in stream.get('side_data_list', []) if 'rotation' in item), 0)
    if not math.isclose(rotation % 90, 0, abs_tol=.001):
        raise ValueError('video rotation must be a multiple of 90 degrees')
    sar_text = stream.get('sample_aspect_ratio', '1:1')
    sar = Fraction(sar_text.replace(':', '/')) if sar_text not in ('N/A', '0:1') else Fraction(1)
    return dict(duration=duration, width=width, height=height, rate=rate, rotation=round(rotation / 90), sar=sar)


class Jobs:
    def __init__(self, root):
        self.root = root.resolve()
        # ponytail: retain completed local jobs for replay; add bounded retention before production use.
        self.root.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.uploading = False
        for name in ('input.part', 'output.part.mp4'):
            for partial in self.root.glob('*/' + name):
                if self.folder(partial.parent.name) == partial.parent: partial.unlink(missing_ok=True)
        for state in self.root.glob('*/state.json'):
            record = json.loads(state.read_text())
            if record['status'] in ('RUNNING', 'QUEUED'):
                record['status'] = 'QUEUED' if (state.parent / 'input.mp4').is_file() else 'FAILED'
                self.write(state.parent.name, record)

    def folder(self, job):
        if not IDENTIFIER.fullmatch(job): raise ValueError('invalid job ID')
        folder = self.root / job
        if folder.is_symlink() or folder.resolve().parent != self.root:
            raise ValueError('invalid job path')
        return folder

    def lookup(self, job, identity=None):
        with self.lock:
            path = self.folder(job) / 'state.json'
            if not path.exists(): return None
            record = json.loads(path.read_text())
            if identity and any(record.get(key) != identity.get(key) for key in IDENTITY):
                raise ValueError('job identity conflict')
            return record

    def write(self, job, record):
        with self.lock:
            folder = self.folder(job)
            folder.mkdir(exist_ok=True)
            temporary = folder / 'state.tmp'
            with temporary.open('w') as output:
                json.dump(record, output)
                output.flush()
                os.fsync(output.fileno())
            temporary.replace(folder / 'state.json')

    def pending(self):
        return [p.parent.name for p in self.root.glob('*/state.json')
                if self.lookup(p.parent.name)['status'] in ('QUEUED', 'RUNNING')]


class DepthModel:
    def __init__(self, path, device):
        import cv2
        import torch
        from transformers import AutoImageProcessor, AutoModelForDepthEstimation
        if device == 'auto': device = 'cuda' if torch.cuda.is_available() else 'mps' if torch.backends.mps.is_available() else 'cpu'
        self.device = device
        torch.set_num_threads(4)
        cv2.setNumThreads(4)
        self.processor = AutoImageProcessor.from_pretrained(path, local_files_only=True, use_fast=False)
        self.model = AutoModelForDepthEstimation.from_pretrained(path, local_files_only=True).to(device).eval()

    def infer(self, rgb):
        import cv2
        import torch
        # Bound inference pixels even for unusual display ratios; retain the full frame.
        height, width = rgb.shape[:2]
        scale = min(518 / min(width, height), 1022 / max(width, height))
        size = (max(28, round(width * scale / 14) * 14), max(28, round(height * scale / 14) * 14))
        inputs = self.processor(images=cv2.resize(rgb, size), do_resize=False, return_tensors='pt').to(self.device)
        with torch.inference_mode():
            return self.model(**inputs).predicted_depth[0].float().cpu().numpy()


def render_video(source, target, resolution, model):
    import av
    import cv2
    import numpy as np
    metadata = inspect_video(source)
    validate_selection(metadata['duration'], resolution)
    width, height = metadata['width'], metadata['height']
    width, height = width * metadata['sar'], Fraction(height)
    if metadata['rotation'] % 2: width, height = height, width
    display_ratio = width / height
    # Short-edge convention, no upscaling. SAR preserves exact display ratio after even-pixel rounding.
    short = min(int(resolution[:-1]), math.floor(min(width, height)))
    if display_ratio >= 1: out_height = max(2, short // 2 * 2); out_width = max(2, round(out_height * float(display_ratio) / 2) * 2)
    else: out_width = max(2, short // 2 * 2); out_height = max(2, round(out_width / float(display_ratio) / 2) * 2)
    if max(out_width, out_height) > 8192 or out_width * out_height > 36_000_000:
        raise ValueError('display dimensions are unsupported')
    sar = display_ratio / Fraction(out_width, out_height)
    previous_gray = previous_depth = levels = None
    flow_engine = cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_FAST)
    yy, xx = np.mgrid[:out_height, :out_width].astype(np.float32)
    frames = 0
    with av.open(str(source)) as incoming, av.open(str(target), 'w', format='mp4', options={'movflags': '+faststart'}) as output:
        stream = incoming.streams.video[0]
        encoded = output.add_stream('libx264', rate=metadata['rate'])
        encoded.width, encoded.height, encoded.pix_fmt = out_width, out_height, 'yuv420p'
        encoded.codec_context.time_base = stream.time_base
        encoded.time_base = stream.time_base
        encoded.codec_context.sample_aspect_ratio = sar
        encoded.codec_context.max_b_frames = 0
        encoded.options = {'preset': 'fast', 'crf': '18', 'tune': 'zerolatency'}
        durations = {}
        origin = None
        last_time = None
        def mux(packets):
            for packet in packets:
                timestamp = packet.pts * packet.time_base
                if timestamp in durations:
                    packet.duration = round(durations.pop(timestamp) / packet.time_base)
                output.mux(packet)
        for frame in incoming.decode(stream):
            if frame.pts is None: raise ValueError('source frame timestamp is missing')
            timestamp = frame.pts * frame.time_base
            if origin is None: origin = timestamp
            timestamp -= origin
            if last_time is not None and timestamp <= last_time: raise ValueError('source frame timestamps are not increasing')
            last_time = timestamp
            rgb = frame.to_ndarray(format='rgb24')
            if metadata['rotation'] % 4: rgb = np.ascontiguousarray(np.rot90(rgb, metadata['rotation'] % 4))
            rgb = cv2.resize(rgb, (out_width, out_height), interpolation=cv2.INTER_AREA)
            depth = model.infer(rgb)
            if not np.isfinite(depth).all(): raise ValueError('model returned non-finite depth')
            current_levels = np.percentile(depth, [1, 99]).astype(np.float32)
            gray = cv2.cvtColor(cv2.resize(rgb, (256, 144)), cv2.COLOR_RGB2GRAY)
            cut = previous_gray is None or np.mean(np.abs(gray.astype(np.float32) - previous_gray.astype(np.float32))) > 35
            # ponytail: shot-local exponential range smoothing; a video-depth model can replace this if temporal quality requires it.
            levels = current_levels if cut else levels * .85 + current_levels * .15
            depth = np.clip((depth - levels[0]) / max(float(levels[1] - levels[0]), 1e-6), 0, 1)
            depth = cv2.resize(depth, (out_width, out_height), interpolation=cv2.INTER_CUBIC)
            if not cut:
                flow = flow_engine.calc(gray, previous_gray, None)
                flow = cv2.resize(flow, (out_width, out_height))
                flow[:, :, 0] *= out_width / 256; flow[:, :, 1] *= out_height / 144
                warped = cv2.remap(previous_depth, xx + flow[:, :, 0], yy + flow[:, :, 1], cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
                blend = .2 * np.clip(1 - np.abs(warped - depth) / .08, 0, 1)
                depth = depth * (1 - blend) + warped * blend
            previous_gray, previous_depth = gray, depth
            result = av.VideoFrame.from_ndarray(np.uint8(np.clip(depth, 0, 1) * 255 + .5), format='gray')
            result.pts = round(timestamp / stream.time_base)
            result.time_base = stream.time_base
            duration = frame.duration * frame.time_base if frame.duration else 1 / metadata['rate']
            durations[timestamp] = duration
            result.duration = round(duration / stream.time_base)
            mux(encoded.encode(result))
            frames += 1
        mux(encoded.encode(None))
    if not frames: raise ValueError('no video frames decoded')
    return {'frames': frames, 'width': out_width, 'height': out_height, 'duration': metadata['duration']}


class Executor:
    def __init__(self, jobs, model):
        self.jobs, self.model = jobs, model
        self.stopping = threading.Event()
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.thread.start()

    def run(self):
        while not self.stopping.wait(.1):
            for job in self.jobs.pending():
                record = self.jobs.lookup(job)
                if record['status'] != 'QUEUED': continue
                record['status'] = 'RUNNING'; self.jobs.write(job, record)
                folder = self.jobs.folder(job)
                try:
                    render_video(folder / 'input.mp4', folder / 'output.part.mp4', record['resolution'], self.model)
                    (folder / 'output.part.mp4').replace(folder / 'output.mp4')
                    record['status'] = 'COMPLETE'
                except Exception:
                    # No signed URLs, paths, model internals or tokens in public diagnostics.
                    (folder / 'output.part.mp4').unlink(missing_ok=True)
                    record['status'] = 'FAILED'
                self.jobs.write(job, record)


def server(jobs, token, port):
    if len(token) < 32: raise ValueError('DEPTH_TOKEN must contain at least 32 characters')
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def reply(self, status, value):
            body = json.dumps(value).encode()
            self.send_response(status); self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
        def authorized(self):
            if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + token):
                self.reply(401, {'error': 'unauthorized'}); return False
            return True
        def do_GET(self):
            if not self.authorized(): return
            if self.path == '/health': return self.reply(200, {'ready': True})
            match = re.fullmatch(r'/jobs/([A-Za-z0-9_.-]+)(/result)?', self.path)
            if not match: return self.reply(404, {'error': 'not found'})
            try:
                job, result = match.groups(); record = jobs.lookup(job)
                if record is None: return self.reply(404, {'error': 'not found'})
                if not result: return self.reply(200, record)
                if record['status'] != 'COMPLETE': return self.reply(409, {'error': 'not complete'})
                path = jobs.folder(job) / 'output.mp4'
                self.send_response(200); self.send_header('Content-Type', 'video/mp4')
                self.send_header('Content-Length', str(path.stat().st_size)); self.end_headers()
                with path.open('rb') as body: shutil.copyfileobj(body, self.wfile)
            except ValueError: self.reply(400, {'error': 'invalid request'})
        def consume(self, size, target=None):
            self.connection.settimeout(30)
            while size:
                chunk = self.rfile.read(min(65536, size))
                if not chunk: raise ValueError('incomplete upload')
                if target: target.write(chunk)
                size -= len(chunk)
        def do_POST(self):
            if not self.authorized(): return
            match = re.fullmatch(r'/jobs/([A-Za-z0-9_.-]+)', self.path)
            if not match: return self.reply(404, {'error': 'not found'})
            job = match[1]
            identity = {key: self.headers.get(header, '') for key, header in zip(IDENTITY, ('X-Owner-Id', 'X-Source-File-Id', 'X-Resolution'))}
            try:
                if not all(IDENTIFIER.fullmatch(identity[key]) for key in ('owner', 'sourceFileId')): raise ValueError()
                validate_selection(1, identity['resolution'])
                size = int(self.headers.get('Content-Length', '0'))
                if size <= 0 or size > MAX_BYTES or self.headers.get('Content-Type') not in ('video/mp4', 'video/quicktime'): raise ValueError()
                folder = jobs.folder(job)
            except ValueError: return self.reply(400, {'error': 'invalid request'})
            reply = None
            with jobs.lock:
                try:
                    existing = jobs.lookup(job, identity)
                    if existing: reply = (202, existing)
                    elif jobs.uploading or jobs.pending(): reply = (503, {'error': 'busy'})
                    else: jobs.uploading = True
                except ValueError: reply = (409, {'error': 'job identity conflict'})
            if reply:
                # Consume bounded retry bodies before responding; early close breaks large HTTP replays.
                try: self.consume(size); self.reply(*reply)
                except (OSError, ValueError): self.reply(400, {'error': 'incomplete request'})
                return
            try:
                folder.mkdir(exist_ok=True)
                with (folder / 'input.part').open('wb') as target:
                    self.consume(size, target)
                    target.flush(); os.fsync(target.fileno())
                metadata = inspect_video(folder / 'input.part')
                validate_selection(metadata['duration'], identity['resolution'])
                (folder / 'input.part').replace(folder / 'input.mp4')
                record = dict(identity, status='QUEUED')
                jobs.write(job, record)
                self.reply(202, record)
            except (ValueError, OSError, KeyError, subprocess.SubprocessError):
                self.reply(422, {'error': 'invalid video or resolution'})
            finally:
                try: (folder / 'input.part').unlink(missing_ok=True)
                finally:
                    with jobs.lock: jobs.uploading = False
    return ThreadingHTTPServer(('127.0.0.1', port), Handler)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model', required=True)
    parser.add_argument('--jobs', required=True, type=Path)
    parser.add_argument('--device', choices=('auto', 'cpu', 'mps', 'cuda'), default='auto')
    parser.add_argument('--port', type=int, default=18727)
    args = parser.parse_args()
    os.umask(0o077)
    args.jobs.mkdir(parents=True, exist_ok=True)
    import fcntl
    with (args.jobs / '.executor.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        model = DepthModel(args.model, args.device)
        jobs = Jobs(args.jobs)
        executor = Executor(jobs, model)
        service = server(jobs, os.environ.get('MEDIA_DEPTH_TOKEN', ''), args.port)
        print(json.dumps({'ready': True, 'device': model.device, 'port': args.port}), flush=True)
        try: service.serve_forever()
        finally: executor.stopping.set(); service.server_close()

if __name__ == '__main__': main()
