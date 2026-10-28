import { cleanup, render } from '@testing-library/react';
import { useRef } from 'react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { useHomeVideo } from './home-motion';

function Background() {
  const video = useRef<HTMLVideoElement>(null);
  const stage = useRef<HTMLElement>(null);
  useHomeVideo(video, stage);
  return <section ref={stage}><video ref={video} data-src="/assets/background-test.mp4" autoPlay muted loop preload="none" /></section>;
}
beforeEach(() => {
  vi.useFakeTimers();
  vi.stubGlobal('requestIdleCallback', undefined);
  vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener() {}, removeEventListener() {} }));
  vi.spyOn(document, 'readyState', 'get').mockReturnValue('loading');
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false);
  vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => {});
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {});
});
afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

it('keeps the video out of the initial network load, then starts it after the page is ready', async () => {
  const { container } = render(<Background />);
  const video = container.querySelector('video')!;
  expect(video.hasAttribute('src')).toBe(false);
  expect(video.querySelector('source')).toBeNull();
  expect(HTMLMediaElement.prototype.play).not.toHaveBeenCalled();
  window.dispatchEvent(new Event('load'));
  await vi.advanceTimersByTimeAsync(1500);
  expect(video.getAttribute('src')).toBe('/assets/background-test.mp4');
  expect(HTMLMediaElement.prototype.play).toHaveBeenCalledTimes(1);
  expect(HTMLMediaElement.prototype.load).toHaveBeenCalledTimes(1);
  window.dispatchEvent(new Event('load'));
  await vi.advanceTimersByTimeAsync(6000);
  expect(HTMLMediaElement.prototype.load).toHaveBeenCalledTimes(1);
});

it('does not download or play the background when reduced motion is requested', async () => {
  vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener() {}, removeEventListener() {} }));
  const { container } = render(<Background />);
  window.dispatchEvent(new Event('load'));
  await vi.advanceTimersByTimeAsync(6000);
  expect(container.querySelector('video')!.hasAttribute('src')).toBe(false);
  expect(HTMLMediaElement.prototype.play).not.toHaveBeenCalled();
});

it('cancels pending background downloads when the page is left before it finishes loading', async () => {
  const { unmount } = render(<Background />);
  unmount();
  window.dispatchEvent(new Event('load'));
  await vi.advanceTimersByTimeAsync(6000);
  expect(HTMLMediaElement.prototype.load).not.toHaveBeenCalled();
  expect(HTMLMediaElement.prototype.play).not.toHaveBeenCalled();
});

it('does not download or play the background when data saving is enabled', async () => {
  vi.stubGlobal('navigator', { connection: { saveData: true } });
  const view = render(<Background />);
  window.dispatchEvent(new Event('load'));
  await vi.advanceTimersByTimeAsync(6000);
  expect(view.container.querySelector('video')!.hasAttribute('src')).toBe(false);
  expect(HTMLMediaElement.prototype.load).not.toHaveBeenCalled();
  expect(HTMLMediaElement.prototype.play).not.toHaveBeenCalled();
});

it('resumes automatic playback when the page becomes visible again', async () => {
  const view = render(<Background />);
  window.dispatchEvent(new Event('load'));
  await vi.advanceTimersByTimeAsync(1500);
  const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
  document.dispatchEvent(new Event('visibilitychange'));
  expect(HTMLMediaElement.prototype.pause).toHaveBeenCalled();
  hidden.mockReturnValue(false);
  document.dispatchEvent(new Event('visibilitychange'));
  expect(HTMLMediaElement.prototype.play).toHaveBeenCalledTimes(2);
});

it('defers attachment while hidden and starts once visible', async () => {
  const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
  const view = render(<Background />);
  window.dispatchEvent(new Event('load'));
  await vi.advanceTimersByTimeAsync(6000);
  expect(view.container.querySelector('video')!.hasAttribute('src')).toBe(false);
  hidden.mockReturnValue(false);
  document.dispatchEvent(new Event('visibilitychange'));
  expect(HTMLMediaElement.prototype.play).toHaveBeenCalledTimes(1);
});
