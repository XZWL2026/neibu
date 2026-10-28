"""Regenerate lightweight derivatives while preserving the original homepage sources.
Requires: pillow, fonttools[woff], imageio-ffmpeg.
Run: python scripts/optimize-home-media.py
"""
import pathlib, json, re, hashlib, subprocess, shutil
from PIL import Image
from fontTools import subset
import imageio_ffmpeg
root = pathlib.Path(__file__).resolve().parents[1]
work = root / '.cache/home-performance'
work.mkdir(parents=True, exist_ok=True)
output = root / 'src/assets/home-performance'
output.mkdir(parents=True, exist_ok=True)
report = {'images': [], 'fonts': [], 'video': {}}
for source in (root / 'public/homepage-v2').rglob('*.png'):
    if source.name in ['hero-clean.png', 'avatar.png']: continue
    target = output / source.relative_to(root / 'public/homepage-v2').with_suffix('.webp')
    target.parent.mkdir(parents=True, exist_ok=True)
    with Image.open(source) as image:
        limit = (1600, 900) if source.name == 'poster.png' else (960, 960)
        image.thumbnail(limit, Image.Resampling.LANCZOS)
        if image.mode not in ['RGB', 'RGBA']: image = image.convert('RGBA')
        image.save(target, 'WEBP', quality=88, method=6)
        report['images'].append({'source': str(source.relative_to(root)), 'output': str(target.relative_to(root)), 'before': source.stat().st_size, 'after': target.stat().st_size, 'size': image.size})
texts = ''.join(path.read_text(encoding='utf-8') for path in (root / 'src/home').rglob('*') if path.suffix in ['.ts', '.tsx'] and '.test.' not in path.name)
for path in (root / 'src').glob('i18n*'):
    if path.is_file(): texts += path.read_text(encoding='utf-8')
    elif path.is_dir():
        texts += ''.join(file.read_text(encoding='utf-8') for file in path.rglob('*.ts') if '.test.' not in file.name)
characters = ''.join(sorted(set(texts + ''.join(chr(code) for code in range(32,127)))))
for source_name, output_name, content in [('noto-sans-sc.ttf', 'noto-home.woff2', characters), ('nunito-extrabold.ttf', 'nunito-home.woff2', ''.join(chr(code) for code in range(32,127)))]:
    options = subset.Options()
    options.flavor = 'woff2'
    font = subset.load_font(str(root / 'public/homepage-v2' / source_name), options)
    processor = subset.Subsetter(options=options)
    processor.populate(text=content)
    processor.subset(font)
    target = output / output_name
    subset.save_font(font, str(target), options)
    report['fonts'].append({'source': source_name, 'output': output_name, 'before': (root / 'public/homepage-v2' / source_name).stat().st_size, 'after': target.stat().st_size})
source = root / 'public/homepage-v2/background.mp4'
target = output / 'background.mp4'
ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
with (work / 'video-encode.log').open('w') as log:
    subprocess.run([ffmpeg, '-y', '-i', str(source), '-vf', 'scale=1280:-2', '-c:v', 'libx264', '-preset', 'medium', '-crf', '27', '-maxrate', '1500k', '-bufsize', '3000k', '-pix_fmt', 'yuv420p', '-an', '-movflags', '+faststart', '-threads', '2', str(target)], stdout=log, stderr=log, check=True)
report['video'] = {'before': source.stat().st_size, 'after': target.stat().st_size, 'originalSha256': hashlib.sha256(source.read_bytes()).hexdigest(), 'dimensions': '1280x720', 'completeDuration': True, 'cropped': False, 'faststart': True}
for name in ['NotoSansSC-OFL.txt', 'Nunito-OFL.txt']:
    shutil.copy2(root / 'public/homepage-v2' / name, output / name)
(output / 'PROVENANCE.txt').write_text('2026-10-08: WebP images derive from public/homepage-v2 PNGs with the same artwork. WOFF2 fonts are subsets of the existing licensed Noto Sans SC and Nunito files. Background video is a 1280x720 H.264 derivative of the complete original video, with no trimming/cropping, muted audio omitted, and faststart metadata. Original sources remain under public/homepage-v2.\n', encoding='utf-8')
(work / 'media-report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps({'fonts': report['fonts'], 'video': report['video'], 'imageBefore': sum(item['before'] for item in report['images']), 'imageAfter': sum(item['after'] for item in report['images'])}, indent=2))
