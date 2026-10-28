import { useEffect, useId, useLayoutEffect, useRef, useState, type CSSProperties, type KeyboardEvent } from 'react';
import { t } from '../i18n';

type ProjectOption = { id: string; name: string; date: string };

export function SuiteProjectSelect({ projects, value, onChange }: {
  projects: ProjectOption[];
  value: string;
  onChange: (id: string) => void;
}) {
  const id = useId();
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const list = useRef<HTMLDivElement>(null);
  const search = useRef({ text: '', at: 0 });
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const [placement, setPlacement] = useState({ side: 'below', height: 280 });
  const selected = projects.findIndex(project => project.id === value);
  const current = projects[selected];
  const expanded = open && projects.length > 0;
  const show = () => { setActive(Math.max(0, selected)); search.current.text = ''; setOpen(true); };
  const choose = (index: number) => {
    if (projects[index] && projects[index].id !== value) onChange(projects[index].id);
    setOpen(false);
  };

  useEffect(() => { setOpen(false); }, [value, projects.length]);
  useEffect(() => {
    if (!expanded) return;
    const outside = (event: PointerEvent) => {
      if (event.target instanceof Node && !root.current?.contains(event.target)) setOpen(false);
    };
    document.addEventListener('pointerdown', outside);
    return () => document.removeEventListener('pointerdown', outside);
  }, [expanded]);
  useLayoutEffect(() => {
    if (!expanded) return;
    const position = () => {
      const rect = trigger.current?.getBoundingClientRect();
      if (!rect) return;
      const below = window.innerHeight - rect.bottom - 16;
      const above = rect.top - 16;
      const up = below < 200 && above > below;
      setPlacement({ side: up ? 'above' : 'below', height: Math.min(280, Math.max(64, up ? above : below)) });
    };
    position();
    window.addEventListener('resize', position);
    window.addEventListener('scroll', position, true);
    return () => { window.removeEventListener('resize', position); window.removeEventListener('scroll', position, true); };
  }, [expanded]);
  useEffect(() => {
    if (!expanded) return;
    const option = list.current?.children[active] as HTMLElement | undefined;
    const menu = list.current;
    if (!option || !menu) return;
    if (option.offsetTop < menu.scrollTop) menu.scrollTop = option.offsetTop;
    else if (option.offsetTop + option.offsetHeight > menu.scrollTop + menu.clientHeight) menu.scrollTop = option.offsetTop + option.offsetHeight - menu.clientHeight;
  }, [active, expanded]);

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>) {
    if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      event.preventDefault();
      if (!expanded) {
        show();
        if (event.key === 'Home') setActive(0);
        if (event.key === 'End') setActive(projects.length - 1);
      } else setActive(index => event.key === 'Home' ? 0 : event.key === 'End' ? projects.length - 1 : Math.max(0, Math.min(projects.length - 1, index + (event.key === 'ArrowDown' ? 1 : -1))));
    } else if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      if (expanded) choose(active); else show();
    } else if (event.key === 'Escape' && expanded) {
      event.preventDefault(); event.stopPropagation(); setOpen(false);
    } else if (event.key === 'Tab') {
      if (expanded) choose(active);
    } else if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
      const now = Date.now();
      const text = (now - search.current.at < 600 ? search.current.text : '') + event.key.toLocaleLowerCase();
      search.current = { text, at: now };
      const match = projects.findIndex(project => project.name.toLocaleLowerCase().startsWith(text));
      if (match >= 0) { event.preventDefault(); setOpen(true); setActive(match); }
    }
  }

  return <div ref={root} className="suite-project-select" data-open={expanded} data-side={placement.side}
    style={{ '--project-menu-height': `${placement.height}px` } as CSSProperties}
    onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false); }}>
    <button ref={trigger} type="button" className="suite-project-trigger" role="combobox" aria-label={t('选择生成项目')}
      aria-haspopup="listbox" aria-expanded={expanded} aria-controls={`${id}-list`}
      aria-activedescendant={expanded ? `${id}-option-${active}` : undefined}
      disabled={!projects.length} title={current ? `${current.name}${current.date ? ` · ${current.date}` : ''}` : t('暂无项目')}
      onClick={() => expanded ? setOpen(false) : show()} onKeyDown={onKeyDown}>
      <span className="suite-project-label">{t('项目')}</span>
      <span className="suite-project-copy"><span>{current?.name || t('暂无项目')}</span>{current?.date && <small>{current.date}</small>}</span>
      <svg className="suite-project-chevron" aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="m6 9 6 6 6-6" /></svg>
    </button>
    <div ref={list} className="suite-project-menu" id={`${id}-list`} role="listbox" aria-label={t('选择生成项目')}
      aria-hidden={!expanded} inert={!expanded} onPointerDown={event => event.preventDefault()}>
      {projects.map((project, index) => <div key={project.id} id={`${id}-option-${index}`} role="option"
        aria-selected={project.id === value} data-active={active === index} className="suite-project-option"
        onPointerMove={() => setActive(index)} onClick={() => { choose(index); trigger.current?.focus(); }}>
        <span className="suite-project-copy"><span>{project.name}</span>{project.date && <small>{project.date}</small>}</span>
        {project.id === value && <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m5 12 4 4L19 6" /></svg>}
      </div>)}
    </div>
  </div>;
}
