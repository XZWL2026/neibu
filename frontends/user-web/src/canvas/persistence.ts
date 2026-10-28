import { canvasReviewStatuses, type CanvasReviewStatus } from "./model";
import type { CanvasDocument, CanvasEdge, CanvasGenerationSettings, CanvasNode, CanvasTaskState, CanvasRun, CanvasGroup } from "./model";
import { groupableNodeIds } from './groups';
import { copyGenerationDefaults } from "./generation-defaults";
import { isSafeCanvasPreviewRef } from "./preview-ref";

export const canvasDraftKey = (projectId: string) => `xzwlab:canvas:v1:${projectId}`;

const corruptKey = (projectId: string, timestamp: number) => (
  `xzwlab:canvas:corrupt:${projectId}:${timestamp}`
);

const finitePoint = (value: unknown): value is { x: number; y: number } => Boolean(
  value
  && typeof value === "object"
  && Number.isFinite((value as { x?: unknown }).x)
  && Number.isFinite((value as { y?: unknown }).y),
);

const taskStates = new Set<CanvasTaskState>([
  "idle",
  "submitting",
  "accepted",
  "working",
  "succeeded",
  "failed",
  "partial_success",
  "timed_out",
  "refund_pending",
]);

const canonicalGenerationSettings = (value: unknown): CanvasGenerationSettings | null => {
  if (value === undefined) return copyGenerationDefaults("IMAGE");
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const saved = value as Record<string, unknown>;
  const type = saved.type === undefined ? "IMAGE" : saved.type;
  if (type !== "IMAGE" && type !== "VIDEO") return null;
  const defaults = copyGenerationDefaults(type);
  const model = saved.model === undefined ? defaults.model : saved.model;
  const aspectRatio = saved.aspectRatio === undefined ? defaults.aspectRatio : saved.aspectRatio;
  const resolution = saved.resolution === undefined ? defaults.resolution : saved.resolution;
  const count = saved.count === undefined ? defaults.count : saved.count;
  const durationSeconds = saved.durationSeconds === undefined ? defaults.durationSeconds : saved.durationSeconds;
  if (
    typeof model !== "string" || model.trim().length === 0
    || typeof aspectRatio !== "string" || aspectRatio.trim().length === 0
    || typeof resolution !== "string" || resolution.trim().length === 0
    || typeof count !== "number" || !Number.isSafeInteger(count) || count < 1
    || typeof durationSeconds !== "number" || !Number.isSafeInteger(durationSeconds) || durationSeconds < 0
  ) return null;
  if (saved.referenceMode !== undefined && saved.referenceMode !== "OMNI" && saved.referenceMode !== "FIRST_LAST_FRAME") return null;
  if (saved.operation !== undefined && saved.operation !== "GENERATION" && saved.operation !== "DEPTH") return null;
  return { type, model, aspectRatio, resolution, count, durationSeconds, ...(saved.operation ? { operation: saved.operation } : {}), ...(saved.referenceMode ? { referenceMode: saved.referenceMode } : {}) };
};

function validRun(run: CanvasRun): boolean {
  if (!run || typeof run.id !== 'string' || typeof run.createdAt !== 'string' || !taskStates.has(run.state) || !(run.taskRef === null || typeof run.taskRef === 'string')) return false;
  if (run.request === null) return true;
  const request = run.request;
  return Boolean(request && typeof request.requestRef === 'string' && typeof request.prompt === 'string' && canonicalGenerationSettings(request.settings)
    && Array.isArray(request.inputAssetRefs) && request.inputAssetRefs.every(id => typeof id === 'string')
    && (request.referenceMode === undefined || request.referenceMode === 'OMNI' || request.referenceMode === 'FIRST_LAST_FRAME')
    && (request.references === undefined || Array.isArray(request.references) && request.references.every(item => item && typeof item.fileId === 'string' && ['IMAGE', 'VIDEO', 'AUDIO'].includes(item.kind))));
}

