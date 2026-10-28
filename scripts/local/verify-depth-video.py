#!/usr/bin/env python3
"""Real model/HTTP/timeline smoke test; artifacts stay in an isolated temporary directory."""
import argparse
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from fractions import Fraction

ROOT = Path(__file__).resolve().parents[2]

def run(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout

def probe(path):
    return json.loads(run('ffprobe', '-v', 'error', '-show_streams', '-show_frames', '-select_streams', 'v:0',
                         '-show_entries', 'stream=width,height,sample_aspect_ratio,display_aspect_ratio,duration,avg_frame_rate:stream_side_data=rotation:frame=best_effort_timestamp_time', '-of', 'json', str(path)))

def verify(source, result, resolution):
    before, after = probe(source), probe(result)
    first, second = before['streams'][0], after['streams'][0]
    assert len(before['frames']) == len(after['frames']) > 0, 'frame count changed'
    a = [float(frame['best_effort_timestamp_time']) for frame in before['frames']]
    b = [float(frame['best_effort_timestamp_time']) for frame in after['frames']]
    assert all(abs((x - a[0]) - (y - b[0])) < .00002 for x, y in zip(a, b)), 'frame timestamps changed'
    assert abs(float(first['duration']) - float(second['duration'])) < .0001, (first, second)
    def ratio(stream):
        sar = stream.get('sample_aspect_ratio', '1:1')
        return Fraction(stream['width'], stream['height']) * (Fraction(sar.replace(':', '/')) if sar != 'N/A' else 1)
    expected = ratio(first)
    rotation = next((item.get('rotation', 0) for item in first.get('side_data_list', []) if 'rotation' in item), 0)
    if round(rotation / 90) % 2: expected = 1 / expected
    assert ratio(second) == expected, 'display aspect changed'
    source_width = Fraction(first['height']) * ratio(first)
    source_height = Fraction(first['height'])
    if round(rotation / 90) % 2: source_width, source_height = source_height, source_width
    assert min(second['width'], second['height']) <= min(source_width, source_height), 'source display was upscaled'
    assert min(second['width'], second['height']) <= int(resolution[:-1])
    streams = json.loads(run('ffprobe', '-v', 'error', '-show_entries', 'stream=codec_type', '-of', 'json', str(result)))['streams']
    assert [stream['codec_type'] for stream in streams] == ['video'], 'output contains audio'
    return {'frames': len(a), 'duration': second['duration'], 'size': f"{second['width']}x{second['height']}", 'fps': second['avg_frame_rate'], 'silent': True}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--worker-python', required=True)
    parser.add_argument('--model', required=True)
    parser.add_argument('--input', type=Path)
    parser.add_argument('--output-dir', type=Path)
    args = parser.parse_args()
    directory = args.output_dir or Path(tempfile.mkdtemp(prefix='xzwl-depth-check-'))
    directory.mkdir(parents=True, exist_ok=True)
    token = secrets.token_urlsafe(32)
    with socket.socket() as s: s.bind(('127.0.0.1', 0)); port = s.getsockname()[1]
    base = f'http://127.0.0.1:{port}'
    def request(path, body=None, headers=None, auth=True):
        supplied = {'Authorization': 'Bearer ' + token} if auth else {}
        supplied.update(headers or {})
        try:
            with urllib.request.urlopen(urllib.request.Request(base + path, data=body, headers=supplied), timeout=60) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            with error: return error.code, error.read()
    log = (directory / 'worker.log').open('w')
    command = [args.worker_python, str(ROOT / 'services/media-service/depth-worker/worker.py'), '--model', args.model, '--jobs', str(directory / 'jobs'), '--port', str(port)]
    def start():
        process = subprocess.Popen(command, env={**os.environ, 'MEDIA_DEPTH_TOKEN': token, 'PYTHONDONTWRITEBYTECODE': '1'}, stdout=log, stderr=log)
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if process.poll() is not None: raise RuntimeError('worker startup failed; inspect ' + str(directory / 'worker.log'))
            try:
                if request('/health')[0] == 200: return process
            except OSError: pass
            time.sleep(.2)
        raise RuntimeError('worker startup timeout')
    process = None
    report = {}
    try:
        if args.input:
            cases = [(args.input, '480p')]
        else:
            cases = []
            for name, size, rate, duration, resolution in [('short', '1280x720', '3', '2', '720p'), ('long', '640x480', '2', '31', '480p'), ('portrait', '480x854', '3', '2', '480p')]:
                path = directory / f'{name}.mp4'
                run('ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', f'testsrc2=size={size}:rate={rate}', '-f', 'lavfi', '-i', 'sine=frequency=440', '-t', duration, '-c:v', 'libx264', '-threads', '1', '-pix_fmt', 'yuv420p', '-c:a', 'aac', str(path))
                cases.append((path, resolution))
            vfr = directory / 'vfr-sar.mp4'
            run('ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=720x576:rate=30000/1001:duration=2', '-vf', "select='not(mod(n,3))+not(mod(n,7))',setsar=16/15", '-fps_mode', 'vfr', '-c:v', 'libx264', '-threads', '1', str(vfr))
            rotated = directory / 'vfr-rotated.mp4'
            run('ffmpeg', '-v', 'error', '-y', '-display_rotation:v:0', '90', '-i', str(vfr), '-c', 'copy', str(rotated))
            assert any(item.get('rotation') == 90 for item in probe(rotated)['streams'][0].get('side_data_list', [])), 'rotation fixture is missing metadata'
            cases.append((rotated, '480p'))
            narrow = directory / 'sar-half.mp4'
            run('ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=640x480:rate=2:duration=1', '-vf', 'setsar=1/2', '-c:v', 'libx264', '-threads', '1', str(narrow))
            cases.append((narrow, '480p'))
        process = start()
        assert request('/health', auth=False)[0] == 401
        assert request('/jobs/../state.json')[0] == 404
        for index, (source, resolution) in enumerate(cases):
            job = f'smoke-{index}'
            headers = {'Content-Type': 'video/mp4', 'X-Owner-Id': 'local-check', 'X-Source-File-Id': f'file-{index}', 'X-Resolution': resolution}
            payload = source.read_bytes()
            status, body = request('/jobs/' + job, payload, headers)
            assert status == 202, (status, body)
            if index == 1:
                assert request('/jobs/busy-probe', payload, headers)[0] == 503, 'native queue must remain bounded'
            if index == 1:  # restart a real accepted long job, not just a fabricated state file
                deadline = time.monotonic() + 60
                while time.monotonic() < deadline:
                    _, body = request('/jobs/' + job)
                    if json.loads(body)['status'] == 'RUNNING': break
                    time.sleep(.1)
                process.terminate(); process.wait(timeout=20)
                process = start()
            deadline = time.monotonic() + 900
            while time.monotonic() < deadline:
                status, body = request('/jobs/' + job)
                state = json.loads(body)
                if state.get('status') == 'COMPLETE': break
                assert state.get('status') != 'FAILED', ('native render failed', source.name, directory / 'worker.log')
                time.sleep(.2)
            else: raise RuntimeError('render timed out')
            status, output = request('/jobs/' + job + '/result')
            assert status == 200
            result = directory / f'{source.stem}-depth-{resolution}.mp4'
            result.write_bytes(output)
            report[source.name] = verify(source, result, resolution)
            if float(probe(source)['streams'][0]['duration']) > 30:
                assert request('/jobs/rejected-' + job, payload, {**headers, 'X-Resolution': '720p'})[0] == 422, 'native long-video limit was bypassed'
            assert request('/jobs/' + job, payload, headers)[0] == 202, 'idempotent replay failed'
            assert request('/jobs/' + job, payload, {**headers, 'X-Owner-Id': 'other'})[0] == 409
            print(json.dumps({source.name: report[source.name]}, ensure_ascii=False), flush=True)
        (directory / 'verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print('PASS; artifacts: ' + str(directory), flush=True)
    finally:
        if process is not None and process.poll() is None: process.terminate(); process.wait(timeout=20)
        log.close()

if __name__ == '__main__': main()
