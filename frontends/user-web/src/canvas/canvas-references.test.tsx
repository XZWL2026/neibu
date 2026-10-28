import { describe, it, expect } from 'vitest';
import { createCanvasDocument, addGenerationNode, addTextNode, connectNodes, updateGenerationTask } from './document';
import { buildMediaTaskRequest, referenceIssue, collectCanvasInputs, type CanvasGenerationOption } from './media-task-port';
import { canonicalizeCanvasDocument } from './persistence';

export const referenceDocument = () => {
  let document = addGenerationNode(createCanvasDocument('refs', 'now'), 'video', { x: 500, y: 0 }, 'now');
  document = addGenerationNode(document, 'image', { x: 200, y: 0 }, 'now');
  document = addTextNode(document, 'text', { x: 0, y: 0 }, 'now');
  document.nodes = document.nodes.map(node => node.kind === 'text' ? { ...node, text: '上游文本' }
    : node.kind === 'generation' && node.id === 'video' ? { ...node, prompt: '自身提示词', settings: { ...node.settings, type: 'VIDEO', referenceMode: 'OMNI', durationSeconds: 5, count: 1 } }
    : node.kind === 'generation' ? { ...node, taskRef: 'task', taskState: 'succeeded' } : node);
  document.nodes.push(...(['image', 'video', 'audio'] as const).map((kind, index) => ({
    id: `asset-${index}`, kind, version: 1 as const, position: { x: 0, y: 0 }, sourceTaskRef: index === 0 ? 'task' : null,
    asset: { assetRef: `file-${index}`, kind, alt: `参考${kind}`, previewRef: 'https://cdn.example.test/ref', ...(index ? { durationSeconds: 4, sizeBytes: 100 } : {}) },
  })));
  document.edges = [
    ['text', 'image'], ['image', 'asset-0'], ['image', 'video'], ['text', 'video'],
    ['asset-0', 'video'], ['asset-1', 'video'], ['asset-2', 'video'], ['video', 'text'], // a cycle must not recurse or include own outputs
  ].map(([source, target]) => ({ id: `${source}->${target}`, source, target }));
  return document;
};
export const omniOption: CanvasGenerationOption = { type: 'VIDEO', model: 'seedream-5.0-lite', displayName: '测试全能模型', resolutions: ['2K'], aspectRatios: ['1:1'], maxCount: 1, minDurationSeconds: 1, maxDurationSeconds: 10,
  maxReferenceImages: 2, omniReference: { maxTotal: 12, maxImages: 9, maxVideos: 3, maxAudios: 3, maxVideoDurationSeconds: 15, maxAudioDurationSeconds: 15, maxVideoBytes: 10000, maxAudioBytes: 10000, audioOnly: false } };

describe('canvas references', () => {
  it('collects upstream text and generated images, video and audio once across diamonds and cycles', () => {
    const doc = referenceDocument();
    const request = buildMediaTaskRequest(doc, 'video', 'request');
    expect(request.prompt).toBe('自身提示词\n上游文本');
    expect(request.inputAssetRefs).toEqual(['file-0', 'file-1', 'file-2']);
    expect(request.referenceMode).toBe('OMNI');
    expect(request.references).toEqual([
      { fileId: 'file-0', kind: 'IMAGE', durationSeconds: null },
      { fileId: 'file-1', kind: 'VIDEO', durationSeconds: 4 },
      { fileId: 'file-2', kind: 'AUDIO', durationSeconds: 4 },
    ]);
    expect(collectCanvasInputs(doc, 'video').texts.map(node => node.id)).toEqual(['text']);
    expect(referenceIssue(doc, 'video', omniOption)).toBe('');
  });
  it('round trips audio metadata and per-node reference mode', () => {
    const doc = referenceDocument();
    const restored = canonicalizeCanvasDocument(JSON.parse(JSON.stringify(doc)), 'refs');
    expect(restored).not.toBeNull();
    expect(buildMediaTaskRequest(restored!, 'video', 'request')).toEqual(buildMediaTaskRequest(doc, 'video', 'request'));
  });
  it('does not silently discard unsupported media or excess frames', () => {
    const doc = referenceDocument();
    const video = doc.nodes.find(node => node.id === 'video')!;
    if (video.kind !== 'generation') throw new Error();
    video.settings.referenceMode = 'FIRST_LAST_FRAME';
    expect(referenceIssue(doc, 'video', omniOption)).toContain('首尾帧仅支持图片');
    video.settings.referenceMode = 'OMNI';
    expect(referenceIssue(doc, 'video', { ...omniOption, omniReference: null })).toContain('不支持全能参考');
    expect(referenceIssue(doc, 'video', { ...omniOption, omniReference: { ...omniOption.omniReference!, maxAudios: 0 } })).toContain('音频');
  });
});

