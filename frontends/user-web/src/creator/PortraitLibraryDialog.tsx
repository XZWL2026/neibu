import { randomUUID } from '../../../shared/randomUUID';
import { t, formatMessage, useLanguage } from '../i18n';
import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  ApiError, mediaPortraitSubmitPostApiV1MediaPortraits, mediaPortraitListGetApiV1MediaPortraits,
  mediaPortraitGetGetApiV1MediaPortraitsPortraitId, storageUploadReferenceImagePostApiV1FilesReferenceImages, storageUploadReferenceMediaPostApiV1FilesReferenceMedia, storageUploadProcessingVideoPostApiV1FilesProcessingVideos,
} from '../api';
import { resolvePreview } from '../canvas/production-api';
import type { ComposerMaterial } from './ComposerMaterials';

type Portrait = Awaited<ReturnType<typeof mediaPortraitGetGetApiV1MediaPortraitsPortraitId>>['data'];
type Kind = NonNullable<ComposerMaterial['kind']>;
type Pending = { key: string; fileId: string; model: string; kind: Kind };
export const REVIEW_MEDIA_ACCEPT = 'image/png,image/jpeg,video/mp4,video/quicktime,audio/mpeg,audio/mp3,audio/wav,audio/x-wav,audio/wave,audio/vnd.wave';
const kinds: Kind[] = ['IMAGE', 'VIDEO', 'AUDIO'];
const rowKind = (row: Portrait): Kind => row.kind ?? 'IMAGE';
const labels: Record<Portrait['status'], string> = {
  SUBMITTING: '正在提交审核', PROCESSING: '审核中', ACTIVE: '审核已通过', FAILED: '审核未通过', UNCERTAIN: '提交结果待确认',
};
const identifier = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$/.test(value);
const valid = (row: Portrait) => row && identifier(row.id) && identifier(row.fileId) && identifier(row.model) && Object.hasOwn(labels, row.status)
  && kinds.includes(rowKind(row)) && (rowKind(row) === 'IMAGE' || typeof row.durationSeconds === 'number' && Number.isFinite(row.durationSeconds) && row.durationSeconds > 0);
const storageKey = (owner: string, model: string) => `xzwl-portrait-submit:${owner}:${model}`;
function readPending(owner: string | null, model: string): Pending | null {
  if (!owner) return null;
  try {
    const entry = JSON.parse(sessionStorage.getItem(storageKey(owner, model)) || 'null');
    return entry && identifier(entry.key) && identifier(entry.fileId) && entry.model === model && kinds.includes(entry.kind ?? 'IMAGE') ? { ...entry, kind: entry.kind ?? 'IMAGE' } : null;
  } catch { return null; }
}
function savePending(owner: string | null, model: string, pending: Pending | null) {
  if (!owner) return;
  if (pending) sessionStorage.setItem(storageKey(owner, model), JSON.stringify(pending));
  else sessionStorage.removeItem(storageKey(owner, model));
}
function failureMessage(cause: unknown) {
  if (cause instanceof ApiError) {
    if (cause.status === 503) return '人像审核服务尚未配置或暂不可用，请联系管理员';
    if (cause.status === 422) return '当前模型、连接或素材不支持人像审核，请检查设置';
    if (cause.status === 401 || cause.status === 403) return '当前账户无权使用此人像，请重新登录或检查权限';
  }
  return '人像审核请求未完成，请刷新状态；已提交的审核不会重新创建';
}

