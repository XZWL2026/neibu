"""Run on the verified aiyomio.cn EDGE. Prepare, activate, or roll back admin UI only."""
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
parser.add_argument('action', choices=['prepare', 'activate', 'rollback'])
parser.add_argument('--release-id', required=True)
parser.add_argument('--bundle', required=True)
args = parser.parse_args()
assert re.fullmatch(r'[0-9]{8}-[0-9]{6}-admin-next', args.release_id)
bundle = Path(args.bundle).resolve()
assert bundle == Path('/root/xzwl-release') / args.release_id
release = Path('/opt/xzwl/admin-ui-releases') / args.release_id
backup = Path('/opt/xzwl/admin-ui-backups') / args.release_id
pointer = Path('/opt/xzwl/admin-ui/current')
conf = Path('/etc/nginx/conf.d/aiyomio.conf')

def run(*cmd):
    return subprocess.check_output(cmd, text=True).strip()

def sha(data):
    return hashlib.sha256(data).hexdigest()

def save(name, value):
    (bundle / name).write_text(json.dumps(value, indent=2) + '\n')

def fetch(path):
    assert path.startswith('/') and not path.startswith('//')
    return subprocess.check_output(['curl', '-fsS', '--max-time', '30', '--resolve',
        'aiyomio.cn:443:127.0.0.1', 'https://aiyomio.cn' + path])

def pids():
    services = run('systemctl', 'list-units', '--type=service', '--state=running', '--no-legend', '--plain', 'xzwl-*')
    return {line.split()[0]: run('systemctl', 'show', line.split()[0], '-p', 'MainPID', '--value')
        for line in services.splitlines() if line.strip()}

def tree_hashes(root):
    return {str(p.relative_to(root)): sha(p.read_bytes()) for p in sorted(root.rglob('*')) if p.is_file()}

def switch(target):
    pointer.parent.mkdir(parents=True, exist_ok=True)
    temporary = pointer.parent / ('current.next-' + args.release_id)
    assert not temporary.exists() and not temporary.is_symlink()
    temporary.symlink_to(target)
    os.replace(temporary, pointer)

def replace_conf(content):
    temporary = conf.with_name('aiyomio.conf.admin-next-tmp')
    assert not temporary.exists()
    temporary.write_bytes(content)
    temporary.chmod(0o644)
    os.replace(temporary, conf)

if args.action == 'prepare':
    assert not backup.exists() and not release.exists(), 'Release ID must be unique'
    assert not pointer.exists() and not pointer.is_symlink(), 'First deployment only; do not overwrite another release'
    original = conf.read_bytes()
    marker = '    location ~ "^/(?:admin/|agent/'
    assert original.decode().count(marker) == 1
    assert 'location ^~ /admin-ui/' not in original.decode()
    assert shutil.disk_usage('/opt/xzwl').free > 250 * 1024 * 1024
    old = Path('/opt/xzwl/current/frontends/admin').resolve()
    assert str(old).startswith('/opt/xzwl/frontend-releases/')
    assert (old / 'index.html').is_file()
    # A complete byte-verified backup exists before touching live configuration.
    backup.mkdir(parents=True)
    shutil.copy2(conf, backup / 'aiyomio.conf.before')
    old_files = tree_hashes(old)
    with tarfile.open(backup / 'original-admin.tar.gz', 'w:gz') as archive:
        archive.add(old, arcname='admin')
    with tarfile.open(backup / 'original-admin.tar.gz', 'r:gz') as archive:
        archived = {member.name.removeprefix('admin/'): sha(archive.extractfile(member).read())
            for member in archive.getmembers() if member.isfile()}
    assert archived == old_files, 'Backup verification failed'
    old_index = (old / 'index.html').read_text()
    old_assets = re.findall(r'(?:src|href)="(/admin/assets/[^"\s]+)"', old_index)
    retained_paths = ['/', '/api/login', '/api/dashboard', '/promoter',
        '/agent/' + '0' * 32 + '/promoter'] + old_assets
    retained = {path: sha(fetch(path)) for path in retained_paths}
    manifest = json.loads((bundle / 'manifest.json').read_text())
    assert manifest['releaseId'] == args.release_id
    assert manifest['archiveSha256'] == sha((bundle / 'admin-ui.tar.gz').read_bytes())
    release.mkdir(parents=True)
    with tarfile.open(bundle / 'admin-ui.tar.gz', 'r:gz') as archive:
        members = archive.getmembers()
        assert len(members) == len(manifest['files'])
        assert len({m.name for m in members}) == len(members)
        for member in members:
            assert member.isfile() and member.name in manifest['files']
            target = (release / member.name).resolve()
            assert target.is_relative_to(release) and target != release
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.extractfile(member) as src, target.open('wb') as dest:
                shutil.copyfileobj(src, dest)
            target.chmod(0o644)
    assert tree_hashes(release) == {k: v['sha256'] for k, v in manifest['files'].items()}
    index = (release / 'index.html').read_text()
    assert '/admin-ui/assets/' in index and 'YOMIO 管理后台' in index
    insertion = '''    # Isolated administrator UI; original agent/API portals remain below.
    location ^~ /admin-ui/ {
        root /opt/xzwl/admin-ui/current;
        try_files $uri =404;
        add_header Cache-Control "public, max-age=31536000, immutable" always;
    }
    location ~ ^/admin/ {
        root /opt/xzwl/admin-ui/current;
        try_files /index.html =404;
        add_header Cache-Control "no-store" always;
    }
'''
    candidate = original.decode().replace(marker, insertion + marker, 1).encode()
    (backup / 'aiyomio.conf.after').write_bytes(candidate)
    state = {'releaseId': args.release_id, 'release': str(release), 'backup': str(backup),
        'originalAdmin': str(old), 'originalFiles': old_files,
        'originalConfSha256': sha(original), 'candidateConfSha256': sha(candidate),
        'backupArchiveSha256': sha((backup / 'original-admin.tar.gz').read_bytes()),
        'retainedResponses': retained, 'backendPids': pids(), 'status': 'prepared'}
    save('prepared.json', state)
    shutil.copy2(bundle / 'prepared.json', backup / 'prepared.json')
    print(json.dumps({k: state[k] for k in ['status', 'release', 'backup', 'backupArchiveSha256']}))
