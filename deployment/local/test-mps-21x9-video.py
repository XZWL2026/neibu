import argparse
import importlib.util
import json
import pathlib
import uuid
import time
import struct
import urllib.request

CODE_ROOT = pathlib.Path(__file__).resolve().parent
ROOT = CODE_ROOT / 'target'
spec = importlib.util.spec_from_file_location('mps_local', CODE_ROOT / 'configure-mps-21x9.py')
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)
OUT = ROOT / 'aiyomio-local-video-results.json'

def user_login():
    session = api.call('/api/v1/auth/session')
    try:
        api.call('/api/v1/auth/otp/send', {'phone': '19900000001'}, session['csrfToken'])
    except RuntimeError as error:
        if 'HTTP 429:' not in str(error):
            raise
    api.call('/api/v1/auth/otp/login', {'phone': '19900000001', 'code': '123456'}, session['csrfToken'])
    session = api.call('/api/v1/auth/session')
    assert session['authenticated']
    return session['csrfToken']

def inspect():
    csrf = user_login()
    options = api.call('/api/v1/media/generation-options')
    print(json.dumps(options, ensure_ascii=False, indent=2))
    api.call('/api/v1/auth/logout', {}, csrf)

def mp4_metadata(data):
    info = {'videoDimensions': []}
    def walk(start, end):
        offset = start
        while offset + 8 <= end:
            size, kind = struct.unpack_from('>I4s', data, offset)
            header = 8
            if size == 1:
                size = struct.unpack_from('>Q', data, offset + 8)[0]
                header = 16
            elif size == 0:
                size = end - offset
            assert size >= header and offset + size <= end, 'Invalid MP4 atom'
            payload = offset + header
            if kind in [b'moov', b'trak']:
                walk(payload, offset + size)
            elif kind == b'tkhd':
                position = payload + (88 if data[payload] == 1 else 76)
                width, height = struct.unpack_from('>II', data, position)
                width, height = width / 65536, height / 65536
                if width and height:
                    info['videoDimensions'].append([int(width), int(height)])
            elif kind == b'mvhd':
                version = data[payload]
                scale = struct.unpack_from('>I', data, payload + (20 if version == 1 else 12))[0]
                duration = struct.unpack_from('>Q' if version == 1 else '>I', data, payload + (24 if version == 1 else 16))[0]
                info['durationSeconds'] = duration / scale
            offset += size
    walk(0, len(data))
    return info

def verify_output():
    evidence = json.loads(OUT.read_text(encoding='utf-8'))
    csrf = api.login()
    diagnostic = api.call('/admin/api/v1/admin/media/tasks/' + evidence['taskId'])
    assert diagnostic['task']['status'] == 'SUCCEEDED', diagnostic['task']['status']
    enhancement = diagnostic['enhancement']
    assert int(enhancement['scheduleId']) == 108415
    assert len(enhancement['steps']) == 1
    step = enhancement['steps'][0]
    assert step['definitionId'] == 1782620 and (step['width'], step['height']) == (1470, 630)
    file_id = diagnostic['task']['results'][0]['fileId']
    access = api.call('/admin/api/v1/admin/media/tasks/' + evidence['taskId'] + '/files/' + file_id + '/access-url')
    url = access['url']
    assert url.startswith('https://')
    downloader = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with downloader.open(url, timeout=45) as response:
        data = response.read(30 * 1024 * 1024 + 1)
        assert response.status == 200 and 0 < len(data) <= 30 * 1024 * 1024
    metadata = mp4_metadata(data)
    assert metadata['videoDimensions'] == [[1470, 630]], metadata
    assert 3.5 <= metadata['durationSeconds'] <= 4.5, metadata
    sample = ROOT / 'aiyomio-21x9-720p-smoke.mp4'
    sample.write_bytes(data)
    evidence.update(diagnostic=diagnostic, verifiedOutput={**metadata, 'sizeBytes': len(data), 'fileId': file_id, 'localSample': str(sample)}, passed=True)
    OUT.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(evidence['verifiedOutput'], ensure_ascii=False, indent=2))
    api.call('/admin/api/v1/auth/logout', {}, csrf)

def run(action):
    if action == 'verify-output':
        return verify_output()
    if action == 'inspect':
        return inspect()
    csrf = user_login()
    if OUT.exists():
        evidence = json.loads(OUT.read_text(encoding='utf-8'))
    else:
        evidence = {'localBase': api.BASE, 'idempotencyKey': 'local-21x9-mps-' + str(uuid.uuid4()), 'request': {'type': 'VIDEO', 'model': 'seedance-2.0', 'prompt': 'A calm cinematic ultra-wide landscape of mountains and a lake at sunrise. Slow smooth camera movement. No people, no text.', 'aspectRatio': '21:9', 'resolution': '720p', 'count': 1, 'durationSeconds': 4, 'references': [], 'referenceMode': 'FIRST_LAST_FRAME'}}
    if action in ['submit', 'watch'] and not evidence.get('taskId'):
        quote_request = {key: value for key, value in evidence['request'].items() if key != 'prompt'}
        evidence['quote'] = api.call('/api/v1/media/quotes', quote_request, csrf)
        OUT.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
        task = api.call('/api/v1/media/tasks', evidence['request'], csrf, timeout=60, extra_headers={'Idempotency-Key': evidence['idempotencyKey']})
        evidence['taskId'] = task['id']
        evidence['initialTask'] = task
        OUT.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
    assert evidence.get('taskId'), 'No submitted smoke task'
    admin_csrf = api.login()
    deadline = time.monotonic() + 1800
    previous = None
    while True:
        task = api.call('/api/v1/media/tasks/' + evidence['taskId'])
        evidence['task'] = task
        diagnostic = api.call('/admin/api/v1/admin/media/tasks/' + evidence['taskId'])
        evidence['diagnostic'] = diagnostic
        OUT.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
        summary = {'taskId': evidence['taskId'], 'quote': evidence.get('quote'), 'status': task['status'], 'results': task.get('results'), 'generation': diagnostic.get('generation'), 'enhancement': diagnostic.get('enhancement')}
        serialized = json.dumps(summary, ensure_ascii=False, indent=2)
        if serialized != previous:
            print(serialized, flush=True)
            previous = serialized
        if action != 'watch' or task['status'] in ['COMPLETED', 'SUCCEEDED', 'FAILED', 'REFUNDED', 'TIMED_OUT', 'REFUND_FAILED'] or time.monotonic() >= deadline:
            break
        time.sleep(20)
    api.call('/admin/api/v1/auth/logout', {}, admin_csrf)
    api.call('/api/v1/auth/logout', {}, csrf)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['inspect', 'submit', 'poll', 'watch', 'verify-output'])
    run(parser.parse_args().action)
