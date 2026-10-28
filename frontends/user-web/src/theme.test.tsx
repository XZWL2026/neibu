import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { ThemeArtwork, ThemeProvider, ThemeToggle, useTheme } from './theme';

afterEach(() => {
  cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); localStorage.clear();
  Reflect.deleteProperty(document, 'startViewTransition');
  Reflect.deleteProperty(Image.prototype, 'decode');
  delete document.documentElement.dataset.theme;
});

function Consumer() { return <output>{useTheme().theme}</output>; }
function mount() { return render(<ThemeProvider><ThemeToggle /><Consumer /></ThemeProvider>); }

it('defaults to light and persists both directions across remounts', () => {
  const first = mount();
  expect(document.documentElement).toHaveAttribute('data-theme', 'light');
  fireEvent.click(screen.getByRole('button', { name: '切换至深色主题' }));
  expect(screen.getByRole('status')).toHaveTextContent('dark');
  expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
  first.unmount();
  const second = mount();
  expect(screen.getByRole('button', { name: '切换至浅色主题' })).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '切换至浅色主题' }));
  second.unmount(); mount();
  expect(document.documentElement).toHaveAttribute('data-theme', 'light');
});

it('ignores unrecognized stored theme values', () => {
  localStorage.setItem('yomio-theme', 'invalid'); mount();
  expect(document.documentElement).toHaveAttribute('data-theme', 'light');
});

it('still switches when accessing localStorage throws SecurityError', () => {
  vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => { throw new DOMException('Blocked', 'SecurityError'); });
  mount();
  fireEvent.click(screen.getByRole('button', { name: '切换至深色主题' }));
  expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
  fireEvent.click(screen.getByRole('button', { name: '切换至浅色主题' }));
  expect(document.documentElement).toHaveAttribute('data-theme', 'light');
});

it('keeps the selected theme when storage is full', () => {
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('Full', 'QuotaExceededError'); });
  mount(); fireEvent.click(screen.getByRole('button', { name: '切换至深色主题' }));
  expect(screen.getByRole('status')).toHaveTextContent('dark');
});

it('honors every toggle even when React batches rapid input', () => {
  function RapidToggle() {
    const { toggleTheme } = useTheme();
    return <button onClick={() => { toggleTheme(); toggleTheme(); toggleTheme(); }}>Rapid toggle</button>;
  }
  render(<ThemeProvider><RapidToggle /><Consumer /></ThemeProvider>);
  fireEvent.click(screen.getByRole('button', { name: 'Rapid toggle' }));
  expect(screen.getByRole('status')).toHaveTextContent('dark');
  expect(localStorage.getItem('yomio-theme')).toBe('dark');
  fireEvent.click(screen.getByRole('button', { name: 'Rapid toggle' }));
  expect(screen.getByRole('status')).toHaveTextContent('light');
  expect(localStorage.getItem('yomio-theme')).toBe('light');
});

it('retains visited artwork for reversals and excludes inactive controls', () => {
  render(<ThemeProvider><ThemeToggle /><ThemeArtwork light={<button>Day action</button>} dark={<button>Night action</button>} /></ThemeProvider>);
  const day = screen.getByRole('button', { name: 'Day action' });
  expect(screen.queryByRole('button', { name: 'Night action', hidden: true })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '切换至深色主题' }));
  expect(day.closest('.theme-artwork-layer')).toHaveAttribute('inert');
  expect(day.closest('.theme-artwork-layer')).toHaveAttribute('aria-hidden', 'true');
  expect(screen.queryByRole('button', { name: 'Day action' })).toBeNull();
  const night = screen.getByRole('button', { name: 'Night action' });
  fireEvent.click(screen.getByRole('button', { name: '切换至浅色主题' }));
  expect(screen.getByRole('button', { name: 'Day action' })).toBe(day);
  expect(night).toBeInTheDocument();
  expect(night.closest('.theme-artwork-layer')).toHaveAttribute('inert');
});

function browserTransition() {
  let update!: () => Promise<void>;
  let finish!: () => void;
  let fail!: (reason: Error) => void;
  const finished = new Promise<void>((resolve, reject) => { finish = resolve; fail = reject; });
  const skipTransition = vi.fn();
  const start = vi.fn((callback: () => Promise<void>) => {
    update = callback;
    return { ready: Promise.resolve(), finished, skipTransition };
  });
  Object.defineProperty(document, 'startViewTransition', { configurable: true, value: start });
  return { start, skipTransition, update: () => update(), finish, fail };
}