else:
    state = json.loads((bundle / 'prepared.json').read_text())
    assert state['release'] == str(release) and state['backup'] == str(backup)
    original = (backup / 'aiyomio.conf.before').read_bytes()
    candidate = (backup / 'aiyomio.conf.after').read_bytes()
    assert sha(original) == state['originalConfSha256']
    assert sha(candidate) == state['candidateConfSha256']
    def rollback():
        # Restore only our exact configuration; do not discard concurrent changes.
        assert sha(conf.read_bytes()) in [state['candidateConfSha256'], state['originalConfSha256']]
        replace_conf(original)
        run('nginx', '-t')
        run('systemctl', 'reload', 'nginx')
        deadline = time.monotonic() + 12
        while sha(fetch('/admin/login')) != state['originalFiles']['index.html']:
            assert time.monotonic() < deadline, 'Rollback configuration has not become active'
            time.sleep(.25)
        if pointer.is_symlink() and pointer.resolve() == release:
            pointer.unlink()
        assert sha(fetch('/admin/login')) == state['originalFiles']['index.html']
        save('rollback.json', {'status': 'rolled_back', 'backup': str(backup)})
    if args.action == 'rollback':
        rollback()
        print('Rolled back administrator UI; backup and release retained.')
    else:
        assert sha(conf.read_bytes()) == state['originalConfSha256'], 'Live configuration changed after preparation'
        assert not pointer.exists() and not pointer.is_symlink()
        assert tree_hashes(Path(state['originalAdmin'])) == state['originalFiles']
        try:
            switch(release)
            replace_conf(candidate)
            run('nginx', '-t')
            run('systemctl', 'reload', 'nginx')
            deadline = time.monotonic() + 12
            expected_index = sha((release / 'index.html').read_bytes())
            while sha(fetch('/admin/login')) != expected_index:
                assert time.monotonic() < deadline, 'New nginx workers not serving the release'
                time.sleep(.25)
            paths = ['/admin/login', '/admin/overview', '/admin/users', '/admin/users/check-deep-link', '/admin/providers']
            for path in paths:
                assert sha(fetch(path)) == expected_index, path
            manifest = json.loads((bundle / 'manifest.json').read_text())
            for name, data in manifest['files'].items():
                if name.startswith('admin-ui/assets/'):
                    assert sha(fetch('/' + name)) == data['sha256'], name
            for path, digest in state['retainedResponses'].items():
                assert sha(fetch(path)) == digest, 'Unrelated route changed: ' + path
            session = json.loads(fetch('/admin/api/v1/auth/session'))
            assert session['authenticated'] is False and 'csrfToken' in session
            assert pids() == state['backendPids'], 'Backend processes changed'
            assert tree_hashes(Path(state['originalAdmin'])) == state['originalFiles']
            result = {'status': 'active', 'release': str(release), 'backup': str(backup),
                'adminRoutesChecked': paths, 'assetsChecked': sum(n.startswith('admin-ui/assets/') for n in manifest['files']),
                'retainedRoutesChecked': list(state['retainedResponses']), 'backendPidsUnchanged': True,
                'originalFilesUnchanged': True, 'adminSessionEndpoint': 'ok'}
            save('activated.json', result)
            print(json.dumps(result))
        except Exception:
            rollback()
            raise