import '@testing-library/jest-dom/vitest';
import { render, screen, fireEvent, waitFor, within, cleanup, act } from '@testing-library/react';
import { vi, afterEach } from 'vitest';
import { CanvasWorkspace } from './CanvasWorkspace';
import { reconcileMediaTask } from './task-reconciler';
import { beginRun } from './workflow';
const mockOptions: CanvasGenerationOption[] = [
  { type: 'IMAGE', model: 'seedream-5.0-lite', displayName: 'Seedream 5.0 Lite', resolutions: ['2K', '4K'], aspectRatios: ['1:1', '4:3', '3:4', '16:9', '9:16'], maxCount: 4, minDurationSeconds: 0, maxDurationSeconds: 0, maxReferenceImages: 9 },
  omniOption,
];
const connectionFlow = vi.hoisted(() => ({ props: null as any }));
vi.mock('@xyflow/react', () => ({
  ReactFlow: (props: any) => { connectionFlow.props = props; return <div className="react-flow__pane">{props.nodes.map((node: any) => {
    const NodeView = props.nodeTypes[node.type]; return <NodeView key={node.id} data={node.data} />;
  })}{props.children}</div>; },
  Handle: () => null, Background: () => null, MiniMap: () => null,
  ViewportPortal: ({ children }: { children: import('react').ReactNode }) => <>{children}</>,
  PanOnScrollMode: { Vertical: "vertical" }, Position: { Left: 'left', Right: 'right' },
}));
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

it('uses right-click selection, grouped clipboard, cursor placement and undo through existing document operations', async () => {
  let doc = addTextNode(addTextNode(createCanvasDocument('menu-ui', 'now'), 'a', { x: 0, y: 0 }, 'now'), 'b', { x: 400, y: 0 }, 'now');
  doc = { ...doc, edges: [{ id: 'ab', source: 'a', target: 'b', role: 'annotation' }] };
  let saved = doc;
  render(<CanvasWorkspace projectId="menu-ui" initialDocument={doc} storage={null} onDocumentChange={value => { saved = value; }} />);
  act(() => connectionFlow.props.onInit({ screenToFlowPosition: () => ({ x: 900, y: 600 }) }));
  fireEvent.keyDown(screen.getByTestId('node-a'), { key: 'Enter' });
  fireEvent.keyDown(screen.getByTestId('node-b'), { key: 'Enter', shiftKey: true });
  fireEvent.contextMenu(screen.getByTestId('node-a'), { clientX: 400, clientY: 300 });
  expect(screen.getByRole('status', { name: '节点选择状态' })).toHaveTextContent('2 个节点');
  fireEvent.click(screen.getByRole('menuitem', { name: /创建分组/ }));
  expect(saved.groups?.[0].nodeIds).toEqual(['a', 'b']);
  fireEvent.contextMenu(screen.getByTestId('node-b'));
  fireEvent.click(screen.getByRole('menuitem', { name: /复制节点/ }));
  fireEvent.contextMenu(document.querySelector('.react-flow__pane')!, { clientX: 600, clientY: 400 });
  fireEvent.click(screen.getByRole('menuitem', { name: /粘贴节点/ }));
  await waitFor(() => expect(saved.nodes).toHaveLength(4));
  expect(saved.groups).toHaveLength(2);
  expect(saved.nodes.slice(2).map(node => node.position)).toEqual([{ x: 900, y: 600 }, { x: 1300, y: 600 }]);
  expect(saved.edges.some(edge => edge.source === saved.nodes[2].id && edge.target === saved.nodes[3].id)).toBe(true);
  fireEvent.keyDown(window, { key: 'z', ctrlKey: true });
  expect(saved.nodes).toHaveLength(2); expect(saved.groups).toHaveLength(1);
  expect(screen.getByRole('status', { name: '节点选择状态' })).toHaveTextContent('未选中节点');
  fireEvent.contextMenu(screen.getByTestId('node-a'));
  expect(screen.getByRole('status', { name: '节点选择状态' })).toHaveTextContent('1 个节点');
  expect(screen.getByRole('menuitem', { name: /创建分组/ })).toBeDisabled();
  fireEvent.keyDown(screen.getByRole('menuitem', { name: /复制节点/ }), { key: 'ArrowDown' });
  expect(screen.getByRole('menuitem', { name: /创建副本/ })).toHaveFocus();
  fireEvent.keyDown(document.activeElement!, { key: 'Escape' });
  expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  fireEvent.contextMenu(document.querySelector('.react-flow__pane')!);
  fireEvent.click(screen.getByRole('menuitem', { name: '新增文本' }));
  expect(saved.nodes.at(-1)?.position).toEqual({ x: 900, y: 600 });
  fireEvent.keyDown(window, { key: 'd', ctrlKey: true });
  await waitFor(() => expect(saved.nodes).toHaveLength(4));
});

