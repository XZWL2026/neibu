import type {
  CanvasDocument,
  CanvasGenerationSettings,
  CanvasNode,
  CanvasPoint,
  CanvasViewport,
  GenerationCanvasNode,
  ImageCanvasNode,
} from "./model";

import { copyGenerationDefaults } from "./generation-defaults";

export const canSubmitGenerationNode = (node: CanvasNode): boolean => (
  node.kind === "generation"
  && ["idle", "failed", "succeeded", "partial_success", "timed_out"].includes(node.taskState)
);

const changed = (
  document: CanvasDocument,
  patch: Partial<CanvasDocument>,
  updatedAt: string,
): CanvasDocument => ({ ...document, ...patch, updatedAt });

const samePoint = (left: CanvasPoint, right: CanvasPoint): boolean => (
  left.x === right.x && left.y === right.y
);

const assertFinitePoint = (point: CanvasPoint): void => {
  if (![point.x, point.y].every(Number.isFinite)) {
    throw new RangeError("invalid canvas point");
  }
};

export const createCanvasDocument = (projectId: string, updatedAt: string): CanvasDocument => ({
  schemaVersion: 1,
  projectId,
  nodes: [],
  edges: [],
  viewport: { x: 0, y: 0, zoom: 1 },
  updatedAt,
});

export const addTextNode = (
  document: CanvasDocument,
  id: string,
  position: CanvasPoint,
  updatedAt: string,
): CanvasDocument => {
  assertFinitePoint(position);
  return document.nodes.some((node) => node.id === id)
    ? document
    : changed(document, {
      nodes: [...document.nodes, { id, kind: "text", version: 1, position: { ...position }, text: "" }],
    }, updatedAt);
};

export const addGenerationNode = (
  document: CanvasDocument,
  id: string,
  position: CanvasPoint,
  updatedAt: string,
): CanvasDocument => {
  assertFinitePoint(position);
  return document.nodes.some((node) => node.id === id)
    ? document
    : changed(document, {
      nodes: [...document.nodes, {
        id,
        kind: "generation",
        prompt: "",
        settings: copyGenerationDefaults("IMAGE"),
        version: 1,
        position: { ...position },
        taskRef: null,
        taskState: "idle",
        message: null,
      }],
    }, updatedAt);
};

export const updateTextNode = (
  document: CanvasDocument,
  id: string,
  text: string,
  updatedAt: string,
): CanvasDocument => {
  const node = document.nodes.find((candidate) => candidate.id === id);
  if (!node || node.kind !== "text" || node.text === text) {
    return document;
  }

  return changed(document, {
    nodes: document.nodes.map((candidate) => candidate.id === id ? { ...candidate, text } : candidate),
  }, updatedAt);
};

export const moveNode = (
  document: CanvasDocument,
  id: string,
  position: CanvasPoint,
  updatedAt: string,
): CanvasDocument => {
  assertFinitePoint(position);
  const node = document.nodes.find((candidate) => candidate.id === id);
  if (!node || samePoint(node.position, position)) {
    return document;
  }

  return changed(document, {
    nodes: document.nodes.map((candidate) => (
      candidate.id === id ? { ...candidate, position: { ...position } } : candidate
    )),
  }, updatedAt);
};

// Keep persisted output references intact while presenting them inside their generation node.
export const generationOutputs = (document: CanvasDocument, generationId: string): ImageCanvasNode[] => {
  const generation = document.nodes.find(node => node.id === generationId && node.kind === "generation");
  if (!generation || generation.kind !== "generation") return [];
  const tasks = new Set([generation.taskRef, ...(generation.runs ?? []).map(run => run.taskRef)].filter(Boolean));
  return document.nodes.filter((node): node is ImageCanvasNode => (
    (node.kind === "image" || node.kind === "video") && tasks.has(node.sourceTaskRef)
    && document.edges.some(edge => edge.source === generationId && edge.target === node.id)
  ));
};

export const canConnectNodeKinds = (
  source: CanvasNode["kind"] | CanvasGenerationSettings["type"],
  target: CanvasNode["kind"] | CanvasGenerationSettings["type"],
): boolean => target.toLowerCase() !== "image" || !["video", "audio"].includes(source.toLowerCase());

