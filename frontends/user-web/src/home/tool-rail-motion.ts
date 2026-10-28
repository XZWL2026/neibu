import { useEffect, useLayoutEffect, useRef, useState, type FocusEvent, type KeyboardEvent, type PointerEvent, type MouseEvent, type RefObject } from 'react';

/** Hover disclosure also works with keyboard and touch; hidden entries never retain focus. */
export function useToolRailDisclosure(rail: RefObject<HTMLElement | null>, trigger: RefObject<HTMLButtonElement | null>) {
  const [expanded, setExpanded] = useState(false);
  const hovered = useRef(false);
  const keyboard = useRef(false);
  const pinned = useRef(false);
  const dismissed = useRef(false);
  const suppressFocus = useRef(false);
  useEffect(() => {
    const root = rail.current;
    if (!root) return;
    let visible = true;
    const update = () => { root.dataset.railVisible = String(visible && !document.hidden); };
    const observer = typeof IntersectionObserver === 'undefined' ? null : new IntersectionObserver(entries => {
      visible = entries.some(entry => entry.isIntersecting); update();
    });
    observer?.observe(root);
    document.addEventListener('visibilitychange', update);
    update();
    return () => { observer?.disconnect(); document.removeEventListener('visibilitychange', update); root.removeAttribute('data-rail-visible'); };
  }, [rail]);
  const close = (restoreFocus = false) => {
    pinned.current = false; keyboard.current = false; dismissed.current = true;
    setExpanded(false);
    if (restoreFocus) {
      suppressFocus.current = true; trigger.current?.focus({ preventScroll: true }); suppressFocus.current = false;
    }
  };
  useEffect(() => {
    if (!expanded) return;
    const outside = (event: globalThis.PointerEvent) => { if (!rail.current?.contains(event.target as Node)) close(); };
    document.addEventListener('pointerdown', outside);
    return () => document.removeEventListener('pointerdown', outside);
  }, [expanded, rail]);
  return {
    expanded,
    close,
    toggle: () => {
      // Hover or keyboard focus may reveal the panel before the first activation.
      // That activation pins it open; only an already pinned panel toggles closed.
      if (expanded && pinned.current) close();
      else { dismissed.current = false; pinned.current = true; setExpanded(true); }
    },
    handlers: {
      onPointerEnter: (event: PointerEvent<HTMLElement>) => {
        if (event.pointerType === 'touch') return;
        hovered.current = true; if (!dismissed.current) setExpanded(true);
      },
      onPointerLeave: () => {
        hovered.current = false; dismissed.current = false;
        if (!keyboard.current && !pinned.current) setExpanded(false);
      },
      onPointerDownCapture: () => { keyboard.current = false; },
      onFocusCapture: (event: FocusEvent<HTMLElement>) => {
        if (!suppressFocus.current && !dismissed.current && event.target.matches(':focus-visible')) {
          keyboard.current = true; setExpanded(true);
        }
      },
      onBlurCapture: (event: FocusEvent<HTMLElement>) => {
        if (event.relatedTarget instanceof Node && rail.current?.contains(event.relatedTarget)) return;
        dismissed.current = false;
        keyboard.current = false;
        if (!hovered.current && !pinned.current) setExpanded(false);
      },
      onKeyDown: (event: KeyboardEvent<HTMLElement>) => {
        if (event.key === 'Escape' && expanded) { event.preventDefault(); event.stopPropagation(); close(true); }
      },
      onClickCapture: (event: MouseEvent<HTMLElement>) => {
        if (event.target instanceof Element && event.target.closest('.ym-tool')) close();
      },
    },
  };
}

