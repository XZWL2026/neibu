import { afterEach, expect, it, vi } from 'vitest';
import { extractVideoFrames } from './video-frames';

afterEach(() => vi.restoreAllMocks());
it('extracts chronological resized JPEG frames and releases the video', async () => {
  vi.spyOn(HTMLMediaElement.prototype, 'duration', 'get').mockReturnValue(4);
  vi.spyOn(HTMLVideoElement.prototype, 'videoWidth', 'get').mockReturnValue(1920);
  vi.spyOn(HTMLVideoElement.prototype, 'videoHeight', 'get').mockReturnValue(1080);
  vi.spyOn(HTMLMediaElement.prototype, 'src', 'set').mockImplementation(function (this: HTMLMediaElement) { queueMicrotask(() => this.dispatchEvent(new Event('loadedmetadata'))); });
  const seeks: number[] = [];
  vi.spyOn(HTMLMediaElement.prototype, 'currentTime', 'set').mockImplementation(function (this: HTMLMediaElement, time: number) { seeks.push(time); queueMicrotask(() => this.dispatchEvent(new Event('seeked'))); });
  vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => {});
  const draw = vi.fn(); vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage: draw } as never);
  vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(callback => callback(new Blob(['jpeg'], { type: 'image/jpeg' })));
  const result = await extractVideoFrames('https://example.test/reference.mp4');
  expect(result.durationSeconds).toBe(4);
  expect(result.frames.map(frame => frame.timestamp)).toEqual(seeks);
  expect(seeks).toHaveLength(8); expect(seeks[0]).toBeGreaterThan(0); expect(seeks.at(-1)).toBeLessThan(4);
  expect(result.frames.every(frame => frame.file.type === 'image/jpeg')).toBe(true);
  expect(draw.mock.calls[0].slice(1)).toEqual([0, 0, 960, 540]);
  expect((draw.mock.calls[0][0] as HTMLVideoElement).hasAttribute('src')).toBe(false);
});
it('rejects an unreadable video instead of returning fabricated frames', async () => {
  vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => {});
  vi.spyOn(HTMLMediaElement.prototype, 'src', 'set').mockImplementation(function (this: HTMLMediaElement) { queueMicrotask(() => this.dispatchEvent(new Event('error'))); });
  await expect(extractVideoFrames('https://example.test/bad.mp4')).rejects.toThrow();
});
