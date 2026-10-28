import { describe, expect, it } from 'vitest';
import { addGenerationNode, addTextNode, canSubmitGenerationNode, connectNodes, connectNodesInDirection, createCanvasDocument, generationOutputs, removeEdges, removeNodes, updateGenerationPrompt, updateGenerationSettings } from './document';
import { beginRun, changeReference, copySelection, normalizeWorkflow, pasteSelection, restoreDraft, selectOutput, syncRunState } from './workflow';
import { buildMediaTaskRequest, collectCanvasInputs, referenceIssue } from './media-task-port';
import { reconcileMediaTask } from './task-reconciler';
import { canonicalizeCanvasDocument } from './persistence';
import { copyGenerationDefaults } from './generation-defaults';
import type { CanvasDocument, GenerationCanvasNode } from './model';
const imageOption = { ...copyGenerationDefaults('IMAGE'), displayName: 'Image', resolutions: ['1024x1024'], aspectRatios: ['1:1'], maxCount: 4, minDurationSeconds: 0, maxDurationSeconds: 0 };
const videoOption = { ...copyGenerationDefaults('VIDEO'), displayName: 'Video', resolutions: ['720p'], aspectRatios: ['16:9'], maxCount: 1, minDurationSeconds: 3, maxDurationSeconds: 15 };

const at = '2026-10-07T00:00:00Z';
const get = (doc: CanvasDocument, id = 'a') => doc.nodes.find(node => node.id === id) as GenerationCanvasNode;
function base() {
  let doc = addGenerationNode(createCanvasDocument('workflow', at), 'a', { x: 0, y: 0 }, at);
  doc = updateGenerationPrompt(doc, 'a', 'first draft', at);
  return updateGenerationSettings(doc, 'a', { ...get(doc).settings, count: 2 }, at);
}
function finish(doc: CanvasDocument, run: string, count = 2) {
  doc = beginRun(doc, 'a', buildMediaTaskRequest(doc, 'a', run), at);
  doc = syncRunState(reconcileMediaTask(doc, 'a', { state: 'accepted', taskRef: run }, at));
  return normalizeWorkflow(syncRunState(reconcileMediaTask(doc, 'a', { state: 'succeeded', taskRef: run, outputs: Array.from({ length: count }, (_, index) => ({ assetRef: `${run}-${index}`, alt: `result ${index}`, kind: 'image', previewRef: 'https://cdn.example.test/result.webp' })) }, at)));
}

