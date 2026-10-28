import { useEffect, useRef, useState, type CSSProperties, type PointerEvent, type KeyboardEvent } from 'react';

const storageKey = 'yomio-creation-navigation-position';
type Position = { x: number; y: number };
const initialPosition = { x: 12, y: 180 };
function constrain({ x, y }: Position): Position {
  return { x: Math.max(8, Math.min(x, window.innerWidth - 88)), y: Math.max(74, Math.min(y, window.innerHeight - 88)) };
}
function readPosition() {
  try {
    const saved = JSON.parse(sessionStorage.getItem(storageKey) ?? 'null');
    if (saved && Number.isFinite(saved.x) && Number.isFinite(saved.y)) return constrain(saved);
  } catch { /* Storage is optional; dragging still works in memory. */ }
  return constrain(initialPosition);
}
function remember(position: Position) {
  try { sessionStorage.setItem(storageKey, JSON.stringify(position)); } catch { /* Keep in-memory position. */ }
}

/** Pointer capture keeps dragging stable when the pointer leaves the circle. */
export function useNavigationPosition(enabled: boolean, close: () => void) {
  const [position, setPosition] = useState(readPosition);
  const [viewport, setViewport] = useState(() => ({ width: innerWidth, height: innerHeight }));
  const [dragging, setDragging] = useState(false);
  const gesture = useRef<{ pointerId: number; x: number; y: number; origin: Position; moved: boolean } | null>(null);
  const current = useRef(position);
  const suppressClick = useRef(false);
  function move(next: Position) { current.current = constrain(next); setPosition(current.current); }
  useEffect(() => {
    if (!enabled) return;
    const resize = () => { setViewport({ width: innerWidth, height: innerHeight }); move(current.current); };
    window.addEventListener('resize', resize);
    return () => window.removeEventListener('resize', resize);
  }, [enabled]);
  const opensUp = position.y > viewport.height / 2;
  const menuHeight = Math.max(80, opensUp ? position.y - 82 : viewport.height - position.y - 88);
  const finish = (event: PointerEvent<HTMLButtonElement>, cancelled = false) => {
    const active = gesture.current;
    if (!active || event.pointerId !== active.pointerId) return;
    gesture.current = null;
    if (cancelled) move(active.origin);
    if (active.moved) { suppressClick.current = true; remember(current.current); }
    setDragging(false);
    if (event.currentTarget.hasPointerCapture?.(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
  };
  return {
    dragging,
    opensUp,
    alignsRight: position.x + 156 > viewport.width - 8,
    style: enabled ? { left: position.x, top: position.y, '--navigation-menu-height': `${menuHeight}px` } as CSSProperties : undefined,
    consumeClick: (detail: number) => {
      const suppressed = detail !== 0 && suppressClick.current;
      suppressClick.current = false;
      return suppressed;
    },
    handlers: enabled ? {
      onPointerDown: (event: PointerEvent<HTMLButtonElement>) => {
        if (event.button !== 0 || gesture.current) return;
        suppressClick.current = false;
        gesture.current = { pointerId: event.pointerId, x: event.clientX, y: event.clientY, origin: current.current, moved: false };
        event.currentTarget.setPointerCapture?.(event.pointerId);
      },
      onPointerMove: (event: PointerEvent<HTMLButtonElement>) => {
        const active = gesture.current;
        if (!active || event.pointerId !== active.pointerId) return;
        const dx = event.clientX - active.x, dy = event.clientY - active.y;
        if (!active.moved && Math.hypot(dx, dy) < 6) return;
        if (!active.moved) { active.moved = true; setDragging(true); close(); }
        event.preventDefault();
        move({ x: active.origin.x + dx, y: active.origin.y + dy });
      },
      onPointerUp: (event: PointerEvent<HTMLButtonElement>) => finish(event),
      onPointerCancel: (event: PointerEvent<HTMLButtonElement>) => finish(event, true),
      onLostPointerCapture: (event: PointerEvent<HTMLButtonElement>) => finish(event, true),
      onKeyDown: (event: KeyboardEvent<HTMLButtonElement>) => {
        if (!event.altKey || !['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home'].includes(event.key)) return;
        event.preventDefault(); close();
        const directions: Record<string, [number, number]> = { ArrowLeft: [-20, 0], ArrowRight: [20, 0], ArrowUp: [0, -20], ArrowDown: [0, 20] };
        const direction = directions[event.key];
        move(direction ? { x: current.current.x + direction[0], y: current.current.y + direction[1] } : initialPosition);
        remember(current.current);
      },
    } : {},
  };
}
