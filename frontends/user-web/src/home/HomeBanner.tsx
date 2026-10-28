import { t, formatMessage, useLanguage } from '../i18n';
import { useEffect, useId, useState } from 'react';
import { Link } from 'react-router-dom';
import cinematicHero from '../assets/home/cinematic-hero.webp';
import mountains from '../assets/home/mountains.webp';
import horizon from '../assets/home/horizon.webp';
import lightHero from '../assets/home/light-home-reference.webp';
import lightWhale from '../assets/home/light-whale.webp';
import lightOrbitCity from '../assets/home/light-orbit-city.webp';
import { useTheme } from '../theme';
import { HomeBreeze } from './HomeBreeze';

const slides = [
  { title: '用 AI，把想象变成现实。', label: 'YOMIO · IMAGE STUDIO', detail: '从一个灵感开始，借助 AI 的力量，创造属于你的独特世界。', image: cinematicHero, lightImage: lightHero, to: '/create/image' },
  { title: '让故事，在光影间发生', label: 'YOMIO · VIDEO STUDIO', detail: '连接关键画面，编织属于你的镜头语言。', image: mountains, lightImage: lightWhale, to: '/create/video' },
  { title: '把灵感，汇成完整世界', label: 'YOMIO · CREATIVE CANVAS', detail: '在自由画布里，连接图片、视频与无限想象。', image: horizon, lightImage: lightOrbitCity, to: '/canvas' },
];

export function HomeBanner() {
  useLanguage();
  const { theme } = useTheme();
  const breezeId = useId();
  const [active, setActive] = useState(0);
  const [hovered, setHovered] = useState(false);
  const [focused, setFocused] = useState(false);
  const [paused, setPaused] = useState(false);
  const [reduced, setReduced] = useState(false);
  const [hidden, setHidden] = useState(document.hidden);
  useEffect(() => {
    const media = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    const updateMotion = () => setReduced(media?.matches ?? false);
    const updateVisibility = () => setHidden(document.hidden);
    updateMotion();
    media?.addEventListener('change', updateMotion);
    document.addEventListener('visibilitychange', updateVisibility);
    return () => {
      media?.removeEventListener('change', updateMotion);
      document.removeEventListener('visibilitychange', updateVisibility);
    };
  }, []);
  useEffect(() => {
    if (hovered || focused || paused || reduced || hidden) return;
    const timer = window.setInterval(() => setActive(index => (index + 1) % slides.length), 5000);
    return () => window.clearInterval(timer);
  }, [hovered, focused, paused, reduced, hidden, active]);
  const move = (offset: number) => setActive(index => (index + offset + slides.length) % slides.length);

  return <section className="home-banner" aria-label={t("首页精选")} aria-roledescription={t("轮播")}
    onPointerEnter={event => { if (event.pointerType !== 'touch') setHovered(true); }}
    onPointerLeave={() => setHovered(false)}
    onFocusCapture={() => setFocused(true)}
    onBlurCapture={event => { if (!event.currentTarget.contains(event.relatedTarget)) setFocused(false); }}
    onKeyDown={event => {
      if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
        event.preventDefault();
        const next = (active + (event.key === 'ArrowRight' ? 1 : -1) + slides.length) % slides.length;
        setActive(next);
        event.currentTarget.querySelector<HTMLButtonElement>(`[data-slide-index="${next}"]`)?.focus();
      }
    }}>
    <h1 className="sr-only">{t("YOMIO，让每一个想法都有画面")}</h1>
    {theme === 'light' && !reduced && <HomeBreeze id={breezeId} paused={paused || hidden || active !== 0} />}
    <div className="home-banner-stage">
      {slides.map((slide, index) => {
        const position = (index - active + slides.length) % slides.length;
        return <article key={slide.image} className={`home-banner-card ${position === 0 ? 'is-current' : position === 1 ? 'is-next' : 'is-previous'}`} aria-label={formatMessage("{0} / {1}", index + 1, slides.length)} aria-roledescription={t("幻灯片")} aria-hidden={index !== active}>
          <img src={theme === 'light' ? slide.lightImage : slide.image} alt="" decoding="async" fetchPriority={index === 0 ? 'high' : 'low'} style={{ filter: theme === 'light' && index === 0 && !reduced ? `url(#${breezeId})` : undefined }} />
          <Link to={slide.to} tabIndex={index === active ? 0 : -1} aria-label={t(slide.title)}>
            <div className="home-banner-copy"><span>{t(slide.label)}</span><h2 style={{ textWrap: 'balance' }}>{index === 0 ? <>{t("用 AI，")}<br />{t("把")}<em>{t("想象")}</em>{t("变成现实。")}</> : t(slide.title)}</h2><p>{t(slide.detail)}</p><span className="home-banner-cta">{t("开始创作 ")}<b aria-hidden="true">→</b></span><div className="home-banner-capabilities" aria-hidden="true"><span>{t("✧ 图片生成")}</span><span>{t("▷ 视频创作")}</span><span>{t("◇ 自由画布")}</span></div></div>
          </Link>
        </article>;
      })}
      <button className="home-banner-arrow previous" type="button" aria-label={t("上一张")} onClick={() => move(-1)}>‹</button>
      <button className="home-banner-arrow next" type="button" aria-label={t("下一张")} onClick={() => move(1)}>›</button>
    </div>
    <div className="home-banner-controls">
      <div className="home-banner-dots" aria-label={t("选择精选内容")}>{slides.map((slide, index) => <button type="button" key={slide.image} data-slide-index={index} aria-label={formatMessage("第 {0} 张：{1}", index + 1, t(slide.title))} aria-current={index === active} onClick={() => setActive(index)}><span /></button>)}</div>
      <button className="home-banner-pause" type="button" aria-label={t(paused ? '播放轮播' : '暂停轮播')} aria-pressed={paused} onClick={() => setPaused(value => !value)} disabled={reduced}>{paused ? '▷' : 'Ⅱ'}</button>
    </div>
  </section>;
}
