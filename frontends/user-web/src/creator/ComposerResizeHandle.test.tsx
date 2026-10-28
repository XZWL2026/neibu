import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { ExpandableComposer } from './ExpandableComposer';

beforeEach(() => {
  vi.stubGlobal('innerHeight', 1000); vi.stubGlobal('innerWidth', 1400);
  vi.stubGlobal('PointerEvent', class extends MouseEvent {
    pointerId: number; isPrimary: boolean;
    constructor(type: string, init: PointerEventInit) { super(type, init); this.pointerId = init.pointerId ?? 1; this.isPrimary = init.isPrimary ?? true; }
  });
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    const height = parseFloat(this.style.getPropertyValue('--composer-resized-height')) || 176;
    return { height, width: 1000, top: 1000 - height, bottom: 1000, left: 0, right: 1000, x: 0, y: 1000 - height, toJSON: () => ({}) };
  });
  Object.defineProperty(HTMLElement.prototype, 'setPointerCapture', { configurable: true, value: vi.fn() });
  Object.defineProperty(HTMLElement.prototype, 'releasePointerCapture', { configurable: true, value: vi.fn() });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
function view(resizable = true, kind: 'image' | 'video' = 'image') {
  const label = kind === 'image' ? '图片生成' : '视频生成';
  const fields = <><textarea aria-label="提示词" defaultValue="保留草稿" /><button type="submit">生成</button></>;
  render(<ExpandableComposer resizable={resizable} aria-label={label} className={kind === 'image' ? 'video-composer image-composer' : 'video-composer'}><form>{kind === 'video' ? <div><div>{fields}</div></div> : fields}</form></ExpandableComposer>);
  const panel = screen.getByRole('region', { name: label });
  return { panel, handle: screen.queryByRole('separator', { name: '调整输入框高度' }) };
}
it.each(['image', 'video'] as const)('resizes %s upward/downward, captures the pointer, clamps height, and keeps the draft and form mounted', kind => {
  const { panel, handle } = view(true, kind); const input = screen.getByRole('textbox');
  fireEvent.pointerDown(handle!, { pointerId: 7, clientY: 824, button: 0 });
  expect(handle!.setPointerCapture).toHaveBeenCalledWith(7);
  fireEvent.pointerMove(handle!, { pointerId: 7, clientY: 600 });
  expect(panel.style.getPropertyValue('--composer-resized-height')).toBe('400px');
  fireEvent.pointerMove(handle!, { pointerId: 7, clientY: 0 });
  expect(panel.style.getPropertyValue('--composer-resized-height')).toBe('700px');
  fireEvent.pointerUp(handle!, { pointerId: 7, clientY: 600 });
  expect(handle).toHaveAttribute('aria-valuenow', '400');
  expect(panel).not.toHaveAttribute('data-resizing');
  expect(screen.getByRole('textbox')).toBe(input); expect(input).toHaveValue('保留草稿');
  fireEvent.pointerDown(handle!, { clientY: 600 }); fireEvent.pointerUp(handle!, { clientY: 1200 });
  expect(panel.style.getPropertyValue('--composer-resized-height')).toBe('176px');
});
it('cancels without changing the previous height and ignores other pointers and secondary buttons', () => {
  const { panel, handle } = view();
  fireEvent.pointerDown(handle!, { button: 2 }); fireEvent.pointerMove(handle!, { clientY: -200 });
  expect(panel.style.getPropertyValue('--composer-resized-height')).toBe('');
  fireEvent.pointerDown(handle!, { pointerId: 4, clientY: 800 });
  fireEvent.pointerMove(handle!, { pointerId: 5, clientY: 300 });
  expect(panel.style.getPropertyValue('--composer-resized-height')).toBe('');
  fireEvent.pointerMove(handle!, { pointerId: 4, clientY: 300 }); fireEvent.pointerCancel(handle!);
  expect(panel.style.getPropertyValue('--composer-resized-height')).toBe('');
  fireEvent.keyDown(handle!, { key: 'ArrowUp' });
  fireEvent.pointerDown(handle!, { clientY: 800 }); fireEvent.pointerMove(handle!, { clientY: 500 });
  fireEvent.keyDown(handle!, { key: 'Escape' });
  expect(panel.style.getPropertyValue('--composer-resized-height')).toBe('186px');
});
it('supports keyboard, viewport limits, default reset and existing enlarged view', () => {
  const { panel, handle } = view();
  fireEvent.keyDown(handle!, { key: 'End' }); expect(panel.style.getPropertyValue('--composer-resized-height')).toBe('700px');
  vi.stubGlobal('innerHeight', 600); fireEvent(window, new Event('resize'));
  expect(panel.style.getPropertyValue('--composer-resized-height')).toBe('432px');
  fireEvent.click(screen.getByRole('button', { name: '放大输入框' }));
  expect(screen.queryByRole('separator')).not.toBeInTheDocument(); expect(panel).toHaveAttribute('data-enlarged', 'true');
  fireEvent.click(screen.getByRole('button', { name: '收起输入框' }));
  const restored = screen.getByRole('separator'); expect(restored).toHaveAttribute('aria-valuenow', '432');
  fireEvent.doubleClick(restored); expect(panel.style.getPropertyValue('--composer-resized-height')).toBe('');
  expect(screen.getByRole('textbox')).toHaveValue('保留草稿');
});
it('does not add resizing to other composers', () => { expect(view(false).handle).toBeNull(); });
it('reserves enough height for a wrapped mobile toolbar', () => {
  vi.stubGlobal('innerWidth', 390);
  const { panel, handle } = view();
  const toolbar = document.createElement('div'); toolbar.className = 'composer-toolbar'; panel.querySelector('form')!.append(toolbar);
  vi.spyOn(toolbar, 'getBoundingClientRect').mockReturnValue({ height: 126 } as DOMRect);
  fireEvent(window, new Event('resize')); fireEvent.keyDown(handle!, { key: 'Home' });
  expect(panel.style.getPropertyValue('--composer-resized-height')).toBe('260px');
});
