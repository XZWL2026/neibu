import { useEffect, useRef } from 'react';
import logo from './reference/assets/yomio-line-logo.svg';

export function HomeReferenceBackdrop() {
  const root = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!window.matchMedia || !window.ResizeObserver || !window.IntersectionObserver) return;
    let cancelled = false;
    let dispose: (() => void) | undefined;
    void import('./reference/effects.js').then(({ mountHomeEffects }) => {
      const home = root.current?.closest<HTMLElement>('.yomio-home');
      if (!cancelled && home) dispose = mountHomeEffects(home);
    }).catch(error => console.warn('Home effects unavailable; using static artwork.', error));
    return () => { cancelled = true; dispose?.(); };
  }, []);
  return <div ref={root} className="ym-backdrop hero-waves" id="hero-waves" aria-hidden="true">
    <canvas className="hero-waves-canvas" id="hero-waves-canvas" />
    <img className="hero-line-logo" src={logo} alt="" width={1280} height={420} fetchPriority="high" />
  </div>;
}
