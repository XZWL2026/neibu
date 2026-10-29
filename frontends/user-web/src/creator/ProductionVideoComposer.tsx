import { randomUUID } from '../../../shared/randomUUID';
import { ComposerParameterFields } from './ComposerParameterFields';
import { homeCreationEntry, homeModelMatches } from '../home/home-entry';
import { readComposerDraft, saveComposerDraft, draftSaveError, type ComposerDraftScope } from './composer-draft';
import { t, formatMessage, useLanguage } from '../i18n';
import { breakDownVideo } from '../commerce/video-breakdown';
import { CommerceVideoBrief } from '../commerce/CommerceVideoBrief';
import { commerceVideoTools, emptyVideoBrief, readCommerceVideoDraft, saveCommerceVideoDraft, type CommerceVideoTool } from '../commerce/commerce-video';
import { formatAmount } from '../../../shared/formatAmount';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import { creationTaskDraft, regenerationEntry, restoreMaterialPreviews } from './creation-task-draft';
import { CreationWorkspace } from './CreationWorkspace';
import { VideoResultsGallery } from './VideoResultsGallery';
import { compareMediaHistory, readVideoHistory } from './video-history';
import { ExpandableComposer } from './ExpandableComposer';
import { submitCreation, CreationRejection } from './creation-submission';
import {
  mediaTemplatesGetApiV1MediaProductSuitesTemplates,
  ApiError, mediaDeleteHistoryDeleteApiV1MediaTasksTaskId, mediaListGetApiV1MediaTasks, accessListGetApiV1AccessKeys, mediaOptionsGetApiV1MediaGenerationOptions, mediaQuotePostApiV1MediaQuotes,
  storageUploadReferenceImagePostApiV1FilesReferenceImages, storageUploadReferenceMediaPostApiV1FilesReferenceMedia,
  storageUploadProcessingVideoPostApiV1FilesProcessingVideos, storageReferenceClipPostApiV1FilesFileIdReferenceClip,
  type ResponseMediaApplicationCatalogGenerationCatalogServiceOption as ModelOption,
} from '../api';
import { mediaReference, resolvePreview } from '../canvas/production-api';
import { VIDEO_GENERATION_DEFAULTS } from '../canvas/generation-defaults';
import type { CanvasGenerationSettings } from '../canvas/model';
import type { CanvasMediaTaskRequest } from '../canvas/media-task-port';
import { VideoTaskCard, type VideoSubmission } from './VideoTaskResults';
import { readVideoSubmission, saveVideoSubmission } from './video-submission-recovery';
import './video-settings.css';
import { ComposerMaterials, materialReviewIssue, typedMaterialLabel, type ComposerMaterial } from './ComposerMaterials';
import { MaterialPrompt, useMaterialMentions } from './MaterialPrompt';
import { uploadErrorMessage } from './upload-error';
import { ComposerDialog, ComposerIcon, ComposerModeMenu, sortedResolutions } from './ComposerControls';
import { apiGenerationNotice, useOptionalUserSession } from '../userSession';
import { generationBalance } from './generation-balance';
import { readReferenceAudioDuration } from './reference-audio-duration';

type VideoOption = ModelOption & { type: 'VIDEO'; model: string; displayName: string; aspectRatios: string[]; resolutions: string[] };
type Frame = ComposerMaterial;
const modelOrder = new Map([[VIDEO_GENERATION_DEFAULTS.model, 0], ['seedance-2-5', 0], ['seedance-2.0', 1], ['seedance-2.0-fast', 2], ['seedance-2.0-mini', 3]]);
const usable = (option: ModelOption): option is VideoOption => option.type === 'VIDEO' && Boolean(option.model && option.displayName)
  && Boolean(option.aspectRatios?.length && option.resolutions?.length)
  && Number.isInteger(option.minDurationSeconds) && option.minDurationSeconds > 0
  && Number.isInteger(option.maxDurationSeconds) && option.maxDurationSeconds >= option.minDurationSeconds
  && Number.isInteger(option.durationStepSeconds) && option.durationStepSeconds > 0;
const validDuration = (value: number, model: VideoOption) => Number.isInteger(value)
  && value >= model.minDurationSeconds && value <= model.maxDurationSeconds
  && (value - model.minDurationSeconds) % model.durationStepSeconds === 0;
function defaults(model: VideoOption): CanvasGenerationSettings {
  const base = VIDEO_GENERATION_DEFAULTS;
  const duration = Math.max(model.minDurationSeconds, Math.min(base.durationSeconds, model.maxDurationSeconds));
  return { ...base, model: model.model,
    aspectRatio: model.aspectRatios.includes(base.aspectRatio) ? base.aspectRatio : model.aspectRatios[0],
    resolution: model.resolutions.includes(base.resolution) ? base.resolution : model.resolutions[0],
    durationSeconds: model.minDurationSeconds + Math.floor((duration - model.minDurationSeconds) / model.durationStepSeconds) * model.durationStepSeconds };
}
function compatible(current: CanvasGenerationSettings | null, model: VideoOption): CanvasGenerationSettings {
  const base = defaults(model);
  return !current ? base : { ...base,
    aspectRatio: model.aspectRatios.includes(current.aspectRatio) ? current.aspectRatio : base.aspectRatio,
    resolution: model.resolutions.includes(current.resolution) ? current.resolution : base.resolution,
    durationSeconds: validDuration(current.durationSeconds, model) ? current.durationSeconds : base.durationSeconds };
}

function quoteError(cause: unknown): string {
  const reasons: Record<string, string> = {
    REFERENCE_METADATA_REQUIRED: '素材缺少可信媒体信息，请移除并重新上传',
    REFERENCE_TYPE_MISMATCH: '素材实际类型不匹配，请移除并重新上传',
    INVALID_REFERENCE_TYPE: '素材实际类型不匹配，请移除并重新上传',
    UNSUPPORTED_OMNI_REFERENCE: '全能参考不符合当前模型要求，请检查素材类型、数量和时长，或切换模型',
    REFERENCE_REJECTED: '素材不符合当前模型要求，请检查类型、数量和时长后重试',
  };
  if (cause instanceof ApiError && cause.status === 422 && cause.body && typeof cause.body === 'object'
    && 'code' in cause.body && typeof cause.body.code === 'string' && Object.hasOwn(reasons, cause.body.code)) return reasons[cause.body.code];
  return '报价失败，请重试';
}

