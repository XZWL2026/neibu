import { describe, expect, it } from 'vitest';
import { addGenerationNode, addTextNode, connectNodes, createCanvasDocument, removeNodes, updateTextNode } from './document';
import { createGroup, groupBounds, moveNodes, normalizeGroups, removeFromGroups, renameGroup } from './groups';
import { canonicalizeCanvasDocument } from './persistence';
import { buildMediaTaskRequest } from './media-task-port';
import { copySelection, pasteSelection, restoreDraft, runtimeChanged } from './workflow';

const at = '2026-10-07T00:00:00Z';
function fixture() {
  let doc = addTextNode(createCanvasDocument('groups', at), 'brief', { x: 0, y: 20 }, at);
  doc = updateTextNode(doc, 'brief', '香氛瓶与自然光', at);
  doc = addGenerationNode(doc, 'image', { x: 500, y: 20 }, at);
  doc = addTextNode(doc, 'notes', { x: 0, y: 700 }, at);
  return connectNodes(doc, 'brief', 'image', at);
}

describe('canvas organization groups', () => {
  it('groups only visible nodes without altering input flow or generation requests', () => {
    const doc = fixture();
    expect(createGroup(doc, ['brief', 'missing', 'brief'], 'g', at)).toBe(doc);
    const grouped = createGroup(doc, ['brief', 'image', 'brief', 'missing'], 'g', at);
    expect(grouped.groups).toEqual([{ id: 'g', name: '分组 1', nodeIds: ['brief', 'image'] }]);
    expect(grouped.nodes).toBe(doc.nodes);
    expect(grouped.edges).toBe(doc.edges);
    expect(buildMediaTaskRequest(grouped, 'image', 'request')).toEqual(buildMediaTaskRequest(doc, 'image', 'request'));
    expect(runtimeChanged(doc, grouped)).toBe(false);
    expect(createGroup(grouped, ['image', 'brief'], 'another', at)).toBe(grouped);
  });

  it('moves several members in one document change and restores them together', () => {
    const doc = createGroup(fixture(), ['brief', 'image'], 'g', at);
    const moved = moveNodes(doc, { brief: { x: 80, y: 100 }, image: { x: 580, y: 100 } }, at);
    expect(moved.nodes[0].position).toEqual({ x: 80, y: 100 });
    expect(moved.nodes[1].position).toEqual({ x: 580, y: 100 });
    expect(moved.nodes[2]).toBe(doc.nodes[2]);
    expect(moved.edges).toBe(doc.edges);
    expect(buildMediaTaskRequest(moved, 'image', 'request')).toEqual(buildMediaTaskRequest(doc, 'image', 'request'));
    expect(restoreDraft(moved, doc).nodes.map(node => node.position)).toEqual(doc.nodes.map(node => node.position));
    expect(moveNodes(doc, {}, at)).toBe(doc);
    expect(() => moveNodes(doc, { brief: { x: Infinity, y: 0 } }, at)).toThrow();
  });

  it('transfers members when regrouping and never destroys nodes on ungroup', () => {
    const first = createGroup(fixture(), ['brief', 'image'], 'g1', at);
    const next = createGroup(first, ['image', 'notes'], 'g2', at);
    expect(next.groups).toEqual([
      { id: 'g1', name: '分组 1', nodeIds: ['brief'] },
      { id: 'g2', name: '分组 2', nodeIds: ['image', 'notes'] },
    ]);
    const removed = removeFromGroups(next, ['image', 'notes'], at);
    expect(removed.groups).toHaveLength(1);
    expect(removed.nodes).toBe(first.nodes);
    expect(removed.edges).toBe(first.edges);
    expect(restoreDraft(removed, next).groups).toEqual(next.groups);
    expect(renameGroup(next, 'g1', '  ', at)).toBe(next);
    expect(renameGroup(next, 'g1', ' 品牌创意 ', at).groups?.[0].name).toBe('品牌创意');
    expect(renameGroup(next, 'g1', 'a'.repeat(100), at).groups?.[0].name).toHaveLength(80);
  });

  it('cleans membership on delete and restores the complete group with undo', () => {
    const doc = createGroup(fixture(), ['brief', 'image'], 'g', at);
    expect(removeNodes(doc, ['brief'], at).groups?.[0].nodeIds).toEqual(['image']);
    const removed = removeNodes(doc, ['brief', 'image'], at);
    expect(removed.groups).toEqual([]);
    const restored = restoreDraft(removed, doc);
    expect(restored.groups).toEqual(doc.groups);
    expect(restored.edges).toEqual(doc.edges);
  });

  it('copies a complete group with fresh ids and keeps partial selections ungrouped', () => {
    const doc = createGroup(fixture(), ['brief', 'image'], 'g', at);
    let sequence = 0;
    const result = pasteSelection(doc, copySelection(doc, ['brief', 'image']), () => `copy-${++sequence}`);
    expect(result.groups).toEqual([{ id: 'copy-3', name: '分组 1', nodeIds: ['copy-1', 'copy-2'] }]);
    expect(result.document.groups).toHaveLength(2);
    expect(result.document.edges).toEqual(expect.arrayContaining([expect.objectContaining({ source: 'copy-1', target: 'copy-2' })]));
    expect(copySelection(doc, ['image']).groups).toEqual([]);
    expect(canonicalizeCanvasDocument(result.document, 'groups')).not.toBeNull();
  });

  it('round trips group metadata through draft storage, accepting old drafts', () => {
    const doc = createGroup(fixture(), ['brief', 'image'], 'g', at);
    expect(canonicalizeCanvasDocument(JSON.parse(JSON.stringify(doc)), 'groups')?.groups).toEqual(doc.groups);
    expect(canonicalizeCanvasDocument(fixture(), 'groups')?.groups).toBeUndefined();
    for (const groups of [
      [{ id: 'g', name: '分组', nodeIds: ['missing'] }],
      [{ id: 'g', name: '分组', nodeIds: ['brief', 'brief'] }],
      [{ id: 'g', name: '分组', nodeIds: [] }],
      [{ id: 'brief', name: '分组', nodeIds: ['brief'] }],
      [{ id: 'g', name: '', nodeIds: ['brief'] }],
      [{ id: 'g', name: '分组', nodeIds: ['brief'] }, { id: 'h', name: '分组', nodeIds: ['brief'] }],
    ]) expect(canonicalizeCanvasDocument({ ...doc, groups }, 'groups')).toBeNull();
  });

  it('excludes folded generation outputs and removes stale hidden members', () => {
    const doc = fixture();
    doc.nodes = doc.nodes.map(node => node.kind === 'generation' ? { ...node, taskRef: 'task' } : node);
    doc.nodes.push({ id: 'result', kind: 'image', version: 1, position: { x: 900, y: 0 }, sourceTaskRef: 'task', asset: { assetRef: 'asset', alt: '结果', kind: 'image', previewRef: 'https://cdn.example.test/result.webp' } });
    doc.edges.push({ id: 'result-edge', source: 'image', target: 'result' });
    expect(createGroup(doc, ['brief', 'result'], 'g', at)).toBe(doc);
    expect(normalizeGroups({ ...doc, groups: [{ id: 'g', name: '组', nodeIds: ['brief', 'result'] }] }).groups?.[0].nodeIds).toEqual(['brief']);
  });

  it('encloses measured nodes and updates its bounds as members move', () => {
    const group = { id: 'g', name: '组', nodeIds: ['a', 'b'] };
    const nodes = [{ id: 'a', position: { x: -20, y: 10 }, measured: { width: 300, height: 200 } }, { id: 'b', position: { x: 400, y: 50 }, measured: { width: 300, height: 500 } }];
    expect(groupBounds(group, nodes)).toEqual({ x: -48, y: -54, width: 776, height: 632 });
    expect(groupBounds(group, [])).toBeNull();
  });
});
