import type { CanvasDocument, CanvasEdge, CanvasNode } from './model';
import type { CanvasMediaTaskRequest } from './media-task-port';
import { canSubmitGenerationNode, generationOutputs } from './document';

export const taskBusy = (node: CanvasNode) => node.kind === 'generation' && !canSubmitGenerationNode(node);

export function beginRun(document: CanvasDocument, id: string, request: CanvasMediaTaskRequest, at: string): CanvasDocument {
  return { ...document, updatedAt: at, nodes: document.nodes.map(node => {
    if (node.id !== id || node.kind !== 'generation' || !canSubmitGenerationNode(node)) return node;
    const runs = [...(node.runs ?? [])];
    if (node.taskRef && !runs.some(run => run.taskRef === node.taskRef)) runs.push({ id: node.taskRef, createdAt: document.updatedAt, taskRef: node.taskRef, state: node.taskState, request: null });
    runs.push({ id: request.requestRef, createdAt: at, request: structuredClone(request), taskRef: null, state: 'submitting' });
    return { ...node, runs, selectedOutputId: undefined, taskRef: null, taskState: 'submitting', message: null };
  }) };
}

export function syncRunState(document: CanvasDocument): CanvasDocument {
  let changed = false;
  const nodes = document.nodes.map(node => {
    if (node.kind !== 'generation' || !node.runs?.length) return node;
    const run = node.runs[node.runs.length - 1];
    if (run.state === node.taskState && run.taskRef === node.taskRef) return node;
    changed = true;
    return { ...node, runs: node.runs.map((item, index) => index === node.runs!.length - 1 ? { ...item, state: node.taskState, taskRef: node.taskRef } : item) };
  });
  return changed ? { ...document, nodes } : document;
}

export function selectOutput(document: CanvasDocument, id: string, outputId: string): CanvasDocument {
  if (!generationOutputs(document, id).some(output => output.id === outputId)) return document;
  const node = document.nodes.find(item => item.id === id);
  if (node?.kind === 'generation' && node.selectedOutputId === outputId && !document.edges.some(edge => edge.source === id && !edge.outputId && document.nodes.some(target => target.id === edge.target && target.kind === 'generation'))) return document;
  return { ...document, updatedAt: new Date().toISOString(),
    nodes: document.nodes.map(node => node.id === id && node.kind === 'generation' ? { ...node, selectedOutputId: outputId } : node),
    // Existing references stay pinned. Only a waiting connection receives its first result.
    edges: document.edges.map(edge => edge.source === id && !edge.outputId && document.nodes.some(node => node.id === edge.target && node.kind === 'generation')
      ? { ...edge, outputId } : edge) };
}

export function changeReference(document: CanvasDocument, edgeId: string, patch: Pick<CanvasEdge, 'outputId' | 'role'>): CanvasDocument {
  const edge = document.edges.find(item => item.id === edgeId);
  const target = document.nodes.find(node => node.id === edge?.target);
  if (!edge || !target || !canSubmitGenerationNode(target)) return document;
  if (patch.outputId && !generationOutputs(document, edge.source).some(output => output.id === patch.outputId)) return document;
  if (patch.outputId && document.edges.some(item => item.id !== edgeId && item.source === edge.source && item.target === edge.target && item.outputId === patch.outputId)) return document;
  return { ...document, updatedAt: new Date().toISOString(), edges: document.edges.map(item => {
    if (item.id === edgeId) return { ...item, ...patch };
    if ((patch.role === 'start' || patch.role === 'end') && item.target === edge.target && item.role === patch.role) return { ...item, role: edge.role === 'start' || edge.role === 'end' ? edge.role : patch.role === 'start' ? 'end' : 'start' };
    return item;
  }) };
}

export function normalizeWorkflow(document: CanvasDocument): CanvasDocument {
  let next = document;
  // Legacy links to notes are annotations, never generation dependencies.
  next = { ...next, edges: next.edges.map(edge => {
    const target = next.nodes.find(node => node.id === edge.target);
    const source = next.nodes.find(node => node.id === edge.source);
    if (target?.kind === 'text') return { ...edge, role: 'annotation' as const };
    if (target?.kind !== 'generation') return edge;
    const asset = source && source.kind !== 'text' && source.kind !== 'generation' && !edge.assetRef ? { assetRef: source.asset.assetRef } : {};
    if (edge.role) return Object.keys(asset).length ? { ...edge, ...asset } : edge;
    const mediaEdges = next.edges.filter(item => item.target === target.id && next.nodes.some(node => node.id === item.source && (node.kind === 'image' || node.kind === 'generation' && node.settings.type === 'IMAGE')));
    const frame = target.settings.type === 'VIDEO' && target.settings.referenceMode !== 'OMNI' && (source?.kind === 'image' || source?.kind === 'generation' && source.settings.type === 'IMAGE');
    return { ...edge, ...asset, role: frame ? mediaEdges.findIndex(item => item.id === edge.id) === 0 ? 'start' as const : 'end' as const : 'reference' as const };
  }) };
  for (const node of next.nodes) {
    if (node.kind !== 'generation') continue;
    const outputs = generationOutputs(next, node.id).filter(output => output.sourceTaskRef === node.taskRef);
    if (node.selectedOutputId || outputs.length === 1) next = selectOutput(next, node.id, node.selectedOutputId ?? outputs[0].id);
  }
  return next;
}