const canonicalNode = (value: unknown): CanvasNode | null => {
  if (!value || typeof value !== "object") {
    return null;
  }

  const node = value as Partial<CanvasNode>;
  if (
    typeof node.id !== "string"
    || node.id.trim().length === 0
    || node.version !== 1
    || !finitePoint(node.position)
  ) {
    return null;
  }
  const position = { x: node.position.x, y: node.position.y };

  if (node.kind === "text" && typeof node.text === "string") {
    return {
      id: node.id,
      kind: "text",
      version: 1,
      position,
      text: node.text,
    };
  }

  if (node.kind === "generation") {
    if (node.promptBindings !== undefined && (!Array.isArray(node.promptBindings) || node.promptBindings.some(binding => !binding || typeof binding.token !== 'string' || typeof binding.fileId !== 'string'))) return null;
    if (node.selectedOutputId !== undefined && typeof node.selectedOutputId !== 'string') return null;
    if (node.runs !== undefined && (!Array.isArray(node.runs) || node.runs.some(run => !validRun(run)))) return null;
    const settings = canonicalGenerationSettings(node.settings);
    const taskRef = node.taskRef;
    const validTaskRef = (
      (typeof taskRef === "string" && taskRef.trim().length > 0)
      || taskRef === null
    );
    const validState = typeof node.taskState === "string"
      && taskStates.has(node.taskState as CanvasTaskState);
    const bindingMatchesState = taskRef === null
      ? node.taskState === "idle" || node.taskState === "submitting" || node.taskState === "failed"
      : node.taskState === "accepted"
        || node.taskState === "working"
        || node.taskState === "succeeded"
        || node.taskState === "failed"
        || node.taskState === "partial_success"
        || node.taskState === "timed_out"
        || node.taskState === "refund_pending";

    if (
      settings
      && (node.prompt === undefined || typeof node.prompt === "string")
      && validTaskRef
      && validState
      && bindingMatchesState
      && (typeof node.message === "string" || node.message === null)
    ) {
      return {
        id: node.id,
        kind: "generation",
        settings,
        ...(node.prompt !== undefined ? { prompt: node.prompt } : {}),
        version: 1,
        position,
        taskRef,
        taskState: node.taskState as CanvasTaskState,
        message: node.message,
        ...(node.promptBindings ? { promptBindings: node.promptBindings.map(binding => ({ ...binding })) } : {}),
        ...(node.selectedOutputId ? { selectedOutputId: node.selectedOutputId } : {}),
        ...(node.runs ? { runs: structuredClone(node.runs) } : {}),
      };
    }

    return null;
  }

  if (
    (node.kind !== "image" && node.kind !== "video" && node.kind !== "audio")
    || !node.asset
    || typeof node.asset !== "object"
  ) {
    return null;
  }

  const sourceTaskRef = node.sourceTaskRef;
  if (!(
    (typeof sourceTaskRef === "string" && sourceTaskRef.trim().length > 0)
    || sourceTaskRef === null
  )) {
    return null;
  }

  const asset = node.asset as Record<string, unknown>;
  const alt = typeof asset.alt === "string" ? asset.alt.trim() : "";
  return typeof asset.assetRef === "string"
    && (/^[A-Za-z0-9_-]{1,128}$/.test(asset.assetRef) || isSafeCanvasPreviewRef(asset.assetRef))
    && (node.kind !== "audio" || sourceTaskRef === null)
    && (asset.durationSeconds === undefined || typeof asset.durationSeconds === "number" && Number.isFinite(asset.durationSeconds) && asset.durationSeconds > 0)
    && (asset.sizeBytes === undefined || typeof asset.sizeBytes === "number" && Number.isSafeInteger(asset.sizeBytes) && asset.sizeBytes >= 0)
    && (asset.portraitId === undefined || typeof asset.portraitId === "string" && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$/.test(asset.portraitId) && /^[A-Za-z0-9_-]{1,128}$/.test(asset.assetRef))
    && (asset.reviewId === undefined || typeof asset.reviewId === "string" && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$/.test(asset.reviewId) && /^[A-Za-z0-9_-]{1,128}$/.test(asset.assetRef))
    && (asset.reviewStatus === undefined || canvasReviewStatuses.includes(asset.reviewStatus as CanvasReviewStatus))
    && (asset.reviewPending === undefined || typeof asset.reviewPending === "boolean")
    && asset.kind === node.kind
    && alt.length > 0
    && (asset.previewRef === "" || isSafeCanvasPreviewRef(asset.previewRef))
    ? {
        id: node.id,
        kind: node.kind,
        version: 1,
        position,
        asset: {
          assetRef: asset.assetRef,
          ...(typeof asset.portraitId === "string" ? { portraitId: asset.portraitId } : {}),
          ...(typeof asset.reviewId === "string" ? { reviewId: asset.reviewId } : {}),
          ...(asset.reviewStatus !== undefined ? { reviewStatus: asset.reviewStatus as CanvasReviewStatus } : {}),
          ...(typeof asset.reviewPending === "boolean" ? { reviewPending: asset.reviewPending } : {}),
          kind: node.kind,
          alt,
          previewRef: asset.previewRef,
          ...(typeof asset.durationSeconds === "number" ? { durationSeconds: asset.durationSeconds } : {}),
          ...(typeof asset.sizeBytes === "number" ? { sizeBytes: asset.sizeBytes } : {}),
        },
        sourceTaskRef,
        ...(node.referenceOnly === true ? { referenceOnly: true } : {}),
      }
    : null;
};

