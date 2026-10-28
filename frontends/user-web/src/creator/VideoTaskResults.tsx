import { t, formatMessage, useLanguage, currentLanguage } from '../i18n';
import { useCallback, useEffect, useRef, useState } from 'react';
import { mediaGetGetApiV1MediaTasksTaskId, type ResponseMediaInterfacesHttpMediaTaskControllerTaskResponse as Task } from '../api';
import { resolvePreview } from '../canvas/production-api';
import type { ComposerMaterial } from './ComposerMaterials';
import type { CanvasMediaTaskRequest } from '../canvas/media-task-port';
import './video-results.css';
import { ComposerDialog, ComposerIcon } from './ComposerControls';
import { LoadingMedia, MediaSpinner } from './LoadingMedia';
import { EnhancementQuotePanel } from './EnhancementQuotePanel';
import { enhancementActive } from './enhancement-submission';

export type VideoSubmission = {
  key: string; taskRef?: string; replacesTaskId?: string; request?: CanvasMediaTaskRequest;
  snapshot: Partial<Task>; state?: 'PREPARED' | 'SUBMITTING' | 'UNCERTAIN' | 'REJECTED';
  message?: string;
  draft?: { prompt: string; materials: (ComposerMaterial | null)[]; mode: string; bindings: { token: string; fileId: string }[] };
};
const terminal = (status: string | null | undefined) => ['SUCCEEDED', 'PARTIAL_SUCCESS', 'FAILED', 'TIMED_OUT'].includes(status ?? '');
const statusLabel = (status: string | null | undefined, delivered: number) => {
  switch (status) {
    case 'SUCCEEDED': return '生成成功';
    case 'PARTIAL_SUCCESS': return '部分成功';
    case 'FAILED': case 'TIMED_OUT': case 'REJECTED': return '生成失败';
    case 'REFUND_PENDING': return delivered ? '部分成功' : '生成失败';
    default: return '生成中';
  }
};
function FailureReason({ message }: { message: string }) {
  useLanguage();
  const [expanded, setExpanded] = useState(false);
  return <div className="video-failure-reason"><p className={expanded ? '' : 'video-reason-clamped'}>{t(message)}</p>
    {message.length > 90 && <button type="button" aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>{t(expanded ? '收起原因' : '展开原因')}</button>}</div>;
}
function TaskVideo({ fileId, index, onReady }: { fileId: string; index: number; onReady?(fileId: string): void }) {
  useLanguage();
  const settled = useCallback(() => onReady?.(fileId), [fileId, onReady]);
  const [url, setUrl] = useState('');
  const [error, setError] = useState(false);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let cancelled = false; setError(false); setUrl('');
    void resolvePreview(fileId).then(value => { if (!cancelled) setUrl(value); }, () => { if (!cancelled) { setError(true); settled(); } });
    return () => { cancelled = true; };
  }, [fileId, retry, settled]);
  return <div className="video-output">{url && <LoadingMedia key={`${fileId}:${retry}`} kind="video" src={url} label={formatMessage("生成视频 {0}", index + 1)} controls onReady={settled} onError={() => { setError(true); settled(); }} />}
    {error ? <div className="video-preview-error"><p>{t("视频暂时无法加载，生成结果已保留。")}</p><button type="button" onClick={() => setRetry(value => value + 1)}>{t("重新加载视频")}</button></div>
      : !url && <MediaSpinner label={t("正在加载视频")} />}
  </div>;
}
function GenerationProgress({ status, image }: { status: string | null | undefined; image: boolean }) {
  useLanguage();
  // Estimates follow observed stages; only a successful server result completes the task.
  const stages: Record<string, [number, number, number]> = {
    PREPARED: [0, 3, 10], SUBMITTING: [3, 7, 15], UNCERTAIN: [3, 7, 30],
    ROUTING: [7, 10, 10], CHARGE_PENDING: [10, 12, 10], SUBMIT_PENDING: [12, 15, 15],
    RUNNING: [15, 90, image ? 45 : 120], ARCHIVING: [92, 99, 30],
    SUCCEEDED: [100, 100, 1],
  };
  const [floor, ceiling, seconds] = stages[status ?? 'PREPARED'] ?? stages.PREPARED;
  const [progress, setProgress] = useState(floor);
  useEffect(() => {
    const started = Date.now();
    const update = () => setProgress(previous => Math.max(previous,
      floor + Math.round((ceiling - floor) * (1 - Math.exp(-(Date.now() - started) / (seconds * 1000))))));
    update();
    const timer = setInterval(update, 1000);
    return () => clearInterval(timer);
  }, [floor, ceiling, seconds]);
  return <div className="generation-progress" role="progressbar" aria-label={formatMessage("{0}生成进度", t(image ? '图片' : '视频'))}
    aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress} aria-valuetext={t(progress === 100 ? '100%' : formatMessage("预估 {0}%", progress))} data-complete={status === 'SUCCEEDED'}>
    <span className="generation-orbit" aria-hidden="true" />
    <div className="generation-progress-row">
      <span className="generation-progress-track" aria-hidden="true"><span className="generation-progress-fill" style={{ width: `${progress}%` }} /></span>
      <span className="generation-progress-percent">{progress}%</span>
    </div>
  </div>;
}
export function MediaTaskCard({ entry, actionsDisabled = false, showPromptAction = true, onEdit, onRegenerate, onDelete, compact = false, showMetadata = true, enableEnhancement = false, enhancementOwnerId, actionNotice }: {
  entry: VideoSubmission; actionsDisabled?: boolean; showPromptAction?: boolean; compact?: boolean; showMetadata?: boolean; enableEnhancement?: boolean; enhancementOwnerId?: string | null;
  onEdit?: (entry: VideoSubmission) => void; onRegenerate?: (entry: VideoSubmission) => void; onDelete?: (entry: VideoSubmission) => void;
  actionNotice?: string;
}) {
  useLanguage();
  const image = entry.snapshot.type === 'IMAGE';
  const noun = image ? '图片' : '视频';
  const [read, setRead] = useState({ ref: entry.taskRef, source: entry.snapshot, task: entry.snapshot });
  const task = read.ref === entry.taskRef && read.source === entry.snapshot ? read.task : entry.snapshot;
  const [expanded, setExpanded] = useState(false);
  const [previewVisited, setPreviewVisited] = useState(false);
  const openPreview = () => { setPreviewVisited(true); setExpanded(true); };
  const [syncError, setSyncError] = useState(false);
  const [retry, setRetry] = useState(0);
  const [showPrompt, setShowPrompt] = useState(false);
  const [enhancementBusy, setEnhancementBusy] = useState<Record<number, boolean>>({});
  useEffect(() => setEnhancementBusy({}), [entry.taskRef, enhancementOwnerId]);
  const [completion, setCompletion] = useState<{ ref?: string; elapsed: boolean; ready: string[] }>({ elapsed: false, ready: [] });
  const mediaReady = useCallback((fileId: string) => setCompletion(current =>
    !current.ref || current.ready.includes(fileId) ? current : { ...current, ready: [...current.ready, fileId] }), []);
  useEffect(() => {
    if (!completion.ref) return;
    // Keep 100% readable for one second after the 400ms fill animation.
    const timer = setTimeout(() => setCompletion(current => ({ ...current, elapsed: true })), 1400);
    return () => clearTimeout(timer);
  }, [completion.ref]);
  const reported = useRef({ ref: entry.taskRef, terminal: terminal(entry.snapshot.status), generating: !entry.taskRef });
  useEffect(() => {
    if (!entry.taskRef) { reported.current = { ref: undefined, terminal: false, generating: true }; return; }
    if (reported.current.ref !== entry.taskRef) reported.current = {
      ref: entry.taskRef, terminal: terminal(entry.snapshot.status), generating: !reported.current.ref && reported.current.generating,
    };
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const read = async () => {
      try {
        const response = (await mediaGetGetApiV1MediaTasksTaskId(entry.taskRef!)).data;
        if (response.id !== entry.taskRef) throw new Error('task mismatch');
        if (cancelled) return;
        // A restored submission can have a stale snapshot: its first successful read is history, not a new completion.
        if (response.status === 'SUCCEEDED' && reported.current.generating && !reported.current.terminal) setCompletion({ ref: entry.taskRef, elapsed: false, ready: [] });
        if (response.status && !terminal(response.status)) reported.current.generating = true;
        setRead({ ref: entry.taskRef, source: entry.snapshot, task: response }); setSyncError(false);
        if (terminal(response.status)) {
          if (!reported.current.terminal) { reported.current.terminal = true; window.dispatchEvent(new Event('xzwl-account-changed')); }
          return;
        }
      } catch { if (!cancelled) setSyncError(true); }
      if (!cancelled) timer = setTimeout(() => void read(), 3000);
    };
    void read();
    return () => { cancelled = true; clearTimeout(timer); };
  }, [entry.taskRef, entry.snapshot, retry]);
  // Server submission data and local admission snapshot never read the editable composer selection.
  const snapshot = { ...entry.snapshot, ...task };
  const omni = snapshot.referenceMode === 'OMNI';
  const followsFrame = !omni && Boolean(snapshot.references?.length && snapshot.referenceFollowsFirstFrame);
  const outputs = task.results?.filter(output => output.fileId) ?? [];
  const enhancements = task.enhancements ?? [];
  const processingEnhancement = Object.values(enhancementBusy).some(Boolean) || enhancements.some(item => !(item.sourceOutputIndex in enhancementBusy) && enhancementActive(item));
  const finishing = Boolean(entry.taskRef && completion.ref === entry.taskRef
    && (!completion.elapsed || outputs.some(output => !completion.ready.includes(output.fileId!))));
  const status = entry.taskRef ? task.status : entry.state;
  const failed = ['FAILED', 'TIMED_OUT', 'PARTIAL_SUCCESS', 'REFUND_PENDING', 'REJECTED'].includes(status ?? '');
  const busy = (Boolean(status) || reported.current.generating) && !failed && !terminal(status);
  const failures = task.failures?.length ? task.failures : failed ? [{ index: outputs.length, message: task.failureMessage || entry.message || '生成失败，请稍后重试' }] : [];
  const label = statusLabel(status, outputs.length);
  const created = snapshot.createdAt ? new Date(snapshot.createdAt) : null;
  const metadata = <>
    {created && !Number.isNaN(created.getTime()) && <time dateTime={snapshot.createdAt!}>{created.toLocaleString(currentLanguage(), { hour12: false })}</time>}
    <header className="video-result-meta"><span>{snapshot.modelName || snapshot.model}</span>
      {!image && <span>{t(omni ? '全能参考' : snapshot.references?.length ? '首尾帧' : '文生视频')}</span>}
      <span>{formatMessage("比例 {0}", t(followsFrame ? '跟随首帧' : snapshot.aspectRatio || '未记录'))}</span>
      <span>{formatMessage("分辨率 {0}", t(snapshot.resolution || '未记录'))}</span>{image ? <span>{formatMessage("数量 {0} 张", t(snapshot.count ?? '未记录'))}</span> : <span>{formatMessage("时长 {0}{1}", t(snapshot.durationSeconds ?? '未记录'), snapshot.durationSeconds != null ? 's' : '')}</span>}
    </header>
  </>;
  const detail = <>
    {showMetadata && metadata}
    <div className="video-result-media" data-media-type={image ? 'IMAGE' : 'VIDEO'}>
      {onDelete && <button type="button" className="result-delete" aria-label={formatMessage("删除{0}", t(noun))} disabled={actionsDisabled || !entry.taskRef || !terminal(status) || processingEnhancement} onClick={() => {
        if (window.confirm(formatMessage("确定删除这条{0}生成记录吗？", t(noun)))) onDelete({ ...entry, snapshot });
      }}><ComposerIcon name="delete" /></button>}
      {!busy && outputs.length > 0 && <div className="video-result-outputs" aria-hidden={finishing} data-revealing={finishing}>
        {outputs.map(output => (image ? <TaskImage key={`${output.index}:${output.fileId}`} fileId={output.fileId!} index={output.index} onReady={mediaReady} /> : <div key={`${output.index}:${output.fileId}`}>
          {enableEnhancement && entry.taskRef && snapshot.type === 'VIDEO' && snapshot.operation === 'GENERATION' && ['SUCCEEDED', 'PARTIAL_SUCCESS'].includes(status ?? '')
            ? <EnhancementQuotePanel key={`${entry.taskRef}:${enhancementOwnerId}:${output.index}`} taskId={entry.taskRef} outputIndex={output.index} fileId={output.fileId!} ownerId={enhancementOwnerId} disabled={actionsDisabled} enhancements={enhancements}
              onBusyChange={busy => setEnhancementBusy(current => current[output.index] === busy ? current : { ...current, [output.index]: busy })}>
              {fileId => <TaskVideo fileId={fileId} index={output.index} onReady={mediaReady} />}</EnhancementQuotePanel>
            : <TaskVideo fileId={output.fileId!} index={output.index} onReady={mediaReady} />}</div>))}
      </div>}
      {(busy || finishing || failed) && <div className="video-result-status" role="status">
        {busy || finishing ? <GenerationProgress key={entry.request?.requestRef ?? entry.taskRef ?? entry.key} status={status} image={image} />
          : <><span className="video-task-info" aria-hidden="true">ⓘ</span><h3>{t(label)}</h3></>}
        {failures.map(failure => <div key={failure.index} className="video-failure-item">{outputs.length > 0 && <span>{formatMessage("{0} {1} 生成失败", t(noun), failure.index + 1)}</span>}<FailureReason message={failure.message || '生成失败，请稍后重试'} /></div>)}
        {task.refundStatus === 'COMPLETED' && <p className="video-result-outcome">{t("失败部分积分已退回")}</p>}
        {entry.state === 'UNCERTAIN' && !entry.taskRef && <p>{t("请求可能已受理，请使用下方原请求重试确认。")}</p>}
      </div>}
    </div>
    {onDelete && processingEnhancement && <p className="video-result-outcome" role="status">{t('增强任务仍在处理或退款中，请完成后再隐藏此视频记录。')}</p>}
    {actionNotice && <p className="video-result-outcome" role="alert">{t(actionNotice)}</p>}
    {(showPromptAction || onEdit || onRegenerate) && <div className="result-card-actions">
      {showPromptAction && <button type="button" aria-haspopup="dialog" onClick={() => setShowPrompt(true)}>{t("查看提示词")}</button>}
      {onEdit && <button type="button" disabled={actionsDisabled || !entry.taskRef} onClick={() => { setExpanded(false); onEdit({ ...entry, snapshot }); }}><ComposerIcon name="edit" />{t("重新编辑")}</button>}
      {onRegenerate && <button type="button" disabled={actionsDisabled || !entry.taskRef || !terminal(status) || processingEnhancement} onClick={() => { setExpanded(false); onRegenerate({ ...entry, snapshot }); }}><ComposerIcon name="retry" />{t("重新生成")}</button>}
    </div>}
    {showPromptAction && showPrompt && <ComposerDialog title={t("提示词")} closeLabel={t("关闭提示词")} onClose={() => setShowPrompt(false)}><p className="result-prompt-text">{snapshot.prompt}</p></ComposerDialog>}
    {task.refundStatus === 'PENDING' && <p className="video-result-outcome" role="status">{t("失败部分退款处理中")}</p>}
    {syncError && <p role="status">{t("任务状态暂不可用，将继续同步；请勿重复提交。")}<button type="button" onClick={() => setRetry(value => value + 1)}>{t("立即同步")}</button></p>}
  </>;
  const previewLabel = formatMessage(image ? '预览图片：{0}' : '预览视频：{0}', snapshot.prompt || t(image ? '图片生成' : '视频生成'));
  return <article className={`video-result-card${compact ? ' video-result-compact' : ''}`} data-task-key={entry.key}
    aria-label={formatMessage("{0}任务：{1}", t(noun), snapshot.prompt || formatMessage("{0}生成", t(noun)))}>
    {compact ? <>
      <button type="button" className="video-compact-open" aria-haspopup="dialog" aria-label={previewLabel} onClick={openPreview}>
        <span className="video-compact-cover">
          {!busy && outputs[0]?.fileId ? <MediaThumbnail fileId={outputs[0].fileId} image={image} /> : <ComposerIcon name={image ? 'image' : 'video'} />}
          <span className="video-compact-status">{t(label)}</span>
        </span>
        <span className="video-compact-prompt">{snapshot.prompt || t(image ? '图片生成' : '视频生成')}</span>
        <span className="video-compact-meta"><span>{snapshot.modelName || snapshot.model}</span><span>{image ? formatMessage('数量 {0} 张', snapshot.count ?? outputs.length) : snapshot.durationSeconds != null ? `${snapshot.durationSeconds}s` : ''}</span></span>
        {created && !Number.isNaN(created.getTime()) && <time dateTime={snapshot.createdAt!}>{created.toLocaleString(currentLanguage(), { hour12: false })}</time>}
        {failed && <span className="video-compact-failure">{t(failures[0]?.message || '生成失败，请稍后重试')}</span>}
        {syncError && <span className="video-compact-failure">{t('任务状态暂不可用，将继续同步；请勿重复提交。')}</span>}
      </button>
    </> : null}
    {image ? compact ? expanded && <ComposerDialog className="video-preview-dialog" title={t('图片预览')} closeLabel={t('关闭图片预览')} onClose={() => setExpanded(false)}><div className="video-result-card">{detail}</div></ComposerDialog> : detail
      : (!compact || previewVisited) && <ComposerDialog className="video-preview-dialog" title={t('视频预览')} closeLabel={t('关闭视频预览')}
        open={expanded} inlineClosed={!compact} onClose={() => setExpanded(false)}>{detail}</ComposerDialog>}
  </article>;
}