export function ProductionVideoComposer({ className = 'floating-composer', testId = 'creator-composer', active = true, historyOnly = false, entryOnly = false, onHandoff, commerceTool, draftScope = 'create' }: {
  className?: string; testId?: string; active?: boolean; historyOnly?: boolean; entryOnly?: boolean; onHandoff?: () => void; commerceTool?: CommerceVideoTool; draftScope?: ComposerDraftScope;
}) {
  useLanguage();
  const navigate = useNavigate();
  const location = useLocation();
  const galleryEnabled = !entryOnly && !commerceTool && location.pathname === '/create/video';
  const entry = useRef(homeCreationEntry(location.state));
  const entryOwner = useRef<string | undefined>(undefined);
  const preferredModel = useRef(entry.current?.modelName);
  const [brief, setBrief] = useState(emptyVideoBrief);
  const [draftOwner, setDraftOwner] = useState<string | null>(null);
  const [draftError, setDraftError] = useState('');
  const [analyzing, setAnalyzing] = useState(false);
  const analyzingRef = useRef(false);
  const [breakdownAvailable, setBreakdownAvailable] = useState(false);
  const commerce = commerceTool ? commerceVideoTools[commerceTool] : null;
  const [editRequest, setEditRequest] = useState(0);
  const [historyReady, setHistoryReady] = useState(false);
  const [submissionKey, setSubmissionKey] = useState('');
  const [submissionError, setSubmissionError] = useState('');
  const recovered = useRef<string | null>(null);
  const lastStarted = useRef(0);
  const lastDraft = useRef('');
  const composing = active && !historyOnly;
  const [models, setModels] = useState<VideoOption[]>([]);
  const [selection, setSelection] = useState<CanvasGenerationSettings | null>(null);
  const [fallbackReviewModel, setReviewModel] = useState('');
  const [catalog, setCatalog] = useState<'loading' | 'ready' | 'failed'>('loading');
  const [catalogError, setCatalogError] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [prompt, setPrompt] = useState('');
  const [chosenMode, setMode] = useState<'omni' | 'text' | 'frames' | null>(null);
  const [frames, setFrames] = useState<(Frame | null)[]>([null, null]);
  const [uploading, setUploading] = useState(false);
  const [queueBusy, setQueueBusy] = useState(false);
  const [clipping, setClipping] = useState(false);
  const [clipError, setClipError] = useState('');
  const [error, setError] = useState('');
  const [uploadError, setUploadError] = useState('');
  const [layer, setLayer] = useState<'models' | 'settings' | null>(null);
  const [quote, setQuote] = useState<{ key: string; points?: string; error?: string } | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [entries, setEntries] = useState<VideoSubmission[]>([]);
  const [historyError, setHistoryError] = useState('');
  const [historyActionNotice, setHistoryActionNotice] = useState<{ taskId: string; message: string } | null>(null);
  const [historyCursor, setHistoryCursor] = useState<string | null>(null);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const pendingEntry = useRef<VideoSubmission | null>(null);
  const alive = useRef(true);
  const sending = useRef(false);
  const uploadingRef = useRef(false);
  const request = useRef<CanvasMediaTaskRequest | null>(null);
  const model = models.find(item => item.model === selection?.model);
  const mode = chosenMode ?? (commerceTool === 'video-replica' || model?.omniReference ? 'omni' : commerceTool ? 'frames' : 'text');
  const omni = model?.omniReference;
  const reviewModel = omni ? model!.model : fallbackReviewModel;
  const latestMaterials = useRef({ frames, model, mode });
  latestMaterials.current = { frames, model, mode };
  const modelLabel = model?.displayName ?? (catalog === 'loading' ? '加载模型…' : catalog === 'failed' ? '模型加载失败' : '暂无可用模型');
  const account = useOptionalUserSession();
  const commerceEntry = entryOnly && draftScope === 'commerce';
  const loginRequired = commerceEntry && account?.status === 'anonymous';
  const accountFailed = commerceEntry && account?.status === 'error';
  const entryStatus = commerceEntry ? accountFailed ? '账户读取失败' : account?.status === 'loading' ? '正在读取账户' : catalog === 'loading' ? '正在加载模型…' : catalog === 'failed' ? '模型加载失败' : !models.length ? '暂无可用模型' : null : null;
  const apiNotice = apiGenerationNotice(account, 'VIDEO');
  const apiNoticeRef = useRef(apiNotice);
  apiNoticeRef.current = apiNotice;
  const locked = analyzing || submitting || uncertain || account?.status === 'loading';

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
      const available = (response.data.models ?? []).filter(usable)
        .filter(option => !commerceTool || (commerceTool === 'video-replica'
          ? (option.omniReference?.maxVideos ?? 0) > 0 && (option.omniReference?.maxImages ?? 0) > 0 && (option.omniReference?.maxTotal ?? 0) >= 2
          : option.maxReferenceImages > 0 || (option.omniReference?.maxImages ?? 0) > 0))
        .sort((a, b) => (modelOrder.get(a.model) ?? modelOrder.size) - (modelOrder.get(b.model) ?? modelOrder.size));
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
        : status === 403 ? '当前账户无权读取视频模型，请检查账户权限。'
        : status === 422 ? '当前账户暂不满足视频生成条件，请检查账户开通、密钥状态和可用价格。'
        : status && status >= 500 ? '模型服务暂不可用，请稍后重试。'
        : '模型请求未完成，请检查网络连接后重试。');
      if (status === 422) {
        try {
          const { data: keys } = await accessListGetApiV1AccessKeys();
          if (cancelled) return;
          if (!keys.length) setCatalogError('当前账户尚未开通生成服务，请先完成账户开通与首充。');
          else if (keys.every(key => !key.enabled)) setCatalogError('当前账户密钥已禁用，请联系管理员处理。');
        } catch { /* Keep the original refusal if account details cannot be read. */ }
      }
    });
    return () => { cancelled = true; };
  }, [composing, refresh, locked, commerceTool]);

  const frameMode = mode === 'frames';
  const assets = frames.filter((frame): frame is Frame => Boolean(frame));
  const countKind = (kind: string) => assets.filter(file => (file.kind ?? 'IMAGE') === kind).length;
  const durationKind = (kind: string) => assets.filter(file => file.kind === kind).reduce((sum, file) => sum + (file.durationSeconds ?? 0), 0);
  // Each submitted video gets a share of the model's total reference budget.
  const replicaClipLimit = commerceTool === 'video-replica' && omni ? Math.floor(omni.maxVideoDurationSeconds / Math.max(1, countKind('VIDEO'))) : 0;
  const videosToClip = replicaClipLimit >= 2 ? assets.filter(file => file.kind === 'VIDEO' && (!file.sourceFileId || file.sourceFileId === file.fileId || (file.durationSeconds ?? 0) > replicaClipLimit || !Number.isFinite(file.durationSeconds))) : [];
  const needsReplicaClip = videosToClip.length > 0;
  const frameIssues = frames.map((frame, index) => {
    if (!frame) return '';
    const kind = frame.kind ?? 'IMAGE';
    if (frame.portraitId && mode !== 'omni') return '已审核人像仅支持全能参考，请切换模式';
    if (mode === 'text') return '当前模式不使用素材，请切换模式或移除';
    if (mode === 'frames') return kind !== 'IMAGE' ? '首尾帧仅支持图片，请切换模式或移除'
      : index >= (model?.maxReferenceImages ?? 0) ? `当前模型不支持${index === 0 ? '首帧' : index === 1 ? '尾帧' : '更多素材'}`
      : index === 1 && !frames[0] ? '请先添加首帧，尾帧不能单独提交' : materialReviewIssue(frame, reviewModel);
    if (!omni) return '当前模型不支持全能参考，请切换模型或移除素材';
    if (assets.length > omni.maxTotal) return `参考素材总数最多 ${omni.maxTotal} 份`;
    const maximum = kind === 'VIDEO' ? omni.maxVideos : kind === 'AUDIO' ? omni.maxAudios : omni.maxImages;
    if (countKind(kind) > maximum) return `当前类型最多 ${maximum} 份素材`;
    if (kind === 'VIDEO' && videosToClip.includes(frame)) return '';
    if (kind !== 'IMAGE' && (!Number.isFinite(frame.durationSeconds) || (frame.durationSeconds ?? 0) <= 0)) return '素材缺少可信时长，请重新上传';
    if (kind === 'VIDEO' && !needsReplicaClip && durationKind(kind) > omni.maxVideoDurationSeconds) return `视频总时长不能超过 ${omni.maxVideoDurationSeconds} 秒`;
    if (kind === 'AUDIO' && durationKind(kind) > omni.maxAudioDurationSeconds) return `音频总时长不能超过 ${omni.maxAudioDurationSeconds} 秒`;
    if (kind === 'VIDEO' && (frame.sizeBytes ?? 0) > omni.maxVideoBytes || kind === 'AUDIO' && (frame.sizeBytes ?? 0) > omni.maxAudioBytes) return '素材超过当前模型大小限制，请更换素材或模型';
    if (!omni.audioOnly && kind === 'AUDIO' && countKind('IMAGE') + countKind('VIDEO') === 0) return '当前模型不支持仅音频参考，请添加图片或视频';
    return materialReviewIssue(frame, reviewModel);
  });
  const mentions = useMaterialMentions(prompt, frames, frameIssues, mode === 'omni' ? 'OMNI' : 'VIDEO');
  const inputAssetRefs = assets.map(frame => frame.fileId);
  const typedReferences = assets.map(file => ({ fileId: file.fileId, kind: file.kind ?? 'IMAGE' as const, durationSeconds: file.durationSeconds ?? null, ...(file.portraitId || file.reviewStatus === 'ACTIVE' && file.reviewId ? { portraitId: file.portraitId ?? file.reviewId } : {}) }));
  const references = mode === 'omni' ? typedReferences : inputAssetRefs.map(mediaReference);
  const referenceMode = mode === 'omni' ? 'OMNI' as const : mode === 'frames' ? 'FIRST_LAST_FRAME' as const : undefined;
  const parametersValid = Boolean(model && selection && validDuration(selection.durationSeconds, model)
    && model.aspectRatios.includes(selection.aspectRatio) && model.resolutions.includes(selection.resolution));
  const commerceInputIssue = !commerceTool ? ''
    : commerceTool === 'video-replica' && mode !== 'omni' ? '视频复刻需要全能参考模式'
    : !countKind('IMAGE') ? '请添加商品图片'
    : commerceTool === 'video-replica' && !countKind('VIDEO') ? '请添加参考视频' : '';
  const inputValid = !needsReplicaClip && !clipping && !commerceInputIssue && !(mode === 'omni' && (!omni || !assets.length)) && !mentions.error && !frameIssues.some(Boolean) && (!frameMode || Boolean(frames[0]));
  // Quotes depend on settings and materials, not prompt text. Keep invalid references
  // and refreshed configuration invalidating the quote before effects or late responses run.
  const quoteKey = JSON.stringify({ selection, references, inputValid, mode, uploading, refresh, composing, catalog });
  const currentQuote = quote?.key === quoteKey ? quote : null;
  const balance = generationBalance(account, currentQuote?.points);
  useEffect(() => {
    if (!composing || catalog !== 'ready' || !selection || !parametersValid || !inputValid || uploading || locked) return;
    let cancelled = false;
    setQuote({ key: quoteKey });
    void mediaQuotePostApiV1MediaQuotes({ ...selection, ...(referenceMode ? { referenceMode } : {}), references }).then(response => {
      if (cancelled) return;
      const points = response.data.points;
      setQuote(typeof points === 'string' && /^\d+\.\d{4}$/.test(points)
        ? { key: quoteKey, points } : { key: quoteKey, error: '报价失败，请重试' });
    }, cause => { if (!cancelled) setQuote({ key: quoteKey, error: quoteError(cause) }); });
    return () => { cancelled = true; };
  }, [quoteKey, parametersValid, inputValid, uploading, locked]);

  // Account refresh temporarily clears its identity; keep this user's results mounted
  // until authentication resolves, while generation remains disabled by balance gating.
  const settledOwner = useRef<string | null>(null);
  if (account?.status !== 'loading') settledOwner.current = account?.userId ?? null;
  const owner = account?.status === 'loading' ? settledOwner.current : account?.userId ?? null;
  const draftIdentity = JSON.stringify([owner, commerceTool, draftScope]);
  const ownerRef = useRef(owner); ownerRef.current = owner;
  const ownerEpoch = useRef(0);
  const [entriesOwner, setEntriesOwner] = useState(owner);
  const identityReady = !account || (account.status !== 'loading' && account.authenticated && Boolean(owner) && account.userId === owner);
  const analysisIdentityEpoch = useRef(0);
  // Invalidate the pipeline at the first identity refresh, even if the same owner later returns.
  if (!identityReady) analysisIdentityEpoch.current++;
  // A native file chooser can return during the focus-triggered account/catalog refresh.
  // Keep uploads available for the established owner; submission still waits for fresh eligibility.
  const uploadLocked = clipping || analyzing || submitting || uncertain || Boolean(account && !owner);
  const referenceVideo = frames.find(file => file?.kind === 'VIDEO');
  useEffect(() => {
    let cancelled = false;
    setBreakdownAvailable(false);
    if (commerceTool === 'video-replica' && owner) void mediaTemplatesGetApiV1MediaProductSuitesTemplates()
      .then(({ data }) => { if (!cancelled) setBreakdownAvailable(Boolean(data.copyAvailable)); }, () => {});
    return () => { cancelled = true; };
  }, [owner, commerceTool, refresh]);
  const clipKey = JSON.stringify([owner, replicaClipLimit, frames.map(file => file && [file.fileId, file.sourceFileId, file.durationSeconds])]);
  useEffect(() => {
    if (!needsReplicaClip) { setClipError(''); return; }
    if (!identityReady || uploading || locked) return;
    let cancelled = false;
    const epoch = ownerEpoch.current;
    const isCurrent = () => !cancelled && alive.current && ownerEpoch.current === epoch;
    setClipping(true); setClipError('');
    void (async () => {
      try {
        const replacements = new Map<string, Frame>();
        for (const file of videosToClip) {
          const sourceFileId = file.sourceFileId ?? file.fileId;
          const { data } = await storageReferenceClipPostApiV1FilesFileIdReferenceClip(sourceFileId, { maxDurationSeconds: replicaClipLimit });
          if (!isCurrent()) return;
          if (!data.fileId || !Number.isFinite(data.durationSeconds) || data.durationSeconds! < 2 || data.durationSeconds! > replicaClipLimit)
            throw new Error('invalid reference clip');
          const preview = await resolvePreview(data.fileId);
          if (!isCurrent()) return;
          replacements.set(file.fileId, { ...file, sourceFileId, fileId: data.fileId, durationSeconds: data.durationSeconds,
            sizeBytes: data.sizeBytes, preview, portraitId: undefined, reviewId: undefined, reviewStatus: 'SUBMITTING', reviewPending: true });
        }
        setFrames(current => current.map(file => file && (replacements.get(file.fileId) ?? file)));
        mentions.restore(current => current.map(binding => ({ ...binding, fileId: replacements.get(binding.fileId)?.fileId ?? binding.fileId })));
      } catch { if (isCurrent()) setClipError('参考视频处理失败，请重试'); }
      finally { if (isCurrent()) setClipping(false); }
    })();
    return () => { cancelled = true; setClipping(false); };
  }, [clipKey, identityReady, uploading, locked, refresh]);
  const analyzeVideo = async () => {
    if (!referenceVideo || !breakdownAvailable || locked || uploading || clipping || analyzingRef.current || !identityReady) return;
    const epoch = ownerEpoch.current, identityEpoch = analysisIdentityEpoch.current, fileId = referenceVideo.fileId;
    const isCurrent = () => alive.current && ownerEpoch.current === epoch && analysisIdentityEpoch.current === identityEpoch
      && latestMaterials.current.frames.find(file => file?.kind === 'VIDEO')?.fileId === fileId;
    analyzingRef.current = true; setAnalyzing(true); setError('');
    try {
      const shots = await breakDownVideo(referenceVideo.sourceFileId ?? fileId, brief, isCurrent);
      if (shots && isCurrent()) setBrief(current => ({ ...current, shots }));
    } catch { if (isCurrent()) setError('参考视频拆解失败，请重试或手动编辑分镜。'); }
    finally { if (alive.current && ownerEpoch.current === epoch) { analyzingRef.current = false; setAnalyzing(false); } }
  };
  const historyRun = useRef(0);
  const historyInFlight = useRef<number | null>(null);
  const historyHead = useRef<string | null>(null);
  const historyRetry = useRef<{ before?: string; refresh: boolean }>({ refresh: false });
  const addEntry = (entry: VideoSubmission) => setEntries(current => {
    const match = current.find(item => item.key === entry.key) ?? current.find(item => entry.taskRef && item.taskRef === entry.taskRef);
    return [{ ...entry, key: match?.key ?? entry.key }, ...current.filter(item => item.key !== entry.key && (!entry.taskRef || item.taskRef !== entry.taskRef))];
  });
  useEffect(() => {
    ownerEpoch.current++;
    analyzingRef.current = false; setAnalyzing(false);
    sending.current = false; uploadingRef.current = false; setSubmitting(false); setUploading(false); setClipping(false); setClipError('');
    // Keep text typed before the first identity resolves; clear it on account changes.
    setPrompt(current => entriesOwner === null ? current : ''); mentions.reset(); setMode(null); setFrames([null, null]); setError(''); setUploadError('');
    setSelection(models[0] ? defaults(models[0]) : null);
    setEntriesOwner(owner); setHistoryReady(false); setHistoryCursor(null); setHistoryError(''); setHistoryActionNotice(null); historyHead.current = null;
    const commerceDraft = commerceTool ? readCommerceVideoDraft(owner, commerceTool) : null;
    setBrief(commerceDraft?.brief ?? emptyVideoBrief()); setDraftError('');
    setDraftOwner(draftIdentity);
    const draft = commerceTool ? null : readComposerDraft(owner, draftScope, 'VIDEO');
    const recovery = readVideoSubmission(owner, 'VIDEO', Boolean(commerceTool));
    const saved = entryOnly && recovery?.taskRef ? null : recovery;
    setEntries(saved ? [saved.replacesTaskId && !saved.taskRef ? { ...saved, key: saved.replacesTaskId, taskRef: saved.replacesTaskId, replacesTaskId: undefined } : saved] : []);
    pendingEntry.current = saved?.taskRef ? null : saved;
    request.current = saved?.taskRef ? null : saved?.request ?? null;
    setUncertain(Boolean(saved && !saved.taskRef));
    recovered.current = null;
    lastStarted.current = saved?.snapshot.createdAt ? Date.parse(saved.snapshot.createdAt) : 0;
    lastDraft.current = saved?.draft ? JSON.stringify({ prompt: saved.draft.prompt, selection: saved.request?.settings, materials: saved.draft.materials, mode: saved.draft.mode }) : '';
    let cancelPreviews: (() => void) | undefined;
    // Recovery replays the saved request independently; retain this tool's own editor draft.
    if (!entryOnly && !commerceTool && saved?.request && !saved.taskRef && !saved.replacesTaskId) {
      setPrompt(saved.draft?.prompt ?? saved.request.prompt); setSelection(saved.request.settings);
      mentions.restore(saved.draft?.bindings ?? []);
      cancelPreviews = restoreMaterialPreviews(saved.draft?.materials ?? [null, null], setFrames);
      setMode((saved.draft?.mode as typeof chosenMode) ?? (saved.request.referenceMode === 'OMNI' ? 'omni' : saved.request.inputAssetRefs.length ? 'frames' : 'text'));
    }
    else if (draft) {
      setPrompt(current => entriesOwner === null && current ? current : draft.prompt); setSelection(draft.selection); setMode(draft.mode as typeof chosenMode);
      mentions.restore(draft.bindings);
      cancelPreviews = restoreMaterialPreviews(draft.materials, setFrames);
    }
    else if (commerceDraft) {
      setPrompt(commerceDraft.prompt); setSelection(commerceDraft.selection);
      mentions.restore(commerceDraft.bindings); setMode(commerceDraft.mode);
      cancelPreviews = restoreMaterialPreviews(commerceDraft.materials, setFrames);
    }
    if (entry.current && !saved?.request) {
      if (entryOwner.current === undefined && owner) entryOwner.current = owner;
      if (entryOwner.current === undefined || entryOwner.current === owner) {
        setPrompt(entry.current.prompt);
        const entryMode = entry.current.mode;
        if (entryMode === 'text' || entryMode === 'omni' || entryMode === 'frames') setMode(entryMode);
        if (entry.current.materials && entry.current.ownerId === owner) {
          cancelPreviews?.(); mentions.reset();
          const materials = entry.current.materials;
          cancelPreviews = restoreMaterialPreviews(materials.length ? materials : [null, null], setFrames);
        }
        const settings = entry.current.settings;
        if (settings?.type === 'VIDEO') {
          const target = models.find(model => model.model === settings.model) ?? models.find(model => homeModelMatches(model.displayName, entry.current?.modelName));
          setSelection(target ? compatible(settings, target) : settings);
        }
      }
    }
    return () => { cancelPreviews?.(); historyRun.current++; };
  }, [owner, commerceTool, draftScope, entryOnly]);
  useEffect(() => {
    if (!owner || historyOnly || draftOwner !== draftIdentity) return;
    const draft = { prompt, selection, mode: chosenMode, materials: frames, bindings: mentions.bindings };
    const saved = commerceTool ? saveCommerceVideoDraft(owner, commerceTool, { ...draft, brief }) : saveComposerDraft(owner, draftScope, 'VIDEO', draft);
    setDraftError(saved ? '' : draftSaveError);
  }, [commerceTool, owner, identityReady, historyOnly, draftOwner, draftIdentity, draftScope, brief, prompt, selection, chosenMode, frames, mentions.bindings]);
  const loadHistory = async (before?: string, refresh = false) => {
    if (historyInFlight.current === historyRun.current) return;
    const run = ++historyRun.current;
    historyInFlight.current = run;
    historyRetry.current = { before, refresh };
    setLoadingHistory(true); setHistoryError('');
    try {
      const isCurrent = () => alive.current && historyRun.current === run;
      const page = galleryEnabled ? await readVideoHistory({ before, commerceOnly: Boolean(commerceTool),
        known: refresh && historyHead.current ? new Set([historyHead.current]) : undefined, isCurrent }) : null;
      const legacy = galleryEnabled ? null : (await mediaListGetApiV1MediaTasks({ limit: 20, excludeCanvas: true, ...(commerceTool ? { commerceOnly: true } : {}), ...(before ? { beforeId: before } : {}) })).data;
      if (!alive.current || historyRun.current !== run) return;
      if (!page && !legacy) return;
      const history = page?.entries ?? legacy!.filter(item => item.type === 'VIDEO' && item.id).map(item => ({ key: item.id!, taskRef: item.id!, snapshot: item }));
      setEntries(current => [...current.map(item => {
        const updated = history.find(next => next.taskRef === item.taskRef);
        return updated ? { ...item, snapshot: updated.snapshot } : item;
      }), ...history.filter(item => !current.some(existing => existing.taskRef === item.taskRef))]
        .sort(compareMediaHistory));
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
  }, [active, entryOnly, owner, account?.authenticated, commerceTool]);

  const chooseModel = (next: VideoOption) => {
    setSelection(compatible(selection, next));
    setLayer(null);
  };
  const upload = async (file: File, index: number) => {
    if (uploadLocked || uploadingRef.current || !model || index < 0 || mode === 'text' || index >= (mode === 'omni' ? omni?.maxTotal ?? 0 : model.maxReferenceImages)) return false;
    const kind = ['image/png', 'image/jpeg'].includes(file.type) ? 'IMAGE' : ['video/mp4', 'video/quicktime'].includes(file.type) ? 'VIDEO' : ['audio/mpeg', 'audio/wav', 'audio/x-wav', 'audio/wave'].includes(file.type) ? 'AUDIO' : null;
    const maxBytes = kind === 'VIDEO' ? omni?.maxVideoBytes ?? 0 : kind === 'AUDIO' ? omni?.maxAudioBytes ?? 0 : 20 * 1024 * 1024;
    if (!kind || (mode !== 'omni' && kind !== 'IMAGE') || file.size === 0 || file.size > maxBytes) {
      setUploadError(mode === 'omni' ? '仅支持当前模型大小限制内的 JPG/PNG、MP4/MOV、MP3/WAV 素材' : '参考图片仅支持 20MB 以内的 PNG 或 JPEG'); return false;
    }
    const epoch = ownerEpoch.current;
    const currentOwner = owner;
    const isCurrent = () => alive.current && ownerRef.current === currentOwner && ownerEpoch.current === epoch;
    uploadingRef.current = true; setUploading(true); setUploadError('');
    const audioAllowed = (duration: number | null | undefined) => {
      const current = latestMaterials.current;
      const limits = current.model?.omniReference;
      const others = current.frames.filter((frame, position) => position !== index && frame?.kind === 'AUDIO');
      const used = others.reduce((sum, frame) => sum + (frame?.durationSeconds ?? 0), 0);
      const remaining = Math.max(0, (limits?.maxAudioDurationSeconds ?? 0) - used);
      const issue = current.mode !== 'omni' || !limits ? '当前模型不支持全能参考，请切换模型或移除素材'
        : !Number.isFinite(duration) || (duration ?? 0) <= 0 || others.some(frame => !Number.isFinite(frame?.durationSeconds) || (frame?.durationSeconds ?? 0) <= 0) ? '素材缺少可信时长，请重新上传'
        : (duration ?? 0) > remaining ? `音频总时长不能超过 ${limits.maxAudioDurationSeconds} 秒，剩余 ${remaining} 秒；请更换音频`
        : '';
      if (issue) setUploadError(issue);
      return !issue;
    };
    try {
      if (kind === 'AUDIO') {
        let duration: number;
        try { duration = await readReferenceAudioDuration(file); }
        catch { if (isCurrent()) setUploadError('无法读取音频时长，请重新导出 MP3/WAV 文件后重试'); return false; }
        if (!isCurrent() || !audioAllowed(duration)) return false;
      }
      const uploaded = (await (commerceTool === 'video-replica' && kind === 'VIDEO' ? storageUploadProcessingVideoPostApiV1FilesProcessingVideos({ file }) : mode === 'omni' ? storageUploadReferenceMediaPostApiV1FilesReferenceMedia({ file }) : storageUploadReferenceImagePostApiV1FilesReferenceImages({ file }))).data;
      if (!uploaded.fileId || !mediaReference(uploaded.fileId).fileId) throw new Error('上传未返回有效文件 ID');
      if (!isCurrent() || kind === 'AUDIO' && !audioAllowed(uploaded.durationSeconds)) return false;
      let preview = '';
      try { preview = await resolvePreview(uploaded.fileId); } catch { /* Keep the uploaded file even if its signed preview is unavailable. */ }
      if (!isCurrent() || kind === 'AUDIO' && !audioAllowed(uploaded.durationSeconds)) return false;
      const next = [...latestMaterials.current.frames];
      next[index] = { fileId: uploaded.fileId!, name: file.name, preview, kind, durationSeconds: uploaded.durationSeconds, sizeBytes: uploaded.sizeBytes ?? file.size, reviewPending: true, reviewStatus: 'SUBMITTING' };
      latestMaterials.current = { ...latestMaterials.current, frames: next };
      setFrames(next);
      return next[index]!;
    } catch (cause) { if (isCurrent()) setUploadError(uploadErrorMessage(cause, mode === 'omni')); return false; }
    finally { if (isCurrent()) { uploadingRef.current = false; setUploading(false); } }
  };
  const canSubmit = !apiNotice && composing && catalog === 'ready' && parametersValid && inputValid && !uploading && !queueBusy && !locked && Boolean(currentQuote?.points) && balance.affordable && Boolean(prompt.trim());
  const editTask = async (entry: VideoSubmission) => {
    if (!identityReady || locked || uploadingRef.current || sending.current) return;
    const epoch = ownerEpoch.current;
    uploadingRef.current = true; setUploading(true); setSubmissionError('');
    try {
      const draft = await creationTaskDraft(entry);
      if (!alive.current || ownerEpoch.current !== epoch) return;
      setPrompt(draft.prompt); mentions.restore(draft.bindings); setSelection(draft.settings);
      setFrames(draft.mode === 'frames' ? [draft.materials[0] ?? null, draft.materials[1] ?? null] : draft.materials);
      setMode(draft.mode as typeof mode); setQuote(null); setUploadError('');
      if (historyOnly) {
        if (!saveComposerDraft(owner, 'create', 'VIDEO', { ...draft, selection: draft.settings, mode: draft.mode as typeof chosenMode })) throw new Error(draftSaveError);
        navigate('/create/video');
      }
      setEditRequest(value => value + 1);
    } catch { if (alive.current && ownerEpoch.current === epoch) setSubmissionError('任务内容暂不可用，请重试'); }
    finally { if (alive.current && ownerEpoch.current === epoch) { uploadingRef.current = false; setUploading(false); } }
  };
  const deleteTask = async (entry: VideoSubmission) => {
    if (!entry.taskRef || !identityReady || locked || uploadingRef.current || sending.current) return;
    const epoch = ownerEpoch.current, requestOwner = owner, taskId = entry.taskRef;
    sending.current = true; setSubmitting(true); setHistoryError(''); setHistoryActionNotice(null);
    try {
      await mediaDeleteHistoryDeleteApiV1MediaTasksTaskId(taskId);
      if (readVideoSubmission(requestOwner, 'VIDEO', Boolean(commerceTool))?.taskRef === taskId) saveVideoSubmission(requestOwner, null, 'VIDEO', Boolean(commerceTool));
      if (!alive.current || ownerEpoch.current !== epoch) return;
      historyRun.current++; setLoadingHistory(false);
      setEntries(current => current.filter(item => item.taskRef !== taskId));
    } catch (cause) { if (alive.current && ownerEpoch.current === epoch) setHistoryActionNotice({ taskId, message: cause instanceof ApiError && cause.status === 409
      ? '增强任务仍在处理或退款中，请完成后再隐藏此视频记录。' : '删除失败，请重试' }); }
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
        const pending = regenerationEntry(replacement, Boolean(commerceTool));
        const { data } = await mediaQuotePostApiV1MediaQuotes({ ...pending.request!.settings,
          ...(pending.request!.referenceMode ? { referenceMode: pending.request!.referenceMode } : {}), references: replacement.snapshot.references ?? [] });
        if (!alive.current || ownerEpoch.current !== epoch || apiNoticeRef.current) return;
        const available = generationBalance(account, data.points ?? undefined);
        if (!/^\d+\.\d{4}$/.test(data.points ?? '') || !available.affordable) { setSubmissionError(available.notice ?? '报价失败，请重试'); return; }
        request.current = pending.request!; pendingEntry.current = pending;
      } catch { if (alive.current && ownerEpoch.current === epoch) setSubmissionError('任务内容或报价暂不可用，请重试'); return; }
      finally { if (alive.current && ownerEpoch.current === epoch) { sending.current = false; setSubmitting(false); } }
    }
    const draftSignature = JSON.stringify({ prompt, selection, materials: frames, mode });
    if (!request.current && draftSignature === lastDraft.current && Date.now() - lastStarted.current < 600) return;
    lastDraft.current = draftSignature;
    const firstAttempt = !uncertain || pendingEntry.current?.state === 'PREPARED';
    lastStarted.current = Date.now();
    sending.current = true; setSubmitting(true); setError('');
    if (!request.current) {
      request.current = { requestRef: `${commerceTool ? 'commerce-' : ''}${randomUUID()}`, settings: { ...selection! }, prompt: mentions.compiled.trim(), inputAssetRefs: [...inputAssetRefs], ...(referenceMode ? { referenceMode } : {}), ...(mode === 'omni' ? { references: typedReferences } : {}) };
      pendingEntry.current = { key: request.current.requestRef, request: request.current, state: 'SUBMITTING', draft: { prompt, materials: frames, mode, bindings: mentions.bindings }, snapshot: {
        ...selection, prompt: request.current.prompt, modelName: model?.displayName || selection!.model,
        referenceMode, referenceFollowsFirstFrame: Boolean(frameMode && model?.referenceFollowsFirstFrame), references: mode === 'omni' ? typedReferences.map(ref => ({ ...ref, httpsUrl: null })) : inputAssetRefs.map(fileId => ({ fileId, httpsUrl: null, kind: 'IMAGE' as const, durationSeconds: null })),
        createdAt: new Date().toISOString(),
      } };
    }
    const requestOwner = owner;
    const epoch = ownerEpoch.current;
    const isCurrent = () => alive.current && ownerRef.current === requestOwner && ownerEpoch.current === epoch;
    const entry: VideoSubmission = { ...pendingEntry.current!, state: entryOnly && firstAttempt ? 'PREPARED' : uncertain ? 'UNCERTAIN' : 'SUBMITTING' };
    if (!saveVideoSubmission(owner, entry)) {
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
      saveVideoSubmission(owner, accepted);
      if (isCurrent()) {
        historyRun.current++; setLoadingHistory(false); addEntry(accepted); setUncertain(false); request.current = null; pendingEntry.current = null;
        if (galleryEnabled) setSubmissionKey(`${accepted.key}:${accepted.taskRef}`);
        setQuote(null); setRefresh(value => value + 1);
      }
    } catch (cause) {
      if (!isCurrent()) return;
      if (cause instanceof CreationRejection) {
        lastStarted.current = 0; request.current = null; pendingEntry.current = null; saveVideoSubmission(owner, null, 'VIDEO', Boolean(commerceTool));
        if (isCurrent() && !entry.replacesTaskId) addEntry({ ...entry, state: 'REJECTED', message: '提交被拒绝，请检查当前模型、素材和账户状态后重试' });
        if (isCurrent()) { if (entry.replacesTaskId) setSubmissionError('提交被拒绝，请检查当前模型、素材和账户状态后重试'); setUncertain(false); setQuote(null); setRefresh(value => value + 1); setError('提交被拒绝，请检查当前模型、素材和账户状态后重试'); }
        return;
      }
      // A disconnected request may already be charged. Freeze and replay the same
      // idempotency key and payload instead of creating a second paid task.
      const pending = { ...entry, state: 'UNCERTAIN' as const };
      saveVideoSubmission(owner, pending); pendingEntry.current = pending;
      if (isCurrent()) { if (!entry.replacesTaskId) addEntry(pending); setUncertain(true); }
    } finally { if (isCurrent()) { sending.current = false; setSubmitting(false); } }
  };

  useEffect(() => {
    if (entryOnly || (historyOnly && !pendingEntry.current?.replacesTaskId) || !active || !identityReady || !uncertain || !request.current
      || recovered.current === request.current.requestRef) return;
    recovered.current = request.current.requestRef;
    void submit(undefined, true);
  }, [entryOnly, historyOnly, active, identityReady, uncertain, owner]);

  return <CreationWorkspace hasResults={entriesOwner === owner && entries.length > 0} editRequest={editRequest} enabled={!entryOnly && !historyOnly && className === 'floating-composer'} ready={historyReady} submission={submissionKey} prompt={prompt} busy={uploading || clipping || Boolean(layer)} results={!entryOnly && <>
    <section className="video-results" aria-label={t(historyOnly ? '视频历史' : '视频生成结果')}>
      {commerce && <h2>{t("我的视频")}</h2>}
      {historyOnly && <h1>{t("视频历史")}</h1>}
      {historyOnly && submissionError && <p role="alert">{t(submissionError)}</p>}
      {!galleryEnabled && historyOnly && loadingHistory && <p role="status">{t("正在读取视频历史…")}</p>}
      {!galleryEnabled && historyOnly && !loadingHistory && !historyError && !historyCursor && entriesOwner === owner && !entries.length && account?.status !== 'loading' && <p role="status">{t("暂无视频任务")}</p>}
      {historyOnly && uncertain && <p>{t("有待确认的提交，")}<Link to="/create/video">{t("返回创作使用原请求确认")}</Link>。</p>}
      {galleryEnabled ? <VideoResultsGallery key={owner ?? 'anonymous'} ready={historyReady && entriesOwner === owner} loading={loadingHistory}
        error={historyError} errorAtTop={Boolean(historyRetry.current.before)} hasMore={Boolean(historyCursor)} empty={!entries.length} submission={submissionKey}
        onLatest={() => void loadHistory(undefined, true)} onOlder={() => { if (historyCursor) void loadHistory(historyCursor); }}
        onRetry={() => void loadHistory(historyRetry.current.before, historyRetry.current.refresh)}>
        {compact => (entriesOwner === owner ? [...entries].sort(compareMediaHistory) : []).map(entry => <VideoTaskCard key={entry.key} entry={entry} enableEnhancement={!commerceTool && draftScope === 'create'} enhancementOwnerId={owner} compact={compact}
          actionNotice={historyActionNotice && historyActionNotice.taskId === entry.taskRef ? historyActionNotice.message : undefined} actionsDisabled={!identityReady || locked || uploading} onEdit={entry => void editTask(entry)} onRegenerate={apiNotice ? undefined : entry => void submit(undefined, false, entry)} onDelete={entry => void deleteTask(entry)} />)}
      </VideoResultsGallery> : <>
      {!historyOnly && historyCursor && <button type="button" disabled={loadingHistory} onClick={() => void loadHistory(historyCursor)}>{t("加载更早任务")}</button>}
      {(entriesOwner === owner ? [...entries].sort(compareMediaHistory) : []).map(entry => <VideoTaskCard key={entry.key} entry={entry} enableEnhancement={!commerceTool && draftScope === 'create'} enhancementOwnerId={owner} actionNotice={historyActionNotice && historyActionNotice.taskId === entry.taskRef ? historyActionNotice.message : undefined} actionsDisabled={!identityReady || locked || uploading} onEdit={entry => void editTask(entry)} onRegenerate={apiNotice ? undefined : entry => void submit(undefined, false, entry)} onDelete={entry => void deleteTask(entry)} />)}
      {historyOnly && historyCursor && <button type="button" disabled={loadingHistory} onClick={() => void loadHistory(historyCursor)}>{t("加载更早任务")}</button>}
      {historyError && <p role="status">{t(historyError)}<button type="button" onClick={() => void loadHistory()}>{t("重新读取任务")}</button></p>}
      </>}
    </section></>}>
    {!historyOnly && <ExpandableComposer resizable={!entryOnly && className === 'floating-composer'} className={`${className} video-composer`} data-testid={testId} aria-label={t("视频生成")}>
    <form onSubmit={event => void submit(event)}>
      <div className={commerce ? "commerce-video-editor" : undefined}>
      {commerceTool && <CommerceVideoBrief analyzing={analyzing} onAnalyze={commerceTool === 'video-replica' && breakdownAvailable && referenceVideo ? () => void analyzeVideo() : undefined} tool={commerceTool} value={brief} onChange={setBrief} disabled={locked || uploading || clipping} hasScript={Boolean(prompt.trim())} onApply={script => { setPrompt(script); mentions.reset(); }} />}
      <div className={commerce ? "commerce-video-generation" : undefined}>
      {commerce && <h2>{t(commerceTool === "video-replica" ? "参考视频与商品素材" : "商品素材与视频脚本")}</h2>}
      <fieldset disabled={submitting || uncertain || analyzing} className="video-inputs">
        <ComposerMaterials key={owner} onQueueBusyChange={setQueueBusy} sourceVideos={commerceTool === 'video-replica'} review={{ owner, model: needsReplicaClip ? '' : reviewModel, onSelecting: setUploading, onReview: (fileId, reviewId, reviewStatus) => setFrames(current => current.map(file => file?.fileId === fileId ? { ...file, reviewId, reviewStatus, reviewPending: !reviewId } : file)) }}
          selectionKey={`${model?.model}:${mode}`}
          capability={mode === 'omni' ? { acceptedKinds: ['image', 'video', 'audio'], acceptedMimeTypes: ['image/png', 'image/jpeg', 'video/mp4', 'video/quicktime', 'audio/mpeg', 'audio/wav', 'audio/x-wav', 'audio/wave'], acceptedSources: ['upload', 'library'], maxFileBytes: 20 * 1024 * 1024, maxItems: omni?.maxTotal ?? 0,
            maxItemsByKind: { image: omni?.maxImages ?? 0, video: omni?.maxVideos ?? 0, audio: omni?.maxAudios ?? 0 }, maxFileBytesByKind: { video: omni?.maxVideoBytes ?? 0, audio: omni?.maxAudioBytes ?? 0 }, verified: Boolean(omni) }
            : { acceptedKinds: ['image'], acceptedMimeTypes: ['image/png', 'image/jpeg'], acceptedSources: ['upload', 'library'], maxFileBytes: 20 * 1024 * 1024, maxItems: frameMode ? model?.maxReferenceImages ?? 0 : 0, verified: Boolean(model) }}
          slots={frameMode || assets.length ? frames.map((file, index) => ({ file, available: mode === 'omni' || frameMode && index < (model?.maxReferenceImages ?? 0), label: frameMode && index < 2 && (!file?.kind || file.kind === 'IMAGE') ? index === 0 ? '首帧' : '尾帧' : typedMaterialLabel(frames, index), issue: frameIssues[index] || (!file && frameMode && index === 0 ? '请先添加首帧' : undefined) })) : []}
          nextIndex={mode === 'text' ? -1 : mode === 'omni' ? assets.length >= (omni?.maxTotal ?? 0) ? -1 : Math.max(0, frames.includes(null) ? frames.indexOf(null) : frames.length) : frames.findIndex((frame, index) => !frame && index < (model?.maxReferenceImages ?? 0))}
          disabled={uploadLocked || !model} busy={uploading || clipping} error={clipError || uploadError}
          hint={mode === 'text' ? '文生视频不使用素材' : mode === 'omni' && !omni ? '当前模型不支持全能参考' : undefined}
          onUpload={upload} onReuse={(material, index) => {
            if (uploadLocked || uploadingRef.current || !model || index < 0 || mode === 'text' || index >= (mode === 'omni' ? omni?.maxTotal ?? 0 : model.maxReferenceImages)) return false;
            const next = [...latestMaterials.current.frames]; next[index] = material;
            latestMaterials.current = { ...latestMaterials.current, frames: next }; setFrames(next); return true;
          }} onRemove={index => setFrames(current => current.map((file, position) => position === index ? null : file))}>
          <MaterialPrompt label={t(commerce ? "视频脚本" : "视频提示词")} value={prompt} onChange={setPrompt} mentions={mentions} disabled={submitting || uncertain} placeholder={t(commerce ? "编辑视频脚本，或将左侧商品资料与分镜应用到这里" : "描述你想要生成的视频画面")} />
        </ComposerMaterials>
      </fieldset>
      <div className="composer-toolbar" role="group" aria-label={t("视频创作工具栏")}>
        <ComposerModeMenu<typeof mode> label={t("视频生成模式")} icon="video" value={mode} disabled={uploading || clipping || locked} onChange={setMode}
          options={[...(!commerceTool ? [{ value: 'text' as const, label: '文生视频' }] : []), ...(omni || mode === 'omni' ? [{ value: 'omni' as const, label: '全能参考' }] : []),
            ...(commerceTool !== 'video-replica' && (Boolean(model?.maxReferenceImages) || mode === 'frames') ? [{ value: 'frames' as const, label: model?.maxReferenceImages === 1 ? '首帧' : '首尾帧' }] : [])]} />
        <button type="button" className="composer-model" aria-label={formatMessage("选择视频模型：{0}", (model?.displayName ?? t(modelLabel)))} aria-haspopup="dialog" disabled={uploading || clipping || locked || catalog !== 'ready' || !models.length} onClick={() => setLayer('models')}><ComposerIcon name="model" /><span>{(model?.displayName ?? t(modelLabel))}</span></button>
        <button type="button" className="composer-summary" aria-label={t(selection ? formatMessage("视频参数摘要：{0}，{1}，{2}秒", t(frameMode && model?.referenceFollowsFirstFrame ? '跟随首帧' : selection.aspectRatio), selection.resolution, selection.durationSeconds) : '视频参数摘要')} aria-haspopup="dialog" disabled={uploading || clipping || locked || !model} onClick={() => setLayer('settings')}><ComposerIcon name="ratio" />{selection ? <><span>{t(frameMode && model?.referenceFollowsFirstFrame ? '跟随首帧' : selection.aspectRatio)}</span><span>{selection.resolution}</span><span>{selection.durationSeconds}s</span></> : t('视频参数')}</button>
        {loginRequired ? <Link className="create-button" to="/user/login">{t('登录后开始创作')}</Link> : <button className="create-button" onClick={event => { if (event.detail > 1) event.preventDefault(); }} type="submit" disabled={Boolean(apiNotice) || !identityReady || submitting || (uncertain ? false : !canSubmit)}>{t(submitting ? '生成中' : uncertain ? (entryOnly ? '继续到生成页' : '使用原请求重试') : entryStatus ?? formatMessage("{0} · {1}", t(commerce?.action ?? '开始生成'), t(!inputValid ? '请处理素材提示' : currentQuote?.error ? '报价失败' : currentQuote?.points ? balance.affordable ? formatMessage("{0} 积分", formatAmount(currentQuote.points)) : balance.notice?.includes('不足') ? '积分不足' : '账户不可用' : catalog === 'ready' && parametersValid ? '报价中…' : '等待有效设置')))}</button>}
      </div>
      {commerceInputIssue && <p className="commerce-video-material-status" role="status">{t(commerceInputIssue)}</p>}
      </div></div>
    </form>
    {draftError && <p role="alert">{t(draftError)}</p>}
    {submissionError && <p role="alert">{t(submissionError)}</p>}
    {currentQuote?.error && <p role="alert">{t(currentQuote.error)}</p>}
    {apiNotice ? <p role="status">{t(apiNotice)}</p> : active && !locked && catalog === 'ready' && currentQuote?.points && balance.notice && <p role="status">{t(balance.notice)}</p>}
    {accountFailed && <div className="commerce-entry-notice"><p role="status">{t('账户信息未能加载，请重试后继续创作。')}</p><button type="button" onClick={() => void account?.refresh()}>{t('重新读取账户')}</button></div>}
    {loginRequired && <p className="commerce-entry-notice" role="status">{t('登录后查看可用模型与积分，继续完成创作。')}</p>}
    {!loginRequired && !accountFailed && catalog === 'ready' && !models.length && <p role="status">{t(commerceEntry ? '暂时没有可用模型。请重新加载；仍不可用时，请联系平台支持。' : commerceTool === 'video-replica' ? '暂无支持商品图片与视频参考的可用模型。' : '暂无可用视频模型，请管理员启用模型并配置当前账户的价格。')}</p>}
    {catalog === 'failed' && <p role="alert">{t(catalogError)}</p>}
    {!loginRequired && !accountFailed && (clipError || catalog === 'failed' || currentQuote?.error || (catalog === 'ready' && !models.length)) && <button type="button" disabled={uploading || clipping || locked} onClick={() => setRefresh(value => value + 1)}>{t(commerceEntry ? '重新加载模型与报价' : '重新获取模型与报价')}</button>}
    {model && !parametersValid && selection && <p role="alert">{t("请输入当前模型范围内、符合步长的整数秒数。")}</p>}
    {error && <p role="alert">{t(error)}</p>}
    {layer && <ComposerDialog anchored title={t(layer === 'settings' ? '视频设置' : '选择视频模型')} closeLabel={t(layer === 'settings' ? '关闭视频设置' : '关闭模型选择')} onClose={() => setLayer(null)} onReset={layer === 'settings' && model ? () => setSelection(defaults(model)) : undefined}>
        {layer === 'models' ? <div className="video-model-list">{models.map(item => <button type="button" key={item.model} aria-label={item.displayName} aria-pressed={item.model === model?.model} onClick={() => chooseModel(item)}><span className="composer-model-icon"><ComposerIcon name="model" /></span><span className="composer-model-copy"><span>{item.displayName}</span><small>{formatMessage("{0} · {1}–{2}秒", sortedResolutions(item.resolutions).join(' / '), item.minDurationSeconds, item.maxDurationSeconds)}</small></span>{item.model === model?.model && <span className="composer-model-check" aria-hidden="true">✓</span>}</button>)}</div>
          : model && selection && <ComposerParameterFields model={model} selection={selection} onChange={setSelection} ratioDisabled={Boolean(frameMode && model.referenceFollowsFirstFrame)} resolutionHint={!commerceTool && draftScope === 'create' ? '生成完成后，可在视频下方按需超分增强。' : undefined} />}
    </ComposerDialog>}
  </ExpandableComposer>}</CreationWorkspace>;
}