export function PortraitLibraryDialog({ owner, model, onClose, onPick, disabled = false, maxSelection = Infinity, maxSelectionByKind = {}, title = '人像库', initialFile, initialMaterial, canvas, visible = true, originals = false }: {
  canvas?: { onUploadState?(busy: boolean, failed?: boolean): void; materials: ComposerMaterial[]; onUploaded(material: ComposerMaterial, file?: File): void; onReview(fileId: string, reviewId: string | undefined, status: ComposerMaterial["reviewStatus"]): void };
  originals?: boolean; visible?: boolean; title?: string; initialFile?: File; initialMaterial?: ComposerMaterial; owner: string | null; model: string; disabled?: boolean; maxSelection?: number; maxSelectionByKind?: Partial<Record<Kind, number>>; onClose(): void; onPick(materials: ComposerMaterial[]): void;
}) {
  useLanguage();
  const dialog = useRef<HTMLDialogElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const consumedFile = useRef<File | null>(null);
  const consumedMaterial = useRef<string | null>(null);
  const localUrls = useRef(new Set<string>());
  const [retryingId, setRetryingId] = useState<string | null>(null);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const latest = useRef({ maxSelection, maxSelectionByKind, disabled, onPick }); latest.current = { maxSelection, maxSelectionByKind, disabled, onPick };
  const [uploadPreview, setUploadPreview] = useState<{ url: string; kind: Kind } | null>(null);
  const [rows, setRows] = useState<Portrait[]>([]);
  const [previews, setPreviews] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [info, setInfo] = useState('');
  const [cursor, setCursor] = useState<string | null>(null);
  const [pending, setPending] = useState<Pending | null>(() => readPending(owner, model));
  const context = `${owner}:${model}`;
  const current = useRef(context); current.current = context;
  const alive = useRef(true);
  const sending = useRef(false);
  const refreshing = useRef(false);
  const rowsRef = useRef(rows); rowsRef.current = rows;
  const materialsRef = useRef(canvas?.materials); materialsRef.current = canvas?.materials;
  const previewAttempts = useRef(new Set<string>());
  const isCurrent = () => alive.current && current.current === context;
  const merge = (items: Portrait[], replace = false, append = false) => setRows(existing => {
    const combined = new Map<string, Portrait>();
    for (const row of replace ? items : append ? [...existing, ...items] : [...items, ...existing]) {
      const key = `${row.model}:${row.fileId}`;
      if (!combined.has(key)) combined.set(key, row);
    }
    return [...combined.values()];
  });
  const load = async (beforeId?: string) => {
    if (canvas && !model) { setLoading(false); return; }
    setLoading(true);
    try {
      const { data } = await mediaPortraitListGetApiV1MediaPortraits({ limit: 20, ...(beforeId ? { beforeId } : {}) });
      if (!data.every(valid)) throw new Error('invalid portrait response');
      if (!isCurrent()) return;
      merge(data.filter(row => originals || row.model === model), !beforeId, Boolean(beforeId));
      setCursor(data.length === 20 ? data.at(-1)!.id : null);
    } catch (cause) { if (isCurrent()) setError(failureMessage(cause)); }
    finally { if (isCurrent()) setLoading(false); }
  };
  const refresh = async () => {
    if (refreshing.current) return;
    refreshing.current = true;
    try {
      const known = rowsRef.current;
      const restore = (materialsRef.current ?? []).filter(item => item.reviewId && !known.some(row => row.id === item.reviewId) && item.reviewStatus !== 'ACTIVE' && item.reviewStatus !== 'FAILED');
      const pendingRows = [...known.filter(row => ['SUBMITTING', 'PROCESSING', 'UNCERTAIN'].includes(row.status)), ...restore.map(item => ({ id: item.reviewId!, fileId: item.fileId, kind: item.kind ?? 'IMAGE' }))];
      for (const row of pendingRows) {
        const { data } = await mediaPortraitGetGetApiV1MediaPortraitsPortraitId(row.id);
        if (!valid(data) || data.id !== row.id || data.fileId !== row.fileId || (!canvas && !originals && data.model !== model) || rowKind(data) !== (row.kind ?? 'IMAGE')) throw new Error('mismatched portrait');
        if (!isCurrent()) return;
        canvas?.onReview(data.fileId, data.id, data.status);
        if (originals || data.model === model) merge([data]);
      }
    } catch (cause) { if (isCurrent()) setError(failureMessage(cause)); }
    finally { refreshing.current = false; }
  };
  useEffect(() => {
    alive.current = true; setRetryingId(null); setSelectedIds([]); setUploadPreview(null); setRows([]); setPreviews({}); previewAttempts.current.clear(); setError('');
    setPending(readPending(owner, model)); void load();
    const timer = setInterval(() => { void refresh(); }, 3000);
    return () => {
      alive.current = false; clearInterval(timer);
      localUrls.current.forEach(url => URL.revokeObjectURL(url)); localUrls.current.clear();
    };
  }, [context]);
  useEffect(() => {
    if (!visible) return;
    const previous = document.activeElement as HTMLElement | null;
    const element = dialog.current;
    if (element?.showModal) element.showModal(); else element?.setAttribute('open', '');
    element?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus();
    return () => { element?.close?.(); previous?.focus(); };
  }, [visible]);
  const previousVisible = useRef(visible);
  useEffect(() => {
    if (visible && !previousVisible.current) void load();
    previousVisible.current = visible;
  }, [visible]);
  useEffect(() => {
    if (canvas) for (const row of rows) canvas.onReview(row.fileId, row.id, row.status);
    for (const row of rows) if (!previews[row.fileId] && !previewAttempts.current.has(row.fileId)) {
      previewAttempts.current.add(row.fileId);
      void resolvePreview(row.fileId).then(url => {
        if (isCurrent()) setPreviews(previous => ({ ...previous, [row.fileId]: url }));
      }).catch(() => {});
    }
  }, [rows, context]);
  const clearPending = (entry: Pending) => {
    if (readPending(owner, model)?.key === entry.key) savePending(owner, model, null);
    if (isCurrent()) setPending(current => current?.key === entry.key ? null : current);
  };
  const send = async (entry: Pending) => {
    try {
      const { data } = await mediaPortraitSubmitPostApiV1MediaPortraits({ fileId: entry.fileId, model: entry.model, ...(entry.kind !== 'IMAGE' ? { kind: entry.kind } : {}) }, { headers: { 'Idempotency-Key': entry.key } });
      if (!valid(data) || data.fileId !== entry.fileId || data.model !== entry.model || rowKind(data) !== entry.kind) throw new Error('mismatched portrait');
      clearPending(entry);
      if (isCurrent()) { canvas?.onReview(data.fileId, data.id, data.status); merge([data]); setUploadPreview(null); }
    } catch (cause) {
      // These admission refusals cannot have accepted a new review operation.
      if (cause instanceof ApiError && [400, 401, 403, 404, 409, 422].includes(cause.status)) clearPending(entry);
      if (isCurrent()) canvas?.onReview(entry.fileId, undefined, cause instanceof ApiError && [400, 401, 403, 404, 409, 422].includes(cause.status) ? 'FAILED' : 'UNCERTAIN');
      throw cause;
    }
  };
  const upload = async (file: File) => {
    if (disabled || (!canvas && loading) || sending.current || pending) return;
    const kind: Kind | null = ['image/png', 'image/jpeg'].includes(file.type) ? 'IMAGE'
      : ['video/mp4', 'video/quicktime'].includes(file.type) ? 'VIDEO'
      : ['audio/mpeg', 'audio/mp3', 'audio/wav', 'audio/x-wav', 'audio/wave', 'audio/vnd.wave'].includes(file.type) ? 'AUDIO' : null;
    if (!kind || !file.size || file.size > (kind === 'IMAGE' ? 20 : kind === 'VIDEO' ? 200 : 15) * 1024 * 1024) {
      setError('支持 PNG/JPEG（20MB）、MP4/MOV（200MB）、MP3/WAV（15MB）'); canvas?.onUploadState?.(false, true); return;
    }
    let failed = false;
    sending.current = true; setBusy(true); canvas?.onUploadState?.(true); setError(''); setInfo('');
    try {
      const preview = URL.createObjectURL(file); localUrls.current.add(preview); setUploadPreview({ url: preview, kind });
      const { data } = await (kind === 'IMAGE' ? storageUploadReferenceImagePostApiV1FilesReferenceImages({ file }) : canvas && kind === 'VIDEO' ? storageUploadProcessingVideoPostApiV1FilesProcessingVideos({ file }) : storageUploadReferenceMediaPostApiV1FilesReferenceMedia({ file }));
      if (!identifier(data.fileId)) throw new Error('invalid file');
      if (!isCurrent()) return;
      setPreviews(previous => ({ ...previous, [data.fileId!]: preview }));
      if (canvas) {
        let durablePreview = '';
        try { durablePreview = await resolvePreview(data.fileId); } catch { /* Original remains usable without its preview. */ }
        if (!isCurrent()) return;
        const duration = 'durationSeconds' in data ? data.durationSeconds : null;
        const longVideo = kind === 'VIDEO' && typeof duration === 'number' && duration > 30;
        canvas.onUploaded({ fileId: data.fileId, name: file.name, kind, preview: durablePreview, reviewPending: true, reviewStatus: longVideo || !model ? 'UNAVAILABLE' : 'SUBMITTING',
          ...(typeof duration === 'number' ? { durationSeconds: duration } : {}), ...(typeof data.sizeBytes === 'number' ? { sizeBytes: data.sizeBytes } : {}) }, file);
        if (longVideo || !model) {
          setUploadPreview(null);
          setInfo(longVideo ? '长视频已保存，可用于深度白模；超过 30 秒不提交人物参考审核。' : '原文件已保存，当前没有可用的人物参考审核模型；普通参考和深度处理仍可使用。');
          return;
        }
      }
      const entry = { key: randomUUID(), fileId: data.fileId, model, kind };
      // Persist before the first review POST so a remount can replay its original key.
      savePending(owner, model, entry); setPending(entry);
      await send(entry);
    } catch (cause) {
      failed = true;
      if (isCurrent()) setError(failureMessage(cause));
    } finally { sending.current = false; if (isCurrent()) { setBusy(false); canvas?.onUploadState?.(false, failed); } }
  };
  useEffect(() => {
    if (!initialFile || consumedFile.current === initialFile || disabled || (!canvas && loading) || busy || pending) return;
    consumedFile.current = initialFile;
    void upload(initialFile);
  }, [initialFile, disabled, loading, busy, pending]);
  useEffect(() => {
    if (!initialMaterial) { consumedMaterial.current = null; return; }
    if (!model || disabled || loading || busy || pending || sending.current || consumedMaterial.current === initialMaterial.fileId) return;
    consumedMaterial.current = initialMaterial.fileId;
    const entry = { key: randomUUID(), fileId: initialMaterial.fileId, model, kind: initialMaterial.kind ?? 'IMAGE' as const };
    sending.current = true; setBusy(true); setError('');
    void (async () => {
      try {
        savePending(owner, model, entry); setPending(entry);
        await send(entry);
      } catch (cause) {
        if (isCurrent()) setError(failureMessage(cause));
      } finally { sending.current = false; if (isCurrent()) setBusy(false); }
    })();
  }, [initialMaterial, model, disabled, loading, busy, pending]);
  const retry = async () => {
    if (disabled || loading || !pending || sending.current) return;
    sending.current = true; setBusy(true); setError('');
    try { await send(pending); }
    catch (cause) { if (isCurrent()) setError(failureMessage(cause)); }
    finally { sending.current = false; if (isCurrent()) setBusy(false); }
  };
  const reviewAgain = async (row: Portrait) => {
    if (disabled || loading || sending.current || pending || row.status !== 'FAILED') return;
    sending.current = true; setBusy(true); setRetryingId(row.id); setError('');
    try {
      const entry = { key: randomUUID(), fileId: row.fileId, model, kind: rowKind(row) };
      savePending(owner, model, entry); setPending(entry);
      await send(entry);
    } catch (cause) { if (isCurrent()) setError(failureMessage(cause)); }
    finally { sending.current = false; if (isCurrent()) { setBusy(false); setRetryingId(null); } }
  };
  const selected = selectedIds.flatMap(id => { const row = rows.find(row => row.id === id); return row && (canvas || row.status === 'ACTIVE') ? [row] : []; });
  const pick = async () => {
    if (disabled || sending.current || !selected.length || selected.length > maxSelection) return;
    sending.current = true; setBusy(true); setError('');
    try {
      const refreshed = await Promise.all(selected.map(async row => {
        const { data } = await mediaPortraitGetGetApiV1MediaPortraitsPortraitId(row.id);
        if (!valid(data) || data.id !== row.id || data.fileId !== row.fileId || (!canvas && !originals && data.model !== model) || rowKind(data) !== (row.kind ?? 'IMAGE')) throw new Error('mismatched portrait');
        return data;
      }));
      if (!isCurrent()) return;
      merge(refreshed);
      const refused = refreshed.find(row => row.status !== 'ACTIVE');
      if (refused && !canvas) { setError(refused.errorMessage || '所选人像审核尚未通过，请重新选择'); return; }
      const materials = await Promise.all(refreshed.map(async data => {
        let preview = previews[data.fileId] || '';
        if (localUrls.current.has(preview)) {
          try { preview = await resolvePreview(data.fileId); } catch { preview = ''; }
        }
        return { fileId: data.fileId, ...(canvas ? { reviewId: data.id, reviewStatus: data.status } : originals ? {} : { portraitId: data.id }), name: `人像 ${data.id}`, kind: rowKind(data), durationSeconds: data.durationSeconds ?? null, preview };
      }));
      if (!isCurrent()) return;
      if (latest.current.disabled || materials.length > latest.current.maxSelection || kinds.some(kind => materials.filter(item => item.kind === kind).length > (latest.current.maxSelectionByKind[kind] ?? Infinity))) { setError('可用名额已变化，请调整选择后重试'); return; }
      latest.current.onPick(materials);
    } catch (cause) { if (isCurrent()) setError(failureMessage(cause)); }
    finally { sending.current = false; if (isCurrent()) setBusy(false); }
  };
  const uploadDisabled = disabled || loading || busy || Boolean(pending);
  const isSelected = (row: Portrait) => selected.some(item => item.id === row.id);
  const cannotSelect = (row: Portrait) => disabled || busy || (!canvas && row.status !== 'ACTIVE') || (!isSelected(row) && (selected.length >= maxSelection || selected.filter(item => rowKind(item) === rowKind(row)).length >= (maxSelectionByKind[rowKind(row)] ?? Infinity)));
  const toggle = (row: Portrait) => {
    if (!cannotSelect(row)) setSelectedIds(current => current.includes(row.id) ? current.filter(id => id !== row.id) : [...current, row.id]);
  };
  if (!visible) return <>
    {busy && <p role="status">{t("正在上传素材并提交审核…")}</p>}
    {error && <p role="alert">{t(error)}</p>}
    {pending && !busy && <button type="button" disabled={disabled || loading} onClick={() => { void retry(); }}>{t("确认上次审核提交")}</button>}
  </>;
  return createPortal(<dialog ref={dialog} className="media-library-dialog portrait-library-dialog" aria-label={t(title)} aria-modal="true"
    onCancel={event => { event.preventDefault(); if (!canvas || !busy) onClose(); }} onKeyDown={event => {
      if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); if (!canvas || !busy) onClose(); }
    }}>
    <header className="media-library-header portrait-library-header">
      <h2>{t(title)}</h2>
      <div className="media-library-actions">
        <span aria-live="polite">{formatMessage("已选：{0}", selected.length)}</span>
        <button type="button" className="media-library-confirm" disabled={disabled || loading || busy || !selected.length || selected.length > maxSelection}
          onClick={() => { void pick(); }}>{t("确认")}</button>
        <button type="button" className="media-library-close" aria-label={formatMessage("关闭{0}", t(title))} disabled={Boolean(canvas && busy)} onClick={onClose}>×</button>
      </div>
    </header>
    <p className="media-library-hint">{t(canvas ? '本地素材自动审核。普通参考和深度处理使用原文件；人物参考需审核通过。' : '上传后自动审核，通过后可选用。')}{t(Number.isFinite(maxSelection) && formatMessage("本次最多可选 {0} 份素材。", maxSelection))}</p>
    {info && <p role="status">{info}</p>}
    {error && <p className="portrait-library-error" role="alert">{t(error)}</p>}
    {pending && <button type="button" disabled={disabled || loading || busy} onClick={() => { void retry(); }}>{t("确认上次审核提交")}</button>}
    {loading && <p role="status">{t("正在读取人像素材…")}</p>}
    <div className="media-library-grid portrait-library-grid">
      {!originals && <button type="button" className="portrait-library-upload" aria-label={t("点击或拖动此处上传")} disabled={uploadDisabled}
        onClick={() => input.current?.click()} onDragOver={event => event.preventDefault()}
        onDrop={event => { event.preventDefault(); const file = event.dataTransfer.files[0]; if (file && !uploadDisabled) void upload(file); }}>
        <span className="portrait-library-plus" aria-hidden="true">＋</span>
        <strong>{t("点击或拖动此处上传")}</strong><small>PNG/JPEG ≤20MB · MP4/MOV ≤200MB · MP3/WAV ≤15MB；{t(canvas ? '长视频可用于深度白模' : '视频、音频 2–30 秒')}</small>
      </button>}
      <input ref={input} hidden aria-label={t("上传人像")} type="file" accept={REVIEW_MEDIA_ACCEPT} disabled={uploadDisabled} onChange={event => {
        const file = event.target.files?.[0]; event.target.value = ''; if (file) void upload(file);
      }} />
      {uploadPreview && <div className="media-library-card">
        <div className="media-library-item portrait-library-item" aria-busy={busy}>
          <PortraitPreview kind={uploadPreview.kind} url={uploadPreview.url} alt={t("正在上传的人像")} />
          {busy ? <span className="portrait-library-overlay"><span className="portrait-library-spinner" role="status" aria-label={t("正在上传人像")} /></span>
            : <span className="media-library-unavailable">{t(pending ? '提交结果待确认' : '上传未完成，请重试')}</span>}
        </div>
      </div>}
      <div className="portrait-library-records" role="listbox" aria-label={t("人像素材")} aria-multiselectable="true">
        {rows.map(row => {
          const reviewing = retryingId === row.id || row.status === 'SUBMITTING' || row.status === 'PROCESSING';
          return <div className="media-library-card" key={row.id}>
            <div className="media-library-item portrait-library-item" role="option" aria-label={formatMessage("人像 {0}", row.id)}
              aria-selected={isSelected(row)} aria-disabled={cannotSelect(row)} aria-busy={reviewing}
              tabIndex={0} onClick={() => toggle(row)} onKeyDown={event => {
                if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); toggle(row); }
                else if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) {
                  event.preventDefault();
                  const options = [...dialog.current!.querySelectorAll<HTMLElement>('[role="option"]')];
                  const direction = event.key === 'ArrowLeft' || event.key === 'ArrowUp' ? -1 : 1;
                  options[(options.indexOf(event.currentTarget) + direction + options.length) % options.length]?.focus();
                }
              }}>
              {previews[row.fileId] ? <PortraitPreview kind={rowKind(row)} url={previews[row.fileId]} alt={t("人像预览")} /> : <span className="portrait-library-preview-empty">{t("预览暂不可用")}</span>}
              {reviewing && <span className="portrait-library-overlay"><span className="portrait-library-spinner" role="status" aria-label={t(retryingId === row.id ? '正在提交审核' : labels[row.status])} /></span>}
              {isSelected(row) && <span className="media-library-check" aria-hidden="true">✓</span>}
              {row.status === 'UNCERTAIN' && <span className="media-library-unavailable">{t(row.errorMessage || labels[row.status])}</span>}
            </div>
            {canvas && <small>{t(labels[row.status])}</small>}
            {rowKind(row) !== 'IMAGE' && previews[row.fileId] && <a href={previews[row.fileId]} target="_blank" rel="noreferrer">{t(rowKind(row) === 'VIDEO' ? '播放视频' : '播放音频')}</a>}
            {row.status === 'FAILED' && !reviewing && <div className="media-library-unavailable portrait-library-failure">
              <span>{t(row.errorMessage || labels[row.status])}</span>
              <button type="button" className="portrait-library-retry" disabled={disabled || loading || busy || Boolean(pending)}
                onClick={() => { void reviewAgain(row); }}>{t("重新审核")}</button>
            </div>}
          </div>;
        })}
      </div>
    </div>
    {canvas && canvas.materials.some(item => !rows.some(row => row.fileId === item.fileId)) && <section aria-label={t("当前画布素材")}>
      <h3>{t("当前画布素材")}</h3><div className="media-library-grid">
        {canvas.materials.filter(item => !rows.some(row => row.fileId === item.fileId)).map(item => <div className="media-library-card" key={item.fileId}>
          <button className="media-library-item" disabled={disabled || busy} aria-label={formatMessage("引用素材 {0}", item.name)} onClick={() => onPick([item])}>
            {item.preview ? <PortraitPreview kind={item.kind ?? 'IMAGE'} url={item.preview} alt={item.name} /> : <span>{t("预览暂不可用")}</span>}
          </button>
          <small>{t(item.kind === 'VIDEO' && (item.durationSeconds ?? 0) > 30 ? '长视频，可用于深度白模' : item.reviewPending ? '原文件已保留，人物参考审核未完成' : '普通参考素材')}</small>
          {(item.reviewPending || item.reviewId) && model && !(item.kind === 'VIDEO' && (item.durationSeconds ?? 0) > 30) && <button disabled={disabled || loading || busy || Boolean(pending)} onClick={() => {
            if (sending.current) return;
            const entry = { key: randomUUID(), fileId: item.fileId, model, kind: item.kind ?? 'IMAGE' as const };
            savePending(owner, model, entry); setPending(entry);
            sending.current = true; setBusy(true); setError('');
            void send(entry).catch(cause => { if (isCurrent()) setError(failureMessage(cause)); }).finally(() => { sending.current = false; if (isCurrent()) setBusy(false); });
          }}>{t(item.reviewId ? '为当前模型审核' : '重试素材审核')}</button>}
        </div>)}
      </div>
    </section>}
    {!loading && !rows.length && !canvas?.materials.length && !uploadPreview && <p className="media-library-hint">{t("还没有人像素材")}</p>}
    {cursor && <button type="button" disabled={loading || busy} onClick={() => { void load(cursor); }}>{t("加载更多人像")}</button>}
  </dialog>, document.body);
}

function PortraitPreview({ kind, url, alt }: { kind: Kind; url: string; alt: string }) {
  useLanguage();
  return kind === 'IMAGE' ? <img src={url} alt={alt} />
    : kind === 'VIDEO' ? <video src={url} muted playsInline preload="metadata" aria-label={alt} />
    : <span className="media-library-kind-icon" aria-label={t("音频人像")}>♫</span>;
}
