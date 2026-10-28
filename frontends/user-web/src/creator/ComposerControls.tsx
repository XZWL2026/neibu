import { t, useLanguage } from '../i18n';
import { useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

export function ComposerIcon({ name }: { name: 'image' | 'video' | 'model' | 'ratio' | 'settings' | 'chevron' | 'edit' | 'retry' | 'delete' }) {
  useLanguage();
  const paths = {
    image: <><rect x="3" y="3" width="18" height="18" rx="3" /><circle cx="8" cy="8" r="1.5" /><path d="m3 16 5-5 4 4 3-3 6 6" /></>,
    video: <><rect x="3" y="4" width="16" height="17" rx="3" /><path d="m9 9 5 4-5 4Z M19 2v6 M16 5h6" /></>,
    model: <><path d="m12 2 9 5v10l-9 5-9-5V7Z M3 7l9 5 9-5 M12 12v10" /></>,
    ratio: <><rect x="3" y="5" width="18" height="14" rx="2" /><rect x="6" y="2" width="12" height="20" rx="2" /></>,
    settings: <><path d="M3 7h10m4 0h4M3 17h4m4 0h10" /><circle cx="15" cy="7" r="3" /><circle cx="9" cy="17" r="3" /></>,
    edit: <path d="m15 4 5 5M4 20l1-5L16 4a2 2 0 0 1 4 4L9 19l-5 1Zm8 0h9" />,
    retry: <path d="M20 9a8 8 0 0 0-14-4L3 8m0-5v5h5M4 15a8 8 0 0 0 14 4l3-3m0 5v-5h-5" />,
    delete: <path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7" />,
    chevron: <path d="m5 9 7 7 7-7" />,
  };
  return <svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">{paths[name]}</svg>;
}

export function RatioIcon({ ratio }: { ratio: string }) {
  useLanguage();
  const parts = /^(\d+(?:\.\d+)?):(\d+(?:\.\d+)?)$/.exec(ratio);
  const width = Number(parts?.[1]), height = Number(parts?.[2]);
  if (!(width > 0 && height > 0 && Number.isFinite(width) && Number.isFinite(height))) return null;
  const scale = 28 / Math.max(width, height);
  return <span className="video-ratio-frame" aria-hidden="true"><span className="video-ratio-icon" style={{ width: width * scale, height: height * scale }} /></span>;
}

export function ComposerDialog({ title, closeLabel, onClose, onReset, children, anchored = false, className = '', open = true, inlineClosed, headerActions, closeIcon }: {
  title: string; closeLabel: string; onClose(): void; onReset?: () => void; children: ReactNode; anchored?: boolean; className?: string; open?: boolean; inlineClosed?: boolean; headerActions?: ReactNode; closeIcon?: ReactNode;
}) {
  useLanguage();
  const dialog = useRef<HTMLDialogElement>(null);
  const inlineHost = useRef<HTMLDivElement>(null), modalHost = useRef<HTMLDivElement>(null);
  const [content] = useState(() => { const element = document.createElement('div'); element.style.display = 'contents'; return element; });
  const persistent = inlineClosed !== undefined;
  useLayoutEffect(() => {
    // Keep one portal target so opening a preview preserves media and paid request state.
    if (persistent) (inlineClosed && !open ? inlineHost.current : modalHost.current)?.append(content);
  }, [content, persistent, inlineClosed, open]);
  useEffect(() => {
    if (!open) return;
    const previous = document.activeElement as HTMLElement | null;
    const element = dialog.current;
    if (element?.showModal) element.showModal(); else element?.setAttribute('open', '');
    const place = () => {
      if (!anchored || !element || !previous) return;
      const rect = previous.getBoundingClientRect();
      element.style.margin = '0'; element.style.top = 'auto'; element.style.right = 'auto';
      element.style.left = `${Math.max(12, Math.min(rect.left, window.innerWidth - element.getBoundingClientRect().width - 12))}px`;
      element.style.bottom = `${Math.max(12, window.innerHeight - rect.top + 10)}px`;
      element.style.maxHeight = `${Math.max(120, rect.top - 22)}px`;
    };
    place(); window.addEventListener('resize', place);
    element?.querySelector<HTMLElement>('[aria-pressed="true"], button')?.focus();
    return () => { window.removeEventListener('resize', place); element?.close?.(); element?.removeAttribute('open'); previous?.focus({ preventScroll: true }); };
  }, [open]);
  return <>{inlineClosed && <div ref={inlineHost} style={{ display: 'contents' }} />}
  <dialog ref={dialog} role="dialog" className={`video-settings-panel ${className}${anchored ? ` composer-popover ${onReset ? 'composer-settings-popover' : 'composer-model-popover'}` : ''}`} aria-label={t(title)}
    onCancel={event => { event.preventDefault(); onClose(); }} onClick={event => { if (event.target === event.currentTarget) onClose(); }}>
    <div>{open && <header><h2>{t(title)}</h2>{onReset && <button type="button" className="video-reset" onClick={onReset}>{t("重置")}</button>}
      {headerActions}<button type="button" aria-label={t(closeLabel)} onClick={onClose}>{closeIcon || '×'}</button></header>}{persistent ? <div ref={modalHost} style={{ display: 'contents' }} /> : children}</div>
  </dialog>{persistent && createPortal(children, content)}</>;
}

export function sortedResolutions(values: readonly string[]) {
  const rank = (value: string) => {
    const match = /^(\d+(?:\.\d+)?)([kp])?$/i.exec(value.trim());
    return match ? Number(match[1]) * (match[2]?.toLowerCase() === 'k' ? 1024 : 1) : Infinity;
  };
  return [...values].sort((a, b) => rank(a) - rank(b));
}

export function ComposerModeMenu<T extends string>({ label, icon, value, options, disabled = false, onChange }: {
  label: string; icon: 'image' | 'video'; value: T; options: readonly { value: T; label: string }[];
  disabled?: boolean; onChange(value: T): void;
}) {
  useLanguage();
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const id = useId();
  const close = () => { setOpen(false); trigger.current?.focus(); };
  useEffect(() => {
    if (disabled) { setOpen(false); return; }
    if (!open) return;
    root.current?.querySelector<HTMLButtonElement>('[aria-checked="true"]')?.focus();
    const outside = (event: PointerEvent) => { if (!root.current?.contains(event.target as Node)) setOpen(false); };
    document.addEventListener('pointerdown', outside);
    return () => document.removeEventListener('pointerdown', outside);
  }, [open, disabled]);
  return <div className="composer-mode-wrap" ref={root}
    onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false); }}
    onKeyDown={event => {
      if (!open) return;
      if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(); }
      if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      const items = [...(root.current?.querySelectorAll<HTMLButtonElement>('[role="menuitemradio"]') ?? [])];
      const current = items.indexOf(document.activeElement as HTMLButtonElement);
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : (current + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
      items[next]?.focus();
    }}>
    <button ref={trigger} className="composer-mode" type="button" aria-label={t(label)} value={value} disabled={disabled}
      aria-haspopup="menu" aria-expanded={open && !disabled} aria-controls={id} onClick={() => setOpen(!open)}
      onKeyDown={event => { if (!open && ['ArrowDown', 'ArrowUp'].includes(event.key)) { event.preventDefault(); setOpen(true); } }}>
      <ComposerIcon name={icon} /><span>{t(options.find(option => option.value === value)?.label)}</span><ComposerIcon name="chevron" />
    </button>
    {open && !disabled && <div id={id} className="composer-mode-menu" role="menu" aria-label={t(label)}>
      {options.map(option => <button type="button" role="menuitemradio" tabIndex={-1} aria-checked={option.value === value} value={option.value} key={option.value}
        onClick={() => { onChange(option.value); close(); }}><ComposerIcon name={icon} /><span>{t(option.label)}</span>{option.value === value && <span aria-hidden="true">✓</span>}</button>)}
    </div>}
  </div>;
}