it('fits selected or all nodes, clears editing on Escape, and keeps navigation shortcuts out of text fields', () => {
  const doc = addGenerationNode(addGenerationNode(createCanvasDocument('nav-ui', 'now'), 'a', { x: 0, y: 0 }, 'now'), 'b', { x: 600, y: 0 }, 'now');
  render(<CanvasWorkspace projectId="nav-ui" initialDocument={doc} storage={null} />);
  const fitBounds = vi.fn(), getNodesBounds = vi.fn((_nodes: any[]) => ({ x: 0, y: 0, width: 920, height: 300 }));
  act(() => connectionFlow.props.onInit({ fitBounds, getNodesBounds }));
  expect(screen.getByRole('button', { name: '定位所选节点' })).toBeDisabled();
  fireEvent.keyDown(screen.getByTestId('node-a'), { key: 'Enter', shiftKey: true });
  fireEvent.keyDown(window, { key: 'f' });
  expect(getNodesBounds.mock.calls.at(-1)?.[0].map((node: any) => node.id)).toEqual(['a']);
  fireEvent.keyDown(window, { key: 'F', shiftKey: true });
  expect(getNodesBounds.mock.calls.at(-1)?.[0]).toHaveLength(2);
  expect(fitBounds).toHaveBeenCalledTimes(2);
  fireEvent.keyDown(window, { code: 'Space', key: ' ' });
  expect(connectionFlow.props.panOnDrag).toBe(true);
  expect(connectionFlow.props.nodesDraggable).toBe(false);
  fireEvent.blur(window);
  expect(connectionFlow.props.panOnDrag).toEqual([1]);
  fireEvent.mouseDown(screen.getByTestId('node-a')); fireEvent.click(screen.getByTestId('node-a'));
  const prompt = within(screen.getByTestId('node-a')).getByRole('textbox', { name: '生成提示词' });
  fireEvent.keyDown(prompt, { key: 'f' }); fireEvent.keyDown(prompt, { code: 'Space', key: ' ' });
  expect(fitBounds).toHaveBeenCalledTimes(2); expect(connectionFlow.props.nodesDraggable).toBe(true);
  fireEvent.change(prompt, { target: { value: '我的草稿' } });
  prompt.focus(); fireEvent.keyDown(prompt, { key: 'Escape' });
  expect(screen.getByRole('status', { name: '节点选择状态' })).toHaveTextContent('未选中节点');
  expect(screen.queryByRole('textbox', { name: '生成提示词' })).not.toBeInTheDocument();
  fireEvent.mouseDown(screen.getByTestId('node-a')); fireEvent.click(screen.getByTestId('node-a'));
  expect(within(screen.getByTestId('node-a')).getByRole('textbox', { name: '生成提示词' })).toHaveValue('我的草稿');
});

it('keeps Shift clicks and group selection separate from opening a composer', () => {
  let doc = addGenerationNode(createCanvasDocument('selection-ui', 'now'), 'a', { x: 0, y: 0 }, 'now');
  doc = addGenerationNode(doc, 'b', { x: 500, y: 0 }, 'now');
  doc.groups = [{ id: 'group', name: '工作组', nodeIds: ['a', 'b'] }];
  render(<CanvasWorkspace projectId="selection-ui" initialDocument={doc} storage={null} />);
  const a = screen.getByTestId('node-a'), b = screen.getByTestId('node-b');
  const count = screen.getByRole('status', { name: '节点选择状态' });
  fireEvent.mouseDown(a, { button: 0, shiftKey: true });
  fireEvent.click(a, { shiftKey: true });
  expect(count).toHaveTextContent('已选中 1 个节点');
  expect(screen.queryByRole('textbox', { name: '生成提示词' })).not.toBeInTheDocument();
  fireEvent.keyUp(window, { key: 'Shift' });
  expect(screen.queryByRole('textbox', { name: '生成提示词' })).not.toBeInTheDocument();

  fireEvent.mouseDown(b, { button: 0, shiftKey: true });
  // The layout engine can select during mousedown, before the click callback runs.
  act(() => connectionFlow.props.onNodesChange([{ type: 'select', id: 'a', selected: false }, { type: 'select', id: 'b', selected: true }]));
  fireEvent.click(b, { shiftKey: true });
  expect(count).toHaveTextContent('已选中 2 个节点');
  fireEvent.mouseDown(a, { button: 0, shiftKey: true });
  fireEvent.click(a, { shiftKey: true });
  expect(count).toHaveTextContent('已选中 1 个节点');
  expect(b).toHaveAttribute('data-selected', 'true');
  expect(screen.queryByRole('textbox', { name: '生成提示词' })).not.toBeInTheDocument();

  fireEvent.mouseDown(b, { button: 0 }); fireEvent.click(b);
  const prompt = within(b).getByRole('textbox', { name: '生成提示词' });
  fireEvent.change(prompt, { target: { value: '保留我的草稿' } });
  fireEvent.keyDown(a, { key: 'Enter', shiftKey: true });
  expect(screen.queryByRole('textbox', { name: '生成提示词' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '取消节点选择' }));
  fireEvent.click(screen.getByRole('button', { name: '选择分组：工作组' }));
  expect(count).toHaveTextContent('已选中 2 个节点');
  expect(screen.queryByRole('textbox', { name: '生成提示词' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '取消节点选择' }));
  fireEvent.mouseDown(b, { button: 0 }); fireEvent.click(b);
  expect(within(b).getByRole('textbox', { name: '生成提示词' })).toHaveValue('保留我的草稿');
  act(() => connectionFlow.props.onSelectionStart());
  expect(screen.queryByRole('textbox', { name: '生成提示词' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '新增图片' }));
  expect(screen.getAllByRole('textbox', { name: '生成提示词' })).toHaveLength(1);
});

