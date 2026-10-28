import { t, useLanguage } from '../i18n';
import { useNavigate } from 'react-router-dom';
import { useEffect, useRef, useState } from 'react';
import { ProductionVideoComposer } from '../creator/ProductionVideoComposer';
import { ProductionImageComposer } from '../creator/ProductionImageComposer';

export function HomeComposer() {
  useLanguage();
  const navigate = useNavigate();
  const [expanded, setExpanded] = useState(false);
  const [kind, setKind] = useState<'image' | 'video'>('image');
  const root = useRef<HTMLElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const hovered = useRef(false);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const cancelCollapse = () => clearTimeout(timer.current);
  useEffect(() => () => clearTimeout(timer.current), []);
  const expand = () => { cancelCollapse(); setExpanded(true); };
  const activeLayerOpen = () => root.current?.querySelector('[role="tabpanel"]:not([hidden]) [role="dialog"]:not([hidden]), [role="tabpanel"]:not([hidden]) [role="menu"], [role="tabpanel"]:not([hidden]) [role="listbox"]');
  const collapseWhenIdle = (pointerLeft = false) => {
    cancelCollapse();
    timer.current = setTimeout(() => {
      const region = root.current;
      if (!region || hovered.current || region.querySelector('[role="tabpanel"]:not([hidden]) [data-enlarged="true"]') || (!pointerLeft && region.contains(document.activeElement))
        || activeLayerOpen() || document.activeElement?.closest('dialog[open]')) return;
      setExpanded(false);
      if (region.contains(document.activeElement)) trigger.current?.focus({ preventScroll: true });
    }, 180);
  };
  const collapse = () => {
    cancelCollapse();
    hovered.current = false;
    setExpanded(false);
    trigger.current?.focus();
  };

  return <section ref={root} className="home-composer-dock" aria-label={t("快捷创作")} data-expanded={expanded}
    onPointerEnter={event => { if (event.pointerType !== 'touch') { hovered.current = true; expand(); } }}
    onPointerLeave={event => { if (event.pointerType !== 'touch') { hovered.current = false; collapseWhenIdle(true); } }}
    onFocusCapture={event => { if (event.target !== trigger.current) expand(); }}
    onBlurCapture={() => collapseWhenIdle()}
    onKeyDown={event => {
      if (event.key === 'Escape' && !activeLayerOpen()) {
        event.preventDefault(); collapse();
      }
    }}>
    <button ref={trigger} className="home-composer-collapsed" type="button" aria-label={t("展开创作区域")} aria-expanded={expanded} aria-controls="home-composer-content" onClick={expand} tabIndex={expanded ? -1 : 0}>
      <span className="home-composer-add" aria-hidden="true"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round"><path d="M12 5v14M5 12h14" /></svg></span><span>{t("描述你想要生成的内容画面")}</span><span className="home-composer-go" aria-hidden="true">{t("开始创作 →")}</span>
    </button>
    <div id="home-composer-content" className="home-composer-content" inert={!expanded} aria-hidden={!expanded}>
      <div className="home-composer-body">
      <header className="home-composer-header">
        <div role="tablist" aria-label={t("创作类型")}>{(['image', 'video'] as const).map((value, index) => <button key={value} id={`home-${value}-tab`} type="button" role="tab" aria-selected={kind === value} aria-controls={`home-${value}-panel`} tabIndex={kind === value ? 0 : -1} onClick={() => setKind(value)} onKeyDown={event => {
          if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
            event.preventDefault();
            const next = event.key === 'Home' ? 'image' : event.key === 'End' ? 'video' : index === 0 ? 'video' : 'image';
            setKind(next);
            document.getElementById(`home-${next}-tab`)?.focus();
          }
        }}>{t(value === 'image' ? '图片' : '视频')}</button>)}</div>
        <span>{t("从一个想法开始")}</span>
      </header>
      {(['image', 'video'] as const).map(value => <div key={value} id={`home-${value}-panel`} role="tabpanel" aria-labelledby={`home-${value}-tab`} hidden={kind !== value}>
        {value === 'video' ? <ProductionVideoComposer draftScope="home" entryOnly onHandoff={() => navigate('/create/video')} className="home-composer-form" testId="home-video-composer" active={expanded && kind === 'video'} /> : <ProductionImageComposer draftScope="home" entryOnly onHandoff={() => navigate('/create/image')} className="home-composer-form" testId="home-quick-composer" active={expanded && kind === 'image'} />}
      </div>)}
      <p className="home-composer-local">{t("选好素材与参数，前往创作页查看生成进度")}</p>
      </div>
    </div>
  </section>;
}