function MediaThumbnail({ fileId, image }: { fileId: string; image: boolean }) {
  const element = useRef<HTMLSpanElement>(null);
  const [visible, setVisible] = useState(false), [url, setUrl] = useState('');
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (typeof IntersectionObserver === 'undefined') { setVisible(true); return; }
    const observer = new IntersectionObserver(items => setVisible(items.some(item => item.isIntersecting)), { rootMargin: '200px' });
    if (element.current) observer.observe(element.current);
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    if (!visible) return;
    let cancelled = false;
    setUrl(''); setFailed(false);
    void resolvePreview(fileId).then(value => { if (!cancelled) setUrl(value); }, () => { if (!cancelled) setFailed(true); });
    return () => { cancelled = true; };
  }, [fileId, visible]);
  return <span ref={element} className="video-thumbnail" aria-hidden="true">
    {visible && url && !failed ? image ? <img src={url} alt="" loading="lazy" decoding="async" onError={() => setFailed(true)} /> : <video src={`${url}${url.includes('#') ? '' : '#t=0.1'}`} preload="metadata" muted playsInline tabIndex={-1} onError={() => setFailed(true)} /> : <ComposerIcon name={image ? 'image' : 'video'} />}
    {failed && <span>{t('预览暂不可用')}</span>}
  </span>;
}

