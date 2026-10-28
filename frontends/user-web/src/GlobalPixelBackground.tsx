import { useEffect, useRef } from 'react';
import './global-pixel-background.css';

/** One PixelCard field survives route changes so every user page shares the same surface. */
export function GlobalPixelBackground() {
  const root = useRef<HTMLDivElement>(null);

  useEffect(() => {
    document.documentElement.dataset.yomioBackground = '';
    if (!window.matchMedia || !window.ResizeObserver || !window.MutationObserver) {
      return () => { delete document.documentElement.dataset.yomioBackground; };
    }
    let cancelled = false;
    let dispose: (() => void) | undefined;
    void import('./home/reference/pixel-effect.js').then(({ mountPixelEffect }) => {
      if (!cancelled && root.current) dispose = mountPixelEffect(root.current);
    }).catch(error => console.warn('Page pixel background unavailable.', error));
    return () => {
      cancelled = true;
      dispose?.();
      delete document.documentElement.dataset.yomioBackground;
    };
  }, []);

  return <div ref={root} className="global-pixel-background" aria-hidden="true">
    <div id="page-pixels"><canvas id="page-pixels-canvas" /></div>
  </div>;
}