export const canonicalizeCanvasDocument = (
  value: unknown,
  projectId: string,
): CanvasDocument | null => {
  if (!value || typeof value !== "object") {
    return null;
  }

  const document = value as Partial<CanvasDocument>;
  if (
    document.schemaVersion !== 1
    || document.projectId !== projectId
    || !Array.isArray(document.nodes)
    || !Array.isArray(document.edges)
    || !finitePoint(document.viewport)
    || !Number.isFinite(document.viewport.zoom)
    || document.viewport.zoom <= 0
    || typeof document.updatedAt !== "string"
  ) {
    return null;
  }

  const canonicalNodes: CanvasNode[] = [];
  for (const value of document.nodes) {
    const node = canonicalNode(value);
    if (!node) {
      return null;
    }
    canonicalNodes.push(node);
  }
  const nodeById = new Map(canonicalNodes.map((node) => [node.id, node] as const));
  if (nodeById.size !== canonicalNodes.length) {
    return null;
  }

  const taskRefs = canonicalNodes.flatMap((node) => (
    node.kind === "generation" && node.taskRef !== null ? [node.taskRef] : []
  ));
  if (new Set(taskRefs).size !== taskRefs.length) {
    return null;
  }

  const canonicalEdges: CanvasEdge[] = [];
  for (const value of document.edges) {
    if (!value || typeof value !== "object") {
      return null;
    }
    const edge = value as Partial<CanvasEdge>;
    if (
      typeof edge.id !== "string"
      || edge.id.trim().length === 0
      || typeof edge.source !== "string"
      || typeof edge.target !== "string"
      || edge.source === edge.target
      || !nodeById.has(edge.source)
      || !nodeById.has(edge.target)
      || (edge.outputId !== undefined && (typeof edge.outputId !== 'string' || !nodeById.has(edge.outputId)))
      || (edge.assetRef !== undefined && typeof edge.assetRef !== 'string')
      || (edge.role !== undefined && !['reference', 'start', 'end', 'annotation'].includes(edge.role))
    ) {
      return null;
    }
    canonicalEdges.push({ id: edge.id, source: edge.source, target: edge.target, ...(edge.outputId ? { outputId: edge.outputId } : {}), ...(edge.assetRef ? { assetRef: edge.assetRef } : {}), ...(edge.role ? { role: edge.role } : {}) });
  }
  const edgeIds = new Set(canonicalEdges.map(({ id }) => id));
  const pairs = new Set(canonicalEdges.map(({ source, target, outputId }) => `${source}\u0000${target}\u0000${outputId ?? ''}`));
  if (
    edgeIds.size !== canonicalEdges.length
    || pairs.size !== canonicalEdges.length
  ) {
    return null;
  }

  const outputTargets = new Set(canonicalEdges
    .filter(({ source, target }) => {
      const sourceNode = nodeById.get(source);
      const targetNode = nodeById.get(target);
      return sourceNode?.kind === "generation" && (sourceNode.taskRef !== null || Boolean(sourceNode.runs?.length))
        && (targetNode?.kind === "image" || targetNode?.kind === "video")
        && (targetNode.sourceTaskRef === sourceNode.taskRef || sourceNode.runs?.some(run => run.taskRef === targetNode.sourceTaskRef));
    })
    .map(({ target }) => target));
  if (canonicalNodes.some((node) => (
    (node.kind === "image" || node.kind === "video") && node.sourceTaskRef !== null && !outputTargets.has(node.id)
  ))) {
    return null;
  }

  const groups: CanvasGroup[] = [];
  if (document.groups !== undefined) {
    if (!Array.isArray(document.groups)) return null;
    const groupIds = new Set<string>(), members = new Set<string>();
    const visible = groupableNodeIds({ ...document, nodes: canonicalNodes, edges: canonicalEdges } as CanvasDocument);
    for (const group of document.groups) {
      if (!group || typeof group.id !== 'string' || !group.id.trim() || nodeById.has(group.id) || groupIds.has(group.id)
        || typeof group.name !== 'string' || !group.name.trim() || group.name.length > 80
        || !Array.isArray(group.nodeIds) || !group.nodeIds.length) return null;
      groupIds.add(group.id);
      for (const id of group.nodeIds) {
        if (typeof id !== 'string' || !visible.has(id) || members.has(id)) return null;
        members.add(id);
      }
      groups.push({ id: group.id, name: group.name, nodeIds: [...group.nodeIds] });
    }
  }
  return {
    schemaVersion: 1,
    projectId,
    nodes: canonicalNodes,
    edges: canonicalEdges,
    ...(document.groups !== undefined ? { groups } : {}),
    viewport: {
      x: document.viewport.x,
      y: document.viewport.y,
      zoom: document.viewport.zoom,
    },
    updatedAt: document.updatedAt,
  };
};

