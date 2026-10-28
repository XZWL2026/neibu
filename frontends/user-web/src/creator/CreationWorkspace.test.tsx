import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { CreationWorkspace } from './CreationWorkspace';

let height = 2400, y = 0, cardTop = 100;
let resize: (() => void)[];
const flush = () => act(() => { vi.advanceTimersByTime(240); });
function View({ ready = true, submission = '', busy = false, earlier = false }) {
  return <CreationWorkspace enabled ready={ready} submission={submission} prompt="保留的提示词" busy={busy}
    results={<section>{earlier && <article data-task-key="old">更早记录</article>}<article data-task-key="current">当前记录<img alt="任务图片" /></article></section>}>
    <textarea aria-label="提示词" defaultValue="完整输入" /><button type="button">参数</button>
  </CreationWorkspace>;
}
beforeEach(() => {
  vi.useFakeTimers(); height = 2400; y = 0; cardTop = 100; resize = [];
  vi.stubGlobal('ResizeObserver', class { constructor(cb: () => void) { resize.push(cb); } observe() {} disconnect() {} });
  vi.spyOn(document.documentElement, 'scrollHeight', 'get').mockImplementation(() => height);
  vi.stubGlobal('innerHeight', 800);
  vi.spyOn(window, 'scrollY', 'get').mockImplementation(() => y);
  vi.spyOn(window, 'scrollTo').mockImplementation((options: unknown) => { y = Math.max(0, Math.min((options as ScrollToOptions).top ?? 0, height - 800)); });
  vi.spyOn(window, 'scrollBy').mockImplementation((options: unknown) => { y += (options as ScrollToOptions).top ?? 0; });
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    return { top: this.dataset.taskKey ? cardTop : 0, bottom: this.dataset.taskKey ? cardTop + 300 : 100, height: 100, width: 900, x: 0, y: 0, left: 0, right: 900, toJSON: () => ({}) };
  });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); });
it('keeps the composer compact while browsing newest-first videos and never follows the history bottom', () => {
  render(<CreationWorkspace enabled ready newestFirst hasResults submission="" prompt="草稿" busy={false} results={<article data-task-key="current">视频</article>}>
    <textarea aria-label="提示词" />
  </CreationWorkspace>);
  flush(); expect(window.scrollTo).not.toHaveBeenCalled();
  expect(screen.getByRole('button', { name: '展开生成框' })).toBeVisible();
  y = height - 800; fireEvent.wheel(window, { deltaY: 500 }); fireEvent.scroll(window); flush();
  expect(screen.getByRole('button', { name: '展开生成框' })).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '展开生成框' }));
  expect(screen.getByRole('textbox', { name: '提示词' })).toHaveFocus();
});
it('waits for real history, follows media layout at bottom and preserves the reader anchor during polling and prepending', () => {
  const view = render(<View ready={false} />); flush(); expect(window.scrollTo).not.toHaveBeenCalled();
  view.rerender(<View />); flush(); expect(y).toBe(1600);
  height = 2800; act(() => resize.forEach(cb => cb())); flush(); expect(y).toBe(2000);
  fireEvent.wheel(window, { deltaY: -500 }); y = 800; fireEvent.scroll(window); flush();
  expect(screen.getByRole('button', { name: '展开生成框' })).toBeVisible();
  vi.mocked(window.scrollTo).mockClear();
  act(() => resize.forEach(cb => cb())); flush(); expect(window.scrollTo).not.toHaveBeenCalled();
  // Existing visible card is pushed down by earlier records and later image layout.
  view.rerender(<View earlier />); cardTop = 460; height = 3200;
  fireEvent.load(screen.getByRole('img', { name: '任务图片' })); flush(); expect(y).toBe(1160);
  expect(window.scrollTo).not.toHaveBeenCalled();
  view.rerender(<View earlier submission="new-request" />); flush(); expect(y).toBe(2400);
  expect(screen.getByRole('textbox')).toHaveValue('完整输入');
});
it('expands on touch/keyboard click and hover, keeps focus/menu/upload open, and never resets inputs', () => {
  const view = render(<View />); flush();
  fireEvent.wheel(window, { deltaY: -500 }); y = 500; fireEvent.scroll(window); flush();
  const button = screen.getByRole('button', { name: '展开生成框' });
  fireEvent.click(button); expect(screen.getByRole('textbox')).toBeVisible();
  expect(screen.getByRole('textbox')).toHaveFocus();
  const dock = screen.getByRole('textbox').closest('.creation-dock')!;
  act(() => screen.getByRole('textbox').focus());
  fireEvent.pointerLeave(dock); flush(); expect(screen.getByRole('textbox')).toBeVisible();
  act(() => (document.activeElement as HTMLElement).blur()); fireEvent.pointerLeave(dock); flush();
  expect(button).toBeVisible();
  fireEvent.pointerEnter(dock, { pointerType: 'mouse' }); expect(screen.getByRole('textbox')).toBeVisible();
  view.rerender(<View busy />); fireEvent.pointerLeave(dock); flush(); expect(screen.getByRole('textbox')).toBeVisible();
  view.rerender(<View />); fireEvent.pointerLeave(dock); flush(); expect(button).toBeVisible();
  y = height - 800; fireEvent.scroll(window); flush(); expect(screen.getByRole('textbox')).toHaveValue('完整输入');
});
it('keeps collapsed controls keyboard reachable and opens the real material entry without submitting', () => {
  const material = vi.fn(), submit = vi.fn();
  render(<CreationWorkspace enabled ready submission="" prompt="草稿" busy={false} results={<article data-task-key="current">记录</article>}>
    <form onSubmit={submit}><button type="button" aria-label="添加素材" onClick={material}>素材</button><textarea aria-label="提示词" /></form>
  </CreationWorkspace>);
  flush(); fireEvent.wheel(window, { deltaY: -500 }); y = 500; fireEvent.scroll(window); flush();
  const entry = screen.getByRole('button', { name: '添加素材' });
  act(() => entry.focus());
  expect(screen.getByRole('button', { name: '展开生成框' })).toBeVisible();
  fireEvent.click(entry);
  expect(material).toHaveBeenCalledTimes(1);
  expect(submit).not.toHaveBeenCalled();
  expect(screen.getByRole('button', { name: '添加素材' })).toHaveFocus();
});
it('does not mistake browser layout/restoration scrolling for an intentional history read during initial media loading', () => {
  render(<View />); flush(); expect(y).toBe(1600);
  height = 3200;
  fireEvent.scroll(window);
  expect(screen.getByRole('textbox')).toBeVisible();
  act(() => resize.forEach(cb => cb())); flush(); expect(y).toBe(2400);
  expect(screen.queryByRole('button', { name: '展开生成框' })).not.toBeInTheDocument();
});
