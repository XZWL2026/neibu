import { t, formatMessage, useLanguage } from '../i18n';
import "./drag.css";
import { apiGenerationNotice, useOptionalUserSession } from "../userSession";
import { createPortal } from "react-dom";
import { ComposerDialog, ComposerIcon, sortedResolutions } from "../creator/ComposerControls";
import { MaterialPrompt } from "../creator/MaterialPrompt";
import { typedMaterialLabel, type ComposerMaterial } from "../creator/ComposerMaterials";
import { formatAmount } from '../../../shared/formatAmount';
import { memo, useEffect, useRef, useState } from "react";
import { CanvasIcon } from "./CanvasIcon";
import { canSubmitGenerationNode } from "./document";
import type { CanvasAssetReference, ImageCanvasNode, CanvasGenerationSettings, CanvasNode, CanvasTaskState, GenerationCanvasNode, CanvasEdge } from "./model";
import { depthResolutions, type collectCanvasInputs, type CanvasGenerationOption } from "./media-task-port";
import { copyGenerationDefaults } from "./generation-defaults";
import { isSafeCanvasPreviewRef } from "./preview-ref";

export type NodeQuoteState =
  | { state: "idle" | "loading" }
  | { state: "ready"; points: string; localDepth?: boolean }
  | { state: "failed"; message: string };

export type CanvasFlowData = Record<string, unknown> & {
  node: CanvasNode;
  selected: boolean;
  suppressComposer?: boolean;
  outputs?: ImageCanvasNode[];
  inputs?: ReturnType<typeof collectCanvasInputs>;
  onCreateDepth?(sourceId: string): void;
  onRemoveReference?(sourceId: string): void;
  onUploadMedia?(id: string): void;
  onStartReferenceGeneration?(id: string, kind: "IMAGE" | "VIDEO" | "FIRST_LAST_FRAME"): void;
  onGenerationPromptChange?(id: string, text: string, bindings?: GenerationCanvasNode['promptBindings']): void;
  onSelectOutput?(id: string, outputId: string): void;
  onUseOutput?(id: string, outputId: string, type: 'IMAGE' | 'VIDEO'): void;
  onChangeReference?(edgeId: string, patch: Pick<CanvasEdge, 'outputId' | 'role'>): void;
  canSubmit: boolean;
  generationOptions: readonly CanvasGenerationOption[];
  generationOptionsStatus: "idle" | "loading" | "ready" | "failed";
  quote: NodeQuoteState;
  onGenerationSettingsChange(id: string, settings: CanvasGenerationSettings): void;
  onTextChange(id: string, text: string): void;
  onSubmitGeneration(id: string): void;
  onReadGeneration(id: string): void;
  onSelect(id: string, additive: boolean): void;
  onKeyboardConnect(id: string): void;
  keyboardSourceId: string | null;
};

const connectionLabel = (data: CanvasFlowData, kind: string) => data.keyboardSourceId
  ? `把${kind}节点连接到所选起点`
  : `把${kind}节点设为连接起点`;

const nodeKindLabel: Record<CanvasNode["kind"], string> = {
  text: "文本节点",
  image: "图片节点",
  video: "视频节点",
  audio: "音频节点",
  generation: "生成节点",
};

const taskLabels: Record<CanvasTaskState, string> = {
  idle: "待生成", submitting: "正在提交", accepted: "排队中", working: "正在生成",
  succeeded: "生成完成", failed: "生成失败", partial_success: "部分完成",
  timed_out: "任务超时", refund_pending: "退款处理中",
};