it('wires group shortcuts, names, atomic multi-node movement, undo and clipboard through the workspace', async () => {
  const initial = addTextNode(addTextNode(createCanvasDocument('group-ui', 'now'), 'one', { x: 0, y: 0 }, 'now'), 'two', { x: 400, y: 0 }, 'now');
  let saved = initial;
  render(<CanvasWorkspace projectId="group-ui" initialDocument={initial} storage={null} onDocumentChange={doc => { saved = doc; }} />);
  expect(screen.getByRole('button', { name: '创建分组' })).toBeDisabled();
  fireEvent.keyDown(screen.getByTestId('node-one'), { key: 'Enter' });
  fireEvent.keyDown(screen.getByTestId('node-two'), { key: 'Enter', shiftKey: true });
  fireEvent.keyDown(window, { key: 'g', ctrlKey: true });
  expect(saved.groups?.[0].nodeIds).toEqual(['one', 'two']);
  fireEvent.click(screen.getByRole('button', { name: '重命名分组：分组 1' }));
  const name = screen.getByRole('textbox', { name: '分组名称' });
  fireEvent.change(name, { target: { value: '品牌概念' } });
  fireEvent.submit(name.closest('form')!);
  expect(saved.groups?.[0].name).toBe('品牌概念');
  act(() => connectionFlow.props.onNodesChange([
    { type: 'position', id: 'one', position: { x: 50, y: 80 }, dragging: false },
    { type: 'position', id: 'two', position: { x: 450, y: 80 }, dragging: false },
  ]));
  expect(saved.nodes.map(node => node.position)).toEqual([{ x: 50, y: 80 }, { x: 450, y: 80 }]);
  fireEvent.click(screen.getByRole('button', { name: '撤销' }));
  expect(saved.nodes.map(node => node.position)).toEqual(initial.nodes.map(node => node.position));
  expect(saved.groups?.[0].name).toBe('品牌概念');
  fireEvent.click(screen.getByRole('button', { name: '选择分组：品牌概念' }));
  fireEvent.click(screen.getByRole('button', { name: '复制节点' }));
  fireEvent.click(screen.getByRole('button', { name: '粘贴' }));
  await waitFor(() => expect(saved.groups).toHaveLength(2));
  expect(saved.nodes).toHaveLength(4);
  expect(saved.groups?.[1].nodeIds).not.toEqual(['one', 'two']);
  fireEvent.keyDown(window, { key: 'g', ctrlKey: true, shiftKey: true });
  expect(saved.groups).toHaveLength(1);
  expect(saved.nodes).toHaveLength(4);
  fireEvent.keyDown(window, { key: 'z', ctrlKey: true });
  expect(saved.groups).toHaveLength(2);
});

it('supports two real submissions from one editor, retains run snapshots and preserves them through undo', async () => {
  let saved = addGenerationNode(createCanvasDocument('repeat', 'now'), 'a', { x: 0, y: 0 }, 'now');
  const submit = vi.fn(async (_request: import('./media-task-port').CanvasMediaTaskRequest) => ({ taskRef: `task-${submit.mock.calls.length}` }));
  const read = vi.fn(async ({ taskRef }: { taskRef: string }) => ({ taskRef, state: 'succeeded' as const, outputs: [{ assetRef: `${taskRef}-image`, kind: 'image' as const, alt: 'result', previewRef: 'https://cdn.example.test/result.webp' }] }));
  render(<CanvasWorkspace projectId="repeat" initialDocument={saved} storage={null} onDocumentChange={doc => { saved = doc; }} mediaTaskPort={{ options: async () => mockOptions, quote: async () => ({ points: '1' }), submit, read }} />);
  const prompt = screen.getByRole('textbox', { name: '生成提示词' });
  fireEvent.change(prompt, { target: { value: 'first' } });
  const button = screen.getByRole('button', { name: '提交生成任务' });
  await waitFor(() => expect(button).toBeEnabled()); fireEvent.click(button);
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
  await act(async () => connectionFlow.props.nodes[0].data.onReadGeneration('a'));
  await waitFor(() => expect(button).toBeEnabled());
  fireEvent.change(prompt, { target: { value: 'second' } }); fireEvent.click(button);
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(2));
  await act(async () => connectionFlow.props.nodes[0].data.onReadGeneration('a'));
  expect(submit.mock.calls[0][0].requestRef).not.toBe(submit.mock.calls[1][0].requestRef);
  expect(saved.nodes[0]).toMatchObject({ taskRef: 'task-2', runs: [{ request: { prompt: 'first' } }, { request: { prompt: 'second' } }] });
  fireEvent.keyDown(window, { key: 'z', ctrlKey: true });
  expect(saved.nodes[0]).toMatchObject({ prompt: 'first', taskRef: 'task-2' });
  // Execution boundaries split edit coalescing; undo never drops accepted tasks or results.
  expect(saved.nodes[0].kind === 'generation' && saved.nodes[0].runs).toHaveLength(2);
  expect(saved.nodes.filter(node => node.kind === 'image')).toHaveLength(2);
});

