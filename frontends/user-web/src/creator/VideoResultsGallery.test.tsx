import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { VideoResultsGallery } from './VideoResultsGallery';

let y = 0, narrow = false, adapt: () => void;
let intersect: IntersectionObserverCallback;
const latest = vi.fn(), older = vi.fn();
function View({ loading = false, error = '', submission = '', hasMore = true, mediaType = 'VIDEO' as 'IMAGE' | 'VIDEO' }) {
  return <VideoResultsGallery ready loading={loading} error={error} hasMore={hasMore} empty={false}
    mediaType={mediaType} submission={submission} onLatest={latest} onOlder={older} onRetry={older}>
    {compact => <article data-task-key="a">{compact ? '精简卡片' : '完整卡片'}</article>}
  </VideoResultsGallery>;
}
beforeEach(() => {
  localStorage.clear(); latest.mockReset(); older.mockReset(); y = 0; narrow = false;
  vi.spyOn(document.documentElement, 'scrollHeight', 'get').mockReturnValue(2400);
  vi.stubGlobal('innerHeight', 800);
  vi.spyOn(window, 'scrollY', 'get').mockImplementation(() => y);
  vi.spyOn(window, 'scrollTo').mockImplementation(options => { y = Math.min((options as ScrollToOptions).top ?? 0, 1600); });
  vi.spyOn(window, 'scrollBy').mockImplementation(() => {});
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    const top = this.dataset.taskKey ? 150 - y : 100 - y;
    return { top, bottom: top + 300, height: 300 } as DOMRect;
  });
  vi.stubGlobal('matchMedia', () => ({ get matches() { return narrow; }, addEventListener: (_: string, cb: () => void) => { adapt = cb; }, removeEventListener() {} }));
  vi.stubGlobal('IntersectionObserver', class {
    constructor(cb: IntersectionObserverCallback) { intersect = cb; } observe() {} disconnect() {}
  });
});
it('keeps image preferences separate from video and uses image boundary labels', () => {
  localStorage.setItem('xzwl:video-results-columns', '3');
  const view = render(<View mediaType="IMAGE" />);
  expect(screen.getByRole('group', { name: '图片布局' })).toBeVisible();
  expect(screen.getByRole('button', { name: '四宫格' })).toHaveAttribute('aria-pressed', 'true');
  expect(screen.getByText('向上滚动加载更早图片')).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '单列' }));
  expect(localStorage.getItem('xzwl:video-results-columns')).toBe('3');
  view.unmount(); render(<View mediaType="IMAGE" />);
  expect(screen.getByText('完整卡片')).toBeVisible();
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
it('uses icon-only controls, preserves full result cards and remembers desktop columns across mobile', () => {
  const view = render(<View />);
  expect(screen.getByRole('button', { name: '四宫格' })).toHaveAttribute('aria-pressed', 'true');
  fireEvent.click(screen.getByRole('button', { name: '九宫格' }));
  expect(screen.getByRole('button', { name: '九宫格' })).toHaveAttribute('title', '九宫格');
  expect(screen.getByRole('button', { name: '九宫格' })).toHaveTextContent('');
  expect(screen.getByText('完整卡片')).toBeVisible();
  act(() => { narrow = true; adapt(); });
  expect(screen.getByText('完整卡片')).toBeVisible();
  expect(screen.getByRole('button', { name: '九宫格' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: '单列' }));
  act(() => { narrow = false; adapt(); });
  expect(screen.getByRole('button', { name: '九宫格' })).toHaveAttribute('aria-pressed', 'true');
  view.unmount(); render(<View />);
  expect(screen.getByRole('button', { name: '九宫格' })).toHaveAttribute('aria-pressed', 'true');
});
it('refreshes once per return to the bottom, never for a stationary edge or an open dialog', () => {
  render(<View />); fireEvent.scroll(window); expect(latest).not.toHaveBeenCalled();
  y = 500; fireEvent.scroll(window); y = 1600; fireEvent.scroll(window); fireEvent.scroll(window);
  expect(latest).toHaveBeenCalledTimes(1);
  y = 500; fireEvent.scroll(window);
  const modal = document.createElement('dialog'); modal.open = true; document.body.append(modal);
  y = 1600; fireEvent.scroll(window); expect(latest).toHaveBeenCalledTimes(1); modal.remove();
  fireEvent.scroll(window); expect(latest).toHaveBeenCalledTimes(2);
});
it('loads at the older boundary but stops during requests, errors, and end of history', () => {
  const view = render(<View />);
  const hit = () => act(() => intersect([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver));
  hit(); expect(older).toHaveBeenCalledTimes(1);
  view.rerender(<View loading />); hit(); expect(older).toHaveBeenCalledTimes(1);
  view.rerender(<View error="失败" />); hit(); expect(older).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', { name: '重新读取任务' })); expect(older).toHaveBeenCalledTimes(2);
  view.rerender(<View hasMore={false} />); hit(); expect(older).toHaveBeenCalledTimes(2);
  expect(screen.getByText('已加载全部')).toBeVisible();
});
it('keeps the visible task anchored through layout changes and follows the latest at bottom for a new submission', () => {
  const view = render(<View />);
  y = 100; fireEvent.scroll(window);
  vi.mocked(window.scrollTo).mockClear();
  fireEvent.click(screen.getByRole('button', { name: '九宫格' }));
  expect(window.scrollTo).not.toHaveBeenCalled();
  view.rerender(<View submission="new-task" />);
  expect(window.scrollTo).toHaveBeenCalledWith({ top: 2400, behavior: 'instant' });
});
