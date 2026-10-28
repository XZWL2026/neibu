import { generationOutputs } from "./document";
import type { CanvasAssetReference, CanvasDocument, CanvasGenerationSettings, TextCanvasNode } from "./model";

export type CanvasMediaTaskRequest = {
  settings: CanvasGenerationSettings;
  requestRef: string;
  prompt: string;
  inputAssetRefs: readonly string[];
  referenceMode?: 'FIRST_LAST_FRAME' | 'OMNI';
  references?: readonly { fileId: string; portraitId?: string; kind: 'IMAGE' | 'VIDEO' | 'AUDIO'; durationSeconds?: number | null }[];
};

export type CanvasMediaTaskHandle = {
  taskRef: string;
};

export type CanvasMediaTaskSnapshot =
  | { state: "accepted" | "working" | "refund_pending"; taskRef: string; message?: string }
  | { state: "succeeded" | "partial_success"; taskRef: string; outputs: readonly CanvasAssetReference[]; message?: string }
  | { state: "failed" | "timed_out"; taskRef: string; message: string };

export type CanvasGenerationOption = {
  operation?: "GENERATION" | "DEPTH";
  highResolutionMaxDurationSeconds?: number;
  maxReferenceImages?: number;
  referenceFollowsFirstFrame?: boolean;
  durationStepSeconds?: number;
  omniReference?: { maxTotal: number; maxImages: number; maxVideos: number; maxAudios: number; maxVideoDurationSeconds: number; maxAudioDurationSeconds: number; maxVideoBytes: number; maxAudioBytes: number; audioOnly: boolean } | null;
  type: "IMAGE" | "VIDEO";
  model: string;
  displayName: string;
  resolutions: string[];
  aspectRatios: string[];
  maxCount: number;
  minDurationSeconds: number;
  maxDurationSeconds: number;
};

export interface MediaTaskPort {
  options(): Promise<CanvasGenerationOption[]>;
  quote(settings: CanvasGenerationSettings & { references?: readonly { fileId?: string; httpsUrl?: string; kind?: "IMAGE" | "VIDEO" | "AUDIO"; durationSeconds?: number | null }[] }): Promise<{ points: string }>;
  submit(request: CanvasMediaTaskRequest): Promise<CanvasMediaTaskHandle>;
  read(handle: CanvasMediaTaskHandle): Promise<CanvasMediaTaskSnapshot>;
}

export const buildMediaTaskRequest = (
  document: CanvasDocument,
  generationNodeId: string,
  requestRef: string,
): CanvasMediaTaskRequest => {
  const generation = document.nodes.find((node) => node.id === generationNodeId);
  if (!generation || generation.kind !== "generation") throw new Error("生成节点不存在");
  const inputs = collectCanvasInputs(document, generationNodeId);
  if (generation.settings.operation === "DEPTH") return {
    requestRef, settings: generation.settings, prompt: "", inputAssetRefs: inputs.media.map(item => item.asset.assetRef),
  };
  const inputAssetRefs = inputs.media.map(item => item.asset.assetRef);
  const referenceMode = generation.settings.type === "VIDEO" && inputs.media.length > 0
    ? generation.settings.referenceMode ?? (inputs.media.some(({ asset }) => asset.kind !== "image" || asset.reviewId || asset.reviewPending) ? "OMNI" : "FIRST_LAST_FRAME")
    : undefined;
  const { referenceMode: _savedMode, ...settings } = generation.settings;

  return {
    requestRef,
    settings,
    ...(referenceMode ? { referenceMode } : {}),
    ...(referenceMode === "OMNI" || inputs.media.some(({ asset }) => asset.portraitId) ? { references: inputs.media.map(({ asset }) => ({
      fileId: asset.assetRef, ...(asset.portraitId || referenceMode === "OMNI" && asset.reviewId
        ? { portraitId: asset.portraitId ?? asset.reviewId } : {}), kind: asset.kind.toUpperCase() as "IMAGE" | "VIDEO" | "AUDIO", durationSeconds: asset.durationSeconds ?? null,
    })) } : {}),
    prompt: [...new Set([
      compileCanvasPrompt(generation.prompt ?? '', generation.promptBindings ?? [], inputs.media, referenceMode, generation.settings.type).text.trim(),
      ...inputs.texts.map(node => node.text.trim()),
    ].filter(Boolean))].join("\n"),
    inputAssetRefs: [...new Set(inputAssetRefs)],
  };
};