it('retries an interrupted persisted request using exactly the same payload', async () => {
  let doc = addGenerationNode(createCanvasDocument('recover', 'now'), 'a', { x: 0, y: 0 }, 'now');
  const request = { ...buildMediaTaskRequest(doc, 'a', 'stable-request'), prompt: 'original' };
  doc = beginRun({ ...doc, nodes: doc.nodes.map(node => node.kind === 'generation' ? { ...node, prompt: 'original' } : node) }, 'a', request, 'now');
  const submit = vi.fn(async (_request: import('./media-task-port').CanvasMediaTaskRequest) => ({ taskRef: 'recovered-task' }));
  render(<CanvasWorkspace projectId="recover" initialDocument={doc} storage={null} production mediaTaskPort={{ options: async () => mockOptions, quote: async () => ({ points: '1' }), submit, read: async () => ({ taskRef: 'recovered-task', state: 'working' }) }} />);
  fireEvent.click(screen.getByRole('button', { name: '使用原请求重试 a' }));
  await waitFor(() => expect(submit).toHaveBeenCalledExactlyOnceWith(request));
});

it.each(['IMAGE', 'VIDEO'] as const)('connects unfinished %s cards through the manual connection callback and waits for their output', async type => {
  let doc = addGenerationNode(createCanvasDocument('refs', 'now'), 'source', { x: 0, y: 0 }, 'now');
  doc = addGenerationNode(doc, 'target', { x: 500, y: 0 }, 'now');
  doc.nodes = doc.nodes.map(node => node.kind === 'generation' ? { ...node, settings: { ...node.settings, type: node.id === 'source' ? type : 'VIDEO' } } : node);
  const changed = vi.fn();
  render(<CanvasWorkspace projectId="refs" initialDocument={doc} storage={null} onDocumentChange={changed} />);
  act(() => connectionFlow.props.onConnect({ source: 'source', target: 'target' }));
  const connected = changed.mock.lastCall![0];
  expect(connected.edges).toEqual([expect.objectContaining({ id: 'source->target', source: 'source', target: 'target' })]);
  expect(collectCanvasInputs(connected, 'target').pending).toEqual(['source']);
  const target = screen.getByTestId('node-target');
  expect(within(target).getByText('上游节点等待生成')).toBeInTheDocument();
  expect(within(target).getByRole('button', { name: '提交生成任务' })).toBeDisabled();
  expect(referenceIssue(connected, 'target')).toBe('上游生成节点尚无结果，请先完成上游生成');
  const restored = canonicalizeCanvasDocument(JSON.parse(JSON.stringify(connected)), 'refs')!;
  expect(restored.edges).toEqual(connected.edges);
  const working = updateGenerationTask(restored, 'source', { taskRef: 'source-task', taskState: 'working', message: null }, 'later');
  const completed = reconcileMediaTask(working, 'source', { taskRef: 'source-task', state: 'succeeded', outputs: [
    { assetRef: 'generated-file', kind: type === 'IMAGE' ? 'image' : 'video', alt: '生成结果', previewRef: 'https://cdn.example.test/result' },
  ] }, 'done');
  expect(collectCanvasInputs(completed, 'target').pending).toEqual([]);
  expect(buildMediaTaskRequest(completed, 'target', 'request').inputAssetRefs).toEqual(['generated-file']);
});

