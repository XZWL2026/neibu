import { t, formatMessage, useLanguage } from '../i18n';
import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { UserFrame } from '../UserFrame';
import { ComposerIcon } from '../creator/ComposerControls';
import { ProductionImageComposer } from '../creator/ProductionImageComposer';
import { ProductionVideoComposer } from '../creator/ProductionVideoComposer';
import { newFrontInspiration } from './new-front-inspiration';
import { ProductGalleryStage } from './ProductGalleryStage';
import { ContinueCommerceCreation } from './ContinueCommerceCreation';
import { inspirationIndustries, toolCategories, useCommerceBrowseState } from './useCommerceBrowseState';
import './commerce-workbench.css';
import './new-front-commerce.css';

const tools = [
  { title: '产品精修', category: '商品', to: '/commerce/product-retouch', image: 'feature-user-retouch.webp' },
  { title: 'A+/详情页', category: '商品', to: '/commerce/detail-page', image: 'feature-user-details.webp' },
  { title: '商品套图', category: '商品', to: '/commerce/product-suite', image: 'feature-user-product-photos.webp' },
  { title: '模特穿戴', category: '图片', to: '/commerce/model-wear', image: 'feature-user-model.webp' },
  { title: '视频复刻', category: '视频', to: '/commerce/video-replica', image: 'feature-user-video.webp' },
  { title: '带货视频', category: '视频', to: '/commerce/seller-video', image: 'skill-seller-video.webp' },
  { title: '图片复刻', category: '图片', to: '/commerce/image-replica', image: 'skill-image-result.webp' },
  { title: '服装组图', category: '商品', to: '/commerce/apparel-set', image: 'skill-apparel-set.webp' },
] as const;

const products = [
  { id: 'headphones', title: 'AURUM ONE', subtitle: '头戴式无线耳机' },
  { id: 'handbag', title: 'NOIR ARCHITECTE', subtitle: '鳄纹皮革手袋' },
  { id: 'perfume', title: 'NiceDesign', subtitle: '不规则肌理玻璃 · 香水' },
  { id: 'cream', title: 'L’ESSENTIEL', subtitle: '纯净花香 · 护手霜' },
  { id: 'phone', title: 'iPhone 16 Pro Max', subtitle: '勃艮第红 · 智能手机' },
  { id: 'cup', title: 'HAERS', subtitle: '梦幻小屋 · 吸管水杯' },
  { id: 'pocket3', title: 'DJI OSMO POCKET 3', subtitle: '大疆 Pocket 3 · 云台相机' },
] as const;

const asset = (folder: string, name: string) => `${import.meta.env.BASE_URL}new-frontend/commerce/${folder}/${name}`;

function EntryArrow() {
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M5 12h14m-6-6 6 6-6 6" /></svg>;
}

function getInspirationColumnCount() {
  const width = window.innerWidth;
  return width <= 360 ? 1 : width <= 600 ? 2 : width <= 760 ? 3 : width <= 1200 ? 4 : 5;
}