function CanvasMediaPreview({ asset, onDepth, showPreviewButton = true, showCaption = true }: { asset: CanvasAssetReference; onDepth?: () => void; showPreviewButton?: boolean; showCaption?: boolean }) {
  useLanguage();
  const [previewOpen, setPreviewOpen] = useState(false);
  const previewDialog = useRef<HTMLDialogElement>(null);
  const safePreview = isSafeCanvasPreviewRef(asset.previewRef);
  const reviewing = asset.reviewStatus === "SUBMITTING" || asset.reviewStatus === "PROCESSING";
  const reviewLabel = asset.reviewStatus ? { SUBMITTING: "审核中…", PROCESSING: "审核中…", ACTIVE: "审核通过", FAILED: "审核未通过", UNCERTAIN: "审核提交待确认", UNAVAILABLE: "原文件已保留，审核暂不适用" }[asset.reviewStatus] : asset.reviewPending ? "待提交审核" : null;
  return <div className="canvas-inline-result nowheel nopan">
    <div className="canvas-media-preview" data-reviewing={reviewing}>
    {safePreview
      ? asset.kind === "audio"
        ? <audio className="nodrag" src={asset.previewRef} aria-label={asset.alt} controls preload="metadata" />
        : asset.kind === "video"
        ? <video className="nodrag" src={asset.previewRef} aria-label={asset.alt} controls preload="metadata" />
        : <img draggable={false} src={asset.previewRef} alt={asset.alt} />
      : <p role="status">{t("预览不可用")}</p>}
    {reviewing && <span className="canvas-review-overlay" role="status" aria-label={formatMessage("{0} 审核状态", asset.alt)}>{t(reviewLabel)}</span>}
    {safePreview && asset.kind === "video" && <div className="canvas-video-drag-area" title={t("拖动视频节点")} aria-hidden="true" />}
    </div>
    {!reviewing && reviewLabel && <small role="status" aria-label={formatMessage("{0} 审核状态", asset.alt)}>{t(reviewLabel)}</small>}
    {showCaption && <span className="canvas-asset-caption">{asset.alt}</span>}
    {asset.kind === "video" && onDepth && <button type="button" className="nodrag" onClick={onDepth}>{t("生成深度白模")}</button>}
    {showPreviewButton && safePreview && asset.kind !== "audio" && <>
      <button type="button" className="canvas-preview-button nodrag nopan" aria-label={t("放大预览")} onClick={() => { setPreviewOpen(true); previewDialog.current?.showModal(); }}><CanvasIcon name="expand" />{t("放大预览")}</button>
      <dialog className="canvas-result-dialog nodrag nopan nowheel" ref={previewDialog} aria-label={t("结果预览")} onClose={() => setPreviewOpen(false)}>
        <button type="button" autoFocus onClick={() => previewDialog.current?.close()}>{t("关闭预览")}</button>
        {previewOpen && (asset.kind === "video"
          ? <video src={asset.previewRef} aria-label={asset.alt} controls preload="metadata" />
          : <img src={asset.previewRef} alt={asset.alt} />)}
      </dialog>
    </>}
  </div>;
}

