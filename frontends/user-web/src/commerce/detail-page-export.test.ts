import { afterEach, expect, it, vi } from 'vitest';
import { buildDetailPageImage, detailPageLayout } from './detail-page-export';
import { storageImageContentGetApiV1FilesFileIdImageContent as readImage } from '../api';
vi.mock('../api', () => ({ storageImageContentGetApiV1FilesFileIdImageContent: vi.fn(async (id: string) => ({ data: { mimeType: 'image/png', base64: btoa(id) } })) }));
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

it('lays out images in their input order, preserving proportions and avoiding upscaling', () => {
  expect(detailPageLayout([{ width: 1600, height: 800 }, { width: 800, height: 1200 }])).toEqual({
    width: 800, height: 1600, sections: [{ y: 0, height: 400 }, { y: 400, height: 1200 }],
  });
});
it('fits tall sets inside both the browser height and pixel budget', () => {
  const layout = detailPageLayout(Array.from({ length: 6 }, () => ({ width: 1000, height: 12000 })));
  expect(layout.width).toBeLessThanOrEqual(1200);
  expect(layout.height).toBeLessThanOrEqual(16384);
  expect(layout.width * layout.height).toBeLessThanOrEqual(16_000_000);
  expect(layout.sections.every(section => Math.abs(section.height / layout.width - 12) < 0.01)).toBe(true);
});
it('reduces width when the pixel budget is tighter than the height limit', () => {
  const layout = detailPageLayout(Array.from({ length: 6 }, () => ({ width: 2000, height: 4400 })));
  expect(layout.width).toBeLessThan(1200);
  expect(layout.height).toBeLessThanOrEqual(16384);
  expect(layout.width * layout.height).toBeLessThanOrEqual(16_000_000);
});
it('rejects invalid dimensions and excess inputs before allocating an output', () => {
  for (const sizes of [[], Array.from({ length: 15 }, () => ({ width: 100, height: 100 })), [{ width: 0, height: 10 }], [{ width: Infinity, height: 10 }], [{ width: 5000, height: 5000 }]]) {
    expect(() => detailPageLayout(sizes)).toThrow();
  }
});
it('reads owned images through the SDK, draws in order, closes every bitmap, and exports PNG', async () => {
  const blobs: Blob[] = [];
  const decoded: { width: number; height: number; close: ReturnType<typeof vi.fn> }[] = [];
  vi.stubGlobal('createImageBitmap', vi.fn(async (blob: Blob) => {
    if (!blobs.includes(blob)) blobs.push(blob);
    const image = { width: 800, height: blob === blobs[0] ? 400 : 1200, close: vi.fn() }; decoded.push(image); return image;
  }));
  const drawImage = vi.fn();
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage } as never);
  const png = new Blob(['png'], { type: 'image/png' });
  const toBlob = vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(function (this: HTMLCanvasElement, callback) {
    expect(this.width).toBe(800); expect(this.height).toBe(1600); callback(png);
  });
  expect(await buildDetailPageImage(['first', 'second'], new AbortController().signal)).toBe(png);
  expect(vi.mocked(readImage).mock.calls.slice(-2).map(([id]) => id)).toEqual(['first', 'second']);
  expect(vi.mocked(readImage).mock.calls.at(-1)?.[1]?.signal).toBeInstanceOf(AbortSignal);
  expect(blobs.map(blob => [blob.type, blob.size])).toEqual([['image/png', 5], ['image/png', 6]]);
  expect(drawImage.mock.calls).toEqual([[decoded[2], 0, 0, 800, 400], [decoded[3], 0, 400, 800, 1200]]);
  expect(decoded.every(image => image.close.mock.calls.length === 1)).toBe(true);
  expect(toBlob).toHaveBeenCalledWith(expect.any(Function), 'image/png');
});
it('closes a bitmap when cancellation occurs during decoding', async () => {
  const controller = new AbortController();
  const close = vi.fn();
  vi.stubGlobal('createImageBitmap', vi.fn(async () => { controller.abort(); return { width: 100, height: 100, close }; }));
  await expect(buildDetailPageImage(['first'], controller.signal)).rejects.toMatchObject({ name: 'AbortError' });
  expect(close).toHaveBeenCalledOnce();
});
it('rejects failed image requests and oversized files', async () => {
  vi.mocked(readImage).mockRejectedValueOnce(new Error('Forbidden')).mockResolvedValueOnce({ data: { mimeType: 'image/png', base64: 'A'.repeat(28 * 1024 * 1024 + 1) } } as never);
  await expect(buildDetailPageImage(['first'], new AbortController().signal)).rejects.toThrow();
  await expect(buildDetailPageImage(['first'], new AbortController().signal)).rejects.toThrow();
});