export function connectionSourceKind(document: CanvasDocument, id: string) {
  const node = document.nodes.find(item => item.id === id);
  if (node?.kind !== 'generation') return node?.kind ?? 'text';
  const outputs = generationOutputs(document, id);
  const output = outputs.find(item => item.id === node.selectedOutputId) ?? (outputs.length === 1 ? outputs[0] : undefined);
  return output?.kind ?? node.settings.type;
}

const connectNodesInternal = (
  document: CanvasDocument,
  source: string,
  target: string,
  updatedAt: string,
): CanvasDocument => {
  const baseId = `${source}->${target}`;
  const sourceNode = document.nodes.find((node) => node.id === source);
  const targetNode = document.nodes.find((node) => node.id === target);
  const outputs = generationOutputs(document, source);
  const selected = sourceNode?.kind === 'generation' ? outputs.find(output => output.id === sourceNode.selectedOutputId) ?? (outputs.length === 1 ? outputs[0] : undefined) : undefined;
  const id = document.edges.some(edge => edge.id === baseId) && selected ? `${baseId}:${selected.id}` : baseId;
  if (connectionIssue(document, source, target) || document.edges.some((edge) => (
    edge.id === id || (edge.source === source && edge.target === target && (!selected || edge.outputId === selected.id))
  ))) {
    return document;
  }

  const existing = document.edges.filter(edge => edge.target === target && edge.role !== 'annotation');
  const frame = targetNode?.kind === 'generation' && targetNode.settings.type === 'VIDEO' && targetNode.settings.referenceMode !== 'OMNI'
    && connectionSourceKind(document, source).toLowerCase() === 'image';
  return changed(document, {
    edges: [...document.edges, { id, source, target, ...(selected ? { outputId: selected.id } : {}),
      ...(sourceNode && sourceNode.kind !== 'generation' && sourceNode.kind !== 'text' ? { assetRef: sourceNode.asset.assetRef } : {}),
      role: frame ? existing.some(edge => edge.role === 'start') ? 'end' : 'start' : 'reference' }],
  }, updatedAt);
};

export const connectNodes = (
  document: CanvasDocument,
  source: string,
  target: string,
  updatedAt: string,
): CanvasDocument => connectNodesInternal(document, source, target, updatedAt);

export const connectNodesInDirection = (
  document: CanvasDocument,
  source: string,
  target: string,
  updatedAt: string,
): CanvasDocument => connectNodesInternal(document, source, target, updatedAt);

export function connectionIssue(document: CanvasDocument, source: string, target: string): string {
  const from = document.nodes.find(node => node.id === source);
  const to = document.nodes.find(node => node.id === target);
  if (!from || !to) return '连接节点不存在';
  if (source === target) return '不能连接节点自身';
  if (to.kind !== 'generation') return '请连接到生成节点的输入端';
  if (!canSubmitGenerationNode(to)) return '请等待该节点的任务完成后再修改引用';
  if (!canConnectNodeKinds(connectionSourceKind(document, source), to.settings.type)) return '图片生成只能连接文本或图片';
  const visited = new Set<string>();
  const reaches = (id: string): boolean => {
    if (id === source) return true;
    if (visited.has(id)) return false;
    visited.add(id);
    return document.edges.filter(edge => edge.source === id && edge.role !== 'annotation').some(edge => reaches(edge.target));
  };
  if (reaches(target)) return '这条连线会形成循环依赖';
  return '';
}

export const removeEdges = (
  document: CanvasDocument,
  ids: readonly string[],
  updatedAt: string,
): CanvasDocument => {
  const edges = document.edges.filter(edge => !ids.includes(edge.id));
  return edges.length === document.edges.length ? document : changed(document, { edges }, updatedAt);
};