it('shows connected inputs, switches mode, and quotes/submits the same typed references without payment', async () => {

  const doc = referenceDocument();
  // Keep the realistic acyclic UI graph while domain coverage above handles cycles.
  doc.edges = doc.edges.filter(edge => edge.source !== 'video');
  const quote = vi.fn(async () => ({ points: '2.0000' }));
  const submit = vi.fn(async () => ({ taskRef: 'new-task' }));
  render(<CanvasWorkspace projectId="refs" initialDocument={doc} storage={null} mediaTaskPort={{ options: async () => [omniOption], quote, submit, read: async () => ({ state: 'working', taskRef: 'new-task' }) }} />);
  const video = screen.getByTestId('node-video');
  await waitFor(() => expect(within(video).getByRole('button', { name: '提交生成任务' })).toBeEnabled());
  const inputs = within(video).getByRole('group', { name: '已连接参考' });
  expect(within(inputs).getByText('上游文本')).toBeVisible();
  expect(within(inputs).getByRole('img', { name: '参考image' })).toBeVisible();
  expect(within(inputs).getByText('参考video')).toBeVisible();
  expect(within(inputs).getByText('参考audio')).toBeVisible();
  const references = buildMediaTaskRequest(doc, 'video', 'r').references;
  expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ referenceMode: 'OMNI', references }));
  fireEvent.click(within(video).getByRole('button', { name: '首尾帧' }));
  expect(within(video).getByRole('button', { name: '提交生成任务' })).toBeDisabled();
  expect(await within(video).findByText('首尾帧仅支持图片，请切换全能参考')).toBeVisible();
  fireEvent.click(within(video).getByRole('button', { name: '全能参考' }));
  await waitFor(() => expect(within(video).getByRole('button', { name: '提交生成任务' })).toBeEnabled());
  fireEvent.click(within(video).getByRole('button', { name: '提交生成任务' }));
  await waitFor(() => expect(submit).toHaveBeenCalledOnce());
  expect(submit).toHaveBeenCalledWith(expect.objectContaining({ prompt: '自身提示词\n上游文本', referenceMode: 'OMNI', references }));
});

it('invalidates an old quote when a connected reference changes and ignores its late response', async () => {
  const doc = referenceDocument(); doc.edges = doc.edges.filter(edge => edge.source !== 'video');
  const pending: ((value: { points: string }) => void)[] = [];
  const quote = vi.fn(() => new Promise<{ points: string }>(resolve => pending.push(resolve)));
  const submit = vi.fn(async (_request: import("./media-task-port").CanvasMediaTaskRequest) => ({ taskRef: 'unused' }));
  render(<CanvasWorkspace projectId="refs" initialDocument={doc} storage={null}
    toolbar={(_, update) => <button onClick={() => update(current => ({ ...current, edges: current.edges.filter(edge => edge.source !== 'asset-2') }))}>断开音频</button>}
    mediaTaskPort={{ options: async () => [omniOption], quote, submit, read: async () => ({ state: 'working', taskRef: 'unused' }) }} />);
  const button = within(screen.getByTestId('node-video')).getByRole('button', { name: '提交生成任务' });
  await waitFor(() => expect(quote).toHaveBeenCalledTimes(1));
  fireEvent.click(screen.getByRole('button', { name: '断开音频' }));
  await waitFor(() => expect(quote).toHaveBeenCalledTimes(2));
  pending[0]({ points: '1.0000' });
  await waitFor(() => expect(button).toBeDisabled());
  pending[1]({ points: '2.0000' });
  await waitFor(() => expect(button).toBeEnabled());
  fireEvent.click(button);
  await waitFor(() => expect(submit).toHaveBeenCalledOnce());
  expect(submit.mock.calls[0][0].references).toHaveLength(2);
});

const depthChain = () => {
  let doc = addGenerationNode(referenceDocument(), 'depth', { x: 200, y: 200 }, 'now');
  doc.edges = doc.edges.filter(edge => edge.source === 'image' && edge.target === 'asset-0');
  doc = connectNodes(doc, 'asset-1', 'depth', 'now');
  doc = updateGenerationTask(doc, 'depth', { taskRef: 'depth-task', taskState: 'working', message: null }, 'now');
  doc = reconcileMediaTask(doc, 'depth', { taskRef: 'depth-task', state: 'succeeded', outputs: [
    { assetRef: 'depth-file', kind: 'video', alt: '白模结果', previewRef: 'https://cdn.example.test/depth.mp4', durationSeconds: 4 },
  ] }, 'now');
  return doc;
};

it.each(['depth', 'depth-task-output-0'])('only references the directly connected %s without inheriting its original video or text', (source) => {
  let doc = depthChain();
  doc = connectNodes(doc, 'text', 'depth', 'now');
  doc = connectNodes(doc, source, 'video', 'now');
  const request = buildMediaTaskRequest(doc, 'video', 'r');
  expect(request.inputAssetRefs).toEqual(['depth-file']);
  expect(request.prompt).toBe('自身提示词');
  expect(request.references).toEqual([{ fileId: 'depth-file', kind: 'VIDEO', durationSeconds: 4 }]);
});

