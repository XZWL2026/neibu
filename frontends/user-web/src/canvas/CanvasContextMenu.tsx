import { t, useLanguage } from '../i18n';
import { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { CanvasIcon } from './CanvasIcon';

export type CanvasMenuAction = {
  label: string; icon: Parameters<typeof CanvasIcon>[0]['name']; shortcut?: string;
  disabled?: boolean; destructive?: boolean; run(): void;
};

export function CanvasContextMenu({ x, y, actions, onClose, returnFocus }: {
  x: number; y: number; actions: CanvasMenuAction[]; onClose(): void; returnFocus: HTMLElement | null;
}) {
  useLanguage();
  const menu = useRef<HTMLDivElement>(null);
  const close = useRef(onClose); close.current = onClose;
  useEffect(() => {
    menu.current?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus();
    const outside = (event: PointerEvent) => { if (!menu.current?.contains(event.target as Node)) close.current(); };
    const resize = () => close.current();
    window.addEventListener('pointerdown', outside);
    window.addEventListener('resize', resize);
    window.addEventListener('blur', resize);
    return () => { window.removeEventListener('pointerdown', outside); window.removeEventListener('resize', resize); window.removeEventListener('blur', resize); };
  }, []);
  return createPortal(<div ref={menu} className="canvas-context-menu" role="menu" aria-label={t("画布快捷操作")}
    style={{ left: Math.max(8, Math.min(x, window.innerWidth - 256)), top: Math.max(8, Math.min(y, window.innerHeight - actions.length * 44 - 16)) }}
    onContextMenu={event => event.preventDefault()}
    onKeyDown={event => {
      if (event.key === 'Escape' || event.key === 'Tab') {
        event.stopPropagation(); if (event.key === 'Escape') { event.preventDefault(); returnFocus?.focus(); } onClose(); return;
      }
      if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      const items = [...event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')];
      const index = items.indexOf(document.activeElement as HTMLButtonElement);
      items[event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length]?.focus();
    }}>
    {actions.map(action => <button key={action.label} type="button" role="menuitem" disabled={action.disabled}
      data-destructive={action.destructive || undefined} onClick={() => { onClose(); returnFocus?.focus(); action.run(); }}>
      <CanvasIcon name={action.icon} /><span>{t(action.label)}</span>{action.shortcut && <kbd>{action.shortcut}</kbd>}
    </button>)}
  </div>, document.body);
}
