import { t, formatMessage, useLanguage } from '../i18n';
import { useCallback, useState, type CSSProperties } from 'react';
import './media-loading.css';

export function MediaSpinner({ label }: { label: string }) {
  useLanguage();
  return <span className="media-loading-overlay" role="status" aria-label={label}><span className="media-loading-spinner" aria-hidden="true" /></span>;
}
type Props = { src: string; kind: 'image' | 'video'; label: string; busy?: boolean; busyLabel?: string; controls?: boolean; muted?: boolean; className?: string; style?: CSSProperties; onError?(): void; onReady?(): void };
export function LoadingMedia(props: Props) {
  useLanguage(); return <MediaContent key={`${props.kind}:${props.src}`} {...props} />; }
function MediaContent({ src, kind, label, busy = false, busyLabel = '上传中', controls, muted, className, style, onError, onReady }: Props) {
  useLanguage();
  const [ready, setReady] = useState(false), [failed, setFailed] = useState(false), [buffering, setBuffering] = useState(false);
  const cached = useCallback((element: HTMLImageElement | HTMLVideoElement | null) => {
    if (element && (element instanceof HTMLImageElement ? element.complete && element.naturalWidth > 0 : element.readyState >= 2)) { setReady(true); onReady?.(); }
  }, [onReady]);
  const loaded = () => { setReady(true); setBuffering(false); onReady?.(); };
  const error = () => { setFailed(true); setBuffering(false); onError?.(); };
  const loading = !failed && (!ready || busy || buffering);
  return <span className={`media-load${className ? ` ${className}` : ''}`} style={style} data-ready={ready} data-loading={loading} aria-busy={loading}>
    {kind === 'image' ? <img ref={cached} src={src} alt={label} style={{ opacity: ready ? busy ? .45 : 1 : 0 }} onLoad={loaded} onError={error} />
      : <video ref={cached} src={src} aria-label={label} controls={controls} muted={muted} playsInline preload="auto" style={{ opacity: ready ? 1 : 0 }} onLoadedData={loaded} onCanPlay={() => setBuffering(false)} onPlaying={() => setBuffering(false)} onWaiting={() => setBuffering(true)} onPause={() => setBuffering(false)} onError={error} />}
    {loading && <MediaSpinner label={formatMessage("{0} {1}", label, t(busy ? busyLabel : '加载中'))} />}
    {failed && !onError && <span className="media-preview-unavailable" aria-label={formatMessage("{0} 加载失败", label)}>ⓘ</span>}
  </span>;
}