export type CanvasDraftLoad =
  | { kind: "loaded"; document: CanvasDocument }
  | { kind: "fresh"; reason: "missing" | "corrupt" | "unavailable" };

export const getCanvasStorage = (): Storage | null => {
  try {
    return globalThis.localStorage;
  } catch {
    return null;
  }
};

export const loadCanvasDraft = (
  storage: Storage | null,
  projectId: string,
  timestamp: number,
): CanvasDraftLoad => {
  if (!storage) {
    return { kind: "fresh", reason: "unavailable" };
  }

  let raw: string | null;
  try {
    raw = storage.getItem(canvasDraftKey(projectId));
  } catch {
    return { kind: "fresh", reason: "unavailable" };
  }

  if (raw === null) {
    return { kind: "fresh", reason: "missing" };
  }

  try {
    const parsed: unknown = JSON.parse(raw);
    const canonical = canonicalizeCanvasDocument(parsed, projectId);
    if (canonical) {
      return { kind: "loaded", document: canonical };
    }
  } catch {
    // Parse failures use the same corrupt-backup path as structural failures.
  }

  try {
    if (!/data:|base64|blob:/i.test(raw)) storage.setItem(corruptKey(projectId, timestamp), raw);
  } catch {
    // A failed backup must not block a fresh in-memory session.
  }
  return { kind: "fresh", reason: "corrupt" };
};

export const saveCanvasDraft = (
  storage: Storage | null,
  document: CanvasDocument,
): { saved: true } | { saved: false; reason: "unavailable" | "write-failed" } => {
  if (!storage) {
    return { saved: false, reason: "unavailable" };
  }

  const canonical = canonicalizeCanvasDocument(document, document.projectId);
  if (!canonical) {
    return { saved: false, reason: "write-failed" };
  }

  try {
    storage.setItem(canvasDraftKey(document.projectId), JSON.stringify(canonical));
    return { saved: true };
  } catch {
    return { saved: false, reason: "write-failed" };
  }
};
