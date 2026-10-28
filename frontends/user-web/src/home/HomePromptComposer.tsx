import { useCallback, useEffect, useRef, useState, type FormEvent, type RefObject } from 'react';
import { Link } from 'react-router-dom';
import { createPortal } from 'react-dom';
import { ComposerDialog, ComposerIcon, ComposerModeMenu, sortedResolutions } from '../creator/ComposerControls';
import { ComposerParameterFields } from '../creator/ComposerParameterFields';
import { groupImageModels } from '../creator/image-model-groups';
import '../creator/video-settings.css';
import '../creator/image-settings.css';
import './home-prompt-controls.css';
import { mediaOptionsGetApiV1MediaGenerationOptions, type ResponseMediaApplicationCatalogGenerationCatalogServiceOption as ModelOption } from '../api';
import type { CanvasGenerationSettings } from '../canvas/model';
import { t, formatMessage } from '../i18n';
import { useUserSession } from '../userSession';
import { homeModelMatches, type HomeGenerationMode } from './home-entry';
import { HomeMaterialPicker, homeMaterialIssues, type HomeMaterials } from './HomeMaterialPicker';

type Option = ModelOption & { type: 'IMAGE' | 'VIDEO'; model: string; displayName: string; aspectRatios: string[]; resolutions: string[] };
export function homeModelUsable(option: ModelOption): option is Option {
  return ['IMAGE', 'VIDEO'].includes(option.type) && Boolean(option.model && option.displayName && option.aspectRatios?.length && option.resolutions?.length)
    && Number.isInteger(option.maxCount) && option.maxCount > 0 && (option.type === 'IMAGE'
      || Number.isInteger(option.minDurationSeconds) && option.minDurationSeconds > 0 && Number.isInteger(option.maxDurationSeconds)
      && option.maxDurationSeconds >= option.minDurationSeconds && Number.isInteger(option.durationStepSeconds) && option.durationStepSeconds > 0);
}
export function homeModelSettings(current: CanvasGenerationSettings | null, model: Option): CanvasGenerationSettings {
  const sameType = current?.type === model.type ? current : null;
  const duration = sameType?.durationSeconds ?? 5;
  return { type: model.type, model: model.model,
    aspectRatio: sameType && model.aspectRatios.includes(sameType.aspectRatio) ? sameType.aspectRatio : model.aspectRatios[0],
    resolution: sameType && model.resolutions.includes(sameType.resolution) ? sameType.resolution : model.resolutions[0],
    count: model.type === 'VIDEO' ? 1 : Number.isInteger(sameType?.count) ? Math.min(Math.max(sameType!.count, 1), model.maxCount) : 1,
    durationSeconds: model.type === 'IMAGE' ? 0 : Number.isInteger(duration) && duration >= model.minDurationSeconds
      && duration <= model.maxDurationSeconds && (duration - model.minDurationSeconds) % model.durationStepSeconds === 0 ? duration : model.minDurationSeconds };
}

