import { t, useLanguage } from '../i18n';
import { useEffect, useRef, useState, type ComponentProps } from 'react';
import { ComposerResizeHandle } from './ComposerResizeHandle';

export function ExpandableComposer({ children, className, resizable = false, ...props }: ComponentProps<'section'> & { resizable?: boolean }) {
  useLanguage();
  const [enlarged, setEnlarged] = useState(false);
  const root = useRef<HTMLElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const open = () => {
    const element = root.current;
    if (element) {
      const { width, left } = element.getBoundingClientRect();
      element.style.setProperty('--composer-expanded-width', `${width}px`);
      element.style.setProperty('--composer-expanded-left', `${left}px`);
    }
    setEnlarged(true);
  };
  const close = () => { setEnlarged(false); button.current?.focus({ preventScroll: true }); };
  useEffect(() => {
    if (!enlarged) return;
    root.current?.querySelector<HTMLElement>('textarea:not(:disabled), [contenteditable="true"]')?.focus({ preventScroll: true });
  }, [enlarged]);
  return <section {...props} ref={root} className={`${className} composer-expandable`} data-enlarged={enlarged}
    onKeyDown={event => {
      if (enlarged && event.key === 'Escape' && !event.defaultPrevented
        && root.current?.contains(event.target as Node) && !(event.target as HTMLElement).closest('[role="dialog"], [role="menu"], [role="listbox"]')) {
        event.preventDefault(); event.stopPropagation(); close();
      }
    }}>
    {resizable && !enlarged && <ComposerResizeHandle composer={root} />}
    <button ref={button} type="button" className="composer-expand-toggle" aria-label={t(enlarged ? '收起输入框' : '放大输入框')}
      title={t(enlarged ? '收起输入框' : '放大输入框')} aria-expanded={enlarged} onClick={() => enlarged ? close() : open()}>
      <svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <path d={enlarged ? 'M20 4l-6 6m0-5v5h5M4 20l6-6m-5 0h5v5' : 'M14 4h6v6m0-6-6 6M10 20H4v-6m0 6 6-6'} />
      </svg>
    </button>
    {children}
  </section>;
}