it.each([[1440, 900, 1220, 16], [390, 844, 130, 12]])('reveals from the button and covers every corner of a %i × %i viewport', async (width, height, left, top) => {
  vi.stubGlobal('innerWidth', width);
  vi.stubGlobal('innerHeight', height);
  const browser = browserTransition();
  mount();
  const button = screen.getByRole('button', { name: '切换至深色主题' });
  vi.spyOn(button, 'getBoundingClientRect').mockReturnValue({ left, top, width: 40, height: 40 } as DOMRect);
  fireEvent.click(button, { detail: 0 });
  const style = document.documentElement.style;
  const x = Number.parseFloat(style.getPropertyValue('--theme-origin-x'));
  const y = Number.parseFloat(style.getPropertyValue('--theme-origin-y'));
  const radius = Number.parseFloat(style.getPropertyValue('--theme-reveal-radius'));
  expect([x, y]).toEqual([left + 20, top + 20]);
  for (const [cornerX, cornerY] of [[0, 0], [width, 0], [0, height], [width, height]]) {
    expect(radius).toBeGreaterThan(Math.hypot(cornerX - x, cornerY - y));
  }
  await act(browser.update);
  await act(async () => { browser.finish(); });
  expect(style.getPropertyValue('--theme-reveal-radius')).toBe('');
  expect(style.getPropertyValue('--theme-origin-x')).toBe('');
  expect(style.getPropertyValue('--theme-origin-y')).toBe('');
});

it('restores the saved theme without animation, then commits the entire theme inside the snapshot callback', async () => {
  const browser = browserTransition();
  localStorage.setItem('yomio-theme', 'dark');
  mount();
  expect(browser.start).not.toHaveBeenCalled();
  expect(document.documentElement.style.colorScheme).toBe('dark');
  fireEvent.click(screen.getByRole('button', { name: '切换至浅色主题' }));
  expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
  await act(browser.update);
  expect(screen.getByRole('status')).toHaveTextContent('light');
  expect(document.documentElement).toHaveAttribute('data-theme', 'light');
  expect(document.documentElement.style.colorScheme).toBe('light');
  expect(document.documentElement).toHaveAttribute('data-theme-transition');
  await act(async () => { browser.finish(); });
  expect(document.documentElement).not.toHaveAttribute('data-theme-transition');
});

it('uses the latest selection when rapid input skips a snapshot before its callback runs', async () => {
  const browser = browserTransition();
  mount();
  fireEvent.click(screen.getByRole('button', { name: '切换至深色主题' }));
  fireEvent.click(screen.getByRole('button', { name: '切换至深色主题' }));
  expect(browser.skipTransition).toHaveBeenCalledOnce();
  await act(browser.update);
  expect(document.documentElement).toHaveAttribute('data-theme', 'light');
  expect(localStorage.getItem('yomio-theme')).toBe('light');
  expect(browser.start).toHaveBeenCalledOnce();
  await act(async () => { browser.finish(); });
});

it('allows reversing an already committed snapshot without queuing animations', async () => {
  const browser = browserTransition();
  mount();
  fireEvent.click(screen.getByRole('button', { name: '切换至深色主题' }));
  await act(browser.update);
  fireEvent.click(screen.getByRole('button', { name: '切换至浅色主题' }));
  expect(document.documentElement).toHaveAttribute('data-theme', 'light');
  expect(browser.skipTransition).toHaveBeenCalledOnce();
  await act(async () => { browser.finish(); });
});

it('still applies the theme if the browser cannot start a snapshot', () => {
  const browser = browserTransition();
  browser.start.mockImplementation(() => { throw new Error('Snapshot unavailable'); });
  mount();
  fireEvent.click(screen.getByRole('button', { name: '切换至深色主题' }));
  expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
  expect(document.documentElement).not.toHaveAttribute('data-theme-transition');
});

it('recovers and clears temporary styles when a transition fails asynchronously', async () => {
  const browser = browserTransition();
  mount();
  fireEvent.click(screen.getByRole('button', { name: '切换至深色主题' }));
  await act(async () => { browser.fail(new Error('Snapshot interrupted')); });
  expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
  expect(document.documentElement).not.toHaveAttribute('data-theme-transition');
});

it('cancels on unmount without allowing a delayed callback to alter the next page', async () => {
  const browser = browserTransition();
  const view = mount();
  fireEvent.click(screen.getByRole('button', { name: '切换至深色主题' }));
  view.unmount();
  await act(browser.update);
  expect(document.documentElement).toHaveAttribute('data-theme', 'light');
  expect(browser.skipTransition).toHaveBeenCalledOnce();
  expect(document.documentElement).not.toHaveAttribute('data-theme-transition');
  await act(async () => { browser.finish(); });
});

it('bounds the wait for first-switch artwork when image decoding stalls', async () => {
  vi.useFakeTimers();
  const browser = browserTransition();
  const decode = vi.fn(() => new Promise<void>(() => {}));
  Object.defineProperty(Image.prototype, 'decode', { configurable: true, value: decode });
  render(<ThemeProvider><ThemeToggle /><ThemeArtwork light={<p>Day</p>} dark={<svg><image href="/night.webp" /><image href="/night.webp" /></svg>} /></ThemeProvider>);
  fireEvent.click(screen.getByRole('button', { name: '切换至深色主题' }));
  await act(async () => {
    const update = browser.update();
    await vi.advanceTimersByTimeAsync(160);
    await update;
  });
  expect(decode).toHaveBeenCalledOnce();
  expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
  await act(async () => { browser.finish(); });
  expect(vi.getTimerCount()).toBe(0);
});