export function HomePromptComposer({ inputRef, prompt, kind, modelName, error, choices, onPrompt, onModel, onSubmit }: {
  inputRef: RefObject<HTMLTextAreaElement | null>; prompt: string; kind: 'image' | 'video'; modelName: string; error: string;
  choices: readonly { name: string; kind: 'image' | 'video' }[];
  onPrompt(value: string): void; onModel(kind: 'image' | 'video', name: string): void;
  onSubmit(event: FormEvent, settings: CanvasGenerationSettings | null, mode: HomeGenerationMode, materials: HomeMaterials): void;
}) {
  const account = useUserSession();
  const root = useRef<HTMLFormElement>(null);
  const hovered = useRef(false), dismissed = useRef(false), restoring = useRef(false);
  const [open, setOpen] = useState(false), [activated, setActivated] = useState(false);
  const [options, setOptions] = useState<Option[]>([]), [preferences, setPreferences] = useState<CanvasGenerationSettings | null>(null);
  const [catalog, setCatalog] = useState<'loading' | 'ready' | 'failed'>('loading'), [refresh, setRefresh] = useState(0);
  const [parameterError, setParameterError] = useState('');
  const [imageMode, setImageMode] = useState<'auto' | 'text' | 'reference'>('auto');
  const [videoMode, setVideoMode] = useState<'text' | 'omni' | 'frames'>('text');
  const [materialDrafts, setMaterialDrafts] = useState<{ owner: string | null; image: HomeMaterials; video: HomeMaterials }>({ owner: account.userId ?? null, image: [], video: [null, null] });
  const [materialBusy, setMaterialBusy] = useState(false), [queueBusy, setQueueBusy] = useState(false);
  const [materialFeedback, setMaterialFeedback] = useState(false);
  const busy = materialBusy || queueBusy;
  const materialDialog = useRef(false);
  const onMaterialDialog = useCallback((value: boolean) => {
    materialDialog.current = value;
    if (value) { dismissed.current = false; setOpen(true); }
  }, []);
  const [layer, setLayer] = useState<'models' | 'settings' | null>(null);
  const reveal = () => { if (!dismissed.current && !restoring.current) { setOpen(true); setActivated(true); } };
  const close = () => {
    dismissed.current = true; setOpen(false); restoring.current = true;
    inputRef.current?.focus({ preventScroll: true }); restoring.current = false;
  };
  useEffect(() => {
    setPreferences(null); setOptions([]);
    if (!activated || !account.userId) return;
    let cancelled = false; setCatalog('loading');
    void mediaOptionsGetApiV1MediaGenerationOptions().then(response => {
      if (!cancelled) { setOptions((response.data.models ?? []).filter(homeModelUsable)); setCatalog('ready'); }
    }, () => { if (!cancelled) setCatalog('failed'); });
    return () => { cancelled = true; };
  }, [activated, account.userId, refresh]);
  useEffect(() => { setImageMode('auto'); setVideoMode('text'); setMaterialDrafts({ owner: account.userId ?? null, image: [], video: [null, null] }); }, [account.userId]);
  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => {
      if (!busy && !materialFeedback && !layer && !materialDialog.current && !root.current?.contains(event.target as Node)) { setOpen(false); dismissed.current = false; }
    };
    document.addEventListener('pointerdown', outside);
    return () => document.removeEventListener('pointerdown', outside);
  }, [open, layer, busy, materialFeedback]);
  const available = options.filter(option => option.type === kind.toUpperCase());
  const model = available.find(option => option.model === preferences?.model && homeModelMatches(option.displayName, modelName)) ?? available.find(option => homeModelMatches(option.displayName, modelName));
  const groups = kind === 'image' ? groupImageModels(available) : available.map(option => ({ key: option.model, displayName: option.displayName, variants: [option] }));
  const modelGroup = groups.find(group => group.variants.some(option => option.model === model?.model));
  const resolutions = sortedResolutions([...new Set(modelGroup?.variants.flatMap(option => option.resolutions) ?? model?.resolutions ?? [])]);
  const settings = model ? preferences?.model === model.model ? preferences : homeModelSettings(preferences, model) : null;
  const mode = kind === 'image' ? imageMode : videoMode;
  const owner = account.authenticated || account.status === 'loading' ? account.userId ?? null : null;
  const materialsEnabled = mode !== 'text';
  const materials: HomeMaterials = materialDrafts.owner === owner && materialsEnabled ? materialDrafts[kind] : kind === 'video' ? [null, null] : [];
  const onMaterials = useCallback((value: HomeMaterials | ((current: HomeMaterials) => HomeMaterials)) => setMaterialDrafts(current => {
    const draft = current.owner === owner ? current : { owner, image: [], video: [null, null] };
    return { ...draft, [kind]: typeof value === 'function' ? value(draft[kind]) : value };
  }), [owner, kind]);
  const reviewModel = kind === 'video' && mode === 'omni' && model?.omniReference ? model.model : options.find(option => option.type === 'VIDEO' && option.omniReference)?.model ?? '';
  const materialIssues = homeMaterialIssues(kind, mode, materials, model, reviewModel);
  const materialIssue = materialIssues.find(Boolean) || (mode === 'reference' && !materials.some(Boolean) ? '请添加参考图片'
    : mode === 'frames' && !materials[0] ? '请先添加首帧' : mode === 'omni' && !materials.some(Boolean) ? '请添加参考素材' : '');
  const keepMaterialsVisible = busy || materialFeedback || materials.some(Boolean);
  useEffect(() => { if (busy || materialFeedback) { dismissed.current = false; setOpen(true); } }, [busy, materialFeedback]);
  const parametersValid = !settings || Boolean(model && Number.isInteger(settings.count) && settings.count >= 1 && settings.count <= model.maxCount
    && (kind === 'image' || Number.isInteger(settings.durationSeconds) && settings.durationSeconds >= model.minDurationSeconds
      && settings.durationSeconds <= model.maxDurationSeconds && (settings.durationSeconds - model.minDurationSeconds) % model.durationStepSeconds === 0));
  const summaryLabel = settings ? kind === 'video' ? formatMessage('视频参数摘要：{0}，{1}，{2}秒', settings.aspectRatio, settings.resolution, settings.durationSeconds) : formatMessage('图片参数摘要：{0}，{1}，{2}张', settings.aspectRatio, settings.resolution, settings.count) : t(kind === 'video' ? '视频参数摘要' : '图片参数摘要');
  const selectType = (next: 'image' | 'video') => {
    setParameterError('');
    const actual = options.find(option => option.type === next.toUpperCase());
    onModel(next, actual?.displayName ?? choices.find(choice => choice.kind === next)!.name);
  };
  return <div className="ym-prompt-shell">
    <form ref={root} className={`ym-prompt-bar${prompt.trim() ? ' ym-has-prompt' : ''}`} data-expanded={open} data-materials={materialsEnabled} noValidate
      onSubmit={event => {
        dismissed.current = false; setOpen(true); setActivated(true);
        if (busy) { event.preventDefault(); return; }
        if (materialIssue) { event.preventDefault(); setParameterError(materialIssue); return; }
        if (!parametersValid) { event.preventDefault(); setParameterError('请按当前模型的范围选择数量或时长。'); setLayer('settings'); return; }
        onSubmit(event, settings, mode, materials);
      }}
      onPointerEnter={event => { if (event.pointerType !== 'touch') { hovered.current = true; reveal(); } }}
      onPointerLeave={() => { hovered.current = false; dismissed.current = false; if (!keepMaterialsVisible && !layer && !materialDialog.current && !root.current?.contains(document.activeElement)) setOpen(false); }}
      onFocusCapture={reveal} onPointerDown={() => { dismissed.current = false; reveal(); }}
      onBlur={event => { if (!keepMaterialsVisible && !layer && !materialDialog.current && !event.currentTarget.contains(event.relatedTarget)) { dismissed.current = false; if (!hovered.current) setOpen(false); } }}
      onKeyDown={event => { if (event.key === 'Escape' && open && !materialDialog.current && !busy) { event.preventDefault(); event.stopPropagation(); close(); } }}>
      <div className="ym-prompt-header" inert={!open} aria-hidden={!open}>
        <div className="ym-prompt-types" role="group" aria-label={t('生成类型')}>
          {(['video', 'image'] as const).map(type => <button type="button" key={type} disabled={busy} aria-pressed={kind === type} onClick={() => selectType(type)}>{t(type === 'video' ? '视频' : '图片')}</button>)}
        </div>
        <button className="ym-prompt-close" type="button" disabled={busy} aria-label={t('收起创作参数')} onClick={close}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 14 6-6 6 6" /></svg></button>
      </div>
      <div className="ym-prompt-input-row">
        <svg className="ym-prompt-spark" aria-hidden="true"><use href="#ym-i-spark" /></svg>
        <label className="ym-sr-only" htmlFor="ym-prompt">{t('创作描述')}</label>
        <HomeMaterialPicker key={`${owner}:${kind}`} owner={owner} kind={kind} mode={mode} model={model} reviewModel={reviewModel} materials={materials}
          enabled={materialsEnabled} disabled={!owner || account.status === 'loading' || catalog !== 'ready'} onMaterials={value => { onMaterials(value); setParameterError(''); setOpen(true); }}
          onBusyChange={setMaterialBusy} onQueueBusyChange={setQueueBusy} onDialogOpenChange={onMaterialDialog} onFeedbackChange={setMaterialFeedback}>
        <textarea ref={inputRef} id="ym-prompt" value={prompt} onChange={event => onPrompt(event.target.value)} placeholder={t('描述你的想法，创造你的影像...')}
          rows={1} maxLength={2000} required aria-invalid={Boolean(error && !prompt.trim())} aria-describedby={error ? 'ym-entry-error' : undefined}
          aria-controls="ym-prompt-parameters" />
        </HomeMaterialPicker>
        <button className="ym-submit-prompt" type="submit" disabled={busy} aria-label={t('开始创作')} title={t('开始创作')}><svg aria-hidden="true"><use href="#ym-i-arrow" /></svg></button>
      </div>
      <div id="ym-prompt-parameters" className="ym-prompt-parameters" inert={!open} aria-hidden={!open}>
        <div className="ym-home-toolbar composer-toolbar" role="group" aria-label={t(kind === 'video' ? '视频创作工具栏' : '图片创作工具栏')}>
          {kind === 'image' ? <ComposerModeMenu<typeof imageMode> key="image" label="图片生成模式" icon="image" value={imageMode} disabled={busy} onChange={value => { setImageMode(value); setParameterError(''); }}
            options={[{ value: 'auto', label: '智能图片' }, { value: 'text', label: '文生图片' },
              ...(!model || model.maxReferenceImages > 0 || imageMode === 'reference' ? [{ value: 'reference' as const, label: '图片参考' }] : [])]} />
            : <ComposerModeMenu<typeof videoMode> key="video" label="视频生成模式" icon="video" value={videoMode} disabled={busy} onChange={value => { setVideoMode(value); setParameterError(''); }}
              options={[{ value: 'text', label: '文生视频' },
                ...(!model || model.omniReference || videoMode === 'omni' ? [{ value: 'omni' as const, label: '全能参考' }] : []),
                ...(!model || model.maxReferenceImages > 0 || videoMode === 'frames' ? [{ value: 'frames' as const, label: model?.maxReferenceImages === 1 ? '首帧' : '首尾帧' }] : [])]} />}
          <button type="button" className="composer-model" disabled={busy} aria-label={formatMessage(kind === 'video' ? '选择视频模型：{0}' : '选择图片模型：{0}', modelName)} aria-haspopup="dialog" onClick={() => setLayer('models')}><ComposerIcon name="model" /><span>{modelName}</span><ComposerIcon name="chevron" /></button>
          <button type="button" className="composer-summary" aria-label={summaryLabel} aria-haspopup="dialog" disabled={!settings || busy} onClick={() => setLayer('settings')}><ComposerIcon name="ratio" />{settings ? <><span>{settings.aspectRatio}</span><span>{settings.resolution}</span><span>{kind === 'video' ? `${Number.isNaN(settings.durationSeconds) ? '—' : settings.durationSeconds}s` : `${Number.isNaN(settings.count) ? '—' : settings.count}${t('张')}`}</span></> : t(kind === 'video' ? '视频参数' : '图片参数')}<ComposerIcon name="chevron" /></button>
        </div>
        {(error || parameterError || !account.authenticated || catalog !== 'ready' || !model) && <div className="ym-prompt-note" role={error || parameterError ? undefined : 'status'}>{error ? <p className="ym-entry-error" id="ym-entry-error" role="alert">{t(error)}</p> : parameterError ? <p className="ym-entry-error" id="ym-parameter-error" role="alert">{t(parameterError)}</p> : !account.authenticated ? <Link to="/user/login">{t('登录后选择更多参数')}</Link> : catalog === 'loading' ? t('正在加载模型参数…') : catalog === 'failed' ? <><span>{t('参数加载失败')}</span><button type="button" onClick={() => setRefresh(value => value + 1)}>{t('重试')}</button></> : t('该模型暂不可用，可在工作台选择其他模型')}</div>}
      </div>
    </form>
    {layer && createPortal(<div className="composer-popover-scope"><ComposerDialog anchored title={layer === 'settings' ? kind === 'video' ? '视频设置' : '图片设置' : kind === 'video' ? '选择视频模型' : '选择图片模型'} closeLabel={layer === 'settings' ? kind === 'video' ? '关闭视频设置' : '关闭图片设置' : '关闭模型选择'} onClose={() => setLayer(null)} onReset={layer === 'settings' && model ? () => { setPreferences(homeModelSettings(null, model)); setParameterError(''); } : undefined}>
      {layer === 'models' ? <div className="video-model-list">{available.length ? groups.map(group => { const item = group.variants.find(option => option.model === model?.model) ?? group.variants.find(option => settings && option.resolutions.includes(settings.resolution)) ?? group.variants[0]; return <button type="button" key={group.key} aria-label={group.displayName} aria-pressed={item.model === model?.model} onClick={() => { setPreferences(homeModelSettings(settings, item)); onModel(kind, item.displayName); setParameterError(''); setLayer(null); }}><span className="composer-model-icon"><ComposerIcon name="model" /></span><span className="composer-model-copy"><span>{group.displayName}</span><small>{kind === 'video' ? formatMessage('{0} · {1}–{2}秒', sortedResolutions([...new Set(group.variants.flatMap(option => option.resolutions))]).join(' / '), item.minDurationSeconds, item.maxDurationSeconds) : formatMessage('{0} · 最多 {1} 张参考图片', sortedResolutions([...new Set(group.variants.flatMap(option => option.resolutions))]).join(' / '), item.maxReferenceImages)}</small></span>{item.model === model?.model && <span className="composer-model-check" aria-hidden="true">✓</span>}</button>; }) : choices.filter(choice => choice.kind === kind).map(choice => <button type="button" key={choice.name} aria-label={choice.name} aria-pressed={choice.name === modelName} onClick={() => { onModel(kind, choice.name); setLayer(null); }}><span className="composer-model-icon"><ComposerIcon name="model" /></span><span className="composer-model-copy"><span>{choice.name}</span><small>{t(account.authenticated ? '该模型暂不可用，可在工作台选择其他模型' : '登录后选择更多参数')}</small></span>{choice.name === modelName && <span className="composer-model-check" aria-hidden="true">✓</span>}</button>)}</div>
        : model && settings && <><ComposerParameterFields model={model} selection={settings} resolutions={resolutions} onResolution={resolution => {
          const next = model.resolutions.includes(resolution) ? model : modelGroup?.variants.find(option => option.resolutions.includes(resolution));
          if (next) { setPreferences(homeModelSettings({ ...settings, resolution }, next)); onModel(kind, next.displayName); setParameterError(''); }
        }} onChange={value => { setPreferences(value); setParameterError(''); }} />{!parametersValid && <p role="alert">{t('请按当前模型的范围选择数量或时长。')}</p>}</>}
    </ComposerDialog></div>, document.body)}
  </div>;
}
