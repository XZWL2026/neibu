"""Build the complete homepage public asset bundle from a committed Git revision."""
import argparse
import hashlib
import io
import json
import subprocess
import tarfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--output', required=True)
parser.add_argument('--commit', default='HEAD')
args = parser.parse_args()
repo = Path(__file__).resolve().parents[3]
output = Path(args.output).resolve()
output.mkdir(parents=True, exist_ok=True)
commit = subprocess.check_output(['git', 'rev-parse', args.commit], cwd=str(repo)).decode().strip()
prefix = 'frontends/user-web/public/'
archive = subprocess.check_output(['git', 'archive', '--format=tar', commit, prefix + 'homepage-v2'], cwd=str(repo))
files = {}
with tarfile.open(fileobj=io.BytesIO(archive)) as source:
    with tarfile.open(str(output / 'homepage-assets.tar.gz'), 'w:gz') as bundle:
        for member in source.getmembers():
            if not member.isfile() or member.name.endswith('.md'):
                continue
            assert member.name.startswith(prefix + 'homepage-v2/')
            name = member.name[len(prefix):]
            data = source.extractfile(member).read()
            files[name] = {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
            if name.endswith('.mp4'):
                files[name]['rangeSha256'] = hashlib.sha256(data[:1024]).hexdigest()
            item = tarfile.TarInfo(name)
            item.size, item.mode = len(data), 0o644
            bundle.addfile(item, io.BytesIO(data))
manifest = {'sourceCommit': commit, 'files': files, 'totalBytes': sum(item['size'] for item in files.values())}
(output / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
print(json.dumps({'files': len(files), 'totalBytes': manifest['totalBytes'], 'archiveBytes': (output / 'homepage-assets.tar.gz').stat().st_size}))
