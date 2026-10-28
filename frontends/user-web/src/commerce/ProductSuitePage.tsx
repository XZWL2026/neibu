import { randomUUID } from '../../../shared/randomUUID';
import { t, formatMessage, useLanguage, currentLanguage } from '../i18n';
import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { UserFrame } from '../UserFrame';
import { accountStatusLabels, apiGenerationNotice, useOptionalUserSession, type UserSession } from '../userSession';
import { formatAmount } from '../../../shared/formatAmount';
import { generationBalance } from '../creator/generation-balance';
import { MediaTaskCard } from '../creator/VideoTaskResults';
import { ProductReferences, type ProductReference } from './ProductReferences';
import { SuiteModules } from './SuiteModules';
import { ToolExamples } from './ToolExamples';
import { ToolParameters, commercePlatforms, commerceLanguages } from './ToolParameters';
import { ModelPortraitPicker } from './ModelPortraitPicker';
import { CommerceToolIcon, CommerceToolMenu } from './CommerceToolMenu';
import { SuiteResultLayout } from './SuiteResultLayout';
import { SuiteProjectSelect } from './SuiteProjectSelect';
import { commerceTools, type CommerceTool } from './commerce-tools';
import { DetailPageExport } from './DetailPageExport';
import { readSuiteDraft, saveSuiteDraft, type SuiteDraft } from './suite-draft';
import { draftSaveError } from '../creator/composer-draft';
import { resolvePreview } from '../canvas/production-api';
import { clearPendingSuite, readPendingSuite, savePendingSuite, type PendingSuite } from './suite-recovery';
import { ApiError, mediaOptionsGetApiV1MediaGenerationOptions, mediaTemplatesGetApiV1MediaProductSuitesTemplates,
  mediaPlanPostApiV1MediaProductSuitesPlan, mediaQuotePostApiV1MediaProductSuitesQuotes, mediaCopyPostApiV1MediaProductSuitesCopy, mediaSubmitPostApiV1MediaProductSuites,
  mediaListGetApiV1MediaProductSuites, mediaGetGetApiV1MediaProductSuitesSuiteId,
  mediaRegeneratePostApiV1MediaProductSuitesSuiteIdModulesModuleRegenerations,
  type ResponseMediaApplicationCatalogGenerationCatalogServiceOption as Model,
  type ResponseMediaApplicationSuiteProductSuitesTemplate as Template,
  type ResponseMediaApplicationSuiteProductSuitesRequest as SuiteRequest,
  type ResponseMediaInterfacesHttpProductSuiteControllerItemResponse as Item,
  type ResponseMediaInterfacesHttpProductSuiteControllerSuiteResponse as Suite } from '../api';
import './product-suite.css';

type Module = NonNullable<SuiteRequest['modules']>[number];
const validPoints = (points: unknown): points is string => typeof points === 'string' && /^\d+\.\d{4}$/.test(points);
const terminal = (status: string | null | undefined) => ['SUCCEEDED', 'PARTIAL_SUCCESS', 'FAILED', 'TIMED_OUT'].includes(status || '');
const suiteLabel = (status: string | null) => status === 'SUCCEEDED' ? '已完成' : status === 'PARTIAL_SUCCESS' ? '部分完成' : status === 'FAILED' ? '生成失败' : '生成中';
function defaultModules(template: Template, tool: CommerceTool): Module[] {
  const defaults: Module[] | null = tool === 'detail-page' && template.version === 2 ? ['WHITE_BACKGROUND', 'SELLING_POINTS', 'MODEL_SCENE', 'MULTI_ANGLE', 'COMPARISON']
    : template.id === 'apparel-set' ? ['WHITE_BACKGROUND', 'VOLUME_3D', 'MANNEQUIN'] : null;
  return (template.modules || []).map(item => item.id).filter(id => !defaults || defaults.includes(id));
}

export function ProductSuitePage({ backLink, tool = 'product-suite' }: { backLink?: ReactNode; tool?: CommerceTool }) {
  useLanguage();
  const account = useOptionalUserSession();
  const settledOwner = useRef<string | null>(null);
  const unresolved = account?.status === 'loading' || (account?.status === 'error' && !account.userId);
  if (!unresolved) settledOwner.current = account?.authenticated ? account.userId : null;
  const owner = unresolved ? settledOwner.current : account?.authenticated ? account.userId : null;
  return <UserFrame>{backLink && (tool !== 'product-suite' || !owner) && <nav className="suite-back" aria-label={t("返回工作台")}>{backLink}</nav>}{owner ? <SuiteWorkspace key={`${owner}:${tool}`} owner={owner} account={account} tool={tool} backLink={tool === 'product-suite' ? backLink : undefined} />
    : <section className="product-suite" data-tool={tool}><div className="suite-workspace"><ToolExamples tool={tool} /><section className="suite-config suite-signed-out" aria-label={t("套图配置")}><p role="status">{t(unresolved ? '正在读取账户…' : '登录后开始创作商品套图。')}</p>{!unresolved && <a className="suite-button" href="/user/login">{t("登录")}</a>}</section></div></section>}</UserFrame>;
}

