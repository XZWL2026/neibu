import { readFileSync } from 'node:fs';
import { expect, it, vi } from 'vitest';

it('preloads only the current page theme before React, including storage fallback', () => {
  const html = readFileSync('index.html', 'utf8');
  for (const [path, saved, expected] of [
    ['/home', null, 'poster'], ['/home', 'dark', 'poster'],
    ['/home', 'unexpected', 'poster'], ['/home', 'blocked', 'poster'],
    ['/', 'dark', 'poster'], ['/user/login', 'light', 'light-home'],
    ['/user/register', 'dark', 'dark-home'], ['/create/image', 'dark', null],
    ['/canvas', null, 'light-canvas'], ['/canvas/', 'dark', 'dark-canvas'],
    ['/canvas', 'blocked', 'light-canvas'], ['/canvas?id=', 'dark', 'dark-canvas'],
    ['/canvas?id=existing', 'dark', null], ['/canvas?tab=recent', 'light', 'light-canvas'],
  ]) {
    const originalHead = document.head.innerHTML;
    const originalUrl = location.href;
    const originalTheme = localStorage.getItem('yomio-theme');
    history.replaceState(null, '', path!);
    document.head.innerHTML = new DOMParser().parseFromString(html, 'text/html').head.innerHTML;
    try {
      if (saved === 'blocked') vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => { throw new DOMException('Denied', 'SecurityError'); });
      else if (saved) localStorage.setItem('yomio-theme', saved);
      else localStorage.removeItem('yomio-theme');
      for (const script of document.querySelectorAll('script:not([src])')) window.eval(script.textContent!);
      const active = [...document.querySelectorAll<HTMLLinkElement>('link[data-home-preload], link[data-canvas-preload], link[data-home-poster]')].filter(link => link.media === 'all');
      expect(active.length, `${path}/${saved}`).toBe(expected ? 1 : 0);
      if (expected) {
        expect(active[0].rel).toBe('preload');
        expect(active[0].getAttribute('as')).toBe('image');
        expect(active[0].getAttribute('fetchpriority')).toBe('high');
        expect(active[0].href).toContain(expected === 'poster' ? '/home/reference/assets/yomio-line-logo.svg' : `${expected}-reference-0928.webp`);
      }
    } finally {
      vi.restoreAllMocks();
      document.head.innerHTML = originalHead;
      history.replaceState(null, '', originalUrl);
      if (originalTheme === null) localStorage.removeItem('yomio-theme');
      else localStorage.setItem('yomio-theme', originalTheme);
    }
  }
});