export function CanvasNodes({ data }: { data: CanvasFlowData }) {
  useLanguage();
  const [formatOpen, setFormatOpen] = useState(false);
  const [settingsPanel, setSettingsPanel] = useState<"type" | "model" | "duration" | null>(null);
  const [viewRun, setViewRun] = useState('latest');
  useEffect(() => {
    if (data.suppressComposer) { setFormatOpen(false); setSettingsPanel(null); }
  }, [data.suppressComposer]);
  const nodeClassName = `canvas-node canvas-${data.node.kind}-node`;
  const selectionDescriptionId = `${data.node.id}-selection-state`;

  const uploadedVisual = (data.node.kind === "image" || data.node.kind === "video") && !data.node.sourceTaskRef;
  const generation = data.node.kind === "generation" ? data.node : null;
  const pendingBindings = useRef<NonNullable<GenerationCanvasNode['promptBindings']>>([]);
  pendingBindings.current = generation?.promptBindings ?? [];
  const viewedRun = viewRun === 'latest' ? generation?.runs?.at(-1) : generation?.runs?.find(run => run.id === viewRun || run.taskRef === viewRun);
  const outputTask = viewedRun ? viewedRun.taskRef : viewRun === 'latest' ? generation?.taskRef ?? data.outputs?.at(-1)?.sourceTaskRef : viewRun;
  const visibleOutputs = data.outputs?.filter(output => output.sourceTaskRef === outputTask) ?? [];
  const settings = generation?.settings;
  const apiNotice = apiGenerationNotice(useOptionalUserSession(), settings?.type ?? "IMAGE");
  const omniReference = settings?.referenceMode === "OMNI" || !settings?.referenceMode && Boolean(data.inputs?.media.some(item => item.asset.kind !== "image" || item.asset.reviewId || item.asset.reviewPending));
  const depth = settings?.operation === "DEPTH";
  const generationEntry = !depth && generation?.taskState === 'idle'
    && !data.outputs?.length
    && !data.inputs?.texts.length && !data.inputs?.media.length && !data.inputs?.pending.length;
  const startReference = (kind: "IMAGE" | "VIDEO" | "FIRST_LAST_FRAME") => {
    data.onStartReferenceGeneration?.(data.node.id, kind);
  };
  const depthEnabled = data.canSubmit && data.generationOptions?.some(option => option.operation === "DEPTH");
  const createDepth = (id: string, asset: CanvasAssetReference) => depthEnabled && data.onCreateDepth && asset.kind === "video" && /^[A-Za-z0-9_-]{1,128}$/.test(asset.assetRef)
    ? () => data.onCreateDepth?.(id) : undefined;
  const options = data.generationOptions?.filter((option) => option.type === settings?.type && (option.operation ?? "GENERATION") === (settings?.operation ?? "GENERATION")) ?? [];
  const option = options.find((item) => item.model === settings?.model);
  const changeSettings = (patch: Partial<CanvasGenerationSettings>) => {
    if (settings) data.onGenerationSettingsChange(data.node.id, { ...settings, ...patch });
  };

  const removeReference = (sourceId: string, label: string) => data.onRemoveReference && <button type="button" className="canvas-reference-remove" aria-label={formatMessage("移除参考：{0}", label)} onClick={() => data.onRemoveReference?.(sourceId)}>×</button>;
  const materials: ComposerMaterial[] = (data.inputs?.media ?? []).map(({ asset }) => ({
    fileId: asset.assetRef, name: asset.alt, preview: isSafeCanvasPreviewRef(asset.previewRef) ? asset.previewRef : '', kind: asset.kind.toUpperCase() as ComposerMaterial['kind'],
  }));
  const materialLabel = (index: number) => settings?.type !== 'VIDEO' ? `图${index + 1}`
    : omniReference ? typedMaterialLabel(materials, index) : index === 0 ? '首帧图片' : '尾帧图片';
  const mentions = {
    kind: settings?.type === 'VIDEO' ? omniReference ? 'OMNI' as const : 'VIDEO' as const : 'IMAGE' as const, error: '', materialLabel,
    candidates: materials.flatMap((file, index) => (settings?.type === 'VIDEO' && omniReference || file.kind === 'IMAGE' && (settings?.type !== 'VIDEO' || index < 2)) ? [{ ...file, index }] : []),
    chips: [...new Set((generation?.prompt ?? '').match(/@【[^】]*】/g) ?? [])].map(token => {
      const binding = pendingBindings.current.find(item => item.token === token);
      const index = materials.findIndex(item => item.fileId === binding?.fileId);
      return { token, label: index < 0 ? '失效引用' : materialLabel(index), preview: index >= 0 && materials[index].kind === 'IMAGE' ? materials[index].preview : undefined, invalid: index < 0 };
    }),
    bind: (file: ComposerMaterial) => {
      const existing = pendingBindings.current.find(item => item.fileId === file.fileId);
      if (existing) return existing.token;
      const token = `@【${file.name.replace(/[【】\r\n]/g, ' ').slice(0, 40)} · ${pendingBindings.current.length + 1}】`;
      pendingBindings.current = [...pendingBindings.current, { token, fileId: file.fileId }];
      return token;
    },
  };

  return (
    <article
      className={nodeClassName}
      data-selected={data.selected}
      data-node-id={data.node.id}
      data-task-state={generation?.taskState}
      data-media-type={settings?.type}
      aria-label={formatMessage("{0} {1}", t(nodeKindLabel[data.node.kind]), data.node.id)}
      aria-describedby={selectionDescriptionId}
      tabIndex={0}
      data-testid={`node-${data.node.id}`}
      onKeyDown={(event) => {
        if (event.currentTarget !== event.target) { if (event.key !== 'Escape') event.stopPropagation(); return; }
        if (event.key === "Enter") {
          data.onSelect(data.node.id, event.shiftKey);
        }
      }}
    >
      <span className="sr-only" id={selectionDescriptionId}>
        {t(data.selected ? "已选择" : "未选择")}
      </span>
      {data.selected && <span className="canvas-node-selected-badge" aria-hidden="true"><CanvasIcon name="check" /><span>{t("已选中")}</span></span>}
      {data.node.kind === "text" ? (
        <>
          <div className="canvas-node-heading"><CanvasIcon name="text" /><span>{t("文本")}</span></div>
          <label htmlFor={`${data.node.id}-text`}>{t("提示词")}</label>
          <textarea
            className="nodrag nowheel nopan"
            id={`${data.node.id}-text`}
            value={data.node.text}
            onChange={(event) => data.onTextChange(data.node.id, event.target.value)}
          />
          <button
            type="button"
            aria-label={t(connectionLabel(data, "文本"))}
            onClick={() => data.onKeyboardConnect(data.node.id)}
          >{t("连接")}</button>
        </>
      ) : null}

      {(data.node.kind === "image" || data.node.kind === "video" || data.node.kind === "audio") ? (
        <>
          <div className="canvas-node-heading"><CanvasIcon name={data.node.kind} /><span>{t(data.node.sourceTaskRef ? "生成结果" : "媒体引用")}</span></div>
          <CanvasMediaPreview asset={data.node.asset} onDepth={createDepth(data.node.id, data.node.asset)} showPreviewButton={!uploadedVisual} showCaption={!uploadedVisual} />
          {!uploadedVisual && <button
            type="button"
            aria-label={t(connectionLabel(data, "图片"))}
            onClick={() => data.onKeyboardConnect(data.node.id)}
          >{t("连接")}</button>}
        </>
      ) : null}

      {data.node.kind === "generation" ? (
        <>
          <div className="canvas-node-heading"><span className="canvas-kind-icon"><CanvasIcon name={settings?.type === "VIDEO" ? "video" : "image"} /></span><h2>{t(depth ? "深度白模视频" : settings?.type === "VIDEO" ? "视频生成" : "图片生成")}</h2></div>
          {apiNotice && <p role="status">{t(apiNotice)}</p>}
          <div className="canvas-generation-preview" data-has-results={Boolean(data.outputs?.length)}>
            {generationEntry ? <div className="canvas-image-entry nopan">
              <button className="nodrag" type="button" disabled={!data.onUploadMedia || data.generationOptionsStatus === 'loading'} onClick={() => data.onUploadMedia?.(data.node.id)}><CanvasIcon name="upload" />{t(settings?.type === "VIDEO" ? "上传视频" : "上传图片")}</button>
              <button className="nodrag" type="button" onClick={() => startReference("IMAGE")}><CanvasIcon name="image" />{t(settings?.type === "VIDEO" ? "图生视频" : "图生图片")}</button>
              {settings?.type === "VIDEO" && <>
                <button className="nodrag" type="button" onClick={() => startReference("FIRST_LAST_FRAME")}><CanvasIcon name="image" />{t("首尾帧视频")}</button>
                <button className="nodrag" type="button" onClick={() => startReference("VIDEO")}><CanvasIcon name="video" />{t("视频生视频")}</button>
              </>}
            </div> : data.outputs?.length || generation?.runs?.length ? <div className="canvas-generation-results canvas-workflow-results nowheel nopan nodrag">
              <label className="canvas-run-picker">{t("生成记录")}<select aria-label={t("生成记录")} value={viewRun} onChange={event => setViewRun(event.target.value)}>
                <option value="latest">{t("本次生成")}</option>
                {(generation?.runs ?? []).map((run, index) => <option key={run.id} value={run.id}>{formatMessage('第 {0} 次生成 · {1}', index + 1, t(taskLabels[run.state]))}</option>)}
                {[...new Set((data.outputs ?? []).map(output => output.sourceTaskRef!))].filter(taskRef => !generation?.runs?.some(run => run.taskRef === taskRef)).map(taskRef => <option key={taskRef} value={taskRef}>{t("历史结果")}</option>)}
              </select></label>
              {visibleOutputs.map((output, index) => <div className="canvas-output-choice" key={output.id} data-chosen={generation?.selectedOutputId === output.id}>
                <CanvasMediaPreview asset={output.asset} onDepth={createDepth(output.id, output.asset)} />
                <div className="canvas-output-actions">
                  <button type="button" aria-label={formatMessage('选择结果 {0}', index + 1)} aria-pressed={generation?.selectedOutputId === output.id} onClick={() => data.onSelectOutput?.(data.node.id, output.id)}>{t(generation?.selectedOutputId === output.id ? '已选中' : '选择结果')}</button>
                  {output.kind === 'image' && <button type="button" onClick={() => data.onUseOutput?.(data.node.id, output.id, 'IMAGE')}>{t("继续生图")}</button>}
                  <button type="button" onClick={() => data.onUseOutput?.(data.node.id, output.id, 'VIDEO')}>{t("用于视频")}</button>
                </div>
              </div>)}
              {viewedRun && !visibleOutputs.length && <small>{t(taskLabels[viewedRun.state])}</small>}
              {viewedRun?.request && <details className="canvas-run-snapshot"><summary>{t("查看本次生成参数")}</summary><p>{viewedRun.request.prompt}</p><small>{viewedRun.request.settings.model} · {viewedRun.request.settings.resolution} · {viewedRun.request.settings.aspectRatio} · {viewedRun.request.settings.type === 'IMAGE' ? formatMessage('{0} 张', viewedRun.request.settings.count) : formatMessage('{0} 秒', viewedRun.request.settings.durationSeconds)} · {formatMessage('{0} 份参考素材', viewedRun.request.inputAssetRefs.length)}</small></details>}
            </div> : <span className="canvas-preview-glyph"><CanvasIcon name={settings?.type === "VIDEO" ? "video" : "image"} /></span>}
            {!generationEntry && <p role="status">{t(data.node.message ?? taskLabels[data.node.taskState])}</p>}
            {!generationEntry && !depth && data.node.taskState === "idle" && <span>{t(!data.canSubmit && data.generationOptionsStatus === "idle" ? "创建画布后，选择模型开始创作" : "选择节点，写下你的创意")}</span>}
          </div>
          {!data.suppressComposer && <div className="canvas-composer nodrag nowheel nopan">
            {!depth && settings?.type === "VIDEO" && <div className="canvas-reference-modes" role="group" aria-label={t("视频参考模式")}>
              <button type="button" disabled={!canSubmitGenerationNode(data.node)} aria-pressed={omniReference} onClick={() => changeSettings({ referenceMode: "OMNI" })}>{t("全能参考")}</button>
              <button type="button" disabled={!canSubmitGenerationNode(data.node)} aria-pressed={!omniReference} onClick={() => changeSettings({ referenceMode: "FIRST_LAST_FRAME" })}>{t("首尾帧")}</button>
              {option && !option.omniReference && <small>{t("当前模型不支持全能参考")}</small>}
            </div>}
            {data.inputs && (data.inputs.texts.length > 0 || data.inputs.media.length > 0 || data.inputs.pending.length > 0 || data.inputs.unselected?.length > 0) && <div className="canvas-connected-inputs" role="group" aria-label={t("已连接参考")}>
              {data.inputs.texts.map(source => <span className="canvas-reference-chip" key={source.id} title={source.text}><CanvasIcon name="text" /><span>{source.text || t("空文本")}</span>{t(removeReference(source.id, source.text || "空文本"))}</span>)}
              {data.inputs.media.map(({ nodeId, asset, edgeId, role, outputId, choices }, index) => <span className="canvas-reference-chip" key={asset.assetRef} title={asset.alt}>
                {asset.kind === "image" && isSafeCanvasPreviewRef(asset.previewRef) ? <img src={asset.previewRef} alt={asset.alt} /> : <CanvasIcon name={asset.kind} />}
                <span>{asset.alt}</span>{asset.kind === 'image' && settings?.type === 'VIDEO' && !omniReference && edgeId ? <select aria-label={formatMessage('参考角色：{0}', asset.alt)} disabled={!canSubmitGenerationNode(data.node)} value={role === 'start' || role === 'end' ? role : index === 0 ? 'start' : 'end'} onChange={event => data.onChangeReference?.(edgeId, { role: event.target.value as 'start' | 'end' })}><option value="start">{t("首帧")}</option><option value="end">{t("尾帧")}</option></select> : <small>{t(asset.kind === 'image' ? '图片' : asset.kind === 'video' ? '视频' : '音频')}</small>}
                {edgeId && choices && choices.length > 1 && <select aria-label={formatMessage('引用结果：{0}', asset.alt)} disabled={!canSubmitGenerationNode(data.node)} value={outputId ?? choices.find(output => output.asset.assetRef === asset.assetRef)?.id} onChange={event => data.onChangeReference?.(edgeId, { outputId: event.target.value })}>{choices.map(output => <option key={output.id} value={output.id} disabled={output.asset.assetRef !== asset.assetRef && data.inputs?.media.some(item => item.asset.assetRef === output.asset.assetRef)}>{formatMessage('第 {0} 批 · 结果 {1}', [...new Set(choices.map(item => item.sourceTaskRef))].indexOf(output.sourceTaskRef) + 1, choices.filter(item => item.sourceTaskRef === output.sourceTaskRef).findIndex(item => item.id === output.id) + 1)}</option>)}</select>}
                {choices?.length && choices.at(-1)?.sourceTaskRef !== choices.find(output => output.asset.assetRef === asset.assetRef)?.sourceTaskRef ? <small>{t("上游有新结果")}</small> : null}
                {removeReference(edgeId ?? nodeId, asset.alt)}
              </span>)}
              {data.inputs.pending.map(id => <span className="canvas-reference-chip" key={id}>{t("上游节点等待生成")}{removeReference(id, "上游节点等待生成")}</span>)}
              {data.inputs.unselected?.map(id => <span className="canvas-reference-chip" key={id}>{t("请选择上游结果")}{removeReference(id, '待选结果')}</span>)}
            </div>}

            {!depth && <>
            <div className="canvas-inline-prompts">
              <MaterialPrompt label={t("生成提示词")} placeholder={t("描述你想创作的画面…")} mentions={mentions} showHelp={false}
                value={data.node.prompt ?? ""}
                disabled={!canSubmitGenerationNode(data.node) || !data.onGenerationPromptChange}
                onChange={value => data.onGenerationPromptChange?.(data.node.id, value, pendingBindings.current)} />
              {Boolean(data.inputs?.texts.length) && <div className="canvas-connected-prompts">
                <span>{t("已连接文本 · 生成时追加到自身提示词后，相同内容仅使用一次")}</span>
                {data.inputs?.texts.map(source => <p key={source.id}>{source.text || t("（空文本）")}</p>)}
              </div>}

            </div>
            </>}
          {depth && <p>{t("相对深度灰度视频（近白远黑），保留原画幅、帧率与完整时长，无声。30 秒以内可选 720p；更长或时长未知时使用 480p。")}</p>}
          <div className="canvas-composer-toolbar">
          {settings ? <fieldset
            className="canvas-generation-settings nodrag nowheel nopan"
            disabled={!canSubmitGenerationNode(data.node)}
          >
            <legend className="sr-only">{t("生成参数")}</legend>
            {!depth && <button type="button" className="canvas-format-trigger" aria-label={t("生成类型")} value={settings.type} aria-haspopup="dialog" aria-expanded={settingsPanel === "type"}
              onClick={() => setSettingsPanel("type")}><ComposerIcon name={settings.type === "IMAGE" ? "image" : "video"} />{t(settings.type === "IMAGE" ? "图片" : "视频")}<ComposerIcon name="chevron" /></button>}
            {data.generationOptionsStatus === "loading" ? <p role="status">{t("正在加载生成模型")}</p>
              : data.generationOptionsStatus === "failed" ? <p role="alert">{t("生成模型加载失败")}</p>
                : data.generationOptionsStatus === "ready" && !option ? <p role="status">{t(depth ? "深度处理未启用或暂不可用" : "当前无可用生成模型")}</p> : null}
            {option ? <>
              {!depth && <><button type="button" className="canvas-format-trigger canvas-model-trigger" aria-label={t("模型")} value={settings.model} aria-haspopup="dialog" aria-expanded={settingsPanel === "model"}
                onClick={() => setSettingsPanel("model")}><ComposerIcon name="model" /><span>{option.displayName}</span><ComposerIcon name="chevron" /></button>
              <button type="button" className="canvas-format-trigger" aria-label={t("比例与分辨率")} aria-haspopup="dialog" aria-expanded={formatOpen}
                onClick={() => setFormatOpen(true)}><ComposerIcon name="ratio" />{settings.aspectRatio} <span>｜</span> {settings.resolution}<ComposerIcon name="chevron" /></button>
              {formatOpen && canSubmitGenerationNode(data.node) && createPortal(
                <ComposerDialog title={t("比例调节")} closeLabel={t("关闭比例调节")} anchored onClose={() => setFormatOpen(false)}>
                  <div className="canvas-format-options nodrag nowheel nopan">
                    <fieldset><legend>{t("宽高比")}</legend><div className="canvas-ratio-options">
                      {option.aspectRatios.map(value => {
                        const [width, height] = value.split(":").map(Number);
                        const ratio = width > 0 && height > 0 ? width / height : 1;
                        return <button type="button" key={value} aria-pressed={settings.aspectRatio === value} onClick={() => changeSettings({ aspectRatio: value })}>
                          <span className="canvas-ratio-icon" aria-hidden="true"><span style={{ width: 22 * Math.min(ratio, 1), height: 22 / Math.max(ratio, 1) }} /></span>{value}
                        </button>;
                      })}
                    </div></fieldset>
                    <fieldset><legend>{t("分辨率")}</legend><div className="canvas-resolution-options">
                      {sortedResolutions(option.resolutions).map(value => <button type="button" key={value} aria-pressed={settings.resolution === value} onClick={() => changeSettings({ resolution: value })}>{value}</button>)}
                    </div></fieldset>
                  </div>
                </ComposerDialog>, document.body)}
              </>}
              {depth && <label>{t("分辨率")}<select value={settings.resolution} onChange={(event) => changeSettings({ resolution: event.target.value })}>
                  {(data.inputs ? depthResolutions(data.inputs, option) : option.resolutions).map((value) => <option value={value} key={value}>{value}</option>)}
                </select>
              </label>}
              {!depth && (settings.type === "IMAGE" ? <label><span className="sr-only">{t("图片数量")}</span>
                <select value={settings.count} onChange={(event) => changeSettings({ count: Number(event.target.value) })}>
                  {Array.from({ length: Math.min(option.maxCount, 4) }, (_, index) => <option key={index + 1} value={index + 1}>{formatMessage("{0} 张", index + 1)}</option>)}
                </select>
              </label> : <button type="button" className="canvas-format-trigger" aria-label={t("时长设置")} aria-haspopup="dialog" aria-expanded={settingsPanel === "duration"}
                onClick={() => setSettingsPanel("duration")}>{settings.durationSeconds}s<ComposerIcon name="chevron" /></button>)}
            </> : null}
          </fieldset> : null}
          {!depth && settings && settingsPanel && canSubmitGenerationNode(data.node) && createPortal(
            <ComposerDialog title={t(settingsPanel === "type" ? "生成类型" : settingsPanel === "model" ? "模型" : "时长")}
              closeLabel={t(settingsPanel === "duration" ? "关闭时长设置" : "关闭参数选择")} anchored onClose={() => setSettingsPanel(null)}>
              <div className="canvas-format-options nodrag nowheel nopan">
                {settingsPanel === "type" && <div className="canvas-choice-list">
                  {(["IMAGE", "VIDEO"] as const).map(type => <button type="button" key={type} value={type} aria-pressed={settings.type === type}
                    onClick={() => { data.onGenerationSettingsChange(data.node.id, copyGenerationDefaults(type)); setSettingsPanel(null); }}>
                    <ComposerIcon name={type === "IMAGE" ? "image" : "video"} /><span>{t(type === "IMAGE" ? "图片生成" : "视频生成")}</span>{settings.type === type && <span aria-hidden="true">✓</span>}
                  </button>)}
                </div>}
                {settingsPanel === "model" && <div className="canvas-choice-list">
                  {options.map(item => <button type="button" key={item.model} value={item.model} aria-pressed={settings.model === item.model}
                    onClick={() => { changeSettings({ model: item.model }); setSettingsPanel(null); }}>
                    <ComposerIcon name="model" /><span>{item.displayName}<small>{sortedResolutions(item.resolutions).join(" / ")}</small></span>{settings.model === item.model && <span aria-hidden="true">✓</span>}
                  </button>)}
                </div>}
                {settingsPanel === "duration" && option && <div className="canvas-duration-controls">
                  <input type="range" aria-label={t("视频时长")} min={option.minDurationSeconds} max={option.maxDurationSeconds} step="1" value={settings.durationSeconds}
                    style={{ background: `linear-gradient(to right, #e4e9ed ${option.maxDurationSeconds > option.minDurationSeconds ? (settings.durationSeconds - option.minDurationSeconds) / (option.maxDurationSeconds - option.minDurationSeconds) * 100 : 0}%, #363739 0)` }}
                    onChange={event => changeSettings({ durationSeconds: Number(event.target.value) })} />
                  <input type="number" aria-label={t("视频时长（秒）")} min={option.minDurationSeconds} max={option.maxDurationSeconds} step="1" value={settings.durationSeconds}
                    onChange={event => changeSettings({ durationSeconds: Number(event.target.value) })} />
                </div>}
              </div>
            </ComposerDialog>, document.body)}
          <div className="canvas-generation-submit nodrag nowheel nopan">
            <span role={data.quote?.state === "failed" ? "alert" : "status"}>
              {t(data.quote?.state === "ready" ? data.quote.localDepth ? "本地深度处理" : formatMessage("预计 {0} 积分", formatAmount(data.quote.points))
                : data.quote?.state === "loading" ? "积分试算中…"
                  : data.quote?.state === "failed" ? data.quote.message : null)}
            </span>
          <button
            type="button"
            className="canvas-submit-button"
            aria-label={t("提交生成任务")}
            disabled={!data.canSubmit || !canSubmitGenerationNode(data.node) || data.quote?.state !== "ready"}
            onClick={() => { setViewRun('latest'); data.onSubmitGeneration(data.node.id); }}
          >
            <span>{t(data.node.runs?.length || data.node.taskRef ? "再次生成" : "生成")}</span><CanvasIcon name="arrow" />
          </button>
          </div>
          </div>
          {depth && data.node.taskRef && ["failed", "timed_out"].includes(data.node.taskState) && <div className="canvas-task-details">
            <button type="button" disabled={!depthEnabled} onClick={() => data.onCreateDepth?.(data.node.id)}>{t("重新生成深度白模")}</button>
          </div>}
          </div>}
        </>
      ) : null}
    </article>
  );
}

export const MemoCanvasNodes = memo(CanvasNodes);
