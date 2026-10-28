import { useEffect, useRef, useState, type RefObject } from 'react';
import { t } from '../i18n';

export function ComposerResizeHandle({ composer }: { composer: RefObject<HTMLElement | null> }) {
  const [height, setHeight] = useState<number | undefined>(() => parseFloat(composer.current?.style.getPropertyValue('--composer-resized-height') ?? '') || undefined);
  const [limits, setLimits] = useState(bounds);
  const limitsRef = useRef(limits);
  const drag = useRef<{ id: number; y: number; height: number; previous: string; current: number } | null>(null);
  function bounds() {
    const max = Math.floor(Math.min(700, window.innerHeight * .72));
    const toolbarHeight = composer.current?.querySelector('.composer-toolbar')?.getBoundingClientRect().height ?? 0;
    return { min: Math.min(Math.max(window.innerWidth <= 760 ? 220 : 176, toolbarHeight + 134), max), max };
  }
  const apply = (value: number) => {
    const { min, max } = limitsRef.current;
    const next = Math.round(Math.max(min, Math.min(max, value)));
    composer.current?.style.setProperty('--composer-resized-height', `${next}px`);
    return next;
  };
  const finish = (cancel = false) => {
    const current = drag.current;
    if (!current) return;
    drag.current = null;
    composer.current?.removeAttribute('data-resizing');
    if (cancel) {
      if (current.previous) composer.current?.style.setProperty('--composer-resized-height', current.previous);
      else composer.current?.style.removeProperty('--composer-resized-height');
    } else setHeight(current.current);
  };
  useEffect(() => {
    const resize = () => {
      limitsRef.current = bounds(); setLimits(limitsRef.current);
      if (composer.current?.style.getPropertyValue('--composer-resized-height')) {
        setHeight(apply(composer.current.getBoundingClientRect().height));
      }
    };
    resize();
    window.addEventListener('resize', resize);
    const toolbar = composer.current?.querySelector('.composer-toolbar');
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(resize);
    if (toolbar) observer?.observe(toolbar);
    return () => { observer?.disconnect(); window.removeEventListener('resize', resize); composer.current?.removeAttribute('data-resizing'); };
  }, [composer]);
  return <div className="composer-resize-handle" role="separator" tabIndex={0} aria-orientation="horizontal"
    aria-label={t('调整输入框高度')} aria-valuemin={limits.min} aria-valuemax={limits.max} aria-valuenow={height ?? limits.min}
    title={t('上下拖动调整高度，双击恢复默认')}
    onPointerDown={event => {
      if (!event.isPrimary || event.button !== 0 || !composer.current) return;
      event.preventDefault(); event.currentTarget.focus({ preventScroll: true });
      limitsRef.current = bounds(); setLimits(limitsRef.current);
      const initial = composer.current.getBoundingClientRect().height;
      drag.current = { id: event.pointerId, y: event.clientY, height: initial, current: initial,
        previous: composer.current.style.getPropertyValue('--composer-resized-height') };
      composer.current.setAttribute('data-resizing', 'true');
      event.currentTarget.setPointerCapture(event.pointerId);
    }}
    onPointerMove={event => {
      const current = drag.current;
      if (current?.id !== event.pointerId) return;
      // Update only the panel's CSS size; avoid rendering the form on every mouse move.
      current.current = apply(current.height + current.y - event.clientY);
    }}
    onPointerUp={event => {
      if (drag.current?.id !== event.pointerId) return;
      drag.current.current = apply(drag.current.height + drag.current.y - event.clientY);
      finish(); event.currentTarget.releasePointerCapture(event.pointerId);
    }}
    onPointerCancel={() => finish(true)} onLostPointerCapture={() => finish()}
    onDoubleClick={() => { composer.current?.style.removeProperty('--composer-resized-height'); setHeight(undefined); }}
    onKeyDown={event => {
      if (event.key === 'Escape' && drag.current) { event.preventDefault(); finish(true); return; }
      if (!['ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key) || drag.current) return;
      event.preventDefault();
      const current = composer.current?.getBoundingClientRect().height ?? limits.min;
      setHeight(apply(event.key === 'Home' ? limits.min : event.key === 'End' ? limits.max : current + (event.key === 'ArrowUp' ? 1 : -1) * (event.shiftKey ? 40 : 10)));
    }}><span aria-hidden="true" /></div>;
}
