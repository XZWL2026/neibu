import { useEffect, useLayoutEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { t, useLanguage } from '../i18n';
import { UserHeaderActions } from '../UserFrame';
import { useUserSession } from '../userSession';
import { usePendingInvitation } from '../promotion/pendingInvitation';
import { draftSaveError, readComposerDraft, saveComposerDraft } from '../creator/composer-draft';
import { homePromptDraft, type HomeCreationEntry, type HomeGenerationMode } from './home-entry';
import { YomioIcons, galleryWorks } from './yomio-content';
import { useArtworkMotion, useGalleryMotion } from './home-motion';
import { CreationNavigation } from '../CreationNavigation';
import { HomePromptComposer } from './HomePromptComposer';
import { HomeCommerceCarousel } from './HomeCommerceCarousel';
import type { CanvasGenerationSettings } from '../canvas/model';
import type { HomeMaterials } from './HomeMaterialPicker';
import './yomio-home.css';
import './home-model-showcase.css';

import { homeImage as asset } from './home-assets';
import { HomeReferenceBackdrop } from './HomeReferenceBackdrop';
import { referenceGalleryImage } from './reference-assets';
import './home-reference.css';
import brandLogo from '../assets/brand.webp';
const models = [
  { name: 'Seedance 2.5', label: 'Seedance', version: '2.5', kind: 'video' },
  { name: 'Seedance 2.0', label: 'Seedance', version: '2.0', kind: 'video' },
  { name: 'Image 2', label: 'Image', version: '2', kind: 'image' },
  { name: 'Nano Banana', label: 'Nano Banana', version: '', kind: 'image' },
] as const;
const filters = ['全部', '精选画布', '专业影视', '短剧', '漫剧', '电商'];
const ideas: Record<string, string> = { 电影: '电影质感的镜头，', 未来: '未来世界中，', 自然: '自然风光中，', 人物: '人物特写，' };
function Icon({ name, className }: { name: string; className?: string }) {
  return <svg className={className} aria-hidden="true"><use href={`#ym-i-${name}`} /></svg>;
}

export function YomioHome() {
  useLanguage();
  usePendingInvitation();
  const navigate = useNavigate();
  const location = useLocation();
  const account = useUserSession();
  const main = useRef<HTMLElement>(null);
  const stage = useRef<HTMLElement>(null);
  const promptInput = useRef<HTMLTextAreaElement>(null);
  const dialog = useRef<HTMLDialogElement>(null);
  const gallery = useRef<HTMLElement>(null);
  const artworkGrid = useRef<HTMLDivElement>(null);
  const modelGrid = useRef<HTMLDivElement>(null);
  const [hoveredModel, setHoveredModel] = useState<string | null>(null);
  const [focusedModel, setFocusedModel] = useState<string | null>(null);
  const [prompt, setPrompt] = useState('');
  const [kind, setKind] = useState<'image' | 'video'>('video');
  const [modelName, setModelName] = useState<string>('Seedance 2.5');
  const highlightedModel = hoveredModel ?? focusedModel ?? modelName;
  const [filter, setFilter] = useState('全部');
  const [artwork, setArtwork] = useState<(typeof galleryWorks)[number] | null>(null);
  const [error, setError] = useState('');
  const previousOwner = useRef<string | null | undefined>(undefined);
  const visibleWorks = [...galleryWorks.filter(work => work.id !== '07'), ...galleryWorks.filter(work => work.id === '07')].filter(work => filter === '全部' || work.genre === filter);
  const captureGallery = useGalleryMotion(artworkGrid, filter);
  const preview = useArtworkMotion(dialog, Boolean(artwork));

  useLayoutEffect(() => {
    const grid = modelGrid.current;
    if (!grid) return;
    const label = grid.querySelector<HTMLElement>('.ym-highlighted .ym-model-name');
    if (!label) return;
    let disposed = false;
    const updateIndicator = () => {
      if (disposed) return;
      const bounds = grid.getBoundingClientRect();
      const target = label.getBoundingClientRect();
      grid.style.setProperty('--model-line-x', `${target.left - bounds.left}px`);
      grid.style.setProperty('--model-line-y', `${target.bottom - bounds.top + 18}px`);
      grid.style.setProperty('--model-line-width', `${target.width}px`);
      grid.style.setProperty('--model-line-visible', '1');
    };
    updateIndicator();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(updateIndicator);
    observer?.observe(grid);
    observer?.observe(label);
    window.addEventListener('resize', updateIndicator);
    void document.fonts?.ready.then(updateIndicator);
    return () => {
      disposed = true;
      observer?.disconnect();
      window.removeEventListener('resize', updateIndicator);
    };
  }, [highlightedModel]);

  useLayoutEffect(() => {
    if (location.pathname !== '/home') return;
    window.scrollTo({ top: 0, left: 0, behavior: 'instant' });
    main.current?.focus({ preventScroll: true });
    if (new URLSearchParams(location.search).get('entry') === 'story') enterStory();
  }, [location.key, location.pathname, location.search]);
  useEffect(() => {
    // Anonymous input stays in memory; drafts are never stored under another account.
    if (account.status === 'loading') return;
    if (previousOwner.current && previousOwner.current !== account.userId) { setPrompt(''); setError(''); }
    previousOwner.current = account.userId;
  }, [account.userId, account.status]);

  function enterStory() {
    setKind('video');
    setModelName('Seedance 2.5');
    setPrompt(t('为一段短剧编排故事和镜头，'));
    promptInput.current?.focus({ preventScroll: true });
  }
  function enterCreator(event: FormEvent, settings: CanvasGenerationSettings | null, mode: HomeGenerationMode, materials: HomeMaterials) {
    event.preventDefault();
    if (!prompt.trim()) { setError('请先描述你的想法，再开始创作。'); promptInput.current?.focus(); return; }
    const type = kind === 'image' ? 'IMAGE' : 'VIDEO';
    const owner = account.authenticated ? account.userId : null;
    if (owner && !saveComposerDraft(owner, 'create', type, homePromptDraft(prompt.trim(), readComposerDraft(owner, 'create', type), type, settings, mode, materials))) {
      setError(draftSaveError); return;
    }
    const entry: HomeCreationEntry = { source: 'yomio-home', prompt: prompt.trim(), modelName, mode, ...(settings ? { settings } : {}), ...(owner ? { ownerId: owner, materials } : {}) };
    navigate(`/create/${kind}`, { state: entry });
  }
  function inspire(name: string) {
    const prefix = Object.values(ideas).map(value => t(value)).find(value => prompt.startsWith(value));
    const next = `${t(ideas[name])}${prefix ? prompt.slice(prefix.length) : prompt}`;
    if (next.length > 2000) {
      setError('添加灵感会超过 2000 字，请先缩短描述。');
      promptInput.current?.focus({ preventScroll: true }); return;
    }
    setPrompt(next);
    setError(''); promptInput.current?.focus({ preventScroll: true });
  }

  return <div className="yomio-home">
    <YomioIcons />
    <a className="ym-skip-link" href="#main-content">{t('跳到主要内容')}</a>
    <CreationNavigation variant="home" selectedTo={`/create/${kind}`} onStory={enterStory} />
    <main ref={main} id="main-content" tabIndex={-1}>
      <section ref={stage} className="ym-stage" aria-label={t('创作首页')}>
        <HomeReferenceBackdrop />
        <div className="ym-shade" aria-hidden="true" />
        <header className="ym-header">
          <Link className="ym-brand" to="/home" aria-label={t("YOMIO 首页")}><img src={brandLogo} decoding="async" fetchPriority="high" width={600} height={221} alt="" /></Link>
          <nav className="ym-primary-nav" aria-label={t('主导航')}>
            <Link className="ym-nav-link ym-active" to="/home" aria-current="page">{t('创作')}</Link>
            <button className="ym-nav-link" onClick={() => gallery.current?.scrollIntoView({ behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })}>{t('社区')}</button>
            <Link className="ym-nav-link" to="/create/image?tab=history">{t('我的作品')}</Link>
          </nav>
          <UserHeaderActions />
        </header>
        <section className="ym-creation" aria-label={t('创作你的影像')}>
          <h1 className="ym-sr-only">{t('描述你的想法，创造你的影像')}</h1>
          <HomePromptComposer inputRef={promptInput} prompt={prompt} kind={kind} modelName={modelName} error={error} choices={models}
            onPrompt={value => { setPrompt(value); setError(''); }} onModel={(type, name) => { setKind(type); setModelName(name); }} onSubmit={enterCreator} />
          <p className="ym-sr-only" role="status">{t('已选择模型')} {modelName} · {t(kind === 'image' ? '图片' : '视频')}</p>
          <div className="ym-categories" aria-label={t('灵感分类')}>{[['电影', 'video'], ['未来', 'city'], ['自然', 'image'], ['人物', 'person']].map(([name, icon]) => <button key={name} aria-pressed={prompt.startsWith(t(ideas[name]))} onClick={() => inspire(name)}><Icon name={icon} /><span>{t(name)}</span></button>)}</div>
        </section>
        <section className="ym-models" aria-label={t('热门模型')}>
          <h2 className="ym-sr-only">{t('热门模型')}</h2>
          <div ref={modelGrid} className="ym-model-grid" onPointerLeave={() => setHoveredModel(null)}>{models.map(model => <Link className={`ym-model-card${modelName === model.name ? ' ym-chosen' : ''}${highlightedModel === model.name ? ' ym-highlighted' : ''}`} onPointerEnter={event => { if (event.pointerType !== 'touch') setHoveredModel(model.name); }} onFocus={() => setFocusedModel(model.name)} onBlur={() => setFocusedModel(null)} data-kind={model.kind} aria-label={model.name} key={model.name} to={`/create/${model.kind}`} state={{ source: 'yomio-home', prompt: prompt.trim(), modelName: model.name } satisfies HomeCreationEntry}>
            <span className="ym-model-name"><span className="ym-model-family">{model.label}</span>{model.version && <sup className="ym-model-version">{model.version}</sup>}</span>
          </Link>)}</div>
          <p className="ym-models-tagline">{t('让创意更快成片')}</p>
        </section>
      </section>
      <div className="ym-below-fold">
        <HomeCommerceCarousel />
        <section ref={gallery} className="ym-gallery-section" id="ym-gallery" aria-labelledby="ym-gallery-heading">
          <div className="ym-gallery-header"><h2 id="ym-gallery-heading">{t('精选作品')}</h2><div className="ym-gallery-filters" role="group" aria-label={t('作品分类')}>{filters.map(name => <button key={name} aria-pressed={filter === name} onClick={() => { if (name !== filter) { captureGallery(); setFilter(name); } }}>{t(name)}</button>)}</div></div>
          <p className="ym-sr-only" role="status">{t(filter)} · {visibleWorks.length}</p>
          <div ref={artworkGrid} className="ym-artwork-grid">{visibleWorks.map(work => <button className="ym-artwork-card" key={work.id} data-work-id={work.id} aria-label={`${t('查看')} ${t(work.title)}，${t('作者')} ${work.author}`} onClick={event => { preview.rememberSource(event.currentTarget); setArtwork(work); }}><img className="ym-artwork-cover" src={referenceGalleryImage(work.id)} alt="" loading="lazy" decoding="async" width={480} height={213} /><span className="ym-artwork-shade" aria-hidden="true" /><span className="ym-artwork-credit"><img className="ym-author-avatar" src={asset(`gallery/${work.id}-avatar.png`)} alt="" loading="lazy" decoding="async" width={33} height={33} /><span className="ym-credit-text"><strong>{t(work.title)}</strong><span>{work.author}</span></span></span></button>)}</div>
        </section>
      </div>
      <footer className="ym-footer"><span>© {new Date().getFullYear()} YOMIO</span><a href="https://beian.miit.gov.cn" target="_blank" rel="noopener noreferrer">粤ICP备2026023603号</a></footer>
    </main>
    <dialog ref={dialog} id="ym-artwork-dialog" aria-labelledby="ym-artwork-title" onClose={() => setArtwork(null)} onCancel={event => { event.preventDefault(); preview.close(); }} onClick={event => { if (event.target === event.currentTarget) { const rect = event.currentTarget.getBoundingClientRect(); if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) preview.close(); } }}>
      <button className="ym-dialog-close" aria-label={t('关闭作品预览')} onClick={preview.close}><Icon name="close" /></button>
      {artwork && <><img id="ym-artwork-detail-image" src={referenceGalleryImage(artwork.id)} alt={t(artwork.title)} /><div className="ym-artwork-detail-caption"><img src={asset(`gallery/${artwork.id}-avatar.png`)} alt="" width={44} height={44} /><div><h2 id="ym-artwork-title">{t(artwork.title)}</h2><p>{artwork.author}</p></div></div></>}
    </dialog>
  </div>;
}