// Only direct inputs contribute; a generated result does not inherit its source references.
export function collectCanvasInputs(document: CanvasDocument, target: string) {
  const visited = new Set([target]);
  const texts: TextCanvasNode[] = [];
  const media: { nodeId: string; asset: CanvasAssetReference; edgeId?: string; role?: import('./model').CanvasEdge['role']; outputId?: string; choices?: ReturnType<typeof generationOutputs> }[] = [];
  const pending: string[] = [];
  const unselected: string[] = [];
  const seenAssets = new Set<string>();
  const add = (nodeId: string, asset: CanvasAssetReference, edge?: import('./model').CanvasEdge, choices?: ReturnType<typeof generationOutputs>) => {
    if (seenAssets.has(asset.assetRef)) return;
    seenAssets.add(asset.assetRef); media.push({ nodeId, asset, ...(edge ? { edgeId: edge.id, role: edge.role, outputId: edge.outputId } : {}), ...(choices ? { choices } : {}) });
  };
  const visit = (id: string, edge: import('./model').CanvasEdge) => {
    const node = document.nodes.find(item => item.id === id);
    if (visited.has(id) && node?.kind !== 'generation') return;
    visited.add(id);
    if (!node) return;
    if (node.kind === "text") texts.push(node);
    else if (node.kind === "generation") {
      const outputs = generationOutputs(document, id);
      if (!outputs.length) pending.push(id);
      else {
        const selected = outputs.find(output => output.id === edge.outputId) ?? (!edge.outputId && outputs.length === 1 ? outputs[0] : undefined);
        if (selected) add(id, selected.asset, edge, outputs);
        else unselected.push(id);
      }
    } else if (edge.assetRef && edge.assetRef !== node.asset.assetRef) unselected.push(id);
    else add(id, node.asset, edge);

  };
  document.edges.filter(edge => edge.target === target && edge.role !== 'annotation').forEach(edge => visit(edge.source, edge));
  const targetNode = document.nodes.find(node => node.id === target);
  if (targetNode?.kind === 'generation' && targetNode.settings.type === 'VIDEO' && targetNode.settings.referenceMode !== 'OMNI') media.sort((a, b) => (a.role === 'start' ? 0 : a.role === 'end' ? 1 : 2) - (b.role === 'start' ? 0 : b.role === 'end' ? 1 : 2));
  return { texts, media, pending, unselected };
}

export function compileCanvasPrompt(value: string, bindings: readonly { token: string; fileId: string }[], media: readonly { asset: CanvasAssetReference }[], mode?: string, type?: string) {
  let missing = false;
  const text = value.replace(/@【[^】]*】/g, token => {
    const binding = bindings.find(item => item.token === token);
    const index = media.findIndex(item => item.asset.assetRef === binding?.fileId);
    if (index < 0) { missing = true; return token; }
    if (type !== 'VIDEO') return `图${index + 1}`;
    if (mode !== 'OMNI') return index === 0 ? '首帧图片' : '尾帧图片';
    const kind = media[index].asset.kind;
    return `${kind === 'image' ? '图片' : kind === 'video' ? '视频' : '音频'}${media.slice(0, index + 1).filter(item => item.asset.kind === kind).length}`;
  });
  return { text, missing: missing || /@【[^】]*$/.test(value) };
}

export function depthResolutions(inputs: ReturnType<typeof collectCanvasInputs>, option?: CanvasGenerationOption) {
  const duration = inputs.media.length === 1 ? inputs.media[0].asset.durationSeconds : undefined;
  return (option?.resolutions ?? []).filter(value => value === "480p" || value === "720p"
    && duration !== undefined && Number.isFinite(duration) && duration > 0 && duration <= (option?.highResolutionMaxDurationSeconds ?? 30));
}

