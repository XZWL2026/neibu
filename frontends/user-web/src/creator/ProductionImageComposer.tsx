import { randomUUID } from '../../../shared/randomUUID';
import { groupImageModels } from './image-model-groups';
import { ComposerParameterFields } from './ComposerParameterFields';
import { homeCreationEntry, homeModelMatches } from '../home/home-entry';
import { readComposerDraft, saveComposerDraft, draftSaveError, type ComposerDraftScope } from './composer-draft';
import { t, formatMessage, useLanguage } from '../i18n';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import { creationTaskDraft, regenerationEntry, restoreMaterialPreviews } from './creation-task-draft';
import { CreationWorkspace } from './CreationWorkspace';
import { ExpandableComposer } from './ExpandableComposer';
import { submitCreation, CreationRejection } from './creation-submission';
import { MediaTaskCard, type VideoSubmission } from './VideoTaskResults';
import { MediaResultsGallery } from './VideoResultsGallery';
import { readMediaHistory } from './video-history';
import { readVideoSubmission, saveVideoSubmission } from './video-submission-recovery';
import { formatAmount } from '../../../shared/formatAmount';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import {
  ApiError, mediaDeleteHistoryDeleteApiV1MediaTasksTaskId, mediaListGetApiV1MediaTasks, accessListGetApiV1AccessKeys, mediaOptionsGetApiV1MediaGenerationOptions,
  mediaQuotePostApiV1MediaQuotes, storageUploadReferenceImagePostApiV1FilesReferenceImages,
  type ResponseMediaApplicationCatalogGenerationCatalogServiceOption as ModelOption,
} from '../api';
import { mediaReference, resolvePreview } from '../canvas/production-api';
import type { CanvasGenerationSettings } from '../canvas/model';
import type { CanvasMediaTaskRequest } from '../canvas/media-task-port';
import './video-settings.css';
import { apiGenerationNotice, useOptionalUserSession } from '../userSession';
import { generationBalance } from './generation-balance';
import './image-settings.css';
import { ComposerMaterials, materialReviewIssue, type ComposerMaterial } from './ComposerMaterials';
import { MaterialPrompt, useMaterialMentions } from './MaterialPrompt';
import { uploadErrorMessage } from './upload-error';
import { ComposerDialog, ComposerIcon, ComposerModeMenu, sortedResolutions } from './ComposerControls';

type ImageOption = ModelOption & { type: 'IMAGE'; model: string; displayName: string; aspectRatios: string[]; resolutions: string[] };
type Reference = ComposerMaterial;
const usable = (option: ModelOption): option is ImageOption => option.type === 'IMAGE' && Boolean(option.model && option.displayName)
  && Boolean(option.aspectRatios?.length && option.resolutions?.length) && Number.isInteger(option.maxCount) && option.maxCount > 0
  && Number.isInteger(option.maxReferenceImages) && option.maxReferenceImages >= 0;
