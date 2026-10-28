import { randomUUID } from '../../../shared/randomUUID';
import { t, formatMessage, useLanguage } from '../i18n';
import { useEffect, useRef, useState } from 'react';
import { ApiError, mediaOptionsGetApiV1MediaGenerationOptions, mediaQuotePostApiV1MediaQuotes, mediaSubmitPostApiV1MediaTasks, mediaGetGetApiV1MediaTasksTaskId,
  type RequestMediaDomainTaskTaskRequest as Request, type ResponseMediaInterfacesHttpMediaTaskControllerTaskResponse as Task,
  type ResponseMediaApplicationCatalogGenerationCatalogServiceOption as Model } from '../api';
import { resolvePreview } from '../canvas/production-api';
import { generationBalance } from '../creator/generation-balance';
import { apiGenerationNotice, useOptionalUserSession } from '../userSession';
import { formatAmount } from '../../../shared/formatAmount';
import type { ProductReference } from './ProductReferences';
import './model-portrait-picker.css';

type Saved = { key: string; request: Request; taskId?: string; terminal?: boolean };
const storageKey = (owner: string) => `xzwl-model-portrait:${owner}`;
function restore(owner: string): Saved | null {
  try {
    const value = JSON.parse(localStorage.getItem(storageKey(owner)) || 'null') as Saved | null;
    return value && typeof value.key === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(value.key)
      && value.request?.type === 'IMAGE' && value.request.count === 1 && value.request.durationSeconds === 0
      && typeof value.request.prompt === 'string' && typeof value.request.model === 'string'
      && typeof value.request.aspectRatio === 'string' && typeof value.request.resolution === 'string'
      && Array.isArray(value.request.references) && value.request.references.length === 0
      && (!value.taskId || /^[A-Za-z0-9_-]{1,128}$/.test(value.taskId)) ? value : null;
  } catch { return null; }
}
function persist(owner: string, value: Saved, replaceKey?: string): boolean {
  try {
    const current = restore(owner);
    if (current && current.key !== value.key && !(current.key === replaceKey && current.taskId && current.terminal)) return false;
    localStorage.setItem(storageKey(owner), JSON.stringify(value)); return true;
  } catch { return false; }
}
function clear(owner: string, key: string) {
  try { if (restore(owner)?.key === key) localStorage.removeItem(storageKey(owner)); } catch { /* Same-key confirmation remains safe. */ }
}
const terminal = (status: string | null | undefined) => ['SUCCEEDED', 'PARTIAL_SUCCESS', 'FAILED', 'TIMED_OUT'].includes(status || '');
type Props = { owner: string; disabled?: boolean; onSelect(reference: ProductReference): void };
export function ModelPortraitPicker(props: Props) {
  useLanguage(); return <PortraitForm key={props.owner} {...props} />; }