// Both composers consume the same task/settlement contract and polling behavior.
export function VideoTaskCard(props: Parameters<typeof MediaTaskCard>[0]) {
  return <MediaTaskCard {...props} enableEnhancement={props.enableEnhancement ?? true} />;
}

function TaskImage({ fileId, index, onReady }: { fileId: string; index: number; onReady?(fileId: string): void }) {
  useLanguage();
  const settled = useCallback(() => onReady?.(fileId), [fileId, onReady]);
  const [url, setUrl] = useState('');
  const [error, setError] = useState(false);
  const [retry, setRetry] = useState(0);
  const [expanded, setExpanded] = useState(false);
  const download = () => {
    // Storage serves attachments directly; native downloads do not require CORS.
    const link = document.createElement('a');
    link.href = url; link.download = `image-${index + 1}`;
    document.body.append(link); link.click(); link.remove();
  };
  useEffect(() => {
    let cancelled = false; setError(false); setUrl('');
    void resolvePreview(fileId).then(value => { if (!cancelled) setUrl(value); }, () => { if (!cancelled) { setError(true); settled(); } });
    return () => { cancelled = true; };
  }, [fileId, retry, settled]);
  return <div className="image-output">
    {url && <button type="button" className="image-output-preview" aria-label={formatMessage("放大图片 {0}", index + 1)} onClick={() => setExpanded(true)}>
      <LoadingMedia key={`${fileId}:${retry}`} kind="image" src={url} label={formatMessage("生成图片 {0}", index + 1)} onReady={settled} onError={() => { setError(true); settled(); }} />
    </button>}
    {error ? <div className="video-preview-error"><p>{t("图片暂时无法加载，生成结果已保留。")}</p><button type="button" onClick={() => setRetry(value => value + 1)}>{t("重新加载图片")}</button></div> : !url && <MediaSpinner label={t("正在加载图片")} />}
    {url && <details className="image-output-actions"
      onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) event.currentTarget.open = false; }}
      onKeyDown={event => { if (event.key === 'Escape') { event.preventDefault(); event.currentTarget.open = false; event.currentTarget.querySelector('summary')?.focus(); } }}>
      <summary aria-label={formatMessage("图片 {0} 更多操作", index + 1)}>•••</summary>
      <div className="image-download-menu"><button type="button" aria-label={formatMessage("下载图片 {0}", index + 1)} onClick={download}>{t("下载")}</button></div>
    </details>}
    {expanded && <ComposerDialog className="image-preview-dialog" title={t("图片预览")} closeLabel={t("关闭图片预览")} onClose={() => setExpanded(false)}
      closeIcon={<svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round"><path d="m6 6 12 12M6 18 18 6" /></svg>}
      headerActions={<button className="image-preview-download" type="button" aria-label={formatMessage("下载图片 {0}", index + 1)} onClick={download}>
        <svg aria-hidden="true" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M12 3v12m-4-4 4 4 4-4M4 16v5h16v-5" /></svg>{t("下载")}
      </button>}>
      <div className="image-preview-stage"><LoadingMedia className="image-output-full" kind="image" src={url} label={formatMessage("生成图片 {0} 放大预览", index + 1)} /></div>
    </ComposerDialog>}
  </div>;
}

export function ImageResultPreview({ fileId }: { fileId: string }) {
  useLanguage();
  return /^[A-Za-z0-9_-]{1,128}$/.test(fileId)
    ? <section className="video-result-card" aria-label={t("图片查看")}><TaskImage fileId={fileId} index={0} /></section>
    : <p role="alert">{t("图片地址无效，请返回图片创作。")}</p>;
}
