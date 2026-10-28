"""Run on aiyomio EDGE after uploading the bundle, manifest and Nginx snippet."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tarfile
import time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--bundle-dir', required=True)
args = parser.parse_args()
bundle = Path(args.bundle_dir).resolve()
assert str(bundle).startswith('/root/xzwl-release/homepage-assets-')
manifest = json.loads((bundle / 'manifest.json').read_text())
release_id = '20261009-' + manifest['sourceCommit'][:8]
releases = Path('/opt/xzwl/homepage-asset-releases')
release, pointer = releases / release_id, releases / 'current'
conf = Path('/etc/nginx/conf.d/aiyomio.conf')
snippet = Path('/etc/nginx/snippets/aiyomio-homepage-assets.conf')
original = conf.read_bytes()
old_snippet = snippet.read_bytes() if snippet.exists() else None
old_pointer = os.readlink(pointer) if pointer.is_symlink() else None
assert not pointer.exists() or pointer.is_symlink()
assert not release.exists() or release.is_dir() and not release.is_symlink()
required_bytes = 150 * 1024 * 1024 + (0 if release.exists() else manifest['totalBytes'])
assert shutil.disk_usage('/opt/xzwl').free > required_bytes
pattern = r'    location /homepage-v2/ \{\n        root /opt/xzwl/frontend-releases/20261003-nav-backdrop-232d14f1/sms/user;\n        try_files \$uri =404;\n    \}'
changed, count = re.subn(pattern, '    include /etc/nginx/snippets/aiyomio-homepage-assets.conf;', original.decode())
assert count == 1, 'Expected obsolete homepage asset mapping exactly once'

def sha(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()

def run(*argv):
    return subprocess.check_output(argv, text=True).strip()

def pids():
    return {row.split()[0]: run('systemctl', 'show', row.split()[0], '-p', 'MainPID', '--value')
            for row in run('systemctl', 'list-units', '--type=service', '--state=running', '--no-legend', '--plain', 'xzwl-*').splitlines()}

frontend = Path('/opt/xzwl/current/frontends')
state = {'sourceCommit': manifest['sourceCommit'], 'release': str(release), 'previousAssetPointer': old_pointer,
         'frontendPointer': str(frontend.resolve()), 'userIndexSha256': sha(frontend/'user/index.html'),
         'adminIndexSha256': sha(frontend/'admin/index.html'), 'backendPidsBefore': pids()}
(bundle / 'aiyomio.conf.before').write_bytes(original)
if old_snippet is not None:
    (bundle / 'homepage-snippet.before').write_bytes(old_snippet)
(bundle / 'prepared.json').write_text(json.dumps(state, indent=2))
if not release.exists():
    release.mkdir(parents=True)
    with tarfile.open(bundle / 'homepage-assets.tar.gz') as tar:
        members = tar.getmembers()
        assert len(members) == len(manifest['files'])
        assert len(set(member.name for member in members)) == len(members)
        for member in members:
            assert member.isfile() and member.name in manifest['files']
            target = (release / member.name).resolve()
            assert str(target).startswith(str(release) + '/')
            target.parent.mkdir(parents=True, exist_ok=True)
            with tar.extractfile(member) as source, target.open('wb') as dest:
                shutil.copyfileobj(source, dest)
            target.chmod(0o644)
for name, expected in manifest['files'].items():
    target = (release / name).resolve()
    assert str(target).startswith(str(release) + '/')
    assert target.stat().st_size == expected['size'] and sha(target) == expected['sha256']

def link(target):
    temporary = releases / 'current.next'
    assert not temporary.exists() and not temporary.is_symlink()
    temporary.symlink_to(target)
    os.replace(temporary, pointer)

def curl(path, *extra):
    return subprocess.check_output(['curl', '-fsS', '--max-time', '45', '--resolve', 'aiyomio.cn:443:127.0.0.1', *extra, 'https://aiyomio.cn/' + path])

checks = []
try:
    link(release)
    snippet.write_bytes((bundle / 'homepage-assets-location.conf').read_bytes())
    conf.write_text(changed)
    subprocess.check_call(['nginx', '-t'])
    subprocess.check_call(['systemctl', 'reload', 'nginx'])
    # Reload sends a signal; old workers can still answer before the new config
    # takes over. Verify readiness before running the full asset checks.
    probe = 'homepage-v2/mountains.png'
    deadline = time.monotonic() + 8
    while True:
        try:
            assert hashlib.sha256(curl(probe)).hexdigest() == manifest['files'][probe]['sha256']
            break
        except (subprocess.CalledProcessError, AssertionError):
            if time.monotonic() >= deadline:
                raise
            time.sleep(0.25)
    for name, expected in manifest['files'].items():
        if name.endswith('.mp4'):
            response = curl(name, '--range', '0-1023', '-D', str(bundle / 'video-headers.txt'))
            headers = (bundle / 'video-headers.txt').read_text().lower()
            assert ' 206 ' in headers and 'content-type: video/mp4' in headers
            assert 'content-range: bytes 0-1023/' + str(expected['size']) in headers
            assert len(response) == 1024 and hashlib.sha256(response).hexdigest() == expected['rangeSha256']
        else:
            assert hashlib.sha256(curl(name)).hexdigest() == expected['sha256']
        checks.append('/' + name)
    assert str(frontend.resolve()) == state['frontendPointer']
    assert sha(frontend/'user/index.html') == state['userIndexSha256']
    assert sha(frontend/'admin/index.html') == state['adminIndexSha256']
    assert pids() == state['backendPidsBefore']
except Exception:
    conf.write_bytes(original)
    if old_snippet is None:
        snippet.unlink(missing_ok=True)
    else:
        snippet.write_bytes(old_snippet)
    if old_pointer is None:
        pointer.unlink(missing_ok=True)
    else:
        link(old_pointer)
    subprocess.check_call(['nginx', '-t'])
    subprocess.check_call(['systemctl', 'reload', 'nginx'])
    raise
state.update(passed=True, checks=checks, videoRangePassed=True, frontendPreserved=True, backendPreserved=True)
(bundle / 'deployment.json').write_text(json.dumps(state, indent=2))
# Only discard the uploaded temporary archive after every check passes.
(bundle / 'homepage-assets.tar.gz').unlink()
print(json.dumps({'passed': True, 'assetChecks': len(checks), 'videoRangePassed': True, 'frontendPreserved': True, 'backendPreserved': True}))