/** A single critically damped highlight carries its velocity when its target changes. */
export function useToolRailMotion(list: RefObject<HTMLDivElement | null>, selectedKind: string) {
  const hover = useRef<HTMLElement | null>(null);
  const focus = useRef<HTMLElement | null>(null);
  const press = useRef<HTMLElement | null>(null);
  const move = useRef<(snap?: boolean) => void>(() => {});

  useLayoutEffect(() => {
    const root = list.current;
    const highlight = root?.querySelector<HTMLElement>('.ym-rail-highlight');
    if (!root || !highlight) return;
    const media = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    let frame = 0;
    let last = 0;
    let x = 0, y = 0, vx = 0, vy = 0, targetX = 0, targetY = 0;
    const paint = () => { highlight.style.transform = `translate3d(${x}px, ${y}px, 0)`; };
    const settle = () => {
      cancelAnimationFrame(frame); frame = 0; last = 0;
      x = targetX; y = targetY; vx = 0; vy = 0;
      paint(); root.removeAttribute('data-rail-moving');
    };
    const tick = (now: number) => {
      const dt = Math.min((now - last) / 1000, .064);
      last = now;
      // Exact critically damped spring step: no oscillation or Euler instability after dropped frames.
      const omega = 26;
      const decay = Math.exp(-omega * dt);
      const dx = x - targetX, dy = y - targetY;
      const cx = vx + omega * dx, cy = vy + omega * dy;
      x = targetX + (dx + cx * dt) * decay;
      y = targetY + (dy + cy * dt) * decay;
      vx = (vx - omega * cx * dt) * decay;
      vy = (vy - omega * cy * dt) * decay;
      paint();
      if (Math.abs(x - targetX) + Math.abs(y - targetY) < .1 && Math.abs(vx) + Math.abs(vy) < 2) settle();
      else frame = requestAnimationFrame(tick);
    };
    const retarget = (snap = false) => {
      const target = press.current ?? hover.current ?? focus.current ?? root.querySelector<HTMLElement>('.ym-selected');
      if (!target || !root.contains(target)) return;
      targetX = target.offsetLeft; targetY = target.offsetTop;
      highlight.style.width = `${target.offsetWidth}px`;
      highlight.style.height = `${target.offsetHeight}px`;
      root.dataset.railReady = 'true';
      if (snap || media?.matches || document.hidden) { settle(); return; }
      if (!frame && (Math.abs(x - targetX) > .1 || Math.abs(y - targetY) > .1)) {
        root.dataset.railMoving = 'true'; last = performance.now(); frame = requestAnimationFrame(tick);
      }
    };
    move.current = retarget;
    retarget(true);
    const resize = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(() => retarget(true));
    resize?.observe(root);
    root.querySelectorAll('.ym-tool').forEach(element => resize?.observe(element));
    const release = () => { press.current = null; root.removeAttribute('data-rail-pressed'); retarget(); };
    const preference = () => { retarget(true); };
    const visibility = () => { if (document.hidden) { hover.current = null; release(); settle(); } };
    media?.addEventListener?.('change', preference);
    window.addEventListener('pointerup', release);
    window.addEventListener('pointercancel', release);
    document.addEventListener('visibilitychange', visibility);
    return () => {
      cancelAnimationFrame(frame); resize?.disconnect(); move.current = () => {};
      media?.removeEventListener?.('change', preference);
      window.removeEventListener('pointerup', release); window.removeEventListener('pointercancel', release);
      document.removeEventListener('visibilitychange', visibility);
      root.removeAttribute('data-rail-moving'); root.removeAttribute('data-rail-ready'); root.removeAttribute('data-rail-pressed');
    };
  }, [list]);

  useLayoutEffect(() => { move.current(); }, [selectedKind]);
  const tool = (target: EventTarget | null) => {
    const element = target instanceof Element ? target.closest<HTMLElement>('.ym-tool') : null;
    return element && list.current?.contains(element) ? element : null;
  };
  return {
    onPointerOver: (event: PointerEvent<HTMLDivElement>) => {
      if (event.pointerType === 'touch') return;
      const next = tool(event.target);
      if (!next || hover.current === next) return;
      hover.current = next; move.current();
    },
    onPointerLeave: () => {
      hover.current = null; press.current = null;
      list.current?.removeAttribute('data-rail-pressed'); move.current();
    },
    onPointerDown: (event: PointerEvent<HTMLDivElement>) => {
      const next = tool(event.target);
      if (!next || event.button !== 0) return;
      focus.current = null;
      press.current = next;
      if (list.current) list.current.dataset.railPressed = 'true';
      move.current(event.pointerType === 'touch');
    },
    onFocusCapture: (event: FocusEvent<HTMLDivElement>) => {
      const next = tool(event.target);
      focus.current = next?.matches(':focus-visible') ? next : null;
      if (focus.current) hover.current = null;
      move.current();
    },
    onBlurCapture: (event: FocusEvent<HTMLDivElement>) => {
      const next = tool(event.relatedTarget);
      focus.current = next?.matches(':focus-visible') ? next : null;
      move.current();
    },
  };
}