function PortraitForm({ owner, disabled = false, onSelect }: Props) {
  useLanguage();
  const account = useOptionalUserSession();
  const [saved, setSaved] = useState(() => restore(owner));
  const [gender, setGender] = useState('女性'), [age, setAge] = useState('25–35岁'), [appearance, setAppearance] = useState('自然日常'), [body, setBody] = useState('匀称'), [details, setDetails] = useState('');
  const [models, setModels] = useState<Model[]>([]), [modelId, setModelId] = useState(saved?.request.model || ''), [ratio, setRatio] = useState(saved?.request.aspectRatio || ''), [resolution, setResolution] = useState(saved?.request.resolution || '');
  const [refresh, setRefresh] = useState(0), [catalogError, setCatalogError] = useState(''), [catalogReady, setCatalogReady] = useState(false);
  const [quote, setQuote] = useState<{ key: string; points?: string; error?: string } | null>(null);
  const [task, setTask] = useState<Task | null>(null), [preview, setPreview] = useState(''), [previewRetry, setPreviewRetry] = useState(0);
  const [submitting, setSubmitting] = useState(false), [error, setError] = useState(''), [syncError, setSyncError] = useState(''), [pollRetry, setPollRetry] = useState(0);
  const alive = useRef(true), sending = useRef(false);
  const accountRef = useRef(account); accountRef.current = account;
  const pending = Boolean(saved && !saved.taskId), running = Boolean(saved?.taskId && !terminal(task?.status));
  const identityReady = !account || Boolean(account.authenticated && account.userId === owner && !['loading', 'error'].includes(account.status));
  const allowed = identityReady && !apiGenerationNotice(account, 'IMAGE');
  const locked = disabled || submitting || pending || running || !allowed;
  const model = models.find(value => value.model === modelId);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => {
    const reload = () => setRefresh(value => value + 1);
    window.addEventListener('focus', reload); window.addEventListener('xzwl-account-changed', reload);
    return () => { window.removeEventListener('focus', reload); window.removeEventListener('xzwl-account-changed', reload); };
  }, []);
  useEffect(() => {
    let cancelled = false; setCatalogReady(false); setCatalogError('');
    void mediaOptionsGetApiV1MediaGenerationOptions().then(({ data }) => {
      if (cancelled) return;
      const available = (data.models || []).filter(value => value.type === 'IMAGE' && value.model && value.maxCount >= 1 && value.aspectRatios?.length && value.resolutions?.length);
      setModels(available); setModelId(current => available.some(item => item.model === current) ? current : available[0]?.model || ''); setCatalogReady(true);
    }, () => { if (!cancelled) setCatalogError('模特图片模型读取失败，请重试。'); });
    return () => { cancelled = true; };
  }, [refresh]);
  useEffect(() => {
    if (!model) return;
    setRatio(current => model.aspectRatios?.includes(current) ? current : model.aspectRatios?.find(value => value === '3:4') || model.aspectRatios?.[0] || '');
    setResolution(current => model.resolutions?.includes(current) ? current : model.resolutions?.[0] || '');
  }, [model]);
  const settings = { type: 'IMAGE' as const, model: modelId, aspectRatio: ratio, resolution, count: 1, durationSeconds: 0, references: [] };
  const quoteKey = JSON.stringify([settings, refresh, catalogReady]);
  const currentQuote = quote?.key === quoteKey ? quote : null;
  const balance = generationBalance(account, currentQuote?.points);
  const valid = Boolean(catalogReady && model && model.aspectRatios?.includes(ratio) && model.resolutions?.includes(resolution));
  useEffect(() => {
    if (!valid || !allowed || pending || running) return;
    let cancelled = false; setQuote({ key: quoteKey });
    void mediaQuotePostApiV1MediaQuotes(settings).then(({ data }) => {
      if (!cancelled) setQuote(typeof data.points === 'string' && /^\d+\.\d{4}$/.test(data.points)
        ? { key: quoteKey, points: data.points } : { key: quoteKey, error: '模特生成报价暂不可用。' });
    }, () => { if (!cancelled) setQuote({ key: quoteKey, error: '模特生成报价失败，请重试。' }); });
    return () => { cancelled = true; };
  }, [quoteKey, valid, allowed, pending, running]);
  useEffect(() => {
    if (!saved?.taskId || !identityReady) return;
    const operation = saved; let cancelled = false; let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      try {
        const { data } = await mediaGetGetApiV1MediaTasksTaskId(operation.taskId!);
        if (cancelled) return;
        if (data.id !== operation.taskId || data.type !== 'IMAGE') throw new Error('wrong task');
        setTask(data); setSyncError('');
        if (terminal(data.status)) {
          persist(owner, { ...operation, terminal: true });
          if (!operation.terminal) window.dispatchEvent(new Event('xzwl-account-changed'));
          return;
        }
      } catch { if (!cancelled) setSyncError('模特生成进度暂不可用，请重新读取。'); }
      if (!cancelled) timer = setTimeout(() => void poll(), 3000);
    }
    void poll(); return () => { cancelled = true; clearTimeout(timer); };
  }, [saved?.taskId, identityReady, pollRetry]);
  const fileId = terminal(task?.status) && ['SUCCEEDED', 'PARTIAL_SUCCESS'].includes(task?.status || '') ? task?.results?.find(result => result.fileId)?.fileId : null;
  useEffect(() => {
    let cancelled = false; setPreview('');
    if (fileId) void resolvePreview(fileId).then(url => { if (!cancelled) { setPreview(url); setSyncError(''); } }, () => { if (!cancelled) setSyncError('模特图片暂时无法显示，请重新加载。'); });
    return () => { cancelled = true; };
  }, [fileId, previewRetry]);
  async function generate() {
    const currentAccount = accountRef.current;
    if (sending.current || disabled || (currentAccount && (!currentAccount.authenticated || currentAccount.userId !== owner || ['loading', 'error'].includes(currentAccount.status) || apiGenerationNotice(currentAccount, 'IMAGE')))) return;
    const first = !pending;
    if (first && (locked || !valid || !currentQuote?.points || !balance.affordable)) return;
    const operation: Saved = pending ? saved! : { key: `model-${randomUUID()}`, request: { ...settings,
      prompt: `生成一张写实电商模特全身形象。模特明确已成年，${gender}，年龄${age}，${body}体型，${appearance}风格。单人从头到脚完整入镜，正面自然站姿，双臂稍离躯干，纯净浅灰背景，柔和均匀棚拍光线，穿着不透明的简洁合身基础服装，无文字或水印。补充外观要求：${details.trim() || '无'}。最终形象必须是明确已成年的成年人，不采用未成年外观。` } };
    if (!persist(owner, operation, saved?.key)) { setError('无法保存本次模特请求，或已有另一笔请求待确认，请刷新页面后重试。'); return; }
    sending.current = true; setSubmitting(true); setSaved(operation); setError(''); setTask(null);
    try {
      const { data } = await mediaSubmitPostApiV1MediaTasks(operation.request, { headers: { 'Idempotency-Key': operation.key } });
      if (!data.id) throw new Error('missing task');
      const accepted = { ...operation, taskId: data.id };
      // Only this request may update recovery; an older unmounted flight cannot replace a newer request.
      if (restore(owner)?.key === operation.key) persist(owner, accepted);
      if (alive.current) { setSaved(accepted); setTask(data); }
    } catch (cause) {
      // Catalog rejection happens only after replay lookup; parsing and authentication can reject before it.
      const rejected = cause instanceof ApiError && (cause.status === 422 || (first && [400, 401, 403, 404].includes(cause.status)));
      if (rejected) clear(owner, operation.key);
      if (alive.current) { if (rejected) { setSaved(null); setRefresh(value => value + 1); } setError(rejected ? '模特生成未受理，请检查账户和参数后重试。' : '暂时无法确认模特生成结果，请使用原请求重试。'); }
    } finally { if (alive.current) { sending.current = false; setSubmitting(false); } }
  }
  return <section className="model-portrait-picker" aria-label={t("AI 模特形象")}>
    <fieldset disabled={locked}><legend>{t("模特形象")}</legend><div className="model-portrait-fields">
      <label>{t("模特性别")}<select value={gender} onChange={event => setGender(event.target.value)}>{['女性', '男性', '中性外观'].map(value => <option value={value} key={value}>{t(value)}</option>)}</select></label>
      <label>{t("成年年龄段")}<select value={age} onChange={event => setAge(event.target.value)}>{['18–24岁', '25–35岁', '36–45岁', '40–55岁', '55岁以上'].map(value => <option value={value} key={value}>{t(value)}</option>)}</select></label>
      <label>{t("外观风格")}<select value={appearance} onChange={event => setAppearance(event.target.value)}>{['自然日常', '简约时尚', '运动活力', '成熟优雅'].map(value => <option value={value} key={value}>{t(value)}</option>)}</select></label>
      <label>{t("模特体型")}<select value={body} onChange={event => setBody(event.target.value)}>{['匀称', '纤细', '健美', '丰盈'].map(value => <option value={value} key={value}>{t(value)}</option>)}</select></label>
      <label className="model-portrait-wide">{t("补充外观要求")}<textarea value={details} maxLength={800} onChange={event => setDetails(event.target.value)} /></label>
      <label className="model-portrait-wide">{t("模特图片模型")}<select value={modelId} onChange={event => setModelId(event.target.value)}>{models.map(item => <option key={item.model} value={item.model || ''}>{item.displayName || item.model}</option>)}</select></label>
      <label>{t("模特画面比例")}<select value={ratio} onChange={event => setRatio(event.target.value)}>{model?.aspectRatios?.map(value => <option value={value} key={value}>{t(value)}</option>)}</select></label>
      <label>{t("模特分辨率")}<select value={resolution} onChange={event => setResolution(event.target.value)}>{model?.resolutions?.map(value => <option value={value} key={value}>{t(value)}</option>)}</select></label>
    </div></fieldset>
    <button type="button" disabled={disabled || submitting || !allowed || (pending ? false : locked || !valid || !currentQuote?.points || !balance.affordable)} onClick={() => void generate()}>
      {t(submitting ? '提交中…' : pending ? '使用原请求重试' : formatMessage("生成模特形象{0}", t(currentQuote?.points ? formatMessage(" · {0} 积分", formatAmount(currentQuote.points)) : '')))}</button>
    {running && <p role="status">{t("模特形象生成中…")}</p>}
    {terminal(task?.status) && !fileId && <p role="alert">{t(task?.failureMessage || '模特形象生成失败，请重新生成。')}{t(task?.refundStatus === 'COMPLETED' ? '失败积分已退回。' : '')}</p>}
    {preview && <div className="model-portrait-result"><img src={preview} alt={t("生成的成年模特形象")} onError={() => { setPreview(''); setSyncError('模特图片暂时无法显示，请重新加载。'); }} />
      <button type="button" disabled={disabled || !identityReady} onClick={() => { if (fileId && !disabled && identityReady) onSelect({ fileId, preview, name: 'AI 模特形象' }); }}>{t("使用此模特")}</button></div>}
    {syncError && <p role="alert">{t(syncError)}<button type="button" onClick={() => { setSyncError(''); setPollRetry(value => value + 1); setPreviewRetry(value => value + 1); }}>{t("重新读取模特")}</button></p>}
    {(catalogError || currentQuote?.error) && <p role="alert">{t(catalogError || currentQuote?.error)}<button type="button" disabled={submitting} onClick={() => setRefresh(value => value + 1)}>{t("重新获取模特报价")}</button></p>}
    {catalogReady && !models.length && <p role="status">{t("暂无可用的图片模型。")}</p>}
    {currentQuote?.points && !pending && balance.notice && <p role="status">{t(balance.notice)}</p>}
    {error && <p role="alert">{t(error)}</p>}
  </section>;
}