function SuiteWorkspace({ owner, account, tool, backLink }: { owner: string; account: UserSession | null; tool: CommerceTool; backLink?: ReactNode }) {
  useLanguage();
  const config = commerceTools[tool];
  const paired = Boolean(config.referenceLabel);
  const [pending, setPending] = useState(() => readPendingSuite(owner, tool));
  const [savedDraft] = useState(() => pending ? null : readSuiteDraft(owner, tool));
  const restoredRequest = pending?.request ?? savedDraft?.request;
  const restoringModules = useRef(Boolean(savedDraft));
  const [draftError, setDraftError] = useState('');
  const [productName, setProductName] = useState(pending?.regeneration ? '' : restoredRequest?.productName || '');
  const [sellingPoints, setSellingPoints] = useState(pending?.regeneration ? '' : restoredRequest?.sellingPoints || '');
  const [language, setLanguage] = useState(restoredRequest?.language || '中文');
  const restoredFiles = pending?.regeneration ? [] : (restoredRequest?.fileIds || []).map((fileId, index) => ({ fileId, name: `参考图片 ${index + 1}`, preview: '' }));
  const [files, setFiles] = useState<ProductReference[]>(() => savedDraft?.files ?? (paired ? restoredFiles.slice(0, -1) : restoredFiles));
  const [referenceFiles, setReferenceFiles] = useState<ProductReference[]>(() => savedDraft?.referenceFiles ?? (paired ? restoredFiles.slice(-1) : []));
  const [referenceUploading, setReferenceUploading] = useState(false);
  const savedOptions = restoredRequest?.options;
  const [platform, setPlatform] = useState(savedOptions?.platform || '亚马逊');
  const [replicationMode, setReplicationMode] = useState(savedOptions?.replicationMode || 'HIGH');
  const [enhancements, setEnhancements] = useState<string[]>(savedOptions?.enhancements || ['GLOSS']);
  const [garmentView, setGarmentView] = useState(savedOptions?.garmentView || 'FRONT');
  const [white3d, setWhite3d] = useState(savedOptions?.whiteBackground3d ?? true);
  const [whiteMannequin, setWhiteMannequin] = useState(savedOptions?.whiteBackgroundMannequin ?? false);
  const [counts, setCounts] = useState<Partial<Record<Module, number>>>(() => Object.fromEntries((savedOptions?.moduleCounts || []).map(item => [item.module, item.count])));
  const [modelSource, setModelSource] = useState(savedDraft?.modelSource ?? 'upload');
  const [planning, setPlanning] = useState(false);
  const plannedSelection = useRef<{ modules: Module[]; counts: Partial<Record<Module, number>> } | null>(null);
  const [templates, setTemplates] = useState<Template[]>([]), [templateId, setTemplateId] = useState(restoredRequest?.templateId || '');
  const [models, setModels] = useState<Model[]>([]), [modelId, setModelId] = useState(restoredRequest?.model || '');
  const [aspectRatio, setAspectRatio] = useState(restoredRequest?.aspectRatio || ''), [resolution, setResolution] = useState(restoredRequest?.resolution || '');
  const [selectedModules, setSelectedModules] = useState<Module[]>(pending?.regeneration ? [] : restoredRequest?.modules || []);
  const [copyAvailable, setCopyAvailable] = useState(false), [copying, setCopying] = useState(false), [uploading, setUploading] = useState(false);
  const [catalogReady, setCatalogReady] = useState(false), [catalogError, setCatalogError] = useState(''), [refresh, setRefresh] = useState(0);
  const [settingsOpen, setSettingsOpen] = useState(true);
  const [toolsOpen, setToolsOpen] = useState(false);
  const [worksOpen, setWorksOpen] = useState(false);
  const [resultScope, setResultScope] = useState<'function' | 'project'>('function');
  const [resultColumns, setResultColumns] = useState(() => window.matchMedia?.('(max-width: 600px)')?.matches ? 2 : 4);
  const lastGridColumns = useRef(resultColumns);
  const [viewChanged, setViewChanged] = useState(false);
  const switchView = (next: boolean) => {
    setToolsOpen(false);
    if (next === worksOpen) return;
    setViewChanged(true);
    setWorksOpen(next);
  };
  const toolsGroup = useRef<HTMLDivElement>(null);
  const toolsToggle = useRef<HTMLButtonElement>(null);
  const [quote, setQuote] = useState<{ key: string; points?: string; error?: string } | null>(null);
  const [error, setError] = useState(''), [submitting, setSubmitting] = useState(false);
  const [history, setHistory] = useState<Suite[]>([]), [selectedId, setSelectedId] = useState('');
  const [historyError, setHistoryError] = useState(''), [historyBusy, setHistoryBusy] = useState(false), [historyCursor, setHistoryCursor] = useState<string | null>(null);
  const [retryQuote, setRetryQuote] = useState<{ suiteId: string; module: Module; sourceTaskId: string; points: string } | null>(null), [quotingRetry, setQuotingRetry] = useState('');
  const alive = useRef(true), sending = useRef(false), retryRun = useRef(0), historyRun = useRef(0);
  const accountRef = useRef(account); accountRef.current = account;
  const identityReady = Boolean(account?.authenticated && account.userId === owner && account.status !== 'loading' && account.status !== 'error' && !apiGenerationNotice(account, 'IMAGE'));
  const eligible = identityReady && account?.status === 'ready';
  const locked = submitting || Boolean(pending) || copying || planning || uploading || referenceUploading || !eligible;
  // Keep the established owner's upload available when a file chooser triggers account refresh.
  const uploadLocked = Boolean(pending) || submitting || copying || planning || uploading || referenceUploading;
  const editingLocked = tool === 'product-suite' ? uploadLocked : locked;
  const model = models.find(value => value.model === modelId), template = templates.find(value => value.id === templateId);
  const selectedSuite = history.find(value => value.id === selectedId);
  const visibleSuites = tool === 'product-suite' && resultScope === 'function' ? history : selectedSuite ? [selectedSuite] : [];
  const updateSuite = (suite: Suite) => { historyRun.current++; setHistoryBusy(false); setHistory(current => [suite, ...current.filter(item => item.id !== suite.id)]); setSelectedId(suite.id || ''); switchView(true); };
  useEffect(() => { alive.current = true; return () => { alive.current = false; retryRun.current++; historyRun.current++; }; }, []);
  useEffect(() => {
    if (!toolsOpen) return;
    const closeOutside = (event: PointerEvent) => {
      if (event.target instanceof Node && !toolsGroup.current?.contains(event.target)) setToolsOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { setToolsOpen(false); toolsToggle.current?.focus(); }
    };
    document.addEventListener('pointerdown', closeOutside);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('pointerdown', closeOutside);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [toolsOpen]);
  useEffect(() => {
    const reload = () => { retryRun.current++; setQuotingRetry(''); setRetryQuote(null); setRefresh(value => value + 1); };
    window.addEventListener('focus', reload); window.addEventListener('xzwl-account-changed', reload);
    return () => { window.removeEventListener('focus', reload); window.removeEventListener('xzwl-account-changed', reload); };
  }, []);
  useEffect(() => {
    let cancelled = false; setCatalogReady(false); setCatalogError('');
    void Promise.all([mediaTemplatesGetApiV1MediaProductSuitesTemplates(), mediaOptionsGetApiV1MediaGenerationOptions()]).then(([catalog, options]) => {
      if (cancelled) return;
      const nextTemplates = (catalog.data.templates || []).filter(item => item.id && config.templates.includes(item.id) && item.modules?.length);
      const nextModels = (options.data.models || []).filter(item => item.type === 'IMAGE' && item.model && item.displayName
        && item.aspectRatios?.length && item.resolutions?.length && item.maxReferenceImages >= (paired ? 2 : 1));
      setTemplates(nextTemplates); setModels(nextModels); setCopyAvailable(catalog.data.copyAvailable);
      if (!pending && !templateId && nextTemplates[0]) setSelectedModules(defaultModules(nextTemplates[0], tool));
      setTemplateId(current => nextTemplates.some(item => item.id === current) ? current : nextTemplates[0]?.id || current);
      setModelId(current => nextModels.some(item => item.model === current) ? current : nextModels[0]?.model || current);
      setCatalogReady(true);
    }, () => { if (!cancelled) setCatalogError('模板或图片模型读取失败，请重试。'); });
    return () => { cancelled = true; };
  }, [refresh]);
  useEffect(() => {
    if (!model || pending) return;
    setAspectRatio(value => model.aspectRatios?.includes(value) ? value : model.aspectRatios?.[0] || '');
    setResolution(value => model.resolutions?.includes(value) ? value : model.resolutions?.[0] || '');
  }, [model, pending]);
  useEffect(() => {
    if (!template || pending) return;
    if (restoringModules.current) {
      restoringModules.current = false;
      if (template.id === savedDraft?.request.templateId && template.version === savedDraft.request.templateVersion) {
        setSelectedModules(current => current.filter(module => template.modules?.some(item => item.id === module)));
        return;
      }
    }
    const planned = plannedSelection.current; plannedSelection.current = null;
    setSelectedModules(planned?.modules || defaultModules(template, tool));
    setCounts(planned?.counts || {});
  }, [templateId, template?.version, Boolean(pending)]);

  const fileIds = [...files, ...referenceFiles].map(file => file.fileId);
  const request: SuiteRequest = { templateId, templateVersion: template?.version ?? savedDraft?.request.templateVersion ?? 0, productName: productName.trim() || config.title, sellingPoints,
    language, fileIds, modules: selectedModules, model: modelId, aspectRatio, resolution,
    options: { platform: ['product-suite', 'detail-page', 'image-replica'].includes(tool) ? platform : null,
      replicationMode: tool === 'image-replica' ? replicationMode : null,
      enhancements: tool === 'product-retouch' ? enhancements : [],
      garmentView: tool === 'apparel-set' ? garmentView : null,
      whiteBackground3d: tool === 'apparel-set' ? white3d : null,
      whiteBackgroundMannequin: tool === 'apparel-set' ? whiteMannequin : null,
      moduleCounts: selectedModules.filter(module => (counts[module] || 1) > 1).map(module => ({ module, count: counts[module]! })) } };
  const draftValue = JSON.stringify({ request: { ...request, productName }, modelSource,
    files: files.map(file => ({ ...file, preview: '' })), referenceFiles: referenceFiles.map(file => ({ ...file, preview: '' })) });
  useEffect(() => {
    if (!account?.authenticated || account.userId !== owner || ['loading', 'error'].includes(account.status) || pending) return;
    setDraftError(saveSuiteDraft(owner, tool, JSON.parse(draftValue) as SuiteDraft) ? '' : draftSaveError);
  }, [owner, tool, account?.authenticated, account?.userId, account?.status, pending, draftValue]);
  useEffect(() => {
    if (!savedDraft) return;
    let cancelled = false;
    for (const file of [...savedDraft.files, ...savedDraft.referenceFiles]) {
      void resolvePreview(file.fileId).then(preview => {
        if (cancelled) return;
        const update = (current: ProductReference[]) => current.map(item => item.fileId === file.fileId ? { ...item, preview } : item);
        setFiles(update); setReferenceFiles(update);
      }, () => { /* Keep the saved material ID and name available when preview loading fails. */ });
    }
    return () => { cancelled = true; };
  }, [savedDraft]);
  const imageCount = selectedModules.reduce((sum, module) => sum + (counts[module] || 1), 0);
  const productLimit = Math.min(tool === 'product-retouch' ? 1 : 3, Math.max(0, (model?.maxReferenceImages || 0) - (paired ? 1 : 0)));
  const valid = Boolean(catalogReady && template && model && request.productName && files.length && fileIds.length <= Math.min(8, model.maxReferenceImages)
    && (!paired || (referenceFiles.length === 1)) && new Set(fileIds).size === fileIds.length
    && files.length <= productLimit && selectedModules.length && selectedModules.every(module => (counts[module] || 1) <= model.maxCount) && model.aspectRatios?.includes(aspectRatio) && model.resolutions?.includes(resolution));
  const quoteKey = JSON.stringify([request, refresh, catalogReady, uploading, referenceUploading]);
  const currentQuote = quote?.key === quoteKey ? quote : null;
  const balance = generationBalance(account, currentQuote?.points);
  useEffect(() => {
    if (!valid || uploading || referenceUploading || pending || !eligible) return;
    let cancelled = false; setQuote({ key: quoteKey });
    const timer = setTimeout(() => void mediaQuotePostApiV1MediaProductSuitesQuotes(request).then(({ data }) => {
      if (!cancelled) setQuote(validPoints(data.points) ? { key: quoteKey, points: data.points } : { key: quoteKey, error: '报价暂不可用，请重新获取。' });
    }, () => { if (!cancelled) setQuote({ key: quoteKey, error: '报价失败，请检查生成设置后重试。' }); }), 250);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [quoteKey, valid, eligible, Boolean(pending)]);

  async function loadHistory(before?: string) {
    const run = ++historyRun.current; setHistoryBusy(true); setHistoryError('');
    try {
      const { data } = await mediaListGetApiV1MediaProductSuites({ limit: 20, ...(before ? { before } : {}) });
      if (!alive.current || run !== historyRun.current) return;
      const entries = data.filter(item => item.id && [...config.templates, ...(tool === 'apparel-set' ? ['apparel', 'streetwear'] : [])].includes(item.request?.templateId || ''));
      setHistory(current => before ? [...current, ...entries.filter(item => !current.some(existing => existing.id === item.id))] : entries);
      setSelectedId(current => (before && current) || entries.some(item => item.id === current) ? current : entries[0]?.id || '');
      setHistoryCursor(data.length === 20 ? data.at(-1)?.id || null : null);
    } catch { if (alive.current && run === historyRun.current) setHistoryError('套图记录读取失败，请重试。'); }
    finally { if (alive.current && run === historyRun.current) setHistoryBusy(false); }
  }
  useEffect(() => { void loadHistory(); }, []);
  useEffect(() => {
    if (!selectedId || terminal(selectedSuite?.status)) return;
    let cancelled = false; let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      try {
        const { data } = await mediaGetGetApiV1MediaProductSuitesSuiteId(selectedId);
        if (cancelled) return;
        setHistory(current => current.map(item => item.id === data.id ? data : item)); setHistoryError('');
        if (terminal(data.status)) { window.dispatchEvent(new Event('xzwl-account-changed')); return; }
      } catch { if (!cancelled) setHistoryError('套图进度暂不可用，正在重新读取。'); }
      if (!cancelled) timer = setTimeout(() => void poll(), 4000);
    }
    timer = setTimeout(() => void poll(), 4000);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [selectedId, selectedSuite?.status]);

  async function send(operation: PendingSuite, firstAttempt: boolean) {
    const currentAccount = accountRef.current;
    if (sending.current || !currentAccount?.authenticated || currentAccount.userId !== owner || ['loading', 'error'].includes(currentAccount.status)
      || (firstAttempt && currentAccount.status !== 'ready') || apiGenerationNotice(currentAccount, 'IMAGE')) return;
    if (!savePendingSuite(owner, operation, tool)) {
      const current = readPendingSuite(owner, tool);
      setError(current && current.key !== operation.key ? '还有一笔未确认的套图请求，请刷新页面后先确认该请求。' : '浏览器无法保存本次请求，请允许本地存储后重试。');
      return;
    }
    sending.current = true; setSubmitting(true); setPending(operation); setError(''); setRetryQuote(null); setQuotingRetry(''); retryRun.current++;
    try {
      const options = { headers: { 'Idempotency-Key': operation.key } };
      const retry = operation.regeneration;
      const { data } = retry ? await mediaRegeneratePostApiV1MediaProductSuitesSuiteIdModulesModuleRegenerations(retry.suiteId, retry.module, { sourceTaskId: retry.sourceTaskId }, options)
        : await mediaSubmitPostApiV1MediaProductSuites(operation.request, options);
      if (!data.id) throw new Error('missing suite');
      clearPendingSuite(owner, operation.key, tool);
      if (alive.current) { setPending(null); updateSuite(data); window.dispatchEvent(new Event('xzwl-account-changed')); }
    } catch (cause) {
      // 422 follows the backend's idempotency lookup; authentication and request/template parsing may run before it.
      const rejected = cause instanceof ApiError && (cause.status === 422 || (firstAttempt && [400, 401, 403, 404].includes(cause.status)) || (Boolean(operation.regeneration) && cause.status === 409));
      if (rejected) clearPendingSuite(owner, operation.key, tool);
      if (alive.current) {
        if (rejected) { setPending(null); setError('请求未被受理，请重新检查账户与生成设置。'); setRefresh(value => value + 1); void loadHistory(); }
        else setError('暂时无法确认提交结果，请使用原请求重试。');
      }
    } finally { if (alive.current) { sending.current = false; setSubmitting(false); } }
  }
  function submit(event: FormEvent) {
    event.preventDefault();
    if (pending) { void send(pending, false); return; }
    if (locked || !valid || !currentQuote?.points || !balance.affordable) return;
    void send({ key: randomUUID(), request }, true);
  }
  async function writeCopy() {
    if (locked || !copyAvailable || !files.length) return;
    setCopying(true); setError('');
    try {
      const { data } = await mediaCopyPostApiV1MediaProductSuitesCopy({ productName: productName.trim() || config.title, sellingPoints, language, fileIds: files.map(file => file.fileId) });
      if (typeof data.text !== 'string' || !data.text.trim()) throw new Error('empty copy');
      if (alive.current) setSellingPoints(data.text);
    } catch { if (alive.current) setError('AI 帮写暂不可用，请重试或手动填写卖点。'); }
    finally { if (alive.current) setCopying(false); }
  }
  async function matchModules() {
    if (locked || !copyAvailable || !files.length || !model) return;
    setPlanning(true); setError('');
    try {
      const { data } = await mediaPlanPostApiV1MediaProductSuitesPlan({ productName: productName.trim() || config.title, sellingPoints, language, fileIds: files.map(file => file.fileId) });
      const general = templates.find(item => item.id === 'general');
      if (!data.modules?.length || !general || data.modules.some(item => !general.modules?.some(module => module.id === item.module) || !Number.isInteger(item.count) || item.count < 1 || item.count > 4)) throw new Error('unsupported plan');
      if (!alive.current) return;
      const selection = { modules: data.modules.map(item => item.module), counts: Object.fromEntries(data.modules.map(item => [item.module, Math.min(item.count, model.maxCount)])) };
      if (templateId === 'general') { setSelectedModules(selection.modules); setCounts(selection.counts); }
      else { plannedSelection.current = selection; setTemplateId('general'); }
    } catch { if (alive.current) setError('智能匹配暂不可用，请重试或自定义配置。'); }
    finally { if (alive.current) setPlanning(false); }
  }
  async function prepareRetry(suite: Suite, item: Item) {
    if (locked || !suite.id || !suite.request || !item.task?.id || !terminal(item.task.status)) return;
    const run = ++retryRun.current; setRetryQuote(null); setQuotingRetry(item.module); setError('');
    try {
      const { data } = await mediaQuotePostApiV1MediaProductSuitesQuotes({ ...suite.request, modules: [item.module], options: suite.request.options ? { ...suite.request.options, moduleCounts: suite.request.options.moduleCounts?.filter(count => count.module === item.module) || [] } : null });
      if (!validPoints(data.points)) throw new Error('invalid quote');
      if (alive.current && run === retryRun.current) setRetryQuote({ suiteId: suite.id, module: item.module, sourceTaskId: item.task.id, points: data.points });
    } catch { if (alive.current && run === retryRun.current) setError('重做报价失败，请稍后重试。'); }
    finally { if (alive.current && run === retryRun.current) setQuotingRetry(''); }
  }

  const modulePicker = <SuiteModules counts={counts} maxCount={model?.maxCount || 1}
    required={tool === 'apparel-set' ? ['WHITE_BACKGROUND', 'VOLUME_3D', 'MANNEQUIN'] : tool === 'detail-page' && template?.version === 2 ? ['WHITE_BACKGROUND'] : []}
    onCountChange={tool === 'detail-page' || (tool === 'product-suite' && (model?.maxCount || 1) <= 1 && !Object.values(counts).some(count => count > 1))
      ? undefined : (module, count) => setCounts(current => ({ ...current, [module]: count }))}
    options={(template?.modules || []).map(item => ({ value: item.id, label: item.name || item.id, description: item.description || '' }))}
    selected={selectedModules} onChange={setSelectedModules} disabled={editingLocked} />;
  const generationSettings = <div className="suite-settings">
    <label>{t("图片模型")}<select value={modelId} disabled={editingLocked || !catalogReady} onChange={event => setModelId(event.target.value)}>{models.map(item => <option key={item.model} value={item.model || ''}>{item.displayName}</option>)}</select></label>
    <label>{t("画面比例")}<select value={aspectRatio} disabled={editingLocked} onChange={event => setAspectRatio(event.target.value)}>{model?.aspectRatios?.map(value => <option value={value} key={value}>{t(value)}</option>)}</select></label>
    <label>{t("分辨率")}<select value={resolution} disabled={editingLocked} onChange={event => setResolution(event.target.value)}>{model?.resolutions?.map(value => <option value={value} key={value}>{t(value)}</option>)}</select></label>
  </div>;
  const readiness = pending ? '' : !eligible ? (account ? accountStatusLabels[account.status] : '')
    : !catalogReady ? (catalogError ? '' : '正在读取模板与模型…')
    : !selectedModules.length ? '至少选择一种图片内容。'
    : !files.length ? '请先上传商品图片。'
    : !valid ? '请检查商品图片与生成设置。'
    : !currentQuote?.points && !currentQuote?.error ? '正在计算积分…' : '';
  const submission = <div className="suite-submission">
    <div className="suite-submission-notices" id="suite-readiness" aria-live="polite">
      {tool === 'product-suite' && readiness && <p role="status">{t(readiness)}</p>}
      {tool === 'product-suite' && account?.status === 'error' && <button type="button" onClick={() => void account.refresh()}>{t("刷新账户")}</button>}
      {draftError && <p role="alert">{t(draftError)}</p>}
      {catalogError && <p role="alert">{t(catalogError)}</p>}
      {catalogReady && (!models.length || !templates.length) && <p role="status">{t("暂无可用的套图模板或图片模型。")}</p>}
      {currentQuote?.error && <p role="alert">{t(currentQuote.error)}</p>}
      {(catalogError || currentQuote?.error) && <button type="button" disabled={locked} onClick={() => setRefresh(value => value + 1)}>{t("重新获取模板与报价")}</button>}
      {currentQuote?.points && !pending && balance.notice && <p role="status">{t(balance.notice)}</p>}
      {apiGenerationNotice(account, 'IMAGE') && <p role="status">{t(apiGenerationNotice(account, 'IMAGE'))}</p>}
      {error && <p role="alert">{t(error)}</p>}
    </div>
    <p className="suite-cost">{t("预计消耗 ")}<strong>{currentQuote?.points ? formatAmount(currentQuote.points) : '—'}</strong>{t(" 积分")}<span>{formatMessage("{0} 张图片", imageCount)}</span></p>
    <button className="suite-primary" type="submit" aria-describedby={tool === 'product-suite' ? 'suite-readiness' : undefined} disabled={submitting || !identityReady || (pending ? false : locked || !valid || !currentQuote?.points || !balance.affordable)}>{t(submitting ? '提交中…' : pending ? '使用原请求重试' : formatMessage("{0} · {1} 张", t(config.action), imageCount))}</button>
  </div>;
  const historyPanel = <section className="suite-history" data-inline={worksOpen} aria-label={t(tool === 'product-suite' ? '创作结果' : '生成记录')}>
      {tool !== 'product-suite' && <><div className="suite-history-heading"><h2>{t("生成记录")}</h2></div>
      <div className="suite-history-list">{history.map(suite => <button type="button" key={suite.id} aria-pressed={selectedId === suite.id} onClick={() => { setSelectedId(suite.id || ''); setRetryQuote(null); setQuotingRetry(''); retryRun.current++; }}>
        {suite.request?.productName}<small>{suite.createdAt ? new Date(suite.createdAt).toLocaleString(currentLanguage(), { hour12: false }) : ''} · {t(suiteLabel(suite.status))}</small>
      </button>)}</div></>}
      {historyBusy && <p role="status">{t("正在读取套图…")}</p>}
      {!historyBusy && !history.length && !historyError && <p>{t("暂无套图记录")}</p>}
      {historyError && <p role="alert">{t(historyError)}<button type="button" disabled={historyBusy} onClick={() => void loadHistory()}>{t("重新读取")}</button></p>}
      {visibleSuites.map(selectedSuite => <div className="suite-result-batch" key={selectedSuite.id}><div className="suite-result-heading">{tool !== 'product-suite' && <h3>{selectedSuite.request?.productName}</h3>}<span>{tool === 'product-suite' && selectedSuite.createdAt && <time dateTime={selectedSuite.createdAt}>{new Date(selectedSuite.createdAt).toLocaleString(currentLanguage(), { hour12: false })} · </time>}{t(suiteLabel(selectedSuite.status))}</span></div>
        {tool === 'detail-page' && <DetailPageExport key={selectedSuite.id} productName={selectedSuite.request?.productName || '详情页'}
          fileIds={(selectedSuite.items || []).flatMap(item => item.task?.status === 'SUCCEEDED' ? (item.task.results || []).flatMap(result => result.fileId ? [result.fileId] : []) : [])}
          disabled={selectedSuite.status !== 'SUCCEEDED' || !selectedSuite.request?.modules?.length || selectedSuite.items?.length !== selectedSuite.request.modules.length || !selectedSuite.items.every(item => item.task?.status === 'SUCCEEDED' && item.task.results?.[0]?.fileId)} />}
        <div className="suite-results-grid" style={tool === 'product-suite' ? { gridTemplateColumns: `repeat(${resultColumns}, minmax(0, 1fr))` } : undefined}>{(selectedSuite.items || []).map(item => {
          const task = item.task;
          if (!task?.id) return null;
          const confirmed = retryQuote?.suiteId === selectedSuite.id && retryQuote.module === item.module && retryQuote.sourceTaskId === task.id ? retryQuote : null;
          const retryBalance = generationBalance(account, confirmed?.points);
          const retryLabel = t(quotingRetry === item.module ? '报价中…' : confirmed ? formatMessage("确认重做{0} · {1} 积分", t(item.name), formatAmount(confirmed.points)) : formatMessage("重新生成{0}", t(item.name)));
          const retryButton = <button className="suite-result-retry" type="button" aria-label={retryLabel} disabled={locked || !terminal(task.status) || Boolean(quotingRetry) || Boolean(confirmed && !retryBalance.affordable)}
              onClick={() => confirmed && selectedSuite.request ? void send({ key: randomUUID(), request: selectedSuite.request, regeneration: { suiteId: confirmed.suiteId, module: confirmed.module, sourceTaskId: confirmed.sourceTaskId } }, true) : void prepareRetry(selectedSuite, item)}>
              {retryLabel}
            </button>;
          return <section className="suite-result-item" key={item.module} aria-label={item.name || item.module}>
            <h4>{t(item.name)}</h4>
            <MediaTaskCard key={task.id} entry={{ key: task.id, taskRef: task.id, snapshot: task }} showPromptAction={false} showMetadata={tool !== 'product-suite'} />
            {item.previousResult?.id && <details><summary>{t("上次成功结果")}</summary><MediaTaskCard key={item.previousResult.id} entry={{ key: item.previousResult.id, taskRef: item.previousResult.id, snapshot: item.previousResult }} showPromptAction={false} showMetadata={tool !== 'product-suite'} /></details>}
            {tool !== 'product-suite' && retryButton}{confirmed && retryBalance.notice && <p role="status">{t(retryBalance.notice)}</p>}
          </section>;
        })}</div></div>)}
      {historyCursor && <button type="button" disabled={historyBusy} onClick={() => void loadHistory(historyCursor)}>{t("加载更多")}</button>}
    </section>;
  return <section className="product-suite" data-tool={tool} data-workbench={tool === 'product-suite' || undefined} data-view={worksOpen ? 'works' : 'examples'} data-view-changed={viewChanged}>
    <div className="suite-tools-bar" data-expanded={toolsOpen}>
      {backLink && <nav className="suite-toolbar-back" aria-label={t("返回工作台")}>{backLink}</nav>}
      <div className="suite-tools-group" ref={toolsGroup}>
      <button ref={toolsToggle} className="suite-tools-toggle" type="button" aria-current="page" aria-expanded={toolsOpen} aria-controls="suite-tools-navigation" aria-label={t(toolsOpen ? '收起电商工具' : '展开电商工具')} title={t(toolsOpen ? '收起电商工具' : '展开电商工具')} onClick={() => setToolsOpen(open => !open)}>
        <CommerceToolIcon tool={tool} />{t(config.title)}
        <svg className="suite-tools-chevron" aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="m6 9 6 6 6-6" /></svg>
      </button>
      <div className="suite-tools-disclosure" id="suite-tools-navigation" aria-hidden={!toolsOpen} inert={!toolsOpen}>
        <div className="suite-tools-clip">
          <CommerceToolMenu current={tool} onClose={() => { setToolsOpen(false); toolsToggle.current?.focus(); }} />
        </div>
      </div>
      </div>
      <div className="suite-view-switch" data-view={worksOpen ? 'works' : 'examples'} role="group" aria-label={t('视图切换')}>
        <button className="suite-view-option" type="button" aria-pressed={!worksOpen} onClick={() => switchView(false)}>
          <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M12 5c-2-2-6-2-9-1v15c3-1 7-1 9 1 2-2 6-2 9-1V4c-3-1-7-1-9 1Zm0 0v15" /></svg>
          {t('功能示例')}
        </button>
        <button className="suite-view-option" data-view="works" type="button" aria-pressed={worksOpen} onClick={() => switchView(true)}>
          <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="m9 3 1.7 5.3L16 10l-5.3 1.7L9 17l-1.7-5.3L2 10l5.3-1.7L9 3ZM19 2v6m-3-3h6M19 14v8m-4-4h8" /></svg>
          {t('创作结果')}
        </button>
      </div>
      {tool === 'product-suite' && worksOpen && <>
        <div className="suite-result-scope" role="group" aria-label={t("创作结果分组")}>
          <button type="button" aria-pressed={resultScope === 'function'} onClick={() => setResultScope('function')}>
            <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round"><path d="m13 2-9 12h7l-1 8 10-13h-7l1-7Z" /></svg>{t("按当前功能")}
          </button>
          <button type="button" aria-pressed={resultScope === 'project'} onClick={() => setResultScope('project')}>
            <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round"><path d="M3 7V5a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z" /></svg>{t("按项目")}
          </button>
        </div>
        <div className="suite-result-controls">
        {resultScope === 'project' && <SuiteProjectSelect value={selectedId}
          projects={history.map(suite => ({ id: suite.id || '', name: suite.request?.productName || t(config.title), date: suite.createdAt ? new Date(suite.createdAt).toLocaleString(currentLanguage(), { hour12: false }) : '' }))}
          onChange={id => { setSelectedId(id); setRetryQuote(null); setQuotingRetry(''); retryRun.current++; }} />}
        <SuiteResultLayout columns={resultColumns} gridColumns={lastGridColumns.current}
          onColumnsChange={columns => { setResultColumns(columns); if (columns > 1) lastGridColumns.current = columns; }} />
        </div>
      </>}
    </div>
    <form onSubmit={submit}>
      <div className="suite-workspace">
        {tool !== 'product-suite' && (worksOpen ? historyPanel : <ToolExamples tool={tool} />)}
        {tool === 'product-suite' && <div className="suite-planning-column">
          {worksOpen ? historyPanel : <ToolExamples tool={tool} />}
        </div>}
        <section className="suite-config" aria-label={t("套图配置")}><div className="suite-controls">
          {tool === 'product-suite' && <h2 className="suite-config-heading">{t("商品信息")}</h2>}
          <ProductReferences files={files} onChange={next => {
            setFiles(next);
            if (files.length && !next.length) { setProductName(''); setSellingPoints(''); setError(''); }
          }} label={t(config.productLabel)} limit={productLimit} disabled={uploadLocked} onBusy={setUploading} showGuidance={tool === 'product-suite'} />
          {tool === 'model-wear' && <><fieldset className="suite-option-group" disabled={uploadLocked || !identityReady}><legend>{t("模特来源")}</legend>
            {[['ai', 'AI 生成'], ['upload', '自行上传']].map(([value, label]) => <label className="suite-option-choice" key={value}><input type="radio" name="model-source" checked={modelSource === value} onChange={() => setModelSource(value)} />{t(label)}</label>)}</fieldset>
            {modelSource === 'ai' && <ModelPortraitPicker owner={owner} disabled={uploadLocked || !identityReady} onSelect={reference => setReferenceFiles([reference])} />}</>}
          {config.referenceLabel && (tool !== 'model-wear' || modelSource === 'upload' || referenceFiles.length > 0) && <ProductReferences files={referenceFiles} onChange={setReferenceFiles} label={t(config.referenceLabel)} limit={model ? 1 : 0} disabled={uploadLocked} onBusy={setReferenceUploading} />}
          <div className="suite-settings suite-template-settings">
            {config.templates.length > 1 && <label>{t("套图模板")}<select value={templateId} disabled={editingLocked || !catalogReady} onChange={event => setTemplateId(event.target.value)}>{templates.map(item => <option key={item.id} value={item.id || ''}>{t(item.name)}</option>)}</select></label>}
            <label>{t("文案语言")}<select value={language} disabled={editingLocked} onChange={event => setLanguage(event.target.value)}>{commerceLanguages.map(value => <option key={value} value={value}>{t(value === 'English' ? '英文' : value)}</option>)}</select></label>
          </div>
          {['product-suite', 'detail-page', 'image-replica'].includes(tool) && <label>{t("商品平台")}<select value={platform} disabled={editingLocked} onChange={event => setPlatform(event.target.value)}>{commercePlatforms.map(value => <option value={value} key={value}>{t(value)}</option>)}</select></label>}
          <ToolParameters tool={tool} disabled={locked} replicationMode={replicationMode} onReplicationMode={setReplicationMode} enhancements={enhancements} onEnhancements={setEnhancements} garmentView={garmentView} onGarmentView={setGarmentView} white3d={white3d} onWhite3d={setWhite3d} whiteMannequin={whiteMannequin} onWhiteMannequin={setWhiteMannequin} />
          <label>{t("商品名称")}<input placeholder={t("选填")} value={productName} maxLength={120} disabled={editingLocked} onChange={event => setProductName(event.target.value)} /></label>
          <div className="suite-field"><div className="suite-field-head"><label htmlFor="suite-selling-points">{t(config.requirements)}</label>{config.copy && <button type="button" disabled={locked || !copyAvailable || !files.length} onClick={() => void writeCopy()}>{t(copying ? '帮写中…' : 'AI 帮写')}</button>}</div>
            <textarea id="suite-selling-points" value={sellingPoints} maxLength={3000} disabled={editingLocked} onChange={event => setSellingPoints(event.target.value)} />
            {tool === 'product-suite' && <small className="suite-character-count">{sellingPoints.length} / 3000</small>}</div>
          {tool === 'product-suite' ? <details className="suite-generation-settings" open={settingsOpen} onToggle={event => setSettingsOpen(event.currentTarget.open)}><summary>{t("生成设置")}<span>{aspectRatio} · {resolution}</span></summary>{generationSettings}</details> : generationSettings}
          {tool === 'product-suite' && <section className="suite-composition" aria-label={t("图片内容配置")}>
            <div className="suite-structure-actions"><span>{t("自定义配置")}</span><button type="button" disabled={locked || !copyAvailable || !files.length} onClick={() => void matchModules()}>{t(planning ? '匹配中…' : '智能匹配')}</button></div>
            {modulePicker}
          </section>}
          {['detail-page', 'apparel-set'].includes(tool) && modulePicker}
        </div>
          {submission}
        </section>
      </div>
    </form>
  </section>;
}
