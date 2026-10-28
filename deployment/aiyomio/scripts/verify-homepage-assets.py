"""Verify deployed homepage assets over HTTPS, using a range request for video."""
import argparse
import hashlib
import json
import urllib.request
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--manifest', required=True)
parser.add_argument('--base-url', default='https://aiyomio.cn')
parser.add_argument('--output', required=True)
args = parser.parse_args()
assert args.base_url.startswith('https://')
manifest = json.loads(Path(args.manifest).read_text())
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
checks = []
for name, expected in manifest['files'].items():
    headers = {'Cache-Control': 'no-cache'}
    video = name.endswith('.mp4')
    if video:
        headers['Range'] = 'bytes=0-1023'
    request = urllib.request.Request(args.base_url.rstrip('/') + '/' + name, headers=headers)
    with opener.open(request, timeout=60) as response:
        data = response.read()
        assert response.status == (206 if video else 200), name
        assert hashlib.sha256(data).hexdigest() == expected['rangeSha256' if video else 'sha256'], name
        if video:
            assert response.headers['Content-Range'] == 'bytes 0-1023/' + str(expected['size']), name
            assert response.headers.get_content_type() == 'video/mp4', name
        elif name.endswith('.png'):
            assert response.headers.get_content_type() == 'image/png', name
        checks.append({'path': '/' + name, 'status': response.status, 'bytesChecked': len(data)})
Path(args.output).write_text(json.dumps({'passed': True, 'checks': checks}, indent=2), encoding='utf-8')
print(json.dumps({'passed': True, 'assetChecks': len(checks), 'videoRangePassed': True}))
