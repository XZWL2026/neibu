import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes, createMemoryRouter, RouterProvider } from 'react-router-dom';
import { CreationNavigation } from './CreationNavigation';

beforeEach(() => {
  sessionStorage.clear();
  vi.stubGlobal('PointerEvent', class extends MouseEvent {
    pointerId: number;
    constructor(type: string, init: PointerEventInit = {}) { super(type, init); this.pointerId = init.pointerId ?? 1; }
  });
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); sessionStorage.clear(); });

function hoverNavigation() {
  const rail = screen.getByRole('complementary');
  fireEvent.pointerLeave(rail);
  fireEvent.pointerEnter(rail, { pointerType: 'mouse' });
}

it.each(['workspace', 'home'] as const)('%s drags without opening, remembers position across routes, and keeps the menu inside the viewport', variant => {
  const label = variant === 'home' ? '创作类型' : '用户端导航';
  render(<MemoryRouter initialEntries={[variant === 'home' ? '/home' : '/create/image']}><CreationNavigation variant={variant} /></MemoryRouter>);
  let trigger = screen.getByRole('button', { name: variant === 'home' ? '展开创作导航' : '返回首页' });
  const rail = screen.getByRole('complementary', { name: label });
  fireEvent.pointerDown(trigger, { button: 0, pointerId: 1, clientX: 40, clientY: 210 });
  fireEvent.pointerMove(trigger, { pointerId: 1, clientX: 2040, clientY: 2210 });
  fireEvent.pointerUp(trigger, { pointerId: 1, clientX: 2040, clientY: 2210 });
  fireEvent.click(trigger, { detail: 1 });
  expect(trigger).toHaveAttribute('aria-expanded', 'false');
  expect(rail.style.left).toBe(`${innerWidth - 88}px`);
  expect(rail.style.top).toBe(`${innerHeight - 88}px`);
  expect(rail).toHaveAttribute('data-opens-up', 'true');
  expect(rail).toHaveAttribute('data-aligns-right', 'true');
  hoverNavigation();
  fireEvent.click(screen.getByRole('link', { name: '视频' }));
  expect(screen.getByRole('complementary', { name: label }).style.left).toBe(`${innerWidth - 88}px`);
  trigger = screen.getByRole('button', { name: '返回首页' });
  fireEvent.keyDown(trigger, { key: 'Home', altKey: true });
  expect(screen.getByRole('complementary', { name: label })).toHaveStyle({ left: '12px', top: '180px' });
});

it('supports keyboard positioning and clamps after resizing', () => {
  render(<MemoryRouter><CreationNavigation /></MemoryRouter>);
  const trigger = screen.getByRole('button', { name: '返回首页' });
  const rail = screen.getByRole('complementary', { name: '用户端导航' });
  fireEvent.keyDown(trigger, { key: 'ArrowRight', altKey: true });
  expect(rail.style.left).toBe('32px');
  vi.stubGlobal('innerWidth', 100);
  vi.stubGlobal('innerHeight', 220);
  fireEvent(window, new Event('resize'));
  expect(rail.style.left).toBe('12px');
  expect(rail.style.top).toBe('132px');
});

it('keeps the menu open on first activation after hover and closes on the second', () => {
  render(<MemoryRouter initialEntries={['/home']}><CreationNavigation /></MemoryRouter>);
  const rail = screen.getByRole('complementary', { name: '用户端导航' });
  const trigger = screen.getByRole('button', { name: '展开创作导航' });
  fireEvent.pointerEnter(rail, { pointerType: 'mouse' });
  fireEvent.click(trigger);
  fireEvent.pointerLeave(rail);
  expect(trigger).toHaveAttribute('aria-expanded', 'true');
  fireEvent.click(trigger);
  expect(trigger).toHaveAttribute('aria-expanded', 'false');
});

