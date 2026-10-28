import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { readReferenceAudioDuration } from './reference-audio-duration';

let audio: HTMLMediaElement;
const revoke = vi.fn();
beforeEach(() => {
  revoke.mockClear();
  vi.stubGlobal('URL', class extends URL { static createObjectURL() { return 'blob:duration'; } static revokeObjectURL = revoke; });
  vi.spyOn(HTMLMediaElement.prototype, 'src', 'set').mockImplementation(function (this: HTMLMediaElement) { audio = this; });
});
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); });

it('reads native metadata duration and releases the temporary URL', async () => {
  vi.spyOn(HTMLMediaElement.prototype, 'duration', 'get').mockReturnValue(7.25);
  const result = readReferenceAudioDuration(new File(['audio'], 'sound.wav'));
  expect(audio.preload).toBe('metadata');
  audio.dispatchEvent(new Event('loadedmetadata'));
  await expect(result).resolves.toBe(7.25);
  expect(revoke).toHaveBeenCalledWith('blob:duration');
  expect(audio.onloadedmetadata).toBeNull();
  expect(audio.onerror).toBeNull();
});

it.each([NaN, Infinity, 0, -1])('rejects unreadable duration %s and releases its URL', async duration => {
  vi.spyOn(HTMLMediaElement.prototype, 'duration', 'get').mockReturnValue(duration);
  const result = readReferenceAudioDuration(new File(['audio'], 'sound.wav'));
  audio.dispatchEvent(new Event('loadedmetadata'));
  await expect(result).rejects.toThrow('无法读取音频时长');
  expect(revoke).toHaveBeenCalledOnce();
});

it('releases unreadable audio and rejects a metadata load error', async () => {
  const result = readReferenceAudioDuration(new File(['audio'], 'sound.wav'));
  audio.dispatchEvent(new Event('error'));
  await expect(result).rejects.toThrow('无法读取音频时长');
  expect(revoke).toHaveBeenCalledOnce();
});

it('times out metadata loading so a broken audio cannot leave upload busy forever', async () => {
  vi.useFakeTimers();
  const result = readReferenceAudioDuration(new File(['audio'], 'sound.wav'));
  const rejected = expect(result).rejects.toThrow('无法读取音频时长');
  await vi.advanceTimersByTimeAsync(10_000);
  await rejected;
  expect(revoke).toHaveBeenCalledOnce();
  expect(audio.onloadedmetadata).toBeNull();
});