// History restores draft edits while retaining accepted tasks and immutable results.
export function restoreDraft(current: CanvasDocument, snapshot: CanvasDocument): CanvasDocument {
  const nodes: CanvasNode[] = snapshot.nodes.map(node => {
    const live = current.nodes.find(item => item.id === node.id);
    if (node.kind !== 'generation' || live?.kind !== 'generation') return node;
    return { ...node, taskRef: live.taskRef, taskState: live.taskState, message: live.message, runs: live.runs };
  });
  const owners = new Set(nodes.filter(node => node.kind === 'generation').map(node => node.id));
  const outputEdges = current.edges.filter(edge => owners.has(edge.source) && current.nodes.some(node => node.id === edge.target && node.kind !== 'generation' && node.kind !== 'text' && node.sourceTaskRef));
  for (const edge of outputEdges) {
    const output = current.nodes.find(node => node.id === edge.target)!;
    if (!nodes.some(node => node.id === output.id)) nodes.push(output);
  }
  const ids = new Set(nodes.map(node => node.id));
  const edges = [...snapshot.edges, ...outputEdges.filter(edge => !snapshot.edges.some(item => item.id === edge.id))].filter(edge => ids.has(edge.source) && ids.has(edge.target));
  return { ...snapshot, nodes, edges, viewport: current.viewport, updatedAt: new Date().toISOString() };
}

export function runtimeChanged(before: CanvasDocument, after: CanvasDocument) {
  return after.nodes.some(node => {
    if (node.kind !== 'generation') return false;
    const old = before.nodes.find(item => item.id === node.id);
    return old?.kind === 'generation' && (old.taskRef !== node.taskRef || old.taskState !== node.taskState || old.runs !== node.runs);
  });
}

export function copySelection(document: CanvasDocument, ids: readonly string[]): CanvasDocument {
  const selected = new Set(ids);
  return { ...document, nodes: document.nodes.filter(node => selected.has(node.id)), edges: document.edges.filter(edge => selected.has(edge.target)),
    ...(document.groups ? { groups: document.groups.filter(group => group.nodeIds.every(id => selected.has(id))) } : {}) };
}

export function pasteSelection(document: CanvasDocument, clipboard: CanvasDocument, unique: () => string) {
  const mapping = new Map(clipboard.nodes.map(node => [node.id, unique()]));
  const nodes = clipboard.nodes.map((node): CanvasNode => ({ ...structuredClone(node), id: mapping.get(node.id)!, position: { x: node.position.x + 48, y: node.position.y + 48 },
    ...(node.kind === 'generation' ? { taskRef: null, taskState: 'idle', message: null, runs: [], selectedOutputId: undefined } : {}) }));
  const allIds = new Set([...document.nodes, ...nodes].map(node => node.id));
  const edges = clipboard.edges.flatMap(edge => {
    const source = mapping.get(edge.source) ?? edge.source, target = mapping.get(edge.target);
    return target && allIds.has(source) ? [{ ...edge, id: `${source}->${target}:${edge.id}`, source, target, ...(mapping.has(edge.source) ? { outputId: undefined } : {}) }] : [];
  });
  const distinct = edges.filter((edge, index) => edges.findIndex(item => item.source === edge.source && item.target === edge.target && item.outputId === edge.outputId) === index);
  const groups = (clipboard.groups ?? []).filter(group => group.nodeIds.every(id => mapping.has(id)))
    .map(group => ({ ...group, id: unique(), nodeIds: group.nodeIds.map(id => mapping.get(id)!) }));
  return { document: { ...document, nodes: [...document.nodes, ...nodes], edges: [...document.edges, ...distinct],
    ...(groups.length ? { groups: [...(document.groups ?? []), ...groups] } : {}), updatedAt: new Date().toISOString() }, mapping, groups };
}
