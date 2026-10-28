import { storageImageContentGetApiV1FilesFileIdImageContent } from '../api';

type ImageSize = { width: number; height: number };

export function detailPageLayout(sizes: ImageSize[]) {
  if (!sizes.length || sizes.length > 14 || sizes.some(({ width, height }) =>
    !Number.isFinite(width) || !Number.isFinite(height) || width < 1 || height < 1 || width * height > 20_000_000)) {
    throw new Error('图片数量或尺寸超出限制');
  }
  const ratio = sizes.reduce((sum, size) => sum + size.height / size.width, 0);
  const width = Math.floor(Math.min(1200, ...sizes.map(size => size.width), 16384 / ratio, Math.sqrt(16_000_000 / ratio)));
  if (width < 1) throw new Error('图片过长，无法拼接');
  let height = 0;
  const sections = sizes.map(size => {
    const section = { y: height, height: Math.max(1, Math.floor(size.height * width / size.width)) };
    height += section.height;
    return section;
  });
  if (height > 16384 || width * height > 16_000_000) throw new Error('图片过长，无法拼接');
  return { width, height, sections };
}

export async function buildDetailPageImage(fileIds: string[], signal: AbortSignal): Promise<Blob> {
  if (!fileIds.length || fileIds.length > 14 || fileIds.some(id => !/^[A-Za-z0-9_-]{1,128}$/.test(id))) {
    throw new Error('请选择完整的详情图片');
  }
  const blobs: Blob[] = [];
  const sizes: ImageSize[] = [];
  let bytes = 0;
  for (const fileId of fileIds) {
    signal.throwIfAborted();
    const { data } = await storageImageContentGetApiV1FilesFileIdImageContent(fileId, { signal });
    signal.throwIfAborted();
    if (!data.mimeType?.startsWith('image/') || !data.base64 || data.base64.length > 28 * 1024 * 1024) {
      throw new Error('图片内容无效或文件过大');
    }
    const binary = atob(data.base64);
    const blob = new Blob([Uint8Array.from(binary, char => char.charCodeAt(0))], { type: data.mimeType });
    bytes += blob.size;
    if (blob.size > 20 * 1024 * 1024 || bytes > 60 * 1024 * 1024) throw new Error('图片文件过大');
    signal.throwIfAborted();
    const bitmap = await createImageBitmap(blob);
    try {
      signal.throwIfAborted();
      sizes.push({ width: bitmap.width, height: bitmap.height });
      detailPageLayout(sizes);
      blobs.push(blob);
    } finally { bitmap.close(); }
  }
  const layout = detailPageLayout(sizes);
  const canvas = document.createElement('canvas');
  canvas.width = layout.width; canvas.height = layout.height;
  try {
    const context = canvas.getContext('2d');
    if (!context) throw new Error('无法创建长图');
    // Read dimensions first, then decode one image at a time to avoid retaining all full bitmaps.
    for (const [index, blob] of blobs.entries()) {
      signal.throwIfAborted();
      const bitmap = await createImageBitmap(blob);
      try {
        signal.throwIfAborted();
        const section = layout.sections[index];
        context.drawImage(bitmap, 0, section.y, layout.width, section.height);
      } finally { bitmap.close(); }
    }
    const output = await new Promise<Blob>((resolve, reject) => canvas.toBlob(
      blob => blob ? resolve(blob) : reject(new Error('长图编码失败')), 'image/png',
    ));
    signal.throwIfAborted();
    return output;
  } finally { canvas.width = 0; canvas.height = 0; }
}
