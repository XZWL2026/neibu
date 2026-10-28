import { useEffect, useId, useRef, useState, type CSSProperties } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { t, formatMessage, useLanguage } from '../i18n';
import type { HomeCarousel } from './reference/carousel';
import retouch from './reference/assets/feature-user-retouch.webp';
import details from './reference/assets/feature-user-details.webp';
import photos from './reference/assets/feature-user-product-photos.webp';
import model from './reference/assets/feature-user-model.webp';
import video from './reference/assets/feature-user-video.webp';
import './reference/circular-carousel.css';
import './reference/reflective-cards.css';
import './home-commerce-carousel.css';

const tools = [
  { label: '产品精修', image: retouch, subtitle: '重塑光影细节，呈现产品质感', route: 'product-retouch' },
  { label: 'A+/详情页', image: details, subtitle: '从产品到场景，构建品牌视觉', route: 'detail-page' },
  { label: '商品套图', image: photos, subtitle: '统一风格，生成多场景商品大片', route: 'product-suite' },
  { label: '模特穿戴', image: model, subtitle: '让穿搭灵感，成为时尚大片', route: 'model-wear' },
  { label: '视频复刻', image: video, subtitle: '捕捉镜头语言，重现影像灵感', route: 'video-replica' },
] as const;

function positionOf(index: number, active: number) {
  const offset = (index - active + tools.length) % tools.length;
  return offset > 2 ? offset - tools.length : offset;
}

function Arrow({ previous = false }: { previous?: boolean }) {
  return <svg aria-hidden="true" viewBox="0 0 24 24"><path d={previous ? 'm14 6-6 6 6 6' : 'm10 6 6 6-6 6'} /></svg>;
}

