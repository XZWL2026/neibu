import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { t, useLanguage } from '../i18n';
import './video-gallery.css';

function readColumns(preferenceKey: string) {
  try { const value = Number(localStorage.getItem(preferenceKey)); return value === 1 || value === 3 ? value : 2; }
  catch { return 2; }
}

export function MediaResultsGallery({ children, ready, loading, error, hasMore, empty, submission, onLatest, onOlder, onRetry, errorAtTop = false, mediaType = 'VIDEO' }: {
  children(compact: boolean): ReactNode; ready: boolean; loading: boolean; error: string; hasMore: boolean;
  empty: boolean; submission: string; onLatest(): void; onOlder(): void; onRetry(): void; errorAtTop?: boolean; mediaType?: 'IMAGE' | 'VIDEO';
}) {
  useLanguage();
  const image = mediaType === 'IMAGE';
  const preferenceKey = `xzwl:${image ? 'image' : 'video'}-results-columns`;
  const loadingLabel = image ? '正在读取图片历史…' : '正在读取视频历史…';
  const root = useRef<HTMLDivElement>(null), grid = useRef<HTMLDivElement>(null), top = useRef<HTMLDivElement>(null);
  const [columns, setColumns] = useState(() => readColumns(preferenceKey));
  const [narrow, setNarrow] = useState(() => typeof matchMedia !== 'undefined' && matchMedia('(max-width: 700px)').matches);
  const current = useRef({ ready, loading, error, hasMore, onLatest, onOlder });
  current.current = { ready, loading, error, hasMore, onLatest, onOlder };
  const anchor = useRef<{ element: HTMLElement; top: number } | null>(null);
  const lastSubmission = useRef(submission);
  const initialized = useRef(false);
  const bottomEntered = useRef(true);
  const followLatest = useRef(false);
  const remember = () => {
    const element = [...(grid.current?.querySelectorAll<HTMLElement>('[data-task-key]') ?? [])]
      .find(card => card.getBoundingClientRect().bottom > 88);
    anchor.current = element ? { element, top: element.getBoundingClientRect().top } : null;
  };
  const restore = () => {
    const saved = anchor.current;
    if (initialized.current && followLatest.current) {
      window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'instant' });
    } else if (saved?.element.isConnected) {
      const delta = saved.element.getBoundingClientRect().top - saved.top;
      if (Math.abs(delta) > 1) window.scrollBy({ top: delta, behavior: 'instant' });
    }
    remember();
  };
  useLayoutEffect(() => {
    if (ready && (!initialized.current || lastSubmission.current !== submission)) {
      initialized.current = true; lastSubmission.current = submission;
      bottomEntered.current = true; followLatest.current = true;
      window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'instant' }); remember();
    } else restore();
  });
  useEffect(() => {
    const resize = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(restore);
    if (grid.current) resize?.observe(grid.current);
    const media = typeof matchMedia === 'undefined' ? null : matchMedia('(max-width: 700px)');
    const adapt = () => { remember(); setNarrow(media?.matches ?? false); };
    media?.addEventListener('change', adapt);
    const scroll = () => {
      remember();
      if (document.querySelector('dialog[open]')) return;
      const atBottom = document.documentElement.scrollHeight - window.innerHeight - window.scrollY <= 64;
      followLatest.current = atBottom;
      const state = current.current;
      if (!atBottom) bottomEntered.current = false;
      else if (!bottomEntered.current && state.ready && !state.loading && !state.error) {
        bottomEntered.current = true; state.onLatest();
      }
    };
    window.addEventListener('scroll', scroll, { passive: true });
    return () => { resize?.disconnect(); media?.removeEventListener('change', adapt); window.removeEventListener('scroll', scroll); };
  }, []);
  useEffect(() => {
    if (!ready || loading || error || !hasMore || !top.current) return;
    const load = () => {
      const state = current.current;
      if (state.ready && !state.loading && !state.error && state.hasMore && !document.querySelector('dialog[open]')) state.onOlder();
    };
    if (typeof IntersectionObserver === 'undefined') {
      const scroll = () => {
        const boundary = top.current?.getBoundingClientRect();
        if (boundary && boundary.bottom >= 66 && boundary.top <= window.innerHeight) load();
      };
      window.addEventListener('scroll', scroll, { passive: true });
      return () => window.removeEventListener('scroll', scroll);
    }
    const observer = new IntersectionObserver(items => { if (items.some(item => item.isIntersecting)) load(); }, { rootMargin: '-66px 0px 0px' });
    observer.observe(top.current);
    return () => observer.disconnect();
  }, [ready, loading, error, hasMore]);
  const selected = narrow ? 1 : columns;
  const failure = <>{t(error)} <button type="button" onClick={onRetry}>{t('重新读取任务')}</button></>;
  return <div ref={root} className="video-gallery">
    <div className="video-gallery-toolbar">
      <span aria-live="polite">{t(loading ? loadingLabel : '最新在下')}</span>
      <div className="video-layout-switch" role="group" aria-label={t(image ? '图片布局' : '视频布局')}>
        {[1, 2, 3].map(count => <button key={count} type="button" aria-pressed={selected === count}
          aria-label={t(count === 1 ? '单列' : count === 2 ? '四宫格' : '九宫格')}
          title={t(count === 1 ? '单列' : count === 2 ? '四宫格' : '九宫格')}
          disabled={narrow && count !== 1} onClick={() => {
            if (narrow) return;
            remember(); setColumns(count);
            try { localStorage.setItem(preferenceKey, String(count)); } catch { /* Keep the session choice when storage is unavailable. */ }
          }}>
          <svg aria-hidden="true" viewBox="0 0 18 18" width="18" height="18" fill="none" stroke="currentColor">
            {Array.from({ length: count * count }, (_, i) => <rect key={i} x={1 + (i % count) * 16 / count} y={1 + Math.floor(i / count) * 16 / count} width={16 / count - 2} height={16 / count - 2} rx="1" />)}
          </svg>
        </button>)}
      </div>
    </div>
    <div ref={top} className="video-gallery-boundary" role={error && errorAtTop ? 'alert' : 'status'}>
      {loading && errorAtTop ? t(loadingLabel) : error && errorAtTop ? failure
        : ready ? t(empty && !hasMore ? image ? '暂无图片任务' : '暂无视频任务' : hasMore ? image ? '向上滚动加载更早图片' : '向上滚动加载更早视频' : '已加载全部') : null}
    </div>
    <div ref={grid} className="video-gallery-grid" data-columns={selected} aria-busy={loading}>{children(false)}</div>
    <div className="video-gallery-boundary" role={error && !errorAtTop ? 'alert' : 'status'}>
      {loading && !errorAtTop ? t(loadingLabel) : error && !errorAtTop ? failure : null}
    </div>
  </div>;
}

export const VideoResultsGallery = MediaResultsGallery;