export function CommerceWorkbench() {
  useLanguage();
  const navigate = useNavigate();
  const [{ kind, category, industry, active }, updateBrowse] = useCommerceBrowseState();
  const setKind = (kind: 'image' | 'video') => updateBrowse({ kind });
  const [columnCount, setColumnCount] = useState(getInspirationColumnCount);
  const [navHidden, setNavHidden] = useState(false);
  const [carouselPaused, setCarouselPaused] = useState(false);
  const [preview, setPreview] = useState<(typeof newFrontInspiration)[number] | null>(null);
  const [inspiration, setInspiration] = useState<{ id: number; prompt: string } | null>(null);
  const previewDialog = useRef<HTMLDialogElement>(null);
  const dragStart = useRef<number | null>(null);
  const suppressClick = useRef(false);
  const lastWheel = useRef(0);
  const productMode = category === '商品';
  const staticTools = category === '图片' || category === '视频';
  const filteredTools = tools.filter(tool => category === '热门推荐' || tool.category === category);
  const carouselItems = productMode ? products : filteredTools;
  const selectedProduct = products[active % products.length];

  useEffect(() => {
    const dialog = previewDialog.current;
    if (!dialog || !preview) return;
    if (typeof dialog.showModal === 'function') dialog.showModal();
    else dialog.setAttribute('open', '');
  }, [preview]);

  const inspirationColumns: (typeof newFrontInspiration[number])[][] = Array.from({ length: columnCount }, () => []);
  const columnHeights = Array(columnCount).fill(0);
  newFrontInspiration.filter(item => industry === '全部' || item.category === industry).forEach(item => {
    const column = columnHeights.indexOf(Math.min(...columnHeights));
    inspirationColumns[column].push(item);
    columnHeights[column] += item.height / item.width + .06;
  });

  useEffect(() => {
    const onResize = () => setColumnCount(getInspirationColumnCount());
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  function selectSlide(index: number) {
    updateBrowse({ active: (index + carouselItems.length) % carouselItems.length });
  }
  useEffect(() => {
    if (!productMode || carouselPaused || document.visibilityState === 'hidden') return;
    const timer = window.setTimeout(() => selectSlide(active + 1), 6000);
    const pauseOnHide = () => { if (document.visibilityState === 'hidden') window.clearTimeout(timer); };
    document.addEventListener('visibilitychange', pauseOnHide);
    return () => { window.clearTimeout(timer); document.removeEventListener('visibilitychange', pauseOnHide); };
  }, [active, productMode, carouselPaused]);
  useEffect(() => {
    const onScroll = () => setNavHidden(window.scrollY > 0);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  return <UserFrame><div className="commerce-workbench" data-nav-hidden={navHidden}>
    <div className="commerce-first-screen">
    <section className="commerce-hero" aria-labelledby="commerce-title">
      <h1 id="commerce-title">{t("让商品 · 更有故事")}</h1>
      <div className="commerce-entry">
        <ContinueCommerceCreation onContinue={kind => {
          setKind(kind);
          requestAnimationFrame(() => {
            const input = document.getElementById(`commerce-${kind}-panel`)?.querySelector<HTMLElement>('textarea, [contenteditable="true"]');
            input?.focus({ preventScroll: true });
            input?.scrollIntoView({ block: 'center', behavior: 'instant' });
          });
        }} />
        <div className="commerce-tabs" role="tablist" aria-label={t("创作类型")}>
          {(['image', 'video'] as const).map(value => <button key={value} type="button" role="tab" id={`commerce-${value}-tab`} aria-controls={`commerce-${value}-panel`} aria-selected={kind === value} tabIndex={kind === value ? 0 : -1}
            onClick={() => setKind(value)} onKeyDown={event => {
              if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
              event.preventDefault();
              const next = event.key === 'Home' ? 'image' : event.key === 'End' ? 'video' : value === 'image' ? 'video' : 'image';
              setKind(next); document.getElementById(`commerce-${next}-tab`)?.focus();
            }}><ComposerIcon name={value} />{t(value === 'image' ? '图片生成' : '视频生成')}</button>)}
        </div>
        {(['image', 'video'] as const).map(value => <div key={value} role="tabpanel" id={`commerce-${value}-panel`} aria-labelledby={`commerce-${value}-tab`} hidden={kind !== value}>
          {value === 'image'
            ? <ProductionImageComposer draftScope="commerce" className="commerce-composer" testId="commerce-image-composer" entryOnly active={kind === value} inspiration={kind === value ? inspiration : null} onHandoff={() => navigate('/create/image')} />
            : <ProductionVideoComposer draftScope="commerce" className="commerce-composer" testId="commerce-video-composer" entryOnly active={kind === value} onHandoff={() => navigate('/create/video')} />}
        </div>)}
      </div>
    </section>

    <section id="commerce-tools" className="commerce-tools" aria-labelledby="commerce-tools-title">
      <div className="commerce-section-heading"><h2 id="commerce-tools-title">{t("热门工具")}</h2>
        <div className="commerce-filters" role="group" aria-label={t("工具分类")}>{toolCategories.map(value => <button key={value} type="button" aria-pressed={category === value} onClick={() => updateBrowse({ category: value, active: value === '热门推荐' || value === '商品' ? 2 : 0 })}>{t(value)}</button>)}</div>
      </div>
      <div className="commerce-tool-track" data-count={carouselItems.length} data-mode={productMode ? 'products' : staticTools ? 'static' : 'skills'}
        onPointerDown={event => { if (productMode) return; dragStart.current = event.clientX; suppressClick.current = false; }}
        onPointerMove={event => {
          if (dragStart.current !== null && Math.abs(event.clientX - dragStart.current) > 10) event.currentTarget.setPointerCapture?.(event.pointerId);
        }}
        onPointerUp={event => {
          if (dragStart.current !== null && Math.abs(event.clientX - dragStart.current) > 40) {
            selectSlide(active + (event.clientX < dragStart.current ? 1 : -1));
            suppressClick.current = true;
          }
          dragStart.current = null;
        }}
        onPointerCancel={() => { dragStart.current = null; }}
        onClickCapture={event => { if (suppressClick.current) { event.preventDefault(); suppressClick.current = false; } }}
        onDragStart={event => event.preventDefault()}
        onWheel={event => {
          if (productMode) return;
          if (Math.abs(event.deltaX) > 20 && Math.abs(event.deltaX) > Math.abs(event.deltaY) && Date.now() - lastWheel.current > 350) {
            selectSlide(active + Math.sign(event.deltaX));
            lastWheel.current = Date.now();
          }
        }}>
        {productMode && <ProductGalleryStage product={selectedProduct} />}
        {carouselItems.map((item, index) => {
          let position = (index - active + carouselItems.length) % carouselItems.length;
          if (position > carouselItems.length / 2) position -= carouselItems.length;
          const distance = Math.abs(position);
          const shared = { className: 'commerce-tool', 'data-active': index === active, 'data-position': position, 'data-distance': distance,
            'aria-hidden': distance > 2 ? true as const : undefined, tabIndex: distance > 2 ? -1 : 0,
            style: { '--card-offset': `${Math.sign(position) * (distance === 1 ? 22.6 : distance >= 2 ? 40 : 0)}%`, '--card-angle': `${-Math.sign(position) * 24}deg` } as CSSProperties };
          if (productMode) {
            const product = products[index];
            return <button key={product.id} type="button" {...shared} className="commerce-tool commerce-product-card" aria-label={`${product.title} · ${product.subtitle}`} onClick={() => selectSlide(index)}>
              <img className="commerce-art" src={asset('products', `product-${index + 1}.webp`)} alt="" loading={distance > 2 ? 'lazy' : 'eager'} decoding="async" />
              <span className="commerce-product-label">{product.title}</span>
            </button>;
          }
          const tool = filteredTools[index];
          return <Link key={tool.title} {...shared} to={tool.to} aria-label={t(tool.title)} draggable={false}>
            <img className="commerce-art" src={asset('skills', tool.image)} alt="" decoding="async" />
            <div className="commerce-tool-copy"><h3>{t(tool.title)}</h3><span className="commerce-tool-arrow"><EntryArrow /></span></div>
          </Link>;
        })}
      </div>
      {!staticTools && <div className="commerce-carousel-controls" role="group" aria-label={t('工具轮播')}>
        <button type="button" aria-label={t('上一个工具')} onClick={() => selectSlide(active - 1)}>‹</button>
        <div className="commerce-carousel-dots">{carouselItems.map((item, index) => <button key={item.title} type="button" aria-label={t(`查看${item.title}`)} aria-pressed={active === index} onClick={() => selectSlide(index)}><span /></button>)}</div>
        <button type="button" aria-label={t('下一个工具')} onClick={() => selectSlide(active + 1)}>›</button>
        {productMode && <button type="button" aria-label={t(carouselPaused ? '播放轮播' : '暂停轮播')} aria-pressed={carouselPaused} onClick={() => setCarouselPaused(value => !value)}>{carouselPaused ? '▶' : 'Ⅱ'}</button>}
      </div>}
    </section>

    </div>

    <section className="commerce-inspiration" aria-labelledby="commerce-inspiration-title">
      <div className="commerce-section-heading"><h2 id="commerce-inspiration-title">{t("创作灵感")}</h2></div>
      <p className="commerce-inspiration-hint">{t('发现喜欢的方向，进入对应工具，用自己的素材开始创作。')}</p>
      <div className="commerce-filters commerce-industry-filters" role="group" aria-label={t('灵感分类')}>
        {inspirationIndustries.map(value => <button key={value} type="button" aria-pressed={industry === value} onClick={() => updateBrowse({ industry: value })}>{t(value)}</button>)}
      </div>
      <div className="commerce-inspiration-grid" style={{ '--inspiration-columns': columnCount } as CSSProperties}>
        {inspirationColumns.map((column, index) => <div className="commerce-inspiration-column" key={index}>
          {column.map(item => <button key={item.id} type="button" className="commerce-inspiration-card commerce-inspiration-photo"
            style={{ aspectRatio: item.ratio }} onClick={() => setPreview(item)} aria-label={`${t(item.title)} · ${t(tools[item.tool].title)}`}>
            <img className="commerce-art" src={asset('inspiration', item.image.replace('inspiration/', ''))} width={item.width} height={item.height} alt="" loading="lazy" decoding="async" />
            <div><h3>{t(item.title)}</h3><span className="commerce-inspiration-action">{formatMessage('进入{0}', t(tools[item.tool].title))}<EntryArrow /></span></div>
          </button>)}
        </div>)}
      </div>
    </section>
    {preview && <dialog ref={previewDialog} className="commerce-preview-dialog" aria-label={`${t(preview.title)} · ${t(tools[preview.tool].title)}`} onCancel={() => setPreview(null)} onClose={() => setPreview(null)}>
      <button type="button" className="commerce-product-close" aria-label={t('关闭')} onClick={() => setPreview(null)}>×</button>
      <img src={asset('inspiration', preview.image.replace('inspiration/', ''))} alt="" />
      <div className="commerce-preview-copy"><h2>{t(preview.title)}</h2><p>{t(preview.category)} · {t(tools[preview.tool].title)}</p>
        <button type="button" onClick={() => {
          setKind('image');
          setInspiration({ id: preview.id, prompt: preview.prompt });
          setPreview(null);
          window.scrollTo({ top: 0, behavior: 'smooth' });
          requestAnimationFrame(() => document.querySelector<HTMLTextAreaElement>('#commerce-image-panel textarea')?.focus({ preventScroll: true }));
        }}>{t('使用这个灵感')}</button>
        <Link to={tools[preview.tool].to}>{formatMessage('进入{0}', t(tools[preview.tool].title))} ↗</Link>
      </div>
    </dialog>}
  </div></UserFrame>;
}