function compatible(current: CanvasGenerationSettings | null, model: ImageOption): CanvasGenerationSettings {
  return { type: model.type, model: model.model,
    aspectRatio: current && model.aspectRatios.includes(current.aspectRatio) ? current.aspectRatio : model.aspectRatios[0],
    resolution: current && model.resolutions.includes(current.resolution) ? current.resolution : model.resolutions[0],
    count: current && Number.isInteger(current.count) && current.count >= 1 && current.count <= model.maxCount ? current.count : 1,
    durationSeconds: 0 };
}
export function ProductionImageComposer({ className = 'floating-composer', testId = 'creator-composer', active = true, historyOnly = false, entryOnly = false, onHandoff, draftScope = 'create', inspiration }: {
  className?: string; testId?: string; active?: boolean; historyOnly?: boolean; entryOnly?: boolean; onHandoff?: () => void; draftScope?: ComposerDraftScope; inspiration?: { id: number; prompt: string } | null;
}) {
  useLanguage();
  const navigate = useNavigate();
  const location = useLocation();
  const galleryEnabled = !entryOnly && location.pathname === '/create/image';
  const entry = useRef(homeCreationEntry(location.state));
  const entryOwner = useRef<string | undefined>(undefined);
  const preferredModel = useRef(entry.current?.modelName);
  const [editRequest, setEditRequest] = useState(0);
  const [draftOwner, setDraftOwner] = useState<string | null>(null);
  const [historyReady, setHistoryReady] = useState(false);
  const [submissionKey, setSubmissionKey] = useState('');
  const [submissionError, setSubmissionError] = useState('');
  const recovered = useRef<string | null>(null);
  const lastStarted = useRef(0);
  const lastDraft = useRef('');
  const composing = active && !historyOnly;
  const [models, setModels] = useState<ImageOption[]>([]);
  const [selection, setSelection] = useState<CanvasGenerationSettings | null>(null);
  const [reviewModel, setReviewModel] = useState('');
  const [catalog, setCatalog] = useState<'loading' | 'ready' | 'failed'>('loading');
  const [catalogError, setCatalogError] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [prompt, setPrompt] = useState('');
  useEffect(() => { if (inspiration) setPrompt(inspiration.prompt); }, [inspiration]);
  const [mode, setMode] = useState<'auto' | 'text' | 'reference'>('auto');
  const [layer, setLayer] = useState<'models' | 'settings' | null>(null);
  const [uploadError, setUploadError] = useState('');
  const [files, setFiles] = useState<Reference[]>([]);
  const [uploading, setUploading] = useState(false);
  const [queueBusy, setQueueBusy] = useState(false);
  const [quote, setQuote] = useState<{ key: string; points?: string; error?: string } | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [entries, setEntries] = useState<VideoSubmission[]>([]);
  const [historyError, setHistoryError] = useState('');
  const [historyCursor, setHistoryCursor] = useState<string | null>(null);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const pendingEntry = useRef<VideoSubmission | null>(null);
  const alive = useRef(true);
  const sending = useRef(false);
  const uploadingRef = useRef(false);
  const request = useRef<CanvasMediaTaskRequest | null>(null);
  const account = useOptionalUserSession();
  const commerceEntry = entryOnly && draftScope === 'commerce';
  const loginRequired = commerceEntry && account?.status === 'anonymous';
  const accountFailed = commerceEntry && account?.status === 'error';
  const entryStatus = commerceEntry ? accountFailed ? '账户读取失败' : account?.status === 'loading' ? '正在读取账户' : catalog === 'loading' ? '正在加载模型…' : catalog === 'failed' ? '模型加载失败' : !models.length ? '暂无可用模型' : null : null;
  const apiNotice = apiGenerationNotice(account, 'IMAGE');
  const apiNoticeRef = useRef(apiNotice);
  apiNoticeRef.current = apiNotice;
  const locked = submitting || uncertain || account?.status === 'loading';
  const model = models.find(item => item.model === selection?.model);
  const modelGroups = groupImageModels(models);
  const modelGroup = modelGroups.find(group => group.variants.some(item => item.model === model?.model));
  const resolutions = sortedResolutions([...new Set(modelGroup?.variants.flatMap(item => item.resolutions) ?? [])]);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => {
    if (!composing || locked) return;
    const reload = () => setRefresh(value => value + 1);
    window.addEventListener('focus', reload);
    window.addEventListener('xzwl-account-changed', reload);
    return () => { window.removeEventListener('focus', reload); window.removeEventListener('xzwl-account-changed', reload); };
  }, [composing, locked]);
  useEffect(() => {
    if (!composing || locked) return;
    let cancelled = false;
    setCatalog('loading'); setCatalogError('');
    void mediaOptionsGetApiV1MediaGenerationOptions().then(response => {
      if (cancelled) return;
      setReviewModel((response.data.models ?? []).find(option => option.type === 'VIDEO' && option.omniReference)?.model ?? '');
      const available = (response.data.models ?? []).filter(usable);
      setModels(available);
      setSelection(current => {
        const preferred = available.find(item => homeModelMatches(item.displayName, preferredModel.current));
        const next = preferred ?? available.find(item => item.model === current?.model) ?? available[0];
        if (next) preferredModel.current = undefined;
        return next ? compatible(current, next) : null;
      });
      setCatalog('ready');
    }, async cause => {
      if (cancelled) return;
      setCatalog('failed'); setModels([]);
      const status = cause instanceof ApiError ? cause.status : null;
      setCatalogError(status === 401 ? '登录已失效，请重新登录。'
        : status === 403 ? '当前账户无权读取图片模型，请检查账户权限。'
        : status === 422 ? '当前账户暂不满足图片生成条件，请检查账户开通、密钥状态和可用价格。'
        : '图片模型加载失败，请稍后重试。');
      if (status === 422) {
        try {
          const { data: keys } = await accessListGetApiV1AccessKeys();
          if (cancelled) return;
          if (!keys.length) setCatalogError('当前账户尚未开通生成服务，请先完成账户开通与首充。');
          else if (keys.every(key => !key.enabled)) setCatalogError('当前账户密钥已禁用，请联系管理员处理。');
        } catch { /* Preserve the server refusal when key metadata is unavailable. */ }
      }
    });
    return () => { cancelled = true; };
  }, [composing, refresh, locked]);

  const fileIssues = files.map((file, index) => mode === 'text' ? '当前模式不使用素材，请切换模式或移除'
    : model && index >= model.maxReferenceImages ? `超出当前模型上限（${model.maxReferenceImages} 张）` : materialReviewIssue(file, reviewModel));
  const mentions = useMaterialMentions(prompt, files, fileIssues, 'IMAGE');
  const inputValid = !mentions.error && !fileIssues.some(Boolean) && (mode !== 'reference' || files.length > 0);
  const references = files.map(file => mediaReference(file.fileId));
  const parametersValid = Boolean(model && selection && Number.isInteger(selection.count) && selection.count >= 1
    && selection.count <= model.maxCount && model.resolutions.includes(selection.resolution) && model.aspectRatios.includes(selection.aspectRatio)
    && files.length <= model.maxReferenceImages && inputValid);
  // Prompt edits do not affect pricing; invalid material mentions still invalidate the quote.
  const quoteKey = JSON.stringify({ selection, references, inputValid, mode, uploading, composing, refresh, catalog });
  const currentQuote = quote?.key === quoteKey ? quote : null;
  const balance = generationBalance(account, currentQuote?.points);
  useEffect(() => {
    if (!composing || catalog !== 'ready' || !selection || !parametersValid || uploading || locked) return;
    let cancelled = false;
    setQuote({ key: quoteKey });
    void mediaQuotePostApiV1MediaQuotes({ ...selection, references }).then(response => {
      if (cancelled) return;
      const points = response.data.points;
      setQuote(typeof points === 'string' && /^\d+\.\d{4}$/.test(points)
        ? { key: quoteKey, points } : { key: quoteKey, error: '报价失败，请检查账户余额与生成设置后重试' });
    }, () => { if (!cancelled) setQuote({ key: quoteKey, error: '报价失败，请检查账户余额与生成设置后重试' }); });
    return () => { cancelled = true; };
  }, [quoteKey, parametersValid, uploading, locked]);
  // Account refresh temporarily clears its identity; keep this user's results mounted
  // until authentication resolves, while generation remains disabled by balance gating.
  const settledOwner = useRef<string | null>(null);
  const unresolvedIdentity = account?.status === 'loading' || (account?.status === 'error' && !account.userId);
  if (!unresolvedIdentity) settledOwner.current = account?.userId ?? null;
  const owner = unresolvedIdentity ? settledOwner.current : account?.userId ?? null;
  const draftIdentity = JSON.stringify([owner, draftScope]);
  const ownerRef = useRef(owner); ownerRef.current = owner;
  const ownerEpoch = useRef(0);
  const [entriesOwner, setEntriesOwner] = useState(owner);
  const identityReady = !account || (account.status !== 'loading' && account.authenticated && Boolean(owner) && account.userId === owner);
  // A native file chooser can return during the focus-triggered account/catalog refresh.
  // Keep uploads available for the established owner; submission still waits for fresh eligibility.
  const uploadLocked = submitting || uncertain || Boolean(account && !owner);
  const historyRun = useRef(0);
  const historyInFlight = useRef<number | null>(null);
  const historyHead = useRef<string | null>(null);
  const historyRetry = useRef<{ before?: string; refresh: boolean }>({ refresh: false });
  const addEntry = (entry: VideoSubmission) => setEntries(current => {
    const match = current.find(item => item.key === entry.key) ?? current.find(item => entry.taskRef && item.taskRef === entry.taskRef) ?? current.find(item => entry.replacesTaskId && item.taskRef === entry.replacesTaskId);
    return [{ ...entry, key: match?.key ?? entry.key }, ...current.filter(item => item.key !== entry.key && (!entry.taskRef || item.taskRef !== entry.taskRef) && (!entry.replacesTaskId || item.taskRef !== entry.replacesTaskId))];
  });
  useEffect(() => {
    ownerEpoch.current++;
    sending.current = false; uploadingRef.current = false; setSubmitting(false); setUploading(false);
    // Keep text typed before the first identity resolves; clear it on account changes.
    setPrompt(current => entriesOwner === null ? current : ''); mentions.reset(); setFiles([]); setUploadError(''); setMode('auto');
    setSelection(models[0] ? compatible(null, models[0]) : null);
    setEntriesOwner(owner); setHistoryReady(false); setHistoryCursor(null); setHistoryError(''); historyHead.current = null;
    const recovery = readVideoSubmission(owner, 'IMAGE');
    const saved = entryOnly && recovery?.taskRef ? null : recovery;
    const draft = readComposerDraft(owner, draftScope, 'IMAGE');
    setDraftOwner(draftIdentity);
    setEntries(saved ? [saved.replacesTaskId && !saved.taskRef ? { ...saved, key: saved.replacesTaskId, taskRef: saved.replacesTaskId, replacesTaskId: undefined } : saved] : []);
    pendingEntry.current = saved?.taskRef ? null : saved;
    request.current = saved?.taskRef ? null : saved?.request ?? null;
    setUncertain(Boolean(saved && !saved.taskRef));
    recovered.current = null;
    lastStarted.current = saved?.snapshot.createdAt ? Date.parse(saved.snapshot.createdAt) : 0;
    lastDraft.current = saved?.draft ? JSON.stringify({ prompt: saved.draft.prompt, selection: saved.request?.settings, materials: saved.draft.materials, mode: saved.draft.mode }) : '';
    let cancelPreviews: (() => void) | undefined;
    if (!entryOnly && saved?.request && !saved.taskRef && !saved.replacesTaskId) {
      setPrompt(saved.draft?.prompt ?? saved.request.prompt); setSelection(saved.request.settings);
      mentions.restore(saved.draft?.bindings ?? []);
      cancelPreviews = restoreMaterialPreviews((saved.draft?.materials ?? []).filter((file): file is Reference => file !== null), setFiles);
      setMode((saved.draft?.mode as typeof mode) ?? 'auto');
    }
    else if (draft) {
      setPrompt(current => entriesOwner === null && current ? current : draft.prompt); setSelection(draft.selection); setMode(draft.mode as typeof mode);
      mentions.restore(draft.bindings);
      cancelPreviews = restoreMaterialPreviews(draft.materials.filter((file): file is Reference => file !== null), setFiles);
    }
    if (entry.current && !saved?.request) {
      if (entryOwner.current === undefined && owner) entryOwner.current = owner;
      if (entryOwner.current === undefined || entryOwner.current === owner) {
        setPrompt(entry.current.prompt);
        const entryMode = entry.current.mode;
        if (entryMode === 'auto' || entryMode === 'text' || entryMode === 'reference') setMode(entryMode);
        if (entry.current.materials && entry.current.ownerId === owner) {
          cancelPreviews?.(); mentions.reset();
          cancelPreviews = restoreMaterialPreviews(entry.current.materials.filter((file): file is Reference => file !== null && (!file.kind || file.kind === 'IMAGE')), setFiles);
        }
        const settings = entry.current.settings;
        if (settings?.type === 'IMAGE') {
          const target = models.find(model => model.model === settings.model) ?? models.find(model => homeModelMatches(model.displayName, entry.current?.modelName));
          setSelection(target ? compatible(settings, target) : settings);
        }
      }
    }
    return () => { cancelPreviews?.(); historyRun.current++; };
  }, [owner, draftScope, entryOnly]);
  useEffect(() => {
    if (!owner || historyOnly || draftOwner !== draftIdentity) return;
    const saved = saveComposerDraft(owner, draftScope, 'IMAGE', { prompt, selection, mode, materials: files, bindings: mentions.bindings });
    setSubmissionError(current => saved ? current === draftSaveError ? '' : current : draftSaveError);
  }, [owner, identityReady, historyOnly, draftOwner, draftIdentity, draftScope, prompt, selection, mode, files, mentions.bindings]);
  const loadHistory = async (before?: string, refresh = false) => {
    if (historyInFlight.current === historyRun.current) return;
    const run = ++historyRun.current;
    historyInFlight.current = run;
    historyRetry.current = { before, refresh };
    setLoadingHistory(true); setHistoryError('');
    try {
      const isCurrent = () => alive.current && historyRun.current === run;
      const page = galleryEnabled ? await readMediaHistory({ before, mediaType: 'IMAGE',
        known: refresh && historyHead.current ? new Set([historyHead.current]) : undefined, isCurrent }) : null;
      const legacy = galleryEnabled ? null : (await mediaListGetApiV1MediaTasks({ limit: 20, excludeCanvas: true, ...(before ? { beforeId: before } : {}) })).data;
      if (!alive.current || historyRun.current !== run) return;
      if (!page && !legacy) return;
      const history = page?.entries ?? legacy!.filter(item => item.type === 'IMAGE' && item.id).map(item => ({ key: item.id!, taskRef: item.id!, snapshot: item }));
      setEntries(current => [...current.map(item => {
        const updated = history.find(next => next.taskRef === item.taskRef);
        return updated ? { ...item, snapshot: updated.snapshot } : item;
      }), ...history.filter(item => !current.some(existing => existing.taskRef === item.taskRef))]
        .sort((a, b) => (b.snapshot.createdAt || '').localeCompare(a.snapshot.createdAt || '')));
      setHistoryReady(true);
      if (!refresh || !historyReady || !historyHead.current) setHistoryCursor(page ? page.cursor : legacy!.length === 20 ? legacy!.at(-1)?.id || null : null);
      if (!before && page) historyHead.current = page.head;
    } catch { if (alive.current && historyRun.current === run) setHistoryError('历史任务暂时无法读取，请重试；已提交任务不会重新提交。'); }
    finally {
      if (historyInFlight.current === run) historyInFlight.current = null;
      if (alive.current && historyRun.current === run) setLoadingHistory(false);
    }
  };
  useEffect(() => {
    if (active && !entryOnly && (!account || account.authenticated)) void loadHistory();
    return () => { historyRun.current++; };
  }, [active, entryOnly, owner, account?.authenticated]);

  const upload = async (file: File, index: number) => {
    if (uploadLocked || uploadingRef.current || !model || (index >= files.length && files.length >= model.maxReferenceImages)) return false;
    if (!['image/png', 'image/jpeg'].includes(file.type) || file.size === 0 || file.size > 20 * 1024 * 1024) {
      setUploadError('参考图片仅支持 20MB 以内的 PNG 或 JPEG'); return false;
    }
    const epoch = ownerEpoch.current;
    const currentOwner = owner;
    const isCurrent = () => alive.current && ownerRef.current === currentOwner && ownerEpoch.current === epoch;
    uploadingRef.current = true; setUploading(true); setUploadError('');
    try {
      const uploaded = (await storageUploadReferenceImagePostApiV1FilesReferenceImages({ file })).data;
      if (!uploaded.fileId || !mediaReference(uploaded.fileId).fileId) throw new Error('上传未返回有效文件 ID');
      let preview = '';
      try { preview = await resolvePreview(uploaded.fileId); } catch { /* Keep the real file ID if preview signing is unavailable. */ }
      const material: ComposerMaterial = { fileId: uploaded.fileId, name: file.name, preview, reviewPending: true, reviewStatus: 'SUBMITTING' };
      if (isCurrent()) setFiles(current => { const next = [...current]; next[index] = material; return next; });
      return isCurrent() ? material : false;
    } catch (cause) { if (isCurrent()) setUploadError(uploadErrorMessage(cause)); return false; }
    finally { if (isCurrent()) { uploadingRef.current = false; setUploading(false); } }
  };
  const canSubmit = !apiNotice && composing && catalog === 'ready' && parametersValid && !uploading && !queueBusy && !locked && Boolean(currentQuote?.points) && balance.affordable && Boolean(prompt.trim());
  const editTask = async (entry: VideoSubmission) => {
    if (!identityReady || locked || uploadingRef.current || sending.current) return;
    const epoch = ownerEpoch.current;
    uploadingRef.current = true; setUploading(true); setSubmissionError('');
    try {
      const draft = await creationTaskDraft(entry);
      if (!alive.current || ownerEpoch.current !== epoch) return;
      setPrompt(draft.prompt); mentions.restore(draft.bindings); setSelection(draft.settings);
      setFiles(draft.materials);
      setMode(draft.mode as typeof mode); setQuote(null); setUploadError('');
      if (historyOnly) {
        if (!saveComposerDraft(owner, 'create', 'IMAGE', { ...draft, selection: draft.settings, mode: draft.mode as typeof mode })) throw new Error(draftSaveError);
        navigate('/create/image');
      }
      setEditRequest(value => value + 1);
    } catch { if (alive.current && ownerEpoch.current === epoch) setSubmissionError('任务内容暂不可用，请重试'); }
    finally { if (alive.current && ownerEpoch.current === epoch) { uploadingRef.current = false; setUploading(false); } }
  };
  const deleteTask = async (entry: VideoSubmission) => {
    if (!entry.taskRef || !identityReady || locked || uploadingRef.current || sending.current) return;
    const epoch = ownerEpoch.current, requestOwner = owner, taskId = entry.taskRef;
    sending.current = true; setSubmitting(true); setHistoryError('');
    try {
      await mediaDeleteHistoryDeleteApiV1MediaTasksTaskId(taskId);
      if (readVideoSubmission(requestOwner, 'IMAGE')?.taskRef === taskId) saveVideoSubmission(requestOwner, null, 'IMAGE');
      if (!alive.current || ownerEpoch.current !== epoch) return;
      historyRun.current++; setLoadingHistory(false);
      setEntries(current => current.filter(item => item.taskRef !== taskId));
    } catch { if (alive.current && ownerEpoch.current === epoch) setHistoryError('删除失败，请重试'); }
    finally { if (alive.current && ownerEpoch.current === epoch) { sending.current = false; setSubmitting(false); } }
  };
  const submit = async (event?: FormEvent, restoring = false, replacement?: VideoSubmission) => {
    event?.preventDefault();
    if (queueBusy || apiNoticeRef.current) return;
    if ((!composing && !replacement && !pendingEntry.current?.replacesTaskId) || !identityReady || sending.current || uploadingRef.current
      || (!restoring && !replacement && !uncertain && !canSubmit) || (!replacement && !request.current && !selection)) return;
    if (replacement) {
      if (uncertain) return;
      const epoch = ownerEpoch.current;
      sending.current = true; setSubmitting(true); setSubmissionError('');
      try {
        const pending = regenerationEntry(replacement);
        const { data } = await mediaQuotePostApiV1MediaQuotes({ ...pending.request!.settings,
          ...(pending.request!.referenceMode ? { referenceMode: pending.request!.referenceMode } : {}), references: replacement.snapshot.references ?? [] });
        if (!alive.current || ownerEpoch.current !== epoch || apiNoticeRef.current) return;
        const available = generationBalance(account, data.points ?? undefined);
        if (!/^\d+\.\d{4}$/.test(data.points ?? '') || !available.affordable) { setSubmissionError(available.notice ?? '报价失败，请重试'); return; }
        request.current = pending.request!; pendingEntry.current = pending;
      } catch { if (alive.current && ownerEpoch.current === epoch) setSubmissionError('任务内容或报价暂不可用，请重试'); return; }
      finally { if (alive.current && ownerEpoch.current === epoch) { sending.current = false; setSubmitting(false); } }
    }
    const draftSignature = JSON.stringify({ prompt, selection, materials: files, mode });
    if (!request.current && draftSignature === lastDraft.current && Date.now() - lastStarted.current < 600) return;
    lastDraft.current = draftSignature;
    const firstAttempt = !uncertain || pendingEntry.current?.state === 'PREPARED';
    lastStarted.current = Date.now();
    sending.current = true; setSubmitting(true);
    if (!request.current) {
      request.current = { requestRef: randomUUID(), settings: { ...selection! }, prompt: mentions.compiled.trim(), inputAssetRefs: files.map(file => file.fileId) };
      pendingEntry.current = { key: request.current.requestRef, request: request.current, state: 'SUBMITTING', draft: { prompt, materials: files, mode, bindings: mentions.bindings }, snapshot: {
        ...selection, prompt: request.current.prompt, modelName: model?.displayName || selection!.model,
        referenceFollowsFirstFrame: false, references: references.map(ref => ({ fileId: ref.fileId ?? null, httpsUrl: ref.httpsUrl ?? null, kind: 'IMAGE', durationSeconds: null })),
        createdAt: new Date().toISOString(),
      } };
    }
    const requestOwner = owner;
    const epoch = ownerEpoch.current;
    const isCurrent = () => alive.current && ownerRef.current === requestOwner && ownerEpoch.current === epoch;
    const entry: VideoSubmission = { ...pendingEntry.current!, state: entryOnly && firstAttempt ? 'PREPARED' : uncertain ? 'UNCERTAIN' : 'SUBMITTING' };
    if (!saveVideoSubmission(owner, entry, 'IMAGE')) {
      if (firstAttempt) { request.current = null; pendingEntry.current = null; lastStarted.current = 0; setUncertain(false); }
      setSubmissionError('无法保存请求恢复信息，请允许当前站点存储后重试。');
      sending.current = false; setSubmitting(false); return;
    }
    setSubmissionError(''); recovered.current = entry.key;
    if (entryOnly) { onHandoff?.(); return; }
    if (!entry.replacesTaskId) { addEntry(entry); if (!galleryEnabled) setSubmissionKey(entry.key); }
    try {
      const result = await submitCreation(owner, entry, firstAttempt);
      const accepted = { ...entry, taskRef: result.taskRef, state: undefined };
      saveVideoSubmission(owner, accepted, 'IMAGE');
      if (isCurrent()) {
        historyRun.current++; setLoadingHistory(false); addEntry(accepted); setUncertain(false); request.current = null; pendingEntry.current = null;
        if (galleryEnabled) setSubmissionKey(`${accepted.key}:${accepted.taskRef}`);
        setQuote(null); setRefresh(value => value + 1);
      }
    } catch (cause) {
      if (!isCurrent()) return;
      if (cause instanceof CreationRejection) {
        lastStarted.current = 0; request.current = null; pendingEntry.current = null; saveVideoSubmission(owner, null, 'IMAGE');
        if (isCurrent() && !entry.replacesTaskId) addEntry({ ...entry, state: 'REJECTED', message: cause.status === 401 ? '登录已失效，请重新登录后提交' : cause.status === 403 ? '当前账户没有生成权限，请联系管理员' : '提交参数或素材未通过校验，请刷新模型与报价后重试' });
        if (isCurrent()) { if (entry.replacesTaskId) setSubmissionError('提交参数或素材未通过校验，请刷新模型与报价后重试'); setUncertain(false); setQuote(null); setRefresh(value => value + 1);  }
        return;
      }
      // A disconnected request may already be charged. Freeze and replay the same
      // idempotency key and payload instead of creating a second paid task.
      const pending = { ...entry, state: 'UNCERTAIN' as const };
      saveVideoSubmission(owner, pending, 'IMAGE'); pendingEntry.current = pending;
      if (isCurrent()) { if (!entry.replacesTaskId) addEntry(pending); setUncertain(true); }
    } finally { if (isCurrent()) { sending.current = false; setSubmitting(false); } }
  };

  useEffect(() => {
    if (entryOnly || (historyOnly && !pendingEntry.current?.replacesTaskId) || !active || !identityReady || !uncertain || !request.current
      || recovered.current === request.current.requestRef) return;
    recovered.current = request.current.requestRef;
    void submit(undefined, true);
  }, [entryOnly, historyOnly, active, identityReady, uncertain, owner]);

  return <CreationWorkspace hasResults={entriesOwner === owner && entries.some(entry => Boolean(entry.taskRef))} editRequest={editRequest} enabled={!entryOnly && !historyOnly && className === 'floating-composer'} ready={historyReady} submission={submissionKey} prompt={prompt} busy={uploading || submitting || uncertain || Boolean(layer) || Boolean(submissionError)} results={!entryOnly && <>
    <section className="video-results" aria-label={t(historyOnly ? '图片历史' : '图片生成结果')}>
      {historyOnly && <h1>{t("图片历史")}</h1>}
      {historyOnly && submissionError && <p role="alert">{t(submissionError)}</p>}
      {!galleryEnabled && historyOnly && loadingHistory && <p role="status">{t("正在读取图片历史…")}</p>}
      {!galleryEnabled && historyOnly && !loadingHistory && !historyError && !historyCursor && entriesOwner === owner && !entries.length && account?.status !== 'loading' && <p role="status">{t("暂无图片任务")}</p>}
      {historyOnly && uncertain && <p>{t("有待确认的提交，")}<Link to="/create/image">{t("返回创作使用原请求确认")}</Link>。</p>}
      {galleryEnabled ? <MediaResultsGallery mediaType="IMAGE" key={owner ?? 'anonymous'} ready={historyReady && entriesOwner === owner} loading={loadingHistory}
        error={historyError} errorAtTop={Boolean(historyRetry.current.before)} hasMore={Boolean(historyCursor)} empty={!entries.length} submission={submissionKey}
        onLatest={() => void loadHistory(undefined, true)} onOlder={() => { if (historyCursor) void loadHistory(historyCursor); }}
        onRetry={() => void loadHistory(historyRetry.current.before, historyRetry.current.refresh)}>
        {compact => (entriesOwner === owner ? [...entries].sort((a, b) => (a.snapshot.createdAt || '').localeCompare(b.snapshot.createdAt || '') || a.key.localeCompare(b.key)) : []).map(entry => <MediaTaskCard key={entry.key} entry={entry} compact={compact}
          actionsDisabled={!identityReady || locked || uploading} onEdit={entry => void editTask(entry)} onRegenerate={apiNotice ? undefined : entry => void submit(undefined, false, entry)} onDelete={entry => void deleteTask(entry)} />)}
      </MediaResultsGallery> : <>
      {!historyOnly && historyCursor && <button type="button" disabled={loadingHistory} onClick={() => void loadHistory(historyCursor)}>{t("加载更早任务")}</button>}
      {(entriesOwner === owner ? [...entries].sort((a, b) => (historyOnly ? -1 : 1) * ((a.snapshot.createdAt || '').localeCompare(b.snapshot.createdAt || '') || a.key.localeCompare(b.key))) : []).map(entry => <MediaTaskCard key={entry.key} entry={entry} actionsDisabled={!identityReady || locked || uploading} onEdit={entry => void editTask(entry)} onRegenerate={apiNotice ? undefined : entry => void submit(undefined, false, entry)} onDelete={entry => void deleteTask(entry)} />)}
      {historyOnly && historyCursor && <button type="button" disabled={loadingHistory} onClick={() => void loadHistory(historyCursor)}>{t("加载更早任务")}</button>}
      {historyError && <p role="status">{t(historyError)}<button type="button" onClick={() => void loadHistory()}>{t("重新读取任务")}</button></p>}
      </>}
    </section></>}>
    {!historyOnly && <ExpandableComposer resizable={!entryOnly && className === 'floating-composer'} className={`${className} video-composer image-composer`} data-testid={testId} aria-label={t("图片生成")}>
    <form onSubmit={event => void submit(event)}>
      <fieldset disabled={submitting || uncertain} className="video-inputs">
        <ComposerMaterials key={owner} onQueueBusyChange={setQueueBusy} review={{ owner, model: reviewModel, onSelecting: setUploading, onReview: (fileId, reviewId, reviewStatus) => setFiles(current => current.map(file => file.fileId === fileId ? { ...file, reviewId, reviewStatus, reviewPending: !reviewId } : file)) }} slots={files.map((file, index) => ({ file, label: `参考图 ${index + 1}`, issue: fileIssues[index] }))}
          selectionKey={`${model?.model}:${mode}`}
          capability={{ acceptedKinds: ['image'], acceptedMimeTypes: ['image/png', 'image/jpeg'], acceptedSources: ['upload', 'library'], maxFileBytes: 20 * 1024 * 1024, maxItems: model?.maxReferenceImages ?? 0, verified: Boolean(model) }}
          nextIndex={model && files.length < model.maxReferenceImages ? files.length : -1} disabled={uploadLocked || !model} busy={uploading}
          hint={model?.maxReferenceImages === 0 ? '当前模型不支持素材' : mode === 'reference' && !files.length ? '请添加参考图片' : undefined} error={uploadError}
          onUpload={upload} onReuse={(material, index) => {
            if (uploadLocked || uploadingRef.current || !model || index >= model.maxReferenceImages || material.kind !== 'IMAGE') return false;
            setFiles(current => { const next = [...current]; next[index] = material; return next; }); return true;
          }} onRemove={index => setFiles(current => current.filter((_, i) => i !== index))}>
          <MaterialPrompt label={t("图片提示词")} value={prompt} onChange={setPrompt} mentions={mentions} disabled={submitting || uncertain} placeholder={t("描述你想要生成的图片画面")} />
        </ComposerMaterials>
      </fieldset>
      <div className="composer-toolbar" role="group" aria-label={t("图片创作工具栏")}>
        <ComposerModeMenu<typeof mode> label={t("图片生成模式")} icon="image" value={mode} disabled={uploading || locked} onChange={setMode}
          options={[{ value: 'auto', label: '智能图片' }, { value: 'text', label: '文生图片' },
            ...(Boolean(model?.maxReferenceImages) || mode === 'reference' ? [{ value: 'reference' as const, label: '图片参考' }] : [])]} />
        <button type="button" className="composer-model" aria-label={formatMessage("选择图片模型：{0}", (modelGroup?.displayName ?? t(catalog === 'loading' ? '加载模型…' : catalog === 'failed' ? '模型加载失败' : '暂无可用模型')))} aria-haspopup="dialog" disabled={uploading || locked || catalog !== 'ready' || !models.length} onClick={() => setLayer('models')}><ComposerIcon name="model" /><span>{(modelGroup?.displayName ?? t(catalog === 'loading' ? '加载模型…' : catalog === 'failed' ? '模型加载失败' : '暂无可用模型'))}</span></button>
        <button type="button" className="composer-summary" aria-label={t(selection ? formatMessage("图片参数摘要：{0}，{1}，{2}张", selection.aspectRatio, selection.resolution, selection.count) : '图片参数摘要')} aria-haspopup="dialog" disabled={uploading || locked || !model} onClick={() => setLayer('settings')}><ComposerIcon name="ratio" />{selection ? <><span>{selection.aspectRatio}</span><span>{selection.resolution}</span><span>{formatMessage("{0}张", selection.count)}</span></> : t('图片参数')}</button>
        {loginRequired ? <Link className="create-button" to="/user/login">{t('登录后开始创作')}</Link> : <button type="submit" className="create-button" onClick={event => { if (event.detail > 1) event.preventDefault(); }} disabled={Boolean(apiNotice) || !identityReady || submitting || (uncertain ? false : !canSubmit)}>{t(submitting ? '生成中' : uncertain ? (entryOnly ? '继续到生成页' : '使用原请求重试') : entryStatus ?? formatMessage("开始生成 · {0}", t(!inputValid ? '请处理素材提示' : currentQuote?.error ? '报价失败' : currentQuote?.points ? balance.affordable ? formatMessage("{0} 积分", formatAmount(currentQuote.points)) : balance.notice?.includes('不足') ? '积分不足' : '账户不可用' : catalog === 'ready' && parametersValid ? '报价中…' : '等待有效设置')))}</button>}
      </div>
    </form>
    {submissionError && <p role="alert">{t(submissionError)}</p>}
    {currentQuote?.error && <p role="alert">{t(currentQuote.error)}</p>}
    {apiNotice ? <p role="status">{t(apiNotice)}</p> : active && !locked && catalog === 'ready' && currentQuote?.points && balance.notice && <p role="status">{t(balance.notice)}</p>}
    {accountFailed && <div className="commerce-entry-notice"><p role="status">{t('账户信息未能加载，请重试后继续创作。')}</p><button type="button" onClick={() => void account?.refresh()}>{t('重新读取账户')}</button></div>}
    {loginRequired && <p className="commerce-entry-notice" role="status">{t('登录后查看可用模型与积分，继续完成创作。')}</p>}
    {!loginRequired && !accountFailed && catalog === 'ready' && !models.length && <p role="status">{t(commerceEntry ? '暂时没有可用模型。请重新加载；仍不可用时，请联系平台支持。' : "暂无可用图片模型，请检查当前账户权限或联系管理员配置模型价格。")}</p>}
    {catalogError && <p role="alert">{t(catalogError)}</p>}
    {!loginRequired && !accountFailed && (catalog === 'failed' || currentQuote?.error || (catalog === 'ready' && !models.length)) && <button type="button" disabled={uploading || locked} onClick={() => setRefresh(value => value + 1)}>{t(commerceEntry ? '重新加载模型与报价' : '重新获取模型与报价')}</button>}
    {model && selection && (selection.count < 1 || selection.count > model.maxCount || !Number.isInteger(selection.count)) && <p role="alert">{formatMessage("生成数量应为 1–{0} 的整数。", model.maxCount)}</p>}
    {layer && <ComposerDialog anchored title={t(layer === 'settings' ? '图片设置' : '选择图片模型')} closeLabel={t(layer === 'settings' ? '关闭图片设置' : '关闭模型选择')} onClose={() => setLayer(null)} onReset={layer === 'settings' && model ? () => setSelection(compatible(null, model)) : undefined}>
      {layer === 'models' ? <div className="video-model-list">{modelGroups.map(group => {
        const selected = group.key === modelGroup?.key;
        const next = group.variants.find(item => item.model === selection?.model)
          ?? group.variants.find(item => selection && item.resolutions.includes(selection.resolution)) ?? group.variants[0];
        return <button type="button" key={group.key} aria-label={group.displayName} aria-pressed={selected} onClick={() => { setSelection(compatible(selection, next)); setLayer(null); }}><span className="composer-model-icon"><ComposerIcon name="model" /></span><span className="composer-model-copy"><span>{group.displayName}</span><small>{formatMessage("{0} · 最多 {1} 张参考图片", sortedResolutions([...new Set(group.variants.flatMap(item => item.resolutions))]).join(' / '), next.maxReferenceImages)}</small></span>{selected && <span className="composer-model-check" aria-hidden="true">✓</span>}</button>;
      })}</div>
        : model && selection && <ComposerParameterFields model={model} selection={selection} onChange={setSelection} resolutions={resolutions} onResolution={resolution => {
          const next = model.resolutions.includes(resolution) ? model : modelGroup?.variants.find(item => item.resolutions.includes(resolution));
          if (next) setSelection(compatible({ ...selection, resolution }, next));
        }} />}
    </ComposerDialog>}
  </ExpandableComposer>}</CreationWorkspace>;
}
