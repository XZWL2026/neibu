import { randomUUID } from '../../../shared/randomUUID';
import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { mediaGetGetApiV1MediaTasksTaskId, mediaOptionsGetApiV1MediaTasksTaskIdOutputsOutputIndexEnhancementOptions, type ResponseMediaApplicationTaskEnhancementQuoteServiceOptions as Options } from '../api';
import { t, formatMessage, useLanguage } from '../i18n';
import { clearEnhancement, enhancementActive, EnhancementRejection, readEnhancement, saveEnhancement, submitEnhancement, type Enhancement, type EnhancementRequest } from './enhancement-submission';
import { resolvePreview } from '../canvas/production-api';
import { ComposerIcon } from './ComposerControls';
import './video-enhancement.css';

function mergeVersions(current: Enhancement[], incoming: Enhancement[]) {
  const next = [...current];
  for (const received of incoming) {
    const index = next.findIndex(item => item.id === received.id);
    if (index < 0) { next.push(received); continue; }
    const known = next[index];
    // Settled attempts and archived files cannot be downgraded by a late older read.
    next[index] = !enhancementActive(known)
      ? { ...received, ...known, amount: known.amount ?? received.amount, points: known.points ?? received.points,
          billableSeconds: known.billableSeconds ?? received.billableSeconds }
      : { ...known, ...received, results: received.results?.length ? received.results : known.results };
  }
  return JSON.stringify(next) === JSON.stringify(current) ? current : next;
}

type EnhancementQuotePanelProps = {
  taskId: string; outputIndex: number; fileId: string; ownerId?: string | null; disabled?: boolean; enhancements?: Enhancement[];
  children?(fileId: string): ReactNode; onBusyChange?(busy: boolean): void;
 };

export function EnhancementQuotePanel(props: EnhancementQuotePanelProps) {
  // The source identity owns every preview, saved request and asynchronous read in this panel.
  const identity = JSON.stringify([props.ownerId, props.taskId, props.outputIndex, props.fileId]);
  return <EnhancementQuotePanelContent key={identity} {...props} />;
}

