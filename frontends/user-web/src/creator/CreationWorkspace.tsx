import { t, useLanguage } from '../i18n';
import { useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react';

export function CreationWorkspace({ enabled, ready, submission, prompt, busy, results, children, editRequest = 0, newestFirst = false, hasResults = false }: {
  newestFirst?: boolean; hasResults?: boolean; editRequest?: number; enabled: boolean; ready: boolean; submission: string; prompt: string; busy: boolean; results: ReactNode; children: ReactNode;
}) {
  useLanguage();
  const root = useRef<HTMLDivElement>(null), dock = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const follow = useRef(true), initialized = useRef(false), hovered = useRef(false);
  const anchor = useRef<{ element: HTMLElement; top: number } | null>(null);
  const frame = useRef(0);
  const userScrolling = useRef(false);
  const [atBottom, setAtBottom] = useState(true), [expanded, setExpanded] = useState(false);
  const [dockHeight, setDockHeight] = useState(0);
  const id = useId();
  const focusAction = useRef<'prompt' | 'materials' | null>(null);
  const expandEditor = (action: 'prompt' | 'materials') => { focusAction.current = action; setExpanded(true); };
  const open = (atBottom && (!newestFirst || !hasResults)) || expanded || busy;
  useLayoutEffect(() => { if (enabled && editRequest) expandEditor('prompt'); }, [enabled, editRequest]);
  useLayoutEffect(() => {
    const action = focusAction.current;
    if (!open || !action) return;
    focusAction.current = null;
    let target = dock.current?.querySelector<HTMLElement>(action === 'materials'
      ? `.creation-dock-content [aria-label="${t('添加素材')}"]` : '.creation-dock-content textarea');
    if (target?.matches(':disabled')) target = dock.current?.querySelector<HTMLElement>('.creation-dock-content button:not(:disabled)');
    target?.focus({ preventScroll: true });
    if (action === 'materials') target?.click();
  }, [open]);
  const remember = () => {
    const element = [...(root.current?.querySelectorAll<HTMLElement>('[data-task-key]') ?? [])]
      .find(card => card.getBoundingClientRect().bottom > 80);
    anchor.current = element ? { element, top: element.getBoundingClientRect().top } : null;
  };
  const align = () => {
    cancelAnimationFrame(frame.current);
    frame.current = requestAnimationFrame(() => {
      if (!initialized.current || newestFirst) return;
      if (follow.current) window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'instant' });
      else if (anchor.current?.element.isConnected) {
        const delta = anchor.current.element.getBoundingClientRect().top - anchor.current.top;
        if (Math.abs(delta) > 1) window.scrollBy({ top: delta, behavior: 'instant' });
      }
    });
  };
  useLayoutEffect(() => {
    if (!enabled || !ready) return;
    initialized.current = true; follow.current = true; userScrolling.current = false; setAtBottom(true); align();
  }, [enabled, ready, submission]);
  useEffect(() => {
    if (!enabled) return;
    const scroll = () => {
      const bottom = newestFirst ? window.scrollY < 64 : document.documentElement.scrollHeight - window.innerHeight - window.scrollY < 64;
      // Scroll restoration and media/viewport layout also emit scroll events.
      // Only explicit user input can opt out of the initial bottom follow.
      if (bottom || userScrolling.current) { follow.current = bottom; setAtBottom(bottom); }
      if (bottom) userScrolling.current = false;
      remember();
    };
    const intent = (event: Event) => {
      if (event.target instanceof HTMLElement && event.target.closest('.creation-dock, input, textarea, [contenteditable="true"]')) return;
      if (event instanceof WheelEvent || event.type === 'touchstart'
        || (event instanceof KeyboardEvent && ['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' '].includes(event.key))
        || (event.type === 'pointerdown' && (event.target === document.documentElement || event.target === document.body))) {
        userScrolling.current = true; follow.current = false; remember();
      }
    };
    window.addEventListener('scroll', scroll, { passive: true });
    window.addEventListener('wheel', intent, { passive: true });
    window.addEventListener('touchstart', intent, { passive: true });
    window.addEventListener('keydown', intent);
    window.addEventListener('pointerdown', intent, { passive: true });
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(align);
    if (root.current) observer?.observe(root.current);
    const mutations = new MutationObserver(align);
    if (root.current) mutations.observe(root.current, { childList: true, subtree: true });
    root.current?.addEventListener('load', align, true);
    root.current?.addEventListener('loadedmetadata', align, true);
    return () => {
      window.removeEventListener('scroll', scroll); window.removeEventListener('wheel', intent);
      window.removeEventListener('touchstart', intent); window.removeEventListener('keydown', intent); window.removeEventListener('pointerdown', intent);
      observer?.disconnect(); mutations.disconnect(); cancelAnimationFrame(frame.current);
      root.current?.removeEventListener('load', align, true); root.current?.removeEventListener('loadedmetadata', align, true);
    };
  }, [enabled, newestFirst]);
  useLayoutEffect(() => {
    if (!enabled || !dock.current) return;
    // Keep the full dock's reserved space when collapsed, so collapsing cannot
    // shorten the document and accidentally drag a history reader back to its end.
    const measure = () => { if (open && !dock.current?.querySelector('[data-enlarged="true"]')) setDockHeight(dock.current?.getBoundingClientRect().height ?? 0); };
    measure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    observer?.observe(dock.current);
    return () => observer?.disconnect();
  }, [enabled, open]);
  useEffect(() => {
    if (!enabled) return;
    let timer: ReturnType<typeof setTimeout>;
    const idle = () => {
      clearTimeout(timer);
      timer = setTimeout(() => {
        if (hovered.current || busy || dock.current?.querySelector('[data-enlarged="true"]') || (dock.current?.contains(document.activeElement) && document.activeElement !== trigger.current)
          || document.querySelector('[role="dialog"]:not([hidden]), [role="menu"], [role="listbox"]')) return;
        setExpanded(false);
      }, 180);
    };
    // Includes portalled material dialogs and focus returning from the native file picker.
    document.addEventListener('focusin', idle); document.addEventListener('focusout', idle);
    document.addEventListener('pointerup', idle);
    dock.current?.addEventListener('pointerleave', idle);
    const observer = new MutationObserver(idle);
    observer.observe(document.body, { childList: true, subtree: true });
    return () => { clearTimeout(timer); observer.disconnect(); document.removeEventListener('focusin', idle); document.removeEventListener('focusout', idle); document.removeEventListener('pointerup', idle); dock.current?.removeEventListener('pointerleave', idle); };
  }, [enabled, busy]);
  if (!enabled) return <div className="video-creation-inline">{results}{children}</div>;
  return <div ref={root} className="video-creation-workspace" style={{ overflowAnchor: 'none' }}>
    {results}
    <div aria-hidden="true" style={{ height: dockHeight + 24 }} />
    <div ref={dock} className="creation-dock" data-expanded={open}
      onPointerEnter={event => { if (event.pointerType !== 'touch') { hovered.current = true; setExpanded(true); } }}
      onPointerLeave={() => { hovered.current = false; }}
      onFocusCapture={event => { if ((event.target as HTMLElement).closest('.creation-dock-content')) setExpanded(true); }}>
      <div className="creation-dock-summary" hidden={open}>
        <button type="button" className="creation-dock-add" aria-label={t("添加素材")} onClick={() => expandEditor('materials')}>＋</button>
        <button ref={trigger} type="button" className="creation-dock-prompt" aria-label={t("展开生成框")} aria-expanded={open} aria-controls={id} onClick={() => expandEditor('prompt')}>{prompt || t('描述你想要生成的内容画面')}</button>
        <button type="button" className="creation-dock-create" onClick={() => expandEditor('prompt')}>{t("✦ 创作")}</button>
      </div>
      <div id={id} className="creation-dock-content" hidden={!open}>{children}</div>
    </div>
  </div>;
}
