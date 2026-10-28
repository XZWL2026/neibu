import { t, formatMessage, useLanguage, currentLanguage } from '../i18n';
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { ApiError, canvasCreatePostApiV1Canvases, canvasListGetApiV1Canvases, refreshSession } from '../api';
import { canvasError, resolvePreview } from './production-api';
import { ProductionCanvas } from './ProductionCanvas';
import { UserHeaderActions } from '../UserFrame';
import { CreationNavigation } from '../CreationNavigation';
import brandLogo from '../assets/brand.webp';
import './canvas-home.css';
import '../home/home-showcase.css';
import { ThemeArtwork } from '../theme';
import { DarkCanvasFeature } from './DarkCanvasFeature';
import { LightCanvasFeature } from './LightCanvasFeature';

type CanvasPageData = Awaited<ReturnType<typeof canvasListGetApiV1Canvases>>['data'];
type CanvasSummary = CanvasPageData['items'][number];

export function CanvasPage() {
  useLanguage();
  const [params] = useSearchParams();
  return params.get('id') ? <ProductionCanvas /> : <CanvasHome />;
}

export function CanvasHome() {
  useLanguage();
  const navigate = useNavigate();
  const location = useLocation();
  const [navHidden, setNavHidden] = useState(false);
  useEffect(() => {
    const onScroll = () => setNavHidden(window.scrollY > 0);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);
  useLayoutEffect(() => {
    if (!location.hash) window.scrollTo({ top: 0, left: 0, behavior: 'instant' });
  }, [location.key, location.hash]);
  const [data, setData] = useState<CanvasPageData | null>(null);
  const [identity, setIdentity] = useState<{ id: string; name: string } | null>(null);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [error, setError] = useState('');
  const [createError, setCreateError] = useState('');
  const request = useRef(0);
  const controller = useRef<AbortController | null>(null);
  const creatingRef = useRef(false);
  const nextOffset = useRef(0);

  const load = useCallback(async (previous: CanvasPageData | null = null, owner?: string) => {
    const current = ++request.current;
    controller.current?.abort();
    const abort = new AbortController();
    controller.current = abort;
    setLoading(true); setError(''); setCreateError('');
    if (!previous) { setData(null); setIdentity(null); nextOffset.current = 0; }
    try {
      const session = await refreshSession();
      if (current !== request.current) return;
      if (!session.authenticated) { navigate('/user/login', { replace: true }); return; }
      if (!session.user?.id || !session.roles?.some(role => role.toUpperCase() === 'USER')) {
        throw new Error('请使用创作者账号登录后查看画布');
      }
      if (owner && owner !== session.user.id) { previous = null; setData(null); nextOffset.current = 0; }
      setIdentity({ id: session.user.id, name: session.user.username || '创作者' });
      const response = await canvasListGetApiV1Canvases({ offset: nextOffset.current, limit: 12 }, { signal: abort.signal });
      if (current !== request.current) return;
      nextOffset.current += response.data.items.length;
      const seen = new Set(previous?.items.map(item => item.id));
      setData({ ...response.data, items: [...(previous?.items ?? []), ...response.data.items.filter(item => !seen.has(item.id))] });
    } catch (cause) {
      if (current === request.current) {
        if (cause instanceof ApiError && cause.status === 401) { setData(null); setIdentity(null); }
        setError(`画布列表加载失败：${homeError(cause)}`);
      }
    } finally { if (current === request.current) setLoading(false); }
  }, [navigate]);

  useEffect(() => {
    void load();
    const refresh = () => { if (document.visibilityState !== 'hidden') void load(); };
    window.addEventListener('focus', refresh);
    return () => { ++request.current; controller.current?.abort(); window.removeEventListener('focus', refresh); };
  }, [load]);

  const create = async () => {
    if (creatingRef.current || !identity || loading) return;
    creatingRef.current = true;
    const current = request.current;
    setCreating(true); setCreateError('');
    try {
      const response = await canvasCreatePostApiV1Canvases({ name: name.trim() || '未命名画布' });
      if (current === request.current) navigate(`/canvas?id=${encodeURIComponent(response.data.id)}`);
    } catch (cause) {
      if (current === request.current) setCreateError(`创建画布失败：${homeError(cause)}。可重新加载列表确认是否已创建。`);
    } finally { creatingRef.current = false; setCreating(false); }
  };
  const unavailable = loading || creating || !identity;
  const visibleItems = data?.items ?? [];
  const nameControl = <details className="canvas-home-name"><summary>{t("设置名称")}</summary><label><span className="sr-only">{t("新画布名称")}</span><input value={name} maxLength={200} placeholder={t("新画布名称（可选）")} disabled={creating} onChange={event => setName(event.target.value)} /></label></details>;

  return <div className="app-frame canvas-home-frame circle-navigation-frame canvas-design" data-nav-hidden={navHidden}>
    <a className="skip-link" href="#canvas-home-main">{t("跳到主要内容")}</a>
    <header className="app-header">
      <Link className="brand-block" to="/home" aria-label={t("YOMIO 首页")}><img className="brand-logo" src={brandLogo} decoding="async" fetchPriority="high" width={600} height={221} alt="" /></Link>
      <UserHeaderActions />
    </header>
    <CreationNavigation />
    <main id="canvas-home-main" className="canvas-home main-content">
      <ThemeArtwork dark={<DarkCanvasFeature unavailable={unavailable} creating={creating} onCreate={() => void create()}>{nameControl}</DarkCanvasFeature>} light={<LightCanvasFeature unavailable={unavailable} creating={creating} onCreate={() => void create()}>{nameControl}</LightCanvasFeature>} />
      <section id="my-canvases" className="canvas-home-projects" aria-labelledby="my-canvases-title">
        <div className="canvas-home-list-heading"><h2 id="my-canvases-title">{t("最近作品")}</h2><a href="#my-canvases">{t("查看全部 ")}<span aria-hidden="true">→</span></a></div>
        {creating && <p role="status">{t("正在创建独立画布…")}</p>}
        {createError && <div className="canvas-home-error" role="alert"><p>{t(createError)}</p><button disabled={loading || creating} onClick={() => void load()}>{t("重新加载列表")}</button></div>}
        {error && <div className="canvas-home-error" role="alert"><p>{t(error)}</p><button disabled={loading} onClick={() => void load(data, identity?.id)}>{t("重新加载")}</button></div>}
        {loading && <p className="canvas-home-loading" role="status">{t("正在加载你的画布…")}</p>}
        {data && !data.items.length && !error && !loading && <div className="canvas-home-empty"><span aria-hidden="true">◇</span><h3>{t("还没有画布，开始你的第一次创作")}</h3><p>{t("新建一张空白画布，把第一个想法放进来。")}</p><button className="btn-primary" disabled={unavailable} onClick={() => void create()}>{t("创建第一张画布")}</button></div>}
        {!!visibleItems.length && <div className="canvas-home-grid">
          {visibleItems.map(item => <Link className="canvas-home-card" key={`${identity?.id}:${item.id}:${item.updatedAt}`} to={`/canvas?id=${encodeURIComponent(item.id)}`} aria-label={formatMessage("打开画布：{0}", item.name)}><CanvasPreview item={item} /><div className="canvas-home-card-meta"><h3 title={item.name}>{item.name}</h3><span aria-hidden="true">↗</span><time dateTime={item.updatedAt}>{formatMessage("更新于 {0}", formatDate(item.updatedAt))}</time></div></Link>)}
        </div>}
        {data?.hasMore && <button className="canvas-home-more" disabled={loading || creating} onClick={() => void load(data, identity?.id)}>{t("加载更多画布")}</button>}
      </section>
    </main>
  </div>;
}

function formatDate(value: string) {
  const date = new Date(value);
  return Number.isFinite(date.getTime()) ? new Intl.DateTimeFormat(currentLanguage(), { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date) : '时间未知';
}

function homeError(cause: unknown) {
  if (cause instanceof TypeError) return '网络连接失败，请检查网络后重试';
  if (cause instanceof ApiError && cause.status >= 500) return '画布服务暂不可用，请稍后重试';
  return canvasError(cause);
}

function CanvasPreview({ item }: { item: CanvasSummary }) {
  useLanguage();
  const [image, setImage] = useState('');
  const reference = item.previewNodes.find(node => node.kind === 'image')?.properties.publicId;
  useEffect(() => {
    let active = true;
    if (reference) void resolvePreview(reference).then(url => { if (active) setImage(url); }).catch(() => {});
    return () => { active = false; };
  }, [reference]);
  const coordinate = (value: string | undefined, fallback: number) => {
    const number = Number(value);
    return Number.isFinite(number) ? Math.max(-1e6, Math.min(1e6, number)) : fallback;
  };
  const nodes = item.previewNodes.map((node, index) => ({ ...node, x: coordinate(node.properties.x, index * 160), y: coordinate(node.properties.y, 0) }));
  const minX = Math.min(0, ...nodes.map(node => node.x));
  const minY = Math.min(0, ...nodes.map(node => node.y));
  const width = Math.max(300, ...nodes.map(node => node.x + 150)) - minX;
  const height = Math.max(170, ...nodes.map(node => node.y + 100)) - minY;
  return <div className="canvas-home-preview">
    {image ? <img src={image} alt={formatMessage("{0}预览缩略图", item.name)} loading="lazy" onError={() => setImage('')} /> : <svg role="img" aria-label={formatMessage("{0}预览缩略图", item.name)} viewBox={`${minX - 25} ${minY - 25} ${width + 50} ${height + 50}`}>
      {nodes.length ? nodes.map(node => <g key={node.id} transform={`translate(${node.x},${node.y})`}><rect width="138" height="84" rx="10" /><text x="12" y="25" className="preview-node-kind">{t(node.kind === 'text' ? '文字' : node.kind === 'generation' ? '生成' : node.kind === 'video' ? '视频' : '图片')}</text><text x="12" y="51">{(node.properties.text || node.properties.alt || '◇').slice(0, 9)}</text><path d="M12 66 H92" /></g>) : <g><rect x="105" y="40" width="90" height="90" rx="12" /><path d="M150 69 V101 M134 85 H166" /></g>}
    </svg>}
    <span className="canvas-home-preview-label">{t(item.previewNodes.length ? '画布' : '空白画布')}</span>
  </div>;
}
