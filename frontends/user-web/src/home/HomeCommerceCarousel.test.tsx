import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { HomeCommerceCarousel } from './HomeCommerceCarousel';

afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
function mount() {
  const router = createMemoryRouter([
    { path: '/home', element: <HomeCommerceCarousel /> },
    { path: '/commerce/:tool', element: <h1>工具工作台</h1> },
  ], { initialEntries: ['/home'] });
  render(<RouterProvider router={router} />);
  return router;
}

it('cycles both directions and wraps while preserving all five destinations', () => {
  const router = mount();
  expect(screen.getByRole('link', { name: '商品套图' })).toHaveAttribute('data-active', 'true');
  const next = screen.getByRole('button', { name: '下一个工具' });
  fireEvent.click(next);
  expect(screen.getByRole('link', { name: '模特穿戴' })).toHaveAttribute('data-active', 'true');
  for (let i = 0; i < 4; i++) fireEvent.click(next);
  expect(screen.getByRole('link', { name: '商品套图' })).toHaveAttribute('data-active', 'true');
  const previous = screen.getByRole('button', { name: '上一个工具' });
  for (let i = 0; i < 3; i++) fireEvent.click(previous);
  expect(screen.getByRole('link', { name: '视频复刻' })).toHaveAttribute('data-active', 'true');
  expect(screen.getAllByRole('link')).toHaveLength(5);
  expect(router.state.location.pathname).toBe('/home');
});

it('selects a tool with dots and keyboard, then follows its original route', () => {
  const router = mount();
  const dot = screen.getByRole('button', { name: '查看产品精修' });
  fireEvent.click(dot);
  expect(dot).toHaveAttribute('aria-pressed', 'true');
  fireEvent.keyDown(dot, { key: 'ArrowLeft' });
  expect(screen.getByRole('button', { name: '查看视频复刻' })).toHaveAttribute('aria-pressed', 'true');
  fireEvent.keyDown(dot, { key: 'ArrowRight' });
  expect(dot).toHaveAttribute('aria-pressed', 'true');
  fireEvent.click(screen.getByRole('link', { name: '产品精修' }));
  expect(router.state.location.pathname).toBe('/commerce/product-retouch');
});

it('swipes without accidentally following a card link and ignores a vertical gesture', () => {
  vi.stubGlobal('PointerEvent', MouseEvent);
  const router = mount();
  const card = screen.getByRole('link', { name: '商品套图' });
  fireEvent.pointerDown(card, { button: 0, clientX: 200, clientY: 100 });
  fireEvent.pointerMove(card, { clientX: 100, clientY: 102 });
  fireEvent.pointerUp(card, { clientX: 100, clientY: 102 });
  fireEvent.click(card);
  expect(router.state.location.pathname).toBe('/home');
  expect(screen.getByRole('link', { name: '模特穿戴' })).toHaveAttribute('data-active', 'true');
  fireEvent.pointerDown(card, { button: 0, clientX: 200, clientY: 100 });
  fireEvent.pointerMove(card, { clientX: 196, clientY: 200 });
  fireEvent.pointerUp(card, { clientX: 196, clientY: 200 });
  expect(screen.getByRole('link', { name: '模特穿戴' })).toHaveAttribute('data-active', 'true');
});

it('advances every five seconds and loops all five cards without navigating', () => {
  vi.useFakeTimers();
  const router = mount();
  act(() => vi.advanceTimersByTime(4999));
  expect(screen.getByRole('link', { name: '商品套图' })).toHaveAttribute('data-active', 'true');
  act(() => vi.advanceTimersByTime(1));
  for (const name of ['模特穿戴', '视频复刻', '产品精修', 'A+/详情页', '商品套图']) {
    expect(screen.getByRole('link', { name })).toHaveAttribute('data-active', 'true');
    act(() => vi.advanceTimersByTime(5000));
  }
  expect(router.state.location.pathname).toBe('/home');
});

it('pauses during hover, focus and dragging, then gives a full five seconds after interaction', () => {
  vi.useFakeTimers();
  mount();
  const section = screen.getByRole('region', { name: '电商创作专区' });
  const active = () => document.querySelector('.ym-commerce-card[data-active=true]')?.textContent;
  act(() => vi.advanceTimersByTime(4000));
  fireEvent.pointerEnter(section);
  act(() => vi.advanceTimersByTime(10000));
  expect(active()).toBe('商品套图');
  fireEvent.pointerLeave(section);
  act(() => vi.advanceTimersByTime(4999));
  expect(active()).toBe('商品套图');
  act(() => vi.advanceTimersByTime(1));
  expect(active()).toBe('模特穿戴');
  const dot = screen.getByRole('button', { name: '查看产品精修' });
  act(() => dot.focus());
  fireEvent.click(dot);
  act(() => vi.advanceTimersByTime(10000));
  expect(active()).toBe('产品精修');
  act(() => dot.blur());
  fireEvent.pointerDown(section);
  act(() => vi.advanceTimersByTime(10000));
  expect(active()).toBe('产品精修');
  fireEvent.pointerUp(document.body);
  act(() => vi.advanceTimersByTime(5000));
  expect(active()).toBe('A+/详情页');
});

it('pauses in a hidden tab and honors changing reduced-motion preferences', () => {
  vi.useFakeTimers();
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  const media = Object.assign(new EventTarget(), { matches: false });
  vi.stubGlobal('matchMedia', () => media);
  mount();
  visibility.mockReturnValue('hidden');
  fireEvent(document, new Event('visibilitychange'));
  act(() => vi.advanceTimersByTime(10000));
  expect(screen.getByRole('link', { name: '商品套图' })).toHaveAttribute('data-active', 'true');
  visibility.mockReturnValue('visible');
  fireEvent(document, new Event('visibilitychange'));
  act(() => vi.advanceTimersByTime(5000));
  expect(screen.getByRole('link', { name: '模特穿戴' })).toHaveAttribute('data-active', 'true');
  media.matches = true;
  act(() => media.dispatchEvent(new Event('change')));
  act(() => vi.advanceTimersByTime(10000));
  expect(screen.getByRole('link', { name: '模特穿戴' })).toHaveAttribute('data-active', 'true');
  media.matches = false;
  act(() => media.dispatchEvent(new Event('change')));
  act(() => vi.advanceTimersByTime(5000));
  expect(screen.getByRole('link', { name: '视频复刻' })).toHaveAttribute('data-active', 'true');
  cleanup();
  expect(vi.getTimerCount()).toBe(0);
});