function EnhancementQuotePanelContent({ taskId, outputIndex, fileId, ownerId, disabled = false, enhancements = [], children, onBusyChange }: EnhancementQuotePanelProps) {
  useLanguage();
  const detailsId = useId();
  const [open, setOpen] = useState(false), [options, setOptions] = useState<Options>();
  const [error, setError] = useState(false), [retry, setRetry] = useState(0), [target, setTarget] = useState('');
  const [request, setRequest] = useState<EnhancementRequest | null>(() => readEnhancement(ownerId, taskId, outputIndex, fileId));
  const [submitting, setSubmitting] = useState(false), [notice, setNotice] = useState('');
  const [versions, setVersions] = useState<Enhancement[]>(() => enhancements.filter(item => item.sourceOutputIndex === outputIndex && item.sourceFileId === fileId));
  const [preview, setPreview] = useState(fileId), [syncError, setSyncError] = useState(false), [downloadError, setDownloadError] = useState(false);
  const lifetime = useRef({ ownerId, taskId, outputIndex, fileId, disabled });
  lifetime.current = { ownerId, taskId, outputIndex, fileId, disabled };
  const alive = useRef(true), sending = useRef(false);
  const recovery = useRef({ versions, request });
  recovery.current = { versions, request };
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => { setRequest(readEnhancement(ownerId, taskId, outputIndex, fileId)); setNotice(''); }, [ownerId, taskId, outputIndex, fileId]);
  useEffect(() => {
    setVersions(current => {
      const received = enhancements.filter(item => item.id && item.sourceOutputIndex === outputIndex && item.sourceFileId === fileId);
      return mergeVersions(current, received);
    });
  }, [enhancements, outputIndex, fileId]);
  useEffect(() => {
    if (!request?.taskRef) return;
    setVersions(current => current.some(item => item.id === request.taskRef) ? current : [...current, {
      id: request.taskRef!, status: 'RUNNING', sourceOutputIndex: outputIndex, sourceFileId: fileId, targetResolution: request.target,
      billableSeconds: request.billableSeconds, amount: null, points: null, results: [], refundStatus: null, failureMessage: null,
    }]);
  }, [request, outputIndex, fileId]);
  const active = versions.filter(item => item.id && enhancementActive(item));
  const activeIds = active.map(item => item.id).join(',');
  const busy = submitting || Boolean(request && !request.taskRef) || active.length > 0;
  const busyCallback = useRef(onBusyChange); busyCallback.current = onBusyChange;
  const reportedTerminal = useRef(new Set<string>());
  useEffect(() => { busyCallback.current?.(busy); }, [busy]);
  useEffect(() => {
    if (!activeIds) return;
    let cancelled = false, timer: ReturnType<typeof setTimeout>;
    let pendingIds = activeIds.split(',');
    const poll = async () => {
      const reads = await Promise.allSettled(pendingIds.map(async id => {
        const { data } = await mediaGetGetApiV1MediaTasksTaskId(id);
        if (data?.id !== id || data.operation !== 'ENHANCEMENT') throw new Error('Enhancement mismatch');
        return data;
      }));
      if (cancelled) return;
      setSyncError(reads.some(result => result.status === 'rejected'));
      const confirmed = reads.flatMap(result => result.status === 'fulfilled' ? [result.value] : []);
      confirmed.forEach(item => {
        if (!enhancementActive(item) && !reportedTerminal.current.has(item.id!)) {
          reportedTerminal.current.add(item.id!); window.dispatchEvent(new Event('xzwl-account-changed'));
        }
      });
      setVersions(current => current.map(item => {
        const update = confirmed.find(value => value.id === item.id);
        if (!update) return item;
        const next = { ...item, status: update.status, results: update.results, refundStatus: update.refundStatus, failureMessage: update.failureMessage };
        return next;
      }));
      pendingIds = pendingIds.filter(id => {
        const update = confirmed.find(value => value.id === id);
        return !update || enhancementActive(update);
      });
      if (pendingIds.length) timer = setTimeout(() => void poll(), 3000);
    };
    void poll();
    return () => { cancelled = true; clearTimeout(timer); };
  }, [activeIds]);
  useEffect(() => {
    if (!ownerId || disabled) return;
    let cancelled = false, timer: ReturnType<typeof setTimeout>;
    const read = async () => {
      try {
        const { data } = await mediaGetGetApiV1MediaTasksTaskId(taskId);
        if (cancelled) return;
        if (data?.id !== taskId || !data.results?.some(item => item.index === outputIndex && item.fileId === fileId)) {
          throw new Error('Native record mismatch');
        }
        const received = (data.enhancements ?? []).filter(item => item.sourceOutputIndex === outputIndex && item.sourceFileId === fileId);
        const decimal = (value: unknown) => typeof value === 'string' && /^\d+\.\d{4}$/.test(value);
        if (received.some(item => !item.id || !['720p', '1080p', '2K', '4K'].includes(item.targetResolution ?? '')
            || !decimal(item.amount) || !decimal(item.points) || !Number.isInteger(item.billableSeconds) || item.billableSeconds! < 1)) {
          throw new Error('Enhancement record mismatch');
        }
        const known = recovery.current.versions;
        received.forEach(item => {
          const previous = known.find(value => value.id === item.id);
          if (previous && enhancementActive(previous) && !enhancementActive(item) && !reportedTerminal.current.has(item.id!)) {
            reportedTerminal.current.add(item.id!); window.dispatchEvent(new Event('xzwl-account-changed'));
          }
        });
        const merged = mergeVersions(known, received);
        setVersions(merged);
        recovery.current = { ...recovery.current, versions: merged };
        setRequest(current => {
          const actual = received.find(item => item.id === current?.taskRef && item.targetResolution === current?.target);
          if (!current || !actual) return current;
          const confirmed = { ...current, amount: actual.amount!, points: actual.points!, billableSeconds: actual.billableSeconds! };
          if (JSON.stringify(confirmed) === JSON.stringify(current)) return current;
          saveEnhancement(confirmed); return confirmed;
        });
        setSyncError(false);
        if (merged.some(enhancementActive) || merged.some(item => item.amount === null || item.points === null)
            || (recovery.current.request?.taskRef && !merged.some(item => item.id === recovery.current.request?.taskRef))) {
          timer = setTimeout(() => void read(), 3000);
        }
      } catch {
        if (!cancelled) { setSyncError(true); timer = setTimeout(() => void read(), 3000); }
      }
    };
    void read();
    return () => { cancelled = true; clearTimeout(timer); };
  }, [ownerId, disabled, taskId, outputIndex, fileId, activeIds, request?.taskRef]);
  useEffect(() => {
    if (!request?.taskRef) return;
    const known = versions.find(item => item.id === request.taskRef);
    if (known && !enhancementActive(known)) { clearEnhancement(request); setRequest(null); setNotice(''); }
  }, [versions, request]);
  useEffect(() => {
    if (!open || disabled || (request && !request.taskRef)) return;
    let cancelled = false; setOptions(undefined); setError(false);
    const read = async () => {
      try {
        const { data } = await mediaOptionsGetApiV1MediaTasksTaskIdOutputsOutputIndexEnhancementOptions(taskId, outputIndex);
        if (cancelled) return;
        const decimal = (value: unknown) => typeof value === 'string' && /^[0-9]{1,20}\.[0-9]{4}$/.test(value);
        if (!data || typeof data !== 'object' || data.taskId !== taskId || data.outputIndex !== outputIndex || data.fileId !== fileId
          || !Number.isInteger(data.billableSeconds) || data.billableSeconds! < 1 || !Array.isArray(data.targets) || data.targets.length > 4
          || data.targets.some(item => !item || typeof item !== 'object' || typeof item.quoteId !== 'string' || !/^[A-Za-z0-9._:-]{1,128}$/.test(item.quoteId)
            || !['720p', '1080p', '2K', '4K'].includes(item.targetResolution ?? '') || !decimal(item.amount) || !decimal(item.rate) || !decimal(item.points)
            || typeof item.executionSupported !== 'boolean'
            || typeof item.expiresAt !== 'string' || !Number.isFinite(Date.parse(item.expiresAt)))
          || new Set(data.targets.map(item => item.targetResolution)).size !== data.targets.length) { setError(true); return; }
        setOptions(data); setTarget(data.targets[0]?.targetResolution ?? '');
      } catch { if (!cancelled) setError(true); }
    };
    void read();
    return () => { cancelled = true; };
  }, [open, taskId, outputIndex, fileId, retry, ownerId, disabled, Boolean(request && !request.taskRef)]);
  const selected = options?.targets?.find(item => item.targetResolution === target);
  const supported = selected?.executionSupported === true;
  const targetVersion = versions.find(item => item.targetResolution === target && (enhancementActive(item) || item.status === 'SUCCEEDED'));
  const confirm = async () => {
    if (!ownerId || disabled || sending.current || (!request && (!selected?.quoteId || !supported))) return;
    if (!request && Date.parse(selected!.expiresAt!) <= Date.now()) { setNotice('增强报价已过期，请重新读取并确认费用。'); setRetry(value => value + 1); return; }
    const pending = request ?? { owner: ownerId, taskId, outputIndex, fileId, target, quoteId: selected!.quoteId!, key: randomUUID(),
      amount: selected!.amount!, points: selected!.points!, billableSeconds: options!.billableSeconds!, state: 'SUBMITTING' as const };
    if (!saveEnhancement(pending)) { setNotice('无法保存增强请求，请允许浏览器存储后重试。'); return; }
    sending.current = true; setSubmitting(true); setRequest(pending); setNotice('');
    const current = () => alive.current && lifetime.current.ownerId === ownerId && lifetime.current.taskId === taskId && lifetime.current.fileId === fileId;
    try {
      const accepted = await submitEnhancement(pending, !request);
      if (current()) {
        setRequest({ ...pending, taskRef: accepted.id!, state: 'ACCEPTED' });
        setVersions(items => [...items.filter(item => item.id !== accepted.id), { id: accepted.id!, status: accepted.status, results: accepted.results,
          refundStatus: accepted.refundStatus, failureMessage: accepted.failureMessage, sourceOutputIndex: outputIndex, sourceFileId: fileId, targetResolution: pending.target,
          billableSeconds: pending.billableSeconds, amount: null, points: null }]);
      }
    } catch (cause) {
      if (current()) {
        if (cause instanceof EnhancementRejection) { setRequest(null); setRetry(value => value + 1); setNotice(cause.status === 409 ? '此增强目标已有任务或报价已变更，请重新读取。' : '增强提交未受理，请重新读取报价后重试。'); }
        else { setRequest({ ...pending, state: 'UNCERTAIN' }); setNotice('增强请求可能已受理，请使用原请求确认；请勿重新付费提交。'); }
      }
    } finally { sending.current = false; if (current()) setSubmitting(false); }
  };
  const delivered = versions.filter(item => item.status === 'SUCCEEDED' && item.results?.some(result => result.fileId));
  const download = async (id: string, variant: string) => {
    setDownloadError(false);
    try {
      const url = await resolvePreview(id);
      if (!alive.current || lifetime.current.ownerId !== ownerId) return;
      const link = document.createElement('a'); link.href = url; link.download = `video-${outputIndex + 1}-${variant}`;
      document.body.append(link); link.click(); link.remove();
    } catch { if (alive.current) setDownloadError(true); }
  };
  return <>
    {children?.(preview)}
    <section className="video-enhancement-quote" aria-label={t('超分增强报价')}>
    <div className="video-version-toolbar">
    {delivered.length > 0 ? <label className="video-version-select">{t('视频版本')} <select aria-label={t('视频版本')} value={preview} onChange={event => setPreview(event.target.value)}>
      <option value={fileId}>{t('原版')}</option>{delivered.map(item => <option key={item.id} value={item.results!.find(result => result.fileId)!.fileId!}>{formatMessage('{0}增强版', item.targetResolution)}</option>)}
    </select></label> : <span className="video-native-label"><ComposerIcon name="video" />{t('原版')}</span>}
    <button type="button" className="video-enhancement-toggle" aria-expanded={open} aria-controls={detailsId} onClick={() => setOpen(value => !value)}>{t('超分增强')}<ComposerIcon name="chevron" /></button>
    </div>
    <div className="video-version-actions"><button type="button" onClick={() => void download(fileId, 'native')}>{t('下载原版')}</button>
      {delivered.map(item => <button key={item.id} type="button" data-selected={preview === item.results!.find(result => result.fileId)!.fileId} onClick={() => void download(item.results!.find(result => result.fileId)!.fileId!, item.targetResolution ?? 'enhanced')}>{formatMessage('下载{0}增强版', item.targetResolution ?? '')}</button>)}
    </div>
    {request && !request.taskRef && <div className="video-enhancement-recovery" role="status"><p>{formatMessage('已确认 {0}增强 · {1} 秒 · {2} 积分 · {3} 元', request.target, request.billableSeconds, request.points, request.amount)}</p>
      <button type="button" disabled={disabled || !ownerId || submitting} onClick={() => void confirm()}>{t(submitting ? '正在提交增强…' : '使用原增强请求确认')}</button>
    </div>}
    <div id={detailsId} className="video-enhancement-details" hidden={!open || Boolean(request && !request.taskRef)} aria-live="polite" aria-busy={open && !error && !options && !disabled}>
      {open && !(request && !request.taskRef) && <>
      <p className="video-enhancement-description">{t('按需提升清晰度，生成与增强分别计费。原版始终保留。')}</p>
      {disabled ? <p role="status">{t('请等待当前操作完成后查看增强报价。')}</p>
        : error ? <div className="video-enhancement-feedback" role="status"><p>{t('增强报价暂不可用，请重新读取。')}</p><button type="button" onClick={() => setRetry(value => value + 1)}>{t('重新读取增强报价')}</button></div>
        : !options ? <div className="video-enhancement-loading" role="status"><p>{t('正在读取增强报价…')}</p><span aria-hidden="true" /></div>
          : <>
            {options.targets?.length ? <>
              <div className="video-enhancement-fields"><label>{t('增强目标分辨率')} <select aria-label={t('增强目标分辨率')} value={target} disabled={submitting || Boolean(request?.taskRef)} onChange={event => setTarget(event.target.value)}>
                {options.targets.map(item => <option key={item.targetResolution} value={item.targetResolution ?? ''}>{item.targetResolution}</option>)}
              </select></label>
              <div className="video-enhancement-fee">{selected && <><strong>{formatMessage('{0} 积分', selected.points ?? '')}</strong><span>{formatMessage('{0} 元', selected.amount ?? '')}</span></>}<span>{formatMessage('计费时长 {0} 秒', options.billableSeconds ?? '')}</span></div></div>
              {supported ? <button type="button" className="video-enhancement-confirm" disabled={disabled || !ownerId || submitting || Boolean(request?.taskRef) || Boolean(targetVersion)} onClick={() => void confirm()}>{t(submitting ? '正在提交增强…' : request && !request.taskRef ? '使用原增强请求确认' : '确认费用并增强')}</button>
                : <p className="video-enhancement-feedback">{t('此组合暂未开放付费增强。')}</p>}
              {targetVersion && <p className="video-enhancement-feedback" role="status">{t(targetVersion.status === 'SUCCEEDED' ? '此目标已有增强版，可在视频版本中查看。' : '此目标正在处理或退款中，请等待完成。')}</p>}
              {!ownerId && <p className="video-enhancement-feedback">{t('请登录后发起超分增强。')}</p>}
            </> : <p role="status">{t('此视频暂无可用增强目标，请联系管理员配置适用模板和价格。')}</p>}
          </>}</>}
    </div>
    {notice && <p className="video-enhancement-feedback" role="status">{t(notice)}</p>}
    {versions.map(item => <div className="video-enhancement-status" key={item.id} role="status" data-state={item.status === 'SUCCEEDED' ? 'success' : ['FAILED', 'TIMED_OUT', 'REFUND_PENDING'].includes(item.status ?? '') ? 'failed' : 'processing'}>
      <div className="video-enhancement-status-heading"><strong>{formatMessage('{0}增强版', item.targetResolution)}</strong><span>{t(item.status === 'SUCCEEDED' ? '增强成功' : ['FAILED', 'TIMED_OUT', 'REFUND_PENDING'].includes(item.status ?? '') ? '增强失败，原版已保留' : '增强处理中')}</span></div>
      {item.amount != null && item.points != null && item.billableSeconds != null && <p>{formatMessage('本次增强 {0} 秒 · {1} 积分 · {2} 元', item.billableSeconds, item.points, item.amount)}</p>}
      {item.refundStatus === 'PENDING' && <p className="video-enhancement-refund">{t('增强费用退款处理中')}</p>}
      {item.refundStatus === 'COMPLETED' && <p className="video-enhancement-refund">{t('本次增强积分已退回')}</p>}
    </div>)}
    {syncError && <p className="video-enhancement-feedback" role="status">{t('增强状态暂不可用，将继续同步；请勿重复提交。')}</p>}
    {downloadError && <p className="video-enhancement-feedback" role="status">{t('视频下载暂不可用，请重试；视频版本已保留。')}</p>}
  </section></>;
}