describe('designer canvas workflow', () => {
  it('reruns in the same node, preserves submitted snapshots and pins downstream to the selected old output', () => {
    let doc = finish(base(), 'run1');
    expect(canSubmitGenerationNode(get(doc))).toBe(true);
    doc = selectOutput(doc, 'a', 'run1-output-1');
    doc = addGenerationNode(doc, 'b', { x: 500, y: 0 }, at);
    doc = updateGenerationSettings(doc, 'b', copyGenerationDefaults('VIDEO'), at);
    doc = connectNodes(doc, 'a', 'b', at);
    expect(buildMediaTaskRequest(doc, 'b', 'downstream').inputAssetRefs).toEqual(['run1-1']);
    doc = updateGenerationPrompt(doc, 'a', 'second draft', at);
    doc = finish(doc, 'run2', 1);
    expect(get(doc).runs?.map(run => run.request?.prompt)).toEqual(['first draft', 'second draft']);
    expect(generationOutputs(doc, 'a')).toHaveLength(3);
    expect(buildMediaTaskRequest(doc, 'b', 'downstream').inputAssetRefs).toEqual(['run1-1']);
    const restored = canonicalizeCanvasDocument(JSON.parse(JSON.stringify(doc)), doc.projectId)!;
    expect(restored).not.toBeNull();
    expect(buildMediaTaskRequest(restored, 'b', 'downstream').inputAssetRefs).toEqual(['run1-1']);
    expect(get(restored).runs).toEqual(get(doc).runs);
    const changed = changeReference(restored, 'a->b', { outputId: 'run2-output-0' });
    expect(buildMediaTaskRequest(changed, 'b', 'new-reference').inputAssetRefs).toEqual(['run2-0']);
  });
  it('requires a choice for multiple results instead of silently sending the whole batch', () => {
    let doc = addGenerationNode(finish(base(), 'batch'), 'b', { x: 0, y: 0 }, at);
    doc = connectNodes(doc, 'a', 'b', at);
    expect(collectCanvasInputs(doc, 'b').media).toHaveLength(0);
    expect(referenceIssue(doc, 'b', imageOption)).toContain('选择结果');
    doc = selectOutput(doc, 'a', 'batch-output-1');
    expect(collectCanvasInputs(doc, 'b').media.map(item => item.asset.assetRef)).toEqual(['batch-1']);
  });
  it('can use two separate results from one batch as start and end frames', () => {
    let doc = addGenerationNode(finish(base(), 'frames'), 'b', { x: 0, y: 0 }, at);
    doc = updateGenerationSettings(doc, 'b', { ...copyGenerationDefaults('VIDEO'), referenceMode: 'FIRST_LAST_FRAME' }, at);
    doc = connectNodes(selectOutput(doc, 'a', 'frames-output-0'), 'a', 'b', at);
    doc = connectNodes(selectOutput(doc, 'a', 'frames-output-1'), 'a', 'b', at);
    expect(collectCanvasInputs(doc, 'b').media.map(item => [item.asset.assetRef, item.role])).toEqual([['frames-0', 'start'], ['frames-1', 'end']]);
    const restored = canonicalizeCanvasDocument(JSON.parse(JSON.stringify(doc)), doc.projectId)!;
    expect(restored).not.toBeNull();
    expect(buildMediaTaskRequest(restored, 'b', 'video').inputAssetRefs).toEqual(['frames-0', 'frames-1']);
    const end = restored.edges.find(edge => edge.role === 'end')!;
    expect(collectCanvasInputs(removeEdges(restored, [end.id], at), 'b').media).toHaveLength(1);
  });
  it('restores interrupted submissions with their original idempotency key and previous outputs', () => {
    const doc = finish(base(), 'old');
    const submitting = beginRun(doc, 'a', buildMediaTaskRequest(doc, 'a', 'fresh-request'), at);
    const restored = canonicalizeCanvasDocument(JSON.parse(JSON.stringify(submitting)), doc.projectId)!;
    expect(restored).not.toBeNull();
    expect(get(restored).runs?.at(-1)?.request?.requestRef).toBe('fresh-request');
    expect(generationOutputs(restored, 'a')).toHaveLength(2);
    expect(canSubmitGenerationNode(get(restored))).toBe(false);
  });
  it('enforces the same target and acyclic rules for every connection path', () => {
    let doc = addGenerationNode(base(), 'b', { x: 500, y: 0 }, at);
    doc = addTextNode(doc, 'note', { x: 0, y: 200 }, at);
    for (const connect of [connectNodes, connectNodesInDirection]) {
      expect(connect(doc, 'a', 'note', at)).toBe(doc);
      const connected = connect(doc, 'a', 'b', at);
      expect(connect(connected, 'b', 'a', at)).toBe(connected);
      expect(connect(connected, 'a', 'a', at)).toBe(connected);
    }
  });
  it('keeps @ bindings on the same asset when roles swap, and blocks removed or replaced inputs', () => {
    let doc = addGenerationNode(base(), 'b', { x: 0, y: 0 }, at);
    doc = updateGenerationSettings(doc, 'b', { ...copyGenerationDefaults('VIDEO'), referenceMode: 'FIRST_LAST_FRAME' }, at);
    doc.nodes.push(...['i1', 'i2'].map(id => ({ id, kind: 'image' as const, version: 1 as const, sourceTaskRef: null, position: { x: 0, y: 0 }, asset: { assetRef: id, alt: id, kind: 'image' as const, previewRef: 'https://cdn.example.test/result.webp' } })));
    doc = connectNodes(connectNodes(doc, 'i1', 'b', at), 'i2', 'b', at);
    doc.nodes = doc.nodes.map(node => node.id === 'b' ? { ...get(doc, 'b'), prompt: '使用 @【第一张】', promptBindings: [{ token: '@【第一张】', fileId: 'i1' }] } : node);
    expect(buildMediaTaskRequest(doc, 'b', 'one').prompt).toBe('使用 首帧图片');
    doc = changeReference(doc, 'i1->b', { role: 'end' });
    expect(buildMediaTaskRequest(doc, 'b', 'two')).toMatchObject({ prompt: '使用 尾帧图片', inputAssetRefs: ['i2', 'i1'] });
    const removed = removeEdges(doc, ['i1->b'], at);
    expect(referenceIssue(removed, 'b', videoOption)).toContain('引用已失效');
    const replaced = { ...doc, nodes: doc.nodes.map(node => node.id === 'i1' && node.kind === 'image' ? { ...node, asset: { ...node.asset, assetRef: 'replacement' } } : node) };
    expect(referenceIssue(replaced, 'b', videoOption)).toContain('重新连接');
  });
  it('undo restores draft fields without losing completed runs; delete and restore retain all outputs', () => {
    const before = base();
    let current = finish(before, 'done');
    current = updateGenerationPrompt(current, 'a', 'modified', at);
    const undo = restoreDraft(current, before);
    expect(get(undo).prompt).toBe('first draft');
    expect(get(undo).taskRef).toBe('done');
    expect(generationOutputs(undo, 'a')).toHaveLength(2);
    const deleted = removeNodes(undo, ['a'], at);
    expect(deleted.nodes).toHaveLength(0);
    expect(restoreDraft(deleted, undo).nodes).toEqual(undo.nodes);
  });
  it('copies editable configuration and internal links without reusing executions', () => {
    let doc = addGenerationNode(finish(base(), 'completed'), 'b', { x: 0, y: 0 }, at);
    doc = connectNodes(selectOutput(doc, 'a', 'completed-output-0'), 'a', 'b', at);
    let seq = 0;
    const pasted = pasteSelection(doc, copySelection(doc, ['a', 'b']), () => `copy-${++seq}`).document;
    expect(get(pasted, 'copy-1')).toMatchObject({ taskRef: null, taskState: 'idle', runs: [], prompt: 'first draft' });
    expect(pasted.edges.find(edge => edge.source === 'copy-1')).toMatchObject({ target: 'copy-2', outputId: undefined });
    expect(get(doc).runs).toHaveLength(1);
  });
});