it.each([
  ['/home', '首页'],
  ['/create/image', '图片'], ['/create/video', '视频'],
  ['/canvas', '画布'], ['/commerce', '电商'],
  ['/commerce/product-suite', '电商'], ['/commerce/video-replica', '电商'],
])('selects the correct destination on %s, including nested workspaces', (path, label) => {
  render(<MemoryRouter initialEntries={[path]}><CreationNavigation /></MemoryRouter>);
  const trigger = screen.getByRole('button', { name: path === '/home' ? '展开创作导航' : '返回首页' });
  expect(screen.queryByRole('link', { name: label })).not.toBeInTheDocument();
  hoverNavigation();
  expect(screen.getByRole('link', { name: label })).toHaveAttribute('aria-current', 'page');
  expect(screen.getByRole('link', { name: '剧创' })).toHaveAttribute('href', '/home?entry=story');
  fireEvent.keyDown(trigger, { key: 'Escape' });
  expect(trigger).toHaveFocus();
  expect(trigger).toHaveAttribute('aria-expanded', 'false');
});

it('closes after a destination change and updates the current link', () => {
  render(<MemoryRouter initialEntries={['/create/image']}><Routes><Route path="*" element={<CreationNavigation />} /></Routes></MemoryRouter>);
  hoverNavigation();
  fireEvent.click(screen.getByRole('link', { name: '视频' }));
  expect(screen.getByRole('button', { name: '返回首页' })).toHaveAttribute('aria-expanded', 'false');
  hoverNavigation();
  expect(screen.getByRole('link', { name: '视频' })).toHaveAttribute('aria-current', 'page');
  expect(screen.getByRole('link', { name: '图片' })).not.toHaveAttribute('aria-current');
  fireEvent.pointerDown(document.body);
  expect(screen.getByRole('button', { name: '返回首页' })).toHaveAttribute('aria-expanded', 'false');
  hoverNavigation();
  fireEvent.click(screen.getByRole('link', { name: '首页' }));
  expect(screen.getByRole('button', { name: '展开创作导航' })).toHaveAttribute('aria-expanded', 'false');
  fireEvent.click(screen.getByRole('button', { name: '展开创作导航' }));
  expect(screen.getByRole('link', { name: '首页' })).toHaveAttribute('aria-current', 'page');
});

it.each(['/create/image', '/create/video', '/canvas', '/commerce/product-suite', '/account/profile'])('returns to home when the circle is activated on %s', path => {
  const router = createMemoryRouter([{ path: '*', element: <CreationNavigation /> }], { initialEntries: [path] });
  render(<RouterProvider router={router} />);
  hoverNavigation();
  fireEvent.click(screen.getByRole('button', { name: '返回首页' }), { detail: 1 });
  expect(router.state.location.pathname).toBe('/home');
  expect(screen.getByRole('button', { name: '展开创作导航' })).toHaveAttribute('aria-expanded', 'false');
});

it('suppresses navigation after dragging and allows the next ordinary click', () => {
  const router = createMemoryRouter([{ path: '*', element: <CreationNavigation /> }], { initialEntries: ['/canvas'] });
  render(<RouterProvider router={router} />);
  const trigger = screen.getByRole('button', { name: '返回首页' });
  fireEvent.pointerDown(trigger, { button: 0, pointerId: 1, clientX: 40, clientY: 210 });
  fireEvent.pointerMove(trigger, { pointerId: 1, clientX: 140, clientY: 310 });
  fireEvent.pointerUp(trigger, { pointerId: 1, clientX: 140, clientY: 310 });
  fireEvent.click(trigger, { detail: 1 });
  expect(router.state.location.pathname).toBe('/canvas');
  fireEvent.click(trigger, { detail: 1 });
  expect(router.state.location.pathname).toBe('/home');
  expect(screen.getByRole('complementary')).toHaveStyle({ left: '112px', top: '280px' });
});

it('supports keyboard activation and respects the deployment base', () => {
  const router = createMemoryRouter([{ path: '*', element: <CreationNavigation /> }], { basename: '/portal', initialEntries: ['/portal/create/video'] });
  render(<RouterProvider router={router} />);
  fireEvent.click(screen.getByRole('button', { name: '返回首页' }), { detail: 0 });
  expect(router.state.location.pathname).toBe('/portal/home');
});
