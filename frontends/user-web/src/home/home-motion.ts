import { useEffect, useLayoutEffect, useRef, type RefObject } from 'react';

const easing = 'cubic-bezier(.16,1,.3,1)';
const reducedMotion = () => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;

/** Autoplay only while the homepage is visible and motion/data-saving preferences allow it. */
export function useHomeVideo(video: RefObject<HTMLVideoElement | null>, stage: RefObject<HTMLElement | null>) {
  useEffect(() => {
    const element = video.current;
    if (!element) return;
    const media = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    const connection = (navigator as Navigator & { connection?: { saveData?: boolean } }).connection;
    let inView = true;
    let ready = false;
    let attached = Boolean(element.getAttribute('src'));
    let scheduled = false;
    let idle: number | undefined;
    let delay: number | undefined;
    const apply = () => {
      if (!ready || media?.matches || connection?.saveData || !inView || document.hidden) {
        element.pause();
        return;
      }
      if (!attached) {
        const source = element.dataset.src;
        if (!source) return;
        element.src = source;
        attached = true;
        element.load();
      }
      void element.play()?.catch(() => { /* Keep the poster visible if autoplay is blocked. */ });
    };
    const start = () => { ready = true; apply(); };
    const schedule = () => {
      if (scheduled) return;
      scheduled = true;
      if (typeof window.requestIdleCallback === 'function') idle = window.requestIdleCallback(start, { timeout: 1500 });
      else delay = window.setTimeout(start, 250);
    };
    const observer = typeof IntersectionObserver === 'undefined' ? null : new IntersectionObserver(entries => {
      inView = entries[0].isIntersecting;
      apply();
    }, { threshold: 0 });
    if (stage.current) observer?.observe(stage.current);
    if (document.readyState === 'complete') schedule();
    else window.addEventListener('load', schedule, { once: true });
    const fallback = window.setTimeout(schedule, 5000);
    media?.addEventListener?.('change', apply);
    document.addEventListener('visibilitychange', apply);
    return () => {
      observer?.disconnect();
      window.removeEventListener('load', schedule);
      window.clearTimeout(fallback);
      if (delay !== undefined) window.clearTimeout(delay);
      if (idle !== undefined) window.cancelIdleCallback(idle);
      media?.removeEventListener?.('change', apply);
      document.removeEventListener('visibilitychange', apply);
      element.pause();
    };
  }, [video, stage]);
}

/** FLIP only surviving gallery cards; new cards crossfade without a repeated scroll entrance. */
export function useGalleryMotion(grid: RefObject<HTMLDivElement | null>, filter: string) {
  const previous = useRef(new Map<string, DOMRect>());
  const animations = useRef<Animation[]>([]);
  const capture = () => {
    previous.current = new Map(Array.from(grid.current?.querySelectorAll<HTMLElement>('[data-work-id]') ?? [])
      .map(card => [card.dataset.workId!, card.getBoundingClientRect()]));
    animations.current.forEach(animation => animation.cancel());
    animations.current = [];
  };
  useLayoutEffect(() => {
    if (reducedMotion() || !previous.current.size) { previous.current.clear(); return; }
    grid.current?.querySelectorAll<HTMLElement>('[data-work-id]').forEach(card => {
      if (!card.animate) return;
      const before = previous.current.get(card.dataset.workId!);
      const after = card.getBoundingClientRect();
      const frames = before
        ? [{ transform: `translate(${before.x - after.x}px, ${before.y - after.y}px)` }, { transform: 'translate(0, 0)' }]
        : [{ opacity: 0.35 }, { opacity: 1 }];
      animations.current.push(card.animate(frames, { duration: before ? 280 : 160, easing }));
    });
    previous.current.clear();
  }, [filter, grid]);
  useEffect(() => {
    const media = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    const stop = () => { if (media?.matches) animations.current.forEach(animation => animation.cancel()); };
    media?.addEventListener?.('change', stop);
    return () => { animations.current.forEach(animation => animation.cancel()); media?.removeEventListener?.('change', stop); };
  }, []);
  return capture;
}

/** Preview grows from its artwork and returns to it. Escape can reverse an opening animation immediately. */
export function useArtworkMotion(dialog: RefObject<HTMLDialogElement | null>, open: boolean) {
  const source = useRef<HTMLButtonElement | null>(null);
  const animation = useRef<Animation | null>(null);
  const closing = useRef(false);
  const originTransform = () => {
    const target = dialog.current?.getBoundingClientRect();
    const from = source.current?.getBoundingClientRect();
    if (!target || !from || from.bottom <= 0 || from.top >= innerHeight) return 'scale(.98)';
    return `translate(${from.x - target.x}px, ${from.y - target.y}px) scale(${from.width / target.width})`;
  };
  useLayoutEffect(() => {
    const element = dialog.current;
    if (!open || !element) return;
    closing.current = false;
    // Keep the page beneath the preview still, without moving the desktop composition when a scrollbar is removed.
    const body = document.body;
    const previousOverflow = body.style.overflow;
    const previousPadding = body.style.paddingRight;
    const scrollbar = innerWidth - document.documentElement.clientWidth;
    if (scrollbar > 0) body.style.paddingRight = `${parseFloat(getComputedStyle(body).paddingRight) + scrollbar}px`;
    body.style.overflow = 'hidden';
    element.dataset.motion = 'opening';
    if (!element.open) element.showModal();
    if (!reducedMotion() && element.animate) {
      const entry = element.animate([{ transform: originTransform(), opacity: 0.55 }, { transform: 'none', opacity: 1 }], { duration: 360, easing });
      animation.current = entry;
      void entry.finished.then(() => { if (animation.current === entry) { animation.current = null; delete element.dataset.motion; } }).catch(() => {});
    } else delete element.dataset.motion;
    const media = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    const stop = () => {
      if (!media?.matches) return;
      animation.current?.cancel(); animation.current = null;
      if (closing.current) element.close(); else delete element.dataset.motion;
    };
    media?.addEventListener?.('change', stop);
    const trapTab = (event: KeyboardEvent) => {
      if (event.key !== 'Tab') return;
      const focusable = Array.from(element.querySelectorAll<HTMLElement>('button:not(:disabled), a[href], input:not(:disabled), [tabindex="0"]'))
        .filter(control => control.getClientRects().length > 0);
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (!first) return;
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    element.addEventListener('keydown', trapTab);
    return () => {
      animation.current?.cancel(); animation.current = null;
      media?.removeEventListener?.('change', stop);
      element.removeEventListener('keydown', trapTab);
      body.style.overflow = previousOverflow; body.style.paddingRight = previousPadding;
      delete element.dataset.motion;
      source.current?.focus({ preventScroll: true });
    };
  }, [open, dialog]);
  return {
    rememberSource: (button: HTMLButtonElement) => { source.current = button; },
    close: () => {
      const element = dialog.current;
      if (!element?.open || closing.current) return;
      closing.current = true;
      const live = getComputedStyle(element);
      const transform = live.transform;
      const opacity = live.opacity;
      element.style.setProperty('--ym-scrim-start', getComputedStyle(element, '::backdrop').opacity);
      animation.current?.cancel(); animation.current = null;
      if (reducedMotion() || !element.animate) { element.close(); return; }
      element.dataset.motion = 'closing';
      const exit = element.animate([{ transform, opacity }, { transform: originTransform(), opacity: 0 }], { duration: 200, easing, fill: 'forwards' });
      animation.current = exit;
      void exit.finished.then(() => { if (animation.current === exit) element.close(); }).catch(() => {});
    },
  };
}