export function HomeCommerceCarousel() {
  useLanguage();
  const trackId = useId();
  const navigate = useNavigate();
  const engine = useRef<HomeCarousel | null>(null);
  const [paused, setPaused] = useState(false);
  const root = useRef<HTMLElement>(null);
  const [{ active, previous }, setSlide] = useState({ active: 2, previous: 2 });
  const gesture = useRef<{ x: number; y: number; moved: boolean } | null>(null);
  const suppressClick = useRef(false);
  const select = (index: number) => engine.current ? engine.current.select(index) : setSlide(current => ({ previous: current.active, active: (index + tools.length) % tools.length }));
  const step = (delta: number) => engine.current ? engine.current.step(delta) : setSlide(current => ({ previous: current.active, active: (current.active + delta + tools.length) % tools.length }));

  useEffect(() => {
    if (!window.matchMedia || !window.ResizeObserver || !window.IntersectionObserver) return;
    let cancelled = false;
    void import('./reference/carousel.js').then(({ mountCarousel }) => {
      const track = root.current?.querySelector<HTMLElement>('.ym-commerce-grid');
      if (cancelled || !track) return;
      engine.current = mountCarousel(track, tools, index => setSlide(current => ({ previous: current.active, active: index })), index => navigate('/commerce/' + tools[index].route));
    }).catch(error => console.warn('Home carousel unavailable; using static cards.', error));
    return () => { cancelled = true; engine.current?.dispose(); engine.current = null; };
  }, [navigate]);

  useEffect(() => {
    const section = root.current!;
    const motion = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    let timer: ReturnType<typeof setTimeout> | undefined;
    let hovered = false, dragging = false, focused = section.contains(document.activeElement);
    const schedule = () => {
      clearTimeout(timer);
      if (engine.current || paused || hovered || dragging || focused || document.visibilityState === 'hidden' || motion?.matches) return;
      timer = setTimeout(() => {
        if (engine.current) return;
        setSlide(current => ({ previous: current.active, active: (current.active + 1) % tools.length }));
        schedule();
      }, 5000);
    };
    const enter = (event: PointerEvent) => { if (event.pointerType !== 'touch') { hovered = true; schedule(); } };
    const leave = () => { hovered = false; schedule(); };
    const focusIn = () => { focused = true; schedule(); };
    const focusOut = (event: FocusEvent) => { focused = section.contains(event.relatedTarget as Node | null); schedule(); };
    const pointerDown = () => { dragging = true; schedule(); };
    const pointerEnd = () => { if (dragging) { dragging = false; schedule(); } };
    section.addEventListener('pointerenter', enter);
    section.addEventListener('pointerleave', leave);
    section.addEventListener('focusin', focusIn);
    section.addEventListener('focusout', focusOut);
    section.addEventListener('pointerdown', pointerDown);
    section.addEventListener('click', schedule);
    document.addEventListener('pointerup', pointerEnd);
    document.addEventListener('pointercancel', pointerEnd);
    document.addEventListener('visibilitychange', schedule);
    motion?.addEventListener('change', schedule);
    schedule();
    return () => {
      clearTimeout(timer);
      section.removeEventListener('pointerenter', enter);
      section.removeEventListener('pointerleave', leave);
      section.removeEventListener('focusin', focusIn);
      section.removeEventListener('focusout', focusOut);
      section.removeEventListener('pointerdown', pointerDown);
      section.removeEventListener('click', schedule);
      document.removeEventListener('pointerup', pointerEnd);
      document.removeEventListener('pointercancel', pointerEnd);
      document.removeEventListener('visibilitychange', schedule);
      motion?.removeEventListener('change', schedule);
    };
  }, [paused]);

  return <section ref={root} className="ym-commerce-section ym-commerce-carousel" aria-label={t('电商创作专区')}>
    <h2 className="ym-sr-only">{t('电商创作专区')}</h2>
    <div className="ym-commerce-grid" id={trackId}
      onPointerDown={event => { if (engine.current) return; if (event.button !== 0) return; gesture.current = { x: event.clientX, y: event.clientY, moved: false }; suppressClick.current = false; }}
      onPointerMove={event => {
        const start = gesture.current;
        if (!start) return;
        const dx = Math.abs(event.clientX - start.x), dy = Math.abs(event.clientY - start.y);
        if (dx > 12 && dx > dy * 1.2) { start.moved = true; event.currentTarget.setPointerCapture?.(event.pointerId); }
      }}
      onPointerUp={event => {
        const start = gesture.current;
        gesture.current = null;
        if (!start?.moved) return;
        suppressClick.current = true;
        if (Math.abs(event.clientX - start.x) > 40) step(event.clientX < start.x ? 1 : -1);
      }}
      onPointerCancel={() => { gesture.current = null; suppressClick.current = false; }}
      onLostPointerCapture={() => { gesture.current = null; }}
      onDragStart={event => event.preventDefault()}
      onClickCapture={event => { if (suppressClick.current) { event.preventDefault(); suppressClick.current = false; } }}>
      <div className="circular-carousel__view"><div className="circular-carousel__stage"><div className="circular-carousel__camera"><div className="circular-carousel__ring">{tools.map((item, index) => {
        const position = positionOf(index, active), distance = Math.abs(position);
        return <div className="circular-carousel__card" key={item.route} data-cc-index={index}><Link className="ym-commerce-card circular-carousel__frame"
          data-active={index === active} data-position={position} data-distance={distance} data-wrap={Math.abs(position - positionOf(index, previous)) > 2}
          style={{ '--card-offset': `${Math.sign(position) * (distance === 1 ? 22.6 : distance === 2 ? 40 : 0)}%`, '--card-angle': `${-Math.sign(position) * 24}deg`, zIndex: 3 - distance } as CSSProperties}
          to={`/commerce/${item.route}`} draggable={false} onFocus={() => { if (window.matchMedia?.('(max-width: 760px)').matches) select(index); }}>
          <img className="commerce-art" src={item.image} alt="" width={1440} height={1920} draggable={false} decoding="async" />
          <span className="circular-carousel__shade" aria-hidden="true" />
          <div className="ym-commerce-copy"><span className="ym-commerce-title">{t(item.label)}</span>
          {index === active && <span className="ym-commerce-arrow" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M5 12h14m-6-6 6 6-6 6" /></svg></span>}</div>
        </Link></div>;
      })}</div></div></div></div>
      <div className="circular-carousel__caption" aria-hidden="true"><span className="circular-carousel__title"><strong>{t(tools[active].label)}</strong><span className="circular-carousel__subtitle">{t(tools[active].subtitle)}</span></span><span className="circular-carousel__count">{String(active + 1).padStart(2, '0')} / 05</span></div>
    </div>
    <div className="ym-commerce-controls" role="group" aria-label={t('工具轮播')} onKeyDown={event => {
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
      event.preventDefault(); step(event.key === 'ArrowLeft' ? -1 : 1);
    }}>
      <button type="button" aria-label={t('上一个工具')} aria-controls={trackId} onClick={() => step(-1)}><Arrow previous /></button>
      <div className="ym-commerce-dots">{tools.map((tool, index) => <button key={tool.route} type="button" aria-label={formatMessage('查看{0}', t(tool.label))} aria-pressed={active === index} aria-controls={trackId} onClick={() => select(index)}><span /></button>)}</div>
      <button type="button" aria-label={t('下一个工具')} aria-controls={trackId} onClick={() => step(1)}><Arrow /></button>
      <button type="button" aria-label={t(paused ? '播放轮播' : '暂停轮播')} aria-pressed={paused} onClick={() => setPaused(engine.current ? engine.current.togglePaused() : !paused)}><svg aria-hidden="true" viewBox="0 0 24 24"><path d={paused ? 'm8 4 12 8-12 8Z' : 'M8 5v14M16 5v14'} /></svg></button>
    </div>
  </section>;
}
