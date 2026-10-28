import type { CanvasDocument, CanvasGroup, CanvasPoint } from './model';
import { generationOutputs } from './document';

export function groupableNodeIds(document: CanvasDocument) {
  const hidden = new Set(document.nodes.filter(node => node.kind === 'generation')
    .flatMap(node => generationOutputs(document, node.id).map(output => output.id)));
  return new Set(document.nodes.filter(node => !hidden.has(node.id)).map(node => node.id));
}

export function normalizeGroups(document: CanvasDocument): CanvasDocument {
  if (!document.groups?.length) return document;
  const visible = groupableNodeIds(document);
  let changed = false;
  const groups = document.groups.flatMap(group => {
    const nodeIds = group.nodeIds.filter(id => visible.has(id));
    if (nodeIds.length === group.nodeIds.length) return [group];
    changed = true;
    return nodeIds.length ? [{ ...group, nodeIds }] : [];
  });
  return changed ? { ...document, groups } : document;
}

export function createGroup(document: CanvasDocument, ids: readonly string[], id: string, at: string): CanvasDocument {
  const visible = groupableNodeIds(document);
  const nodeIds = [...new Set(ids)].filter(nodeId => visible.has(nodeId));
  if (nodeIds.length < 2 || document.nodes.some(node => node.id === id) || document.groups?.some(group => group.id === id)) return document;
  if (document.groups?.some(group => group.nodeIds.length === nodeIds.length && group.nodeIds.every(nodeId => nodeIds.includes(nodeId)))) return document;
  let number = 1;
  while (document.groups?.some(group => group.name === `分组 ${number}`)) number++;
  const remaining = removeFromGroups(document, nodeIds, at).groups ?? [];
  return { ...document, groups: [...remaining, { id, name: `分组 ${number}`, nodeIds }], updatedAt: at };
}

export function removeFromGroups(document: CanvasDocument, ids: readonly string[], at: string): CanvasDocument {
  if (!document.groups?.some(group => group.nodeIds.some(id => ids.includes(id)))) return document;
  const groups = document.groups.flatMap(group => {
    const nodeIds = group.nodeIds.filter(id => !ids.includes(id));
    return nodeIds.length ? [{ ...group, nodeIds }] : [];
  });
  return { ...document, groups, updatedAt: at };
}

export function renameGroup(document: CanvasDocument, id: string, name: string, at: string): CanvasDocument {
  const clean = name.trim().slice(0, 80);
  if (!clean || !document.groups?.some(group => group.id === id && group.name !== clean)) return document;
  return { ...document, groups: document.groups.map(group => group.id === id ? { ...group, name: clean } : group), updatedAt: at };
}

export function moveNodes(document: CanvasDocument, positions: Readonly<Record<string, CanvasPoint>>, at: string): CanvasDocument {
  if (Object.values(positions).some(point => !Number.isFinite(point.x) || !Number.isFinite(point.y))) throw new RangeError('invalid node position');
  let changed = false;
  const nodes = document.nodes.map(node => {
    const position = positions[node.id];
    if (!position || node.position.x === position.x && node.position.y === position.y) return node;
    changed = true;
    return { ...node, position: { ...position } };
  });
  return changed ? { ...document, nodes, updatedAt: at } : document;
}

export function groupBounds(group: CanvasGroup, nodes: readonly { id: string; position: CanvasPoint; measured?: { width?: number; height?: number } }[]) {
  const members = nodes.filter(node => group.nodeIds.includes(node.id));
  if (!members.length) return null;
  const x = Math.min(...members.map(node => node.position.x)) - 28;
  const y = Math.min(...members.map(node => node.position.y)) - 64;
  return { x, y,
    width: Math.max(260, Math.max(...members.map(node => node.position.x + (node.measured?.width ?? 320))) - x + 28),
    height: Math.max(...members.map(node => node.position.y + (node.measured?.height ?? 300))) - y + 28 };
}
