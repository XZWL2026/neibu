"""Build responsive WebP derivatives from the original commerce PNGs.

Requires Pillow. Run from any directory; originals are never modified.
The crops match the existing cards' centered object-fit: cover presentation.
"""
import json
from pathlib import Path
from PIL import Image, ImageOps

root = Path(__file__).resolve().parents[1]
source_dir = root / 'src/assets/commerce'
output_dir = source_dir / 'optimized'
output_dir.mkdir(exist_ok=True)
ratios = {'cake': 1, 'jewelry': 1, 'shoes': 1, 'headphones': 1,
          'livingroom': 2 / 3, 'portrait': 2 / 3, 'figtart': 3 / 4}
report = []
for name, ratio in ratios.items():
    source = source_dir / f'inspiration-{name}-0930.png'
    outputs = []
    with Image.open(source) as image:
        image = ImageOps.exif_transpose(image).convert('RGB')
        for width in (400, 800):
            size = (width, round(width / ratio))
            target = output_dir / f'{name}-{width}.webp'
            ImageOps.fit(image, size, method=Image.Resampling.LANCZOS).save(target, 'WEBP', quality=85, method=6)
            outputs.append({'file': target.name, 'bytes': target.stat().st_size, 'width': size[0], 'height': size[1]})
    report.append({'source': source.name, 'bytes': source.stat().st_size, 'outputs': outputs})
source = source_dir / 'editorial-atlas-0930.png'
target = output_dir / 'editorial-atlas.webp'
with Image.open(source) as image:
    image.save(target, 'WEBP', quality=88, method=6)
report.append({'source': source.name, 'bytes': source.stat().st_size,
               'outputs': [{'file': target.name, 'bytes': target.stat().st_size}]})
(output_dir / 'manifest.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'source_bytes': sum(item['bytes'] for item in report),
                  'small_bytes': sum(item['outputs'][0]['bytes'] for item in report),
                  'large_bytes': sum(item['outputs'][-1]['bytes'] for item in report)}))
