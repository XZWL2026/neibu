import { t, useLanguage } from './i18n';
import { createContext, useContext, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { flushSync } from 'react-dom';
import './theme-motion.css';

type Theme = 'light' | 'dark';
type ThemeOrigin = { x: number; y: number };
const ThemeContext = createContext<{ theme: Theme; selectTheme(theme: Theme, origin?: ThemeOrigin): void; toggleTheme(origin?: ThemeOrigin): void }>({ theme: 'light', selectTheme() {}, toggleTheme() {} });

function clearThemeTransition() {
  const root = document.documentElement;
  delete root.dataset.themeTransition;
  for (const name of ['--theme-origin-x', '--theme-origin-y', '--theme-reveal-radius']) root.style.removeProperty(name);
}

// Decode SVG artwork before taking the new snapshot, including the first switch.
// Bound the wait so slow images never lock the interface.
async function prepareArtwork() {
  if (typeof Image.prototype.decode !== 'function') return;
  const sources = new Set<string>();
  document.querySelectorAll('.theme-artwork-layer[data-active="true"] :is(img, image)').forEach(node => {
    const source = node instanceof HTMLImageElement ? node.currentSrc || node.src : node.getAttribute('href');
    if (source) sources.add(source);
  });
  if (!sources.size) return;
  let timeout: ReturnType<typeof setTimeout> | undefined;
  try {
    await Promise.race([
      Promise.all([...sources].map(source => {
        const image = new Image();
        image.src = source;
        return image.decode().catch(() => {});
      })),
      new Promise<void>(resolve => { timeout = setTimeout(resolve, 160); }),
    ]);
  } finally { clearTimeout(timeout); }
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  useLanguage();
  const [theme, setTheme] = useState<Theme>(() => {
    try { return localStorage.getItem('yomio-theme') === 'dark' ? 'dark' : 'light'; }
    catch { return 'light'; }
  });
  const currentTheme = useRef(theme);
  const transition = useRef<ViewTransition | null>(null);
  const mounted = useRef(false);
  useLayoutEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      transition.current?.skipTransition();
      transition.current = null;
      clearThemeTransition();
    };
  }, []);
  useLayoutEffect(() => {
    document.documentElement.dataset.theme = theme;
    document.documentElement.style.colorScheme = theme;
  }, [theme]);
  function selectTheme(next: Theme, origin?: ThemeOrigin) {
    if (next === currentTheme.current) return;
    currentTheme.current = next;
    try { localStorage.setItem('yomio-theme', next); }
    catch { /* Storage restrictions must not prevent switching in this session. */ }
    const applyTheme = () => {
      if (mounted.current) flushSync(() => setTheme(currentTheme.current));
    };
    // Skipping still runs a pending browser callback. Read the latest selection
    // there, so an older callback cannot undo rapid input.
    if (transition.current) {
      transition.current.skipTransition();
      applyTheme();
      return;
    }
    if (typeof document.startViewTransition !== 'function' || document.visibilityState === 'hidden') {
      applyTheme();
      return;
    }
    const root = document.documentElement;
    const x = Math.min(window.innerWidth, Math.max(0, origin?.x ?? window.innerWidth / 2));
    const y = Math.min(window.innerHeight, Math.max(0, origin?.y ?? window.innerHeight / 2));
    // Reach the farthest viewport corner, including when the page is scrolled.
    const radius = Math.hypot(Math.max(x, window.innerWidth - x), Math.max(y, window.innerHeight - y)) + 2;
    root.style.setProperty('--theme-origin-x', `${x}px`);
    root.style.setProperty('--theme-origin-y', `${y}px`);
    root.style.setProperty('--theme-reveal-radius', `${radius}px`);
    root.dataset.themeTransition = '';
    try {
      const active = document.startViewTransition(async () => {
        applyTheme();
        if (mounted.current) await prepareArtwork();
      });
      transition.current = active;
      // Skipped snapshots reject ready; the selected theme must still apply.
      void active.ready.catch(() => {});
      void active.finished.catch(() => { applyTheme(); }).finally(() => {
        if (transition.current !== active) return;
        transition.current = null;
        clearThemeTransition();
      });
    } catch {
      clearThemeTransition();
      applyTheme();
    }
  }
  return <ThemeContext.Provider value={{ theme, selectTheme, toggleTheme: origin => selectTheme(currentTheme.current === 'light' ? 'dark' : 'light', origin) }}>{children}</ThemeContext.Provider>;
}

export function useTheme() { return useContext(ThemeContext); }

/** Preserve visited form state; the browser snapshots handle the reveal. */
export function ThemeArtwork({ light, dark }: { light: ReactNode; dark: ReactNode }) {
  const { theme } = useTheme();
  const visited = useRef(new Set<Theme>());
  visited.current.add(theme);
  return <div className="theme-artwork">
    {(['light', 'dark'] as const).map(value => visited.current.has(value) &&
      <div key={value} className="theme-artwork-layer" data-active={theme === value} hidden={theme !== value} aria-hidden={theme !== value} inert={theme !== value}>
        {value === 'light' ? light : dark}
      </div>)}
  </div>;
}

export function ThemeToggle() {
  useLanguage();
  const { theme, toggleTheme } = useTheme();
  const label = theme === 'light' ? '切换至深色主题' : '切换至浅色主题';
  return <button className="header-icon-button theme-toggle" type="button" aria-label={t(label)} title={t(label)} onClick={event => {
    const rect = event.currentTarget.getBoundingClientRect();
    // Keyboard activation uses the same visible origin as pointer activation.
    toggleTheme({ x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 });
  }}>
    <span className="theme-toggle-symbols" data-theme={theme} aria-hidden="true">
      <svg className="theme-toggle-moon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M20.8 13A9 9 0 0 1 11 3.2 9 9 0 1 0 20.8 13Z" /></svg>
      <svg className="theme-toggle-sun" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="4" /><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.4 1.4m11.2 11.2L19 19M5 19l1.4-1.4M17.6 6.4 19 5" /></svg>
    </span>
  </button>;
}