export function referenceIssue(document: CanvasDocument, id: string, option?: CanvasGenerationOption) {
  const inputs = collectCanvasInputs(document, id);
  if (inputs.pending.length) return "上游生成节点尚无结果，请先完成上游生成";
  if (inputs.unselected.length) return '请在上游选择结果；已替换的素材请重新连接';
  const request = buildMediaTaskRequest(document, id, "quote");
  const generation = document.nodes.find(node => node.id === id);
  if (generation?.kind === 'generation' && compileCanvasPrompt(generation.prompt ?? '', generation.promptBindings ?? [], inputs.media, request.referenceMode, generation.settings.type).missing) return '提示词中的素材引用已失效，请重新 @ 选择';
  const assets = inputs.media.map(item => item.asset);
  if (request.settings.operation === "DEPTH") {
    if (assets.length !== 1 || assets[0].kind !== "video" || inputs.texts.length) return "深度处理仅支持连接一个已上传视频";
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(assets[0].assetRef)) return "请先上传视频，再生成深度白模";
    if (!depthResolutions(inputs, option).includes(request.settings.resolution)) return "当前视频仅支持 480p，请调整分辨率";
    return "";
  }
  if (assets.some(asset => asset.portraitId) && (request.settings.type !== "VIDEO" || request.referenceMode !== "OMNI"))
    return "已审核人像仅支持视频全能参考，请切换模式";
  if (request.settings.type === "IMAGE") {
    if (assets.some(asset => asset.kind !== "image")) return "图片生成仅支持图片参考";
    const maximum = option?.maxReferenceImages ?? 0;
    return maximum > 0 && assets.length > maximum ? `当前模型最多支持 ${maximum} 张参考图片` : "";
  }
  if (request.referenceMode === "FIRST_LAST_FRAME") {
    if (assets.some(asset => asset.kind !== "image")) return "首尾帧仅支持图片，请切换全能参考";
    if (assets.length > Math.min(option?.maxReferenceImages ?? 2, 2)) return "首尾帧参考图片超过当前模型上限";
    if (inputs.media.some(item => item.role === 'end') && !inputs.media.some(item => item.role === 'start')) return '请先指定首帧图片';
  }
  if (request.referenceMode !== "OMNI") return "";
  if (assets.some(asset => asset.reviewPending && !asset.reviewId && !asset.portraitId)) return "素材尚未完成审核提交";
  const omni = option?.omniReference;
  if (!omni) return "当前模型不支持全能参考，请切换模型或参考模式";
  if (!assets.length) return "请连接图片、视频或音频参考素材";
  if (assets.length > omni.maxTotal) return `参考素材总数最多 ${omni.maxTotal} 份`;
  for (const kind of ["image", "video", "audio"] as const) {
    const subset = assets.filter(asset => asset.kind === kind);
    const max = kind === "image" ? omni.maxImages : kind === "video" ? omni.maxVideos : omni.maxAudios;
    const label = kind === "image" ? "图片" : kind === "video" ? "视频" : "音频";
    if (subset.length > max) return `${label}参考最多 ${max} 份`;
    if (kind === "image") continue;
    if (subset.reduce((sum, asset) => sum + (asset.durationSeconds ?? 0), 0) > (kind === "video" ? omni.maxVideoDurationSeconds : omni.maxAudioDurationSeconds)) return `${label}参考总时长超过模型上限`;
    if (subset.some(asset => (asset.sizeBytes ?? 0) > (kind === "video" ? omni.maxVideoBytes : omni.maxAudioBytes))) return `${label}参考文件超过模型大小限制`;
  }
  if (!omni.audioOnly && assets.every(asset => asset.kind === "audio")) return "当前模型不支持仅音频参考，请连接图片或视频";
  if (assets.some(asset => !/^[A-Za-z0-9_-]{1,128}$/.test(asset.assetRef))) return "全能参考需使用已上传的文件，请上传素材后连接";
  return "";
}

export function canvasQuoteParameters(document: CanvasDocument, id: string) {
  const request = buildMediaTaskRequest(document, id, "quote");
  return { ...request.settings, ...(request.referenceMode ? { referenceMode: request.referenceMode } : {}),
    ...(request.inputAssetRefs.length ? { references: request.references ?? request.inputAssetRefs.map(ref =>
      /^[A-Za-z0-9_-]{1,128}$/.test(ref) ? { fileId: ref } : { httpsUrl: ref }) } : {}) };
}