export const removeNodes = (
  document: CanvasDocument,
  ids: readonly string[],
  updatedAt: string,
): CanvasDocument => {
  const removed = new Set(ids.flatMap(id => [id, ...generationOutputs(document, id).map(output => output.id)]));
  if (!document.nodes.some((node) => removed.has(node.id))) {
    return document;
  }

  return changed(document, {
    nodes: document.nodes.filter((node) => !removed.has(node.id)),
    edges: document.edges.filter((edge) => !removed.has(edge.source) && !removed.has(edge.target)),
    ...(document.groups ? { groups: document.groups.flatMap(group => {
      const nodeIds = group.nodeIds.filter(id => !removed.has(id));
      return nodeIds.length ? [{ ...group, nodeIds }] : [];
    }) } : {}),
  }, updatedAt);
};

export const updateViewport = (
  document: CanvasDocument,
  viewport: CanvasViewport,
  updatedAt: string,
): CanvasDocument => {
  if (![viewport.x, viewport.y, viewport.zoom].every(Number.isFinite) || viewport.zoom <= 0) {
    throw new RangeError("invalid canvas viewport");
  }

  if (samePoint(document.viewport, viewport) && document.viewport.zoom === viewport.zoom) {
    return document;
  }

  return changed(document, { viewport: { ...viewport } }, updatedAt);
};

export const updateGenerationTask = (
  document: CanvasDocument,
  id: string,
  patch: Pick<GenerationCanvasNode, "taskRef" | "taskState" | "message">,
  updatedAt: string,
): CanvasDocument => {
  const node = document.nodes.find((candidate) => candidate.id === id);
  if (!node || node.kind !== "generation") {
    return document;
  }

  if (node.taskRef !== null && patch.taskRef !== node.taskRef) {
    return document;
  }

  if (
    node.taskRef === patch.taskRef
    && node.taskState === patch.taskState
    && node.message === patch.message
  ) {
    return document;
  }

  return changed(document, {
    nodes: document.nodes.map((candidate) => (
      candidate.id === id && candidate.kind === "generation"
        ? { ...candidate, ...patch }
        : candidate
    )),
  }, updatedAt);
};

export const updateGenerationSettings = (
  document: CanvasDocument,
  id: string,
  settings: CanvasGenerationSettings,
  updatedAt: string,
): CanvasDocument => {
  const node = document.nodes.find((candidate) => candidate.id === id);
  if (!node || node.kind !== "generation" || !canSubmitGenerationNode(node) || (
    node.settings.type === settings.type
    && node.settings.model === settings.model
    && node.settings.aspectRatio === settings.aspectRatio
    && node.settings.resolution === settings.resolution
    && node.settings.count === settings.count
    && node.settings.durationSeconds === settings.durationSeconds
    && node.settings.referenceMode === settings.referenceMode
    && node.settings.operation === settings.operation
  )) {
    return document;
  }
  let edges = document.edges;
  if (settings.type === 'VIDEO' && settings.referenceMode !== 'OMNI') {
    const imageEdges = document.edges.filter(edge => edge.target === id && (edge.outputId
      ? document.nodes.find(node => node.id === edge.outputId)?.kind === 'image'
      : connectionSourceKind(document, edge.source).toLowerCase() === 'image'));
    const used = new Set(imageEdges.map(edge => edge.role));
    edges = document.edges.map(edge => {
      if (!imageEdges.includes(edge) || edge.role === 'start' || edge.role === 'end') return edge;
      const role = !used.has('start') ? 'start' : !used.has('end') ? 'end' : 'reference';
      used.add(role); return { ...edge, role };
    });
  }
  return changed(document, {
    edges,
    nodes: document.nodes.map((candidate) => (
      candidate.id === id ? { ...node, settings: { ...settings } } : candidate
    )),
  }, updatedAt);
};

export const updateGenerationPrompt = (
  document: CanvasDocument, id: string, prompt: string, updatedAt: string,
): CanvasDocument => {
  const node = document.nodes.find(candidate => candidate.id === id);
  if (!node || node.kind !== "generation" || !canSubmitGenerationNode(node) || (node.prompt ?? "") === prompt) return document;
  return changed(document, {
    nodes: document.nodes.map(candidate => candidate.id === id ? { ...node, prompt } : candidate),
  }, updatedAt);
};