it.each(['edge', 'chip'])('preserves selection and removes every reference represented by a collapsed %s, including after restore', async removal => {
  let doc = depthChain();
  // Old saved graphs may connect both the result and its visible generation node.
  doc = connectNodes(doc, 'depth-task-output-0', 'video', 'now');
  const changed = vi.fn();
  const quote = vi.fn(async () => ({ points: '2.0000' }));
  const submit = vi.fn(async (_request: import('./media-task-port').CanvasMediaTaskRequest) => ({ taskRef: 'submitted' }));
  render(<CanvasWorkspace projectId="refs" initialDocument={doc} storage={null} onDocumentChange={changed}
    mediaTaskPort={{ options: async () => [omniOption], quote, submit, read: async () => ({ taskRef: 'submitted', state: 'working' }) }} />);
  fireEvent.keyDown(screen.getByTestId('node-depth'), { key: 'Enter' });
  act(() => connectionFlow.props.onConnect({ source: 'depth', target: 'video' }));
  expect(connectionFlow.props.nodes.find((node: any) => node.id === 'depth').selected).toBe(true);
  const video = screen.getByTestId('node-video');
  const inputs = within(video).getByRole('group', { name: '已连接参考' });
  expect(within(inputs).getByText('白模结果')).toBeVisible();
  expect(within(inputs).queryByText('参考video')).not.toBeInTheDocument();
  const edge = connectionFlow.props.edges.find((item: any) => item.source === 'depth' && item.target === 'video');
  if (removal === 'edge') act(() => edge.data.onDelete());
  else fireEvent.click(within(video).getByRole('button', { name: '移除参考：白模结果' }));
  expect(within(video).queryByRole('group', { name: '已连接参考' })).not.toBeInTheDocument();
  const restored = canonicalizeCanvasDocument(JSON.parse(JSON.stringify(changed.mock.calls.at(-1)![0])), 'refs')!;
  expect(buildMediaTaskRequest(restored, 'video', 'r').inputAssetRefs).toEqual([]);
  expect(restored.nodes).toHaveLength(doc.nodes.length);
  expect(restored.edges).toEqual(doc.edges.filter(edge => edge.target !== 'video'));
  await waitFor(() => expect(within(video).getByRole('button', { name: '提交生成任务' })).toBeEnabled());
  fireEvent.click(within(video).getByRole('button', { name: '提交生成任务' }));
  await waitFor(() => expect(submit).toHaveBeenCalledOnce());
  expect(submit.mock.calls[0][0].inputAssetRefs).toEqual([]);
});

it('selects connected media through @, persists stable bindings and compiles submission ordinals', async () => {
  const submit = vi.fn(async (_request: import('./media-task-port').CanvasMediaTaskRequest) => ({ taskRef: 'mentions-task' }));
  let saved = referenceDocument();
  render(<CanvasWorkspace projectId="refs" initialDocument={saved} storage={null} onDocumentChange={doc => { saved = doc; }}
    toolbar={(_, update) => <button onClick={() => update(current => ({ ...current, edges: current.edges.filter(edge => edge.source !== 'asset-2') }))}>断开音频</button>}
    mediaTaskPort={{ options: async () => [omniOption], quote: async () => ({ points: '1.0000' }), submit, read: async () => ({ state: 'working', taskRef: 'mentions-task' }) }} />);
  const video = screen.getByTestId('node-video');
  for (const [index, label] of ['图片1', '视频1', '音频1'].entries()) {
    const input = within(video).getByRole('textbox', { name: '生成提示词' });
    const node = saved.nodes.find(node => node.id === 'video');
    const value = (node?.kind === 'generation' ? node.prompt : '') + ' @';
    if (input instanceof HTMLTextAreaElement) fireEvent.change(input, { target: { value, selectionStart: value.length, selectionEnd: value.length } });
    else { input.textContent = value; const range = document.createRange(); range.selectNodeContents(input); range.collapse(false); window.getSelection()?.removeAllRanges(); window.getSelection()?.addRange(range); fireEvent.input(input); }
    expect(within(within(video).getByRole('listbox')).getAllByRole('option')).toHaveLength(3);
    for (let i = 0; i < index; i++) fireEvent.keyDown(input, { key: 'ArrowDown' });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(within(video).getByRole('textbox', { name: '生成提示词' })).toHaveTextContent(label);
    expect(within(video).queryByRole('listbox')).not.toBeInTheDocument();
  }
  const restored = canonicalizeCanvasDocument(JSON.parse(JSON.stringify(saved)), 'refs')!;
  expect(buildMediaTaskRequest(restored, 'video', 'saved').prompt).toBe('自身提示词 图片1 视频1 音频1\n上游文本');
  const button = within(video).getByRole('button', { name: '提交生成任务' });
  await waitFor(() => expect(button).toBeEnabled());
  fireEvent.click(button);
  await waitFor(() => expect(submit).toHaveBeenCalledOnce());
  expect(submit.mock.calls[0][0]).toMatchObject({ prompt: '自身提示词 图片1 视频1 音频1\n上游文本', inputAssetRefs: ['file-0', 'file-1', 'file-2'] });
});

