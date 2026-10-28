import { useEffect, useRef } from 'react';
import './creator-background.css';

export function CreatorPixelBackground() {
  const root = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!window.matchMedia || !window.ResizeObserver) return;
    let cancelled = false;
    let dispose: (() => void) | undefined;
    void import('../home/reference/pixel-effect.js').then(({ mountPixelEffect }) => {
      if (!cancelled && root.current) dispose = mountPixelEffect(root.current);
    }).catch(error => console.warn('Pixel background unavailable; using plain background.', error));
    return () => { cancelled = true; dispose?.(); };
  }, []);
  return <div ref={root} className="creator-pixel-background" aria-hidden="true">
    <div id="page-pixels"><canvas id="page-pixels-canvas" /></div>
  </div>;
}
