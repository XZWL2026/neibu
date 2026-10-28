import { afterEach, expect, it, vi } from 'vitest';
import { canvasContentChanged, readSessionView, saveSessionView } from './session-view';
import { createCanvasDocument, updateViewport, addTextNode } from './document';

afterEach(() => { sessionStorage.clear(); vi.restoreAllMocks(); });
it('keeps view navigation separate from content edits and group changes', () => {
  const before = createCanvasDocument('canvas', 'now');
  const moved = updateViewport(before, { x: 100, y: -50, zoom: .5 }, 'later');
  expect(canvasContentChanged(before, moved)).toBe(false);
  expect(canvasContentChanged(before, addTextNode(moved, 'text', { x: 0, y: 0 }, 'later'))).toBe(true);
  expect(canvasContentChanged(before, { ...before, groups: [] })).toBe(true);
});
it('isolates views by canvas and account, and ignores corrupt or unavailable session storage', () => {
  const fallback = { x: 0, y: 0, zoom: 1 };
  saveSessionView('alice', 'canvas', { x: 100, y: -50, zoom: .5 });
  expect(readSessionView('alice', 'canvas', fallback)).toEqual({ x: 100, y: -50, zoom: .5 });
  expect(readSessionView('bob', 'canvas', fallback)).toBe(fallback);
  expect(readSessionView('alice', 'other', fallback)).toBe(fallback);
  sessionStorage.setItem('xzwl-canvas-view:alice:canvas', '{bad');
  expect(readSessionView('alice', 'canvas', fallback)).toBe(fallback);
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
  expect(() => saveSessionView('alice', 'canvas', fallback)).not.toThrow();
  expect(readSessionView('alice', 'canvas', fallback)).toBe(fallback);
});