it.each(['OMNI', 'FIRST_LAST_FRAME'] as const)('uses current connections for mouse mentions in %s mode', mode => {
  const doc = referenceDocument();
  doc.nodes = doc.nodes.map(node => node.id === 'video' && node.kind === 'generation' ? { ...node, settings: { ...node.settings, referenceMode: mode } } : node);
  render(<CanvasWorkspace projectId="refs" initialDocument={doc} storage={null}
    toolbar={(_, update) => <button onClick={() => update(current => ({ ...current, edges: current.edges.filter(edge => edge.source !== 'asset-2') }))}>断开音频</button>}
    mediaTaskPort={{ options: async () => [omniOption], quote: async () => ({ points: '1.0000' }), submit: async () => ({ taskRef: 'unused' }), read: async () => ({ state: 'working', taskRef: 'unused' }) }} />);
  const video = screen.getByTestId('node-video');
  const input = within(video).getByRole('textbox', { name: '生成提示词' });
  fireEvent.change(input, { target: { value: '@', selectionStart: 1, selectionEnd: 1 } });
  const menu = within(video).getByRole('listbox');
  expect(within(menu).getAllByRole('option')).toHaveLength(mode === 'OMNI' ? 3 : 1);
  if (mode === 'OMNI') {
    fireEvent.click(screen.getByRole('button', { name: '断开音频' }));
    expect(within(menu).getAllByRole('option')).toHaveLength(2);
    expect(within(menu).queryByText('参考audio')).not.toBeInTheDocument();
  }
  // DOM implementations differ in whitespace between the label and its badge.
  fireEvent.click(within(menu).getByRole('option', { name: mode === 'OMNI' ? /^参考image\s*图片1$/ : /^参考image\s*首帧图片$/ }));
  expect(within(video).getByRole('textbox', { name: '生成提示词' })).toHaveTextContent(mode === 'OMNI' ? '图片1' : '首帧图片');
  expect(video).not.toHaveTextContent('输入 @');
});

it('removes text and media references without changing source nodes, settings or other connections', () => {
  const doc = referenceDocument();
  const changed = vi.fn();
  render(<CanvasWorkspace projectId="refs" initialDocument={doc} storage={null} onDocumentChange={changed} />);
  const video = screen.getByTestId('node-video');
  for (const label of ['参考video', '上游文本']) {
    fireEvent.click(within(video).getByRole('button', { name: `移除参考：${label}` }));
  }
  const updated = changed.mock.lastCall![0];
  expect(updated.nodes).toEqual(doc.nodes);
  expect(updated.edges).toEqual(doc.edges.filter(edge => !(edge.target === 'video' && ['asset-1', 'text'].includes(edge.source))));
  expect(buildMediaTaskRequest(updated, 'video', 'r')).toMatchObject({ prompt: '自身提示词', inputAssetRefs: ['file-0', 'file-2'] });
});

it.each(['OMNI', 'FIRST_LAST_FRAME'] as const)('quotes and submits text-only video without references in saved %s mode', async referenceMode => {
  const doc = referenceDocument();
  doc.nodes = doc.nodes.filter(node => node.id === 'video').map(node => node.kind === 'generation' ? { ...node, prompt: '小猫在草地玩耍', settings: { ...node.settings, referenceMode } } : node);
  doc.edges = [];
  const quote = vi.fn(async (_settings: import('./model').CanvasGenerationSettings) => ({ points: '2.0000' }));
  const submit = vi.fn(async (_request: import('./media-task-port').CanvasMediaTaskRequest) => ({ taskRef: 'text-video-task' }));
  render(<CanvasWorkspace projectId="refs" initialDocument={doc} storage={null}
    mediaTaskPort={{ options: async () => [{ ...omniOption, omniReference: null }], quote, submit, read: async () => ({ state: 'working', taskRef: 'text-video-task' }) }} />);
  const button = within(screen.getByTestId('node-video')).getByRole('button', { name: '提交生成任务' });
  await waitFor(() => expect(button).toBeEnabled());
  expect(quote).toHaveBeenCalled();
  expect(quote.mock.lastCall![0]).not.toHaveProperty('referenceMode');
  expect(quote.mock.lastCall![0]).not.toHaveProperty('references');
  fireEvent.click(button);
  await waitFor(() => expect(submit).toHaveBeenCalledOnce());
  expect(submit.mock.calls[0][0]).toMatchObject({ prompt: '小猫在草地玩耍', settings: { type: 'VIDEO' }, inputAssetRefs: [] });
  expect(submit.mock.calls[0][0]).not.toHaveProperty('referenceMode');
  expect(submit.mock.calls[0][0].settings).not.toHaveProperty('referenceMode');
  expect(submit.mock.calls[0][0]).not.toHaveProperty('references');
  expect(doc.nodes[0]).toMatchObject({ settings: { referenceMode } });
});
