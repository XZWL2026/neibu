import { webcrypto } from 'node:crypto';
import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { configureGateway } from '../api';
import { ProductionCanvas } from './ProductionCanvas';
import { CanvasNodes } from './CanvasNodes';
import * as canvasApi from './production-api';
import * as userSession from '../userSession';
import { CreatorMediaProvider } from '../creator/CreatorMediaProvider';
import { collectCanvasInputs } from './media-task-port';
import { copyGenerationDefaults } from './generation-defaults';

// Persistence races use the existing coordinator callbacks. Actual React Flow interactions
// and accessibility are covered in canvas-accessibility.test.tsx.
const surfaceState = vi.hoisted(() => ({ onUploadAt: null as null | ((position: { x: number; y: number }) => void), onConnect: null as null | ((source: string, target: string) => void), onReadGeneration: null as null | ((id: string) => void) }));
vi.mock('./CanvasSurface', () => ({ CanvasSurface: (props: any) => { surfaceState.onUploadAt = props.onUploadAt; surfaceState.onConnect = props.onConnect; surfaceState.onReadGeneration = props.onReadGeneration; return <div>{props.document.nodes.map((node: any) => <CanvasNodes key={node.id} data={{ ...props, node, inputs: node.kind === 'generation' ? collectCanvasInputs(props.document, node.id) : undefined, quote: props.nodeQuotes?.[node.id] ?? { state: 'idle' }, selected: props.selectedNodeIds.includes(node.id), onSelect: () => props.onSelectionChange([node.id]), onKeyboardConnect: () => {}, keyboardSourceId: null }} />)}</div>; } }));
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
let remote: any;
let fetchMock: ReturnType<typeof vi.fn>;
const session = { authenticated: true, csrfToken: 'csrf', roles: ['USER'], primary: false, user: null };
beforeEach(() => {
  sessionStorage.clear();
  configureGateway({});
  remote = { id: 'canvas-1', name: '服务端画布', version: 4, ownerId: 'user-1', deleted: false,
    createdAt: '2026-09-08', updatedAt: '2026-09-08', content: { nodes: [{ id: 'text-1', kind: 'text', properties: { text: '服务端提示词', x: '20', y: '30' } }], edges: [], viewport: { x: 0, y: 0, zoom: 1 } } };
  fetchMock = vi.fn(async (url: string, options: RequestInit) => {
    if (url.endsWith('/auth/session')) return json(session);
    if (url.endsWith('/generation-options')) return json({ models: generationOptions });
    if (url.endsWith('/quotes')) return json({ points: '12.3400' });
    if (options.method === 'PUT') {
      const body = JSON.parse(options.body as string);
      if (body.expectedVersion !== remote.version) return json({}, 409);
      remote = { ...remote, version: remote.version + 1, name: body.name ?? remote.name, content: body.content };
    }
    if (options.method === 'DELETE') return json({ ...remote, deleted: true, version: remote.version + 1 });
    if (url.endsWith('/copies')) { remote = { ...remote, id: 'copy-1', name: '副本', version: 1 }; return json(remote, 201); }
    if (url.endsWith('/canvases') && options.method === 'POST') { remote = { ...remote, id: 'new-1', version: 1, content: { ...remote.content, nodes: [], edges: [] } }; return json(remote, 201); }
    return json(remote);
  });
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });
function show(path = '/canvas?id=canvas-1') {
  const router = createMemoryRouter([{ path: '/canvas', element: <ProductionCanvas /> }], { initialEntries: [path] });
  render(<RouterProvider router={router} />);
  return router;
}

it('automatically saves content after editing stops and saves immediately with Ctrl+S', async () => {
  show();
  await screen.findByDisplayValue('服务端提示词');
  await waitFor(() => expect(screen.getByRole('button', { name: '保存画布' })).toBeEnabled());
  fireEvent.change(screen.getByLabelText('提示词'), { target: { value: '自动保存的创意' } });
  expect(remote.version).toBe(4);
  await waitFor(() => expect(remote.version).toBe(5), { timeout: 2000 });
  expect(remote.content.nodes[0].properties.text).toBe('自动保存的创意');
  fireEvent.change(screen.getByLabelText('画布名称'), { target: { value: '我的创意' } });
  fireEvent.keyDown(window, { key: 's', ctrlKey: true });
  await waitFor(() => expect(remote.version).toBe(6));
  expect(remote.name).toBe('我的创意');
});

it('refreshes a clean editor from another tab but preserves dirty edits and pauses auto-save on conflict', async () => {
  show();
  await screen.findByDisplayValue('服务端提示词');
  await waitFor(() => expect(screen.getByRole('button', { name: '保存画布' })).toBeEnabled());
  remote.version = 5; remote.content.nodes[0].properties.text = '其他页面已保存';
  fireEvent(window, new StorageEvent('storage', { key: 'xzwl-canvas-revision:user-1:canvas-1', newValue: '5' }));
  await screen.findByDisplayValue('其他页面已保存');
  fireEvent.change(screen.getByLabelText('提示词'), { target: { value: '保留本页草稿' } });
  remote.version = 6; remote.content.nodes[0].properties.text = '另一个新版本';
  fireEvent(window, new StorageEvent('storage', { key: 'xzwl-canvas-revision:user-1:canvas-1', newValue: '6' }));
  await screen.findByRole('button', { name: '另存当前编辑' });
  expect(screen.getByLabelText('提示词')).toHaveValue('保留本页草稿');
  expect(screen.getByRole('button', { name: '保存画布' })).toBeDisabled();
  expect(screen.getByRole('button', { name: '读取最新版本' })).toBeEnabled();
  fireEvent.click(screen.getByRole('button', { name: '读取最新版本' }));
  expect(screen.getByRole('group', { name: '确认画布操作' })).toBeVisible();
  expect(remote.content.nodes[0].properties.text).toBe('另一个新版本');
});


it.each([
  {kind: 'IMAGE', reviewAvailable: true}, {kind: 'IMAGE', reviewAvailable: false},
  {kind: 'VIDEO', reviewAvailable: true}, {kind: 'VIDEO', reviewAvailable: false},
] as const)('卡片上传 $kind 原位替换并保留位置、连线，重开后仍为同一张无边框媒体（审核可用=$reviewAvailable）', async ({kind, reviewAvailable}) => {
  URL.createObjectURL = vi.fn(() => 'blob:card-upload'); URL.revokeObjectURL = vi.fn();
  const video = kind === 'VIDEO';
  const uploadPath = video ? '/processing-videos' : '/reference-images';
  const filename = video ? '卡片视频.mp4' : '卡片图片.png';
  remote.content.nodes = [
    { id: 'upload-card', kind: 'generation', properties: { x: '140', y: '220', taskState: 'idle', prompt: '选择文件前已填写的提示词', settings: JSON.stringify(copyGenerationDefaults(kind)) } },
    { id: 'downstream', kind: 'generation', properties: { x: '600', y: '220', taskState: 'idle' } },
  ];
  remote.content.edges = [{ id: 'existing-edge', source: 'upload-card', target: 'downstream' }];
  const base = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  fetchMock.mockImplementation(async (url, options) => {
    if (!reviewAvailable && url.endsWith('/generation-options')) return json({ models: generationOptions.filter(option => option.type === 'IMAGE') });
    if (url.endsWith(uploadPath)) return json({ fileId: 'card-file', sizeBytes: 100, ...(video ? {durationSeconds: 5} : {}) });
    if (url.includes('/media/portraits?')) return json([]);
    if (url.endsWith('/media/portraits')) return json({ id: 'card-review', fileId: 'card-file', model: 'seedance-2.5', kind, ...(video ? { durationSeconds: 5 } : {}), status: 'PROCESSING' }, 202);
    if (url.endsWith('/access-url')) return json({ url: 'https://cdn.example.test/card.png' });
    return base(url, options);
  });
  show();
  const card = await screen.findByTestId('node-upload-card');
  await waitFor(() => expect(screen.getByLabelText('本地上传文件')).toBeEnabled());
  fireEvent.click(within(card).getByRole('button', { name: video ? '上传视频' : '上传图片' }));
  expect(screen.getByLabelText('本地上传文件')).not.toHaveAttribute('multiple');
  expect(screen.getByLabelText('本地上传文件')).toHaveAttribute('accept', video ? 'video/mp4,video/quicktime' : 'image/png,image/jpeg');
  fireEvent.change(screen.getByLabelText('本地上传文件'), { target: { files: [new File(['media'], filename, { type: video ? 'video/mp4' : 'image/png' })] } });
  if (video) expect(await screen.findByLabelText(filename)).toHaveAttribute('controls');
  else expect(await within(screen.getByTestId('node-upload-card')).findByRole('img', { name: filename })).toBeInTheDocument();
  await waitFor(() => expect(remote.content.nodes.find((node: any) => node.id === 'upload-card')).toMatchObject({ kind: kind.toLowerCase(), properties: { x: '140', y: '220', publicId: 'card-file',
    ...(reviewAvailable ? { reviewId: 'card-review' } : { reviewStatus: 'UNAVAILABLE' }) } }));
  expect(remote.content.nodes).toHaveLength(2);
  expect(remote.content.edges).toEqual([expect.objectContaining({ source: 'upload-card', target: 'downstream' })]);
  cleanup(); show();
  const restored = await screen.findByTestId('node-upload-card');
  if (video) expect(within(restored).getByLabelText(filename)).toHaveAttribute('controls');
  else expect(within(restored).getByRole('img', { name: filename })).toBeInTheDocument();
  expect(restored).toHaveClass(`canvas-${kind.toLowerCase()}-node`);
  expect(within(restored).queryByRole('button', { name: '放大预览' })).not.toBeInTheDocument();
  expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith(uploadPath))).toHaveLength(1);
  expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/media/portraits'))).toHaveLength(reviewAvailable ? 1 : 0);
});

it.each(['图片', '视频'])('取消%s卡片上传后保留卡片，拒绝错类文件，工具栏上传恢复全部类型', async kind => {
  show(); await screen.findByDisplayValue('服务端画布');
  await waitFor(() => expect(screen.getByLabelText('本地上传文件')).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: `新增${kind}` }));
  const card = screen.getByRole('article', { name: /^生成节点 / });
  fireEvent.click(within(card).getByRole('button', { name: `上传${kind}` }));
  fireEvent.change(screen.getByLabelText('本地上传文件'), { target: { files: [new File(['wrong'], 'wrong.file', { type: kind === '图片' ? 'video/mp4' : 'image/png' })] } });
  fireEvent.click(within(card).getByRole('button', { name: `上传${kind}` }));
  fireEvent(screen.getByLabelText('本地上传文件'), new Event('cancel'));
  expect(within(card).getByRole('button', { name: `图生${kind}` })).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '本地上传' }));
  expect(screen.getByLabelText('本地上传文件').getAttribute('accept')).toContain('video/mp4');
  expect(fetchMock.mock.calls.some(([url]) => /\/(reference-images|processing-videos)$/.test(String(url)))).toBe(false);
});

it.each([{kind: '图片', action: '删除'}, {kind: '图片', action: '图生图片'}, {kind: '视频', action: '删除'}, {kind: '视频', action: '图生视频'}, {kind: '图片', action: '编辑提示词'}, {kind: '视频', action: '编辑提示词'}])('上传$kind期间$action，不让迟到媒体覆盖当前操作或新增游离节点', async ({kind, action}) => {
  const video = kind === '视频';
  URL.createObjectURL = vi.fn(() => 'blob:late-card'); URL.revokeObjectURL = vi.fn();
  let finishUpload!: (response: Response) => void;
  const base = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  fetchMock.mockImplementation((url, options) => url.endsWith(video ? '/processing-videos' : '/reference-images') ? new Promise(resolve => { finishUpload = resolve; })
    : url.endsWith('/access-url') ? Promise.resolve(json({ url: 'https://cdn.example.test/late.png' }))
    : url.includes('/media/portraits?') ? Promise.resolve(json([])) : base(url, options));
  show(); await screen.findByDisplayValue('服务端画布');
  await waitFor(() => expect(screen.getByLabelText('本地上传文件')).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: `新增${kind}` }));
  const card = screen.getByRole('article', { name: /^生成节点 / });
  const originalId = card.dataset.testid!;
  fireEvent.click(within(card).getByRole('button', { name: `上传${kind}` }));
  fireEvent.change(screen.getByLabelText('本地上传文件'), { target: { files: [new File(['media'], '迟到媒体', { type: video ? 'video/mp4' : 'image/png' })] } });
  await waitFor(() => expect(finishUpload).toBeDefined());
  if (action === '删除') fireEvent.click(screen.getByRole('button', { name: '删除所选节点' }));
  else if (action === '编辑提示词') fireEvent.change(within(card).getByLabelText('生成提示词'), { target: { value: '上传后新编辑' } });
  else fireEvent.click(within(card).getByRole('button', { name: action }));
  await act(async () => { finishUpload(json({ fileId: 'late-card-file', sizeBytes: 5, ...(video ? { durationSeconds: 5 } : {}) })); });
  await waitFor(() => expect(screen.getByRole('button', { name: '本地上传' })).toBeEnabled());
  expect(screen.queryByRole('img', { name: '迟到媒体' })).not.toBeInTheDocument();
  expect(screen.queryByLabelText('迟到媒体')).not.toBeInTheDocument();
  if (action === '编辑提示词') expect(within(card).getByLabelText('生成提示词')).toHaveValue('上传后新编辑');
  if (action === '删除') expect(screen.queryByTestId(originalId)).not.toBeInTheDocument();
  else expect(within(screen.getByTestId(originalId)).getByRole('textbox', { name: '生成提示词' })).toBeInTheDocument();
});

it.each(['图片', '视频'])('%s自身提示词保存重开后通过生产 SDK 提交，无文本节点或连线', async (label) => {
  remote.content.nodes = [];
  const baseFetch = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  fetchMock.mockImplementation((url: string, options: RequestInit) => url.endsWith('/media/tasks')
    ? Promise.resolve(json({ id: 'own-prompt-task' }, 202)) : baseFetch(url, options));
  show();
  await screen.findByText('已加载 · 版本 4');
  await waitFor(() => expect(screen.getByRole('button', { name: `新增${label}` })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: `新增${label}` }));
  await waitFor(() => expect(screen.getByRole('button', { name: '提交生成任务' })).toBeEnabled());
  fireEvent.change(screen.getByLabelText('生成提示词'), { target: { value: `${label}自己的提示词` } });
  fireEvent.click(screen.getByRole('button', { name: '保存画布' }));
  await waitFor(() => expect(remote.content.nodes).toHaveLength(1));
  expect(remote.content.nodes[0].properties.prompt).toBe(`${label}自己的提示词`);
  expect(remote.content.edges).toEqual([]);
  cleanup();
  show();
  await screen.findByDisplayValue(`${label}自己的提示词`);
  await waitFor(() => expect(screen.getByRole('button', { name: '提交生成任务' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '提交生成任务' }));
  await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/media/tasks'))).toHaveLength(1));
  const submit = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/media/tasks'))!;
  expect(JSON.parse(submit[1].body as string)).toMatchObject({ prompt: `${label}自己的提示词`, type: label === '图片' ? 'IMAGE' : 'VIDEO', references: [] });
  expect(submit[1].headers.get('Idempotency-Key')).toBe(JSON.parse(remote.content.nodes[0].properties.runs).at(-1).request.requestRef);
  await waitFor(() => expect(remote.content.nodes[0].properties.taskRef).toBe('own-prompt-task'));
  expect(remote.content.nodes).toHaveLength(1);
  expect(remote.content.edges).toEqual([]);
});

it.each(['保存画布', '返回画布首页', '返回画布首页 · YOMIO'])('修改已有画布名称后通过%s持久保存', async action => {
  show();
  const input = await screen.findByDisplayValue('服务端画布');
  expect(input).not.toHaveAttribute('readonly');
  fireEvent.change(input, { target: { value: '  我的分镜  ' } });
  expect(screen.getByRole('status', { name: '画布保存状态' })).toHaveTextContent('未保存');
  fireEvent.click(screen.getByRole(action.includes('YOMIO') ? 'link' : 'button', { name: action }));
  await waitFor(() => expect(remote.name).toBe('我的分镜'));
  expect(remote.content.nodes[0].properties.text).toBe('服务端提示词');
  cleanup();
  show();
  await screen.findByDisplayValue('我的分镜');
});

it('名称为空或保存冲突时保留名称编辑，重新加载须确认', async () => {
  show();
  const input = await screen.findByDisplayValue('服务端画布');
  fireEvent.change(input, { target: { value: '  ' } });
  fireEvent.click(screen.getByRole('button', { name: '保存画布' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('请填写画布名称');
  expect(remote.version).toBe(4);
  fireEvent.change(input, { target: { value: '未保存新名' } });
  remote.version++;
  fireEvent.click(screen.getByRole('button', { name: '保存画布' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('冲突');
  expect(input).toHaveValue('未保存新名');
  fireEvent.click(screen.getByRole('button', { name: '重新加载' }));
  expect(screen.getByRole('group', { name: '确认画布操作' })).toBeVisible();
});

it('loads from Canvas, retains edits across a save, and sends the server version', async () => {
  const getItem = vi.fn(); const setItem = vi.fn();
  vi.stubGlobal('localStorage', { getItem, setItem });
  show();
  await waitFor(() => expect(screen.getByLabelText('提示词')).toBeVisible());
  fireEvent.change(screen.getByLabelText('提示词'), { target: { value: '新的编辑' } });
  fireEvent.click(screen.getByRole('button', { name: '保存画布' }));
  await waitFor(() => expect(remote.version).toBe(5));
  expect(remote.content.nodes[0].properties.text).toBe('新的编辑');
  expect(screen.getByLabelText('提示词')).toHaveValue('新的编辑');
  expect(setItem).toHaveBeenCalledExactlyOnceWith('xzwl-canvas-revision:user-1:canvas-1', '5');
  expect(getItem).not.toHaveBeenCalled();
});

it('saves edited content before returning to the canvas home', async () => {
  const router = show();
  await screen.findByDisplayValue('服务端提示词');
  await waitFor(() => expect(screen.getByRole('button', { name: '保存画布' })).toBeEnabled());
  fireEvent.change(screen.getByLabelText('提示词'), { target: { value: '返回前保存' } });
  fireEvent.click(screen.getByRole('button', { name: '返回画布首页' }));
  await waitFor(() => expect(router.state.location.search).toBe(''));
  expect(remote.content.nodes[0].properties.text).toBe('返回前保存');
});

it('a failed save on return leaves the editor open with edits intact', async () => {
  const router = show();
  await screen.findByDisplayValue('服务端提示词');
  await waitFor(() => expect(screen.getByRole('button', { name: '保存画布' })).toBeEnabled());
  fireEvent.change(screen.getByLabelText('提示词'), { target: { value: '保留未保存内容' } });
  remote.version++;
  fireEvent.click(screen.getByRole('button', { name: '返回画布首页' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('冲突');
  expect(router.state.location.search).toBe('?id=canvas-1');
  expect(screen.getByLabelText('提示词')).toHaveValue('保留未保存内容');
});

it('waits for an in-flight generation response and its saved task reference before allowing return', async () => {
  remote.content.nodes.push({ id: 'generation-1', kind: 'generation', properties: { taskState: 'idle' } });
  remote.content.edges.push({ id: 'connected', source: 'text-1', target: 'generation-1' });
  const baseFetch = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  let finish!: (response: Response) => void;
  fetchMock.mockImplementation((url: string, options: RequestInit) => url.endsWith('/media/tasks')
    ? new Promise<Response>(resolve => { finish = resolve; }) : baseFetch(url, options));
  const router = show();
  await waitFor(() => expect(screen.getByRole('button', { name: '提交生成任务' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '提交生成任务' }));
  await waitFor(() => expect(finish).toBeDefined());
  const back = screen.getByRole('button', { name: '返回画布首页' });
  expect(back).toBeDisabled();
  fireEvent.click(back);
  expect(router.state.location.search).toBe('?id=canvas-1');
  await act(async () => finish(json({ id: 'in-flight-task' }, 202)));
  await waitFor(() => expect(remote.content.nodes[1].properties.taskRef).toBe('in-flight-task'));
  await waitFor(() => expect(back).toBeEnabled());
  fireEvent.click(back);
  await waitFor(() => expect(router.state.location.search).toBe(''));
});

it('409 preserves draft, blocks blind retry, and offers explicit reload or save as new', async () => {
  show();
  await screen.findByDisplayValue('服务端提示词');
  await waitFor(() => expect(screen.getByRole('button', { name: '保存画布' })).toBeEnabled());
  fireEvent.change(screen.getByLabelText('提示词'), { target: { value: '保留我的编辑' } });
  remote = { ...remote, version: 5 };
  fireEvent.click(screen.getByRole('button', { name: '保存画布' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('冲突');
  expect(screen.getByLabelText('提示词')).toHaveValue('保留我的编辑');
  expect(screen.getByRole('button', { name: '保存画布' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: '另存为新画布' }));
  await waitFor(() => expect(remote.content.nodes[0].properties.text).toBe('保留我的编辑'));
});

it('creates without losing the unsaved draft, copies the saved snapshot, and deletes with version', async () => {
  const router = show('/canvas');
  fireEvent.click(screen.getByRole('button', { name: '新增文本节点' }));
  fireEvent.change(screen.getByLabelText('提示词'), { target: { value: '新画布草稿' } });
  fireEvent.click(screen.getByRole('button', { name: '创建画布' }));
  await waitFor(() => expect(router.state.location.search).toContain('new-1'));
  await screen.findByText('已加载 · 版本 2');
  await waitFor(() => expect(screen.getByDisplayValue('新画布草稿')).toBeVisible());
  await waitFor(() => expect(screen.getByRole('button', { name: '复制画布' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '复制画布' }));
  await waitFor(() => expect(router.state.location.search).toContain('copy-1'));
  fireEvent.click(await screen.findByRole('button', { name: '删除画布' }));
  fireEvent.click(screen.getByRole('button', { name: '确认删除画布' }));
  await waitFor(() => expect(router.state.location.search).toBe(''));
  expect(fetchMock.mock.calls.some(([url, options]) => options.method === 'DELETE' && String(url).includes('expectedVersion='))).toBe(true);
});

it('saves a new draft before the YOMIO back link returns to the canvas page', async () => {
  const router = show('/canvas');
  fireEvent.click(screen.getByRole('button', { name: '新增文本节点' }));
  fireEvent.change(screen.getByLabelText('提示词'), { target: { value: '返回前的新草稿' } });
  fireEvent.click(screen.getByRole('link', { name: '返回画布首页 · YOMIO' }));
  await waitFor(() => expect(remote.content.nodes[0].properties.text).toBe('返回前的新草稿'));
  expect(router.state.location.pathname + router.state.location.search).toBe('/canvas');
  expect(remote.id).toBe('new-1');
  expect(fetchMock.mock.calls.filter(([url, options]) => url.endsWith('/canvases') && options.method === 'POST')).toHaveLength(1);
});

it('read errors are recoverable and never fall back to local production data', async () => {
  fetchMock.mockResolvedValue(json({}, 502));
  show();
  expect(await screen.findByRole('alert')).toHaveTextContent('暂不可用');
  expect(screen.queryByLabelText('提示词')).not.toBeInTheDocument();
  fetchMock.mockResolvedValue(json(remote));
  fireEvent.click(screen.getByRole('button', { name: '重试加载' }));
  await waitFor(() => expect(screen.getByLabelText('提示词')).toBeVisible());
});

it('late responses cannot replace a different canvas', async () => {
  let resolve!: (value: Response) => void;
  fetchMock.mockImplementationOnce(() => new Promise<Response>(r => { resolve = r; }));
  const router = show();
  remote = { ...remote, id: 'canvas-2' };
  await act(() => router.navigate('/canvas?id=canvas-2'));
  await screen.findByDisplayValue('服务端提示词');
  await waitFor(() => expect(screen.getByRole('button', { name: '保存画布' })).toBeEnabled());
  await act(async () => resolve(json({ ...remote, name: '迟到画布' })));
  expect(screen.queryByDisplayValue('迟到画布')).not.toBeInTheDocument();
});

it('submits once, recovers a transient read failure, then persists partial video results and their file ID', async () => {
  remote.content.nodes.push({ id: 'generation-1', kind: 'generation', properties: { x: '300', y: '30', taskState: 'idle', taskRef: '', message: '' } });
  remote.content.edges.push({ id: 'edge-1', source: 'text-1', target: 'generation-1' });
  const baseFetch = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  let taskStatus = 'PARTIAL_SUCCESS';
  let readFailure = true;
  fetchMock.mockImplementation(async (url: string, options: RequestInit) => {
    if (url.endsWith('/media/tasks')) return json({ id: 'task-1', status: 'RUNNING', type: 'VIDEO' }, 202);
    if (url.endsWith('/tasks/task-1')) {
      if (readFailure) { readFailure = false; return json({}, 502); }
      return json({ id: 'task-1', status: taskStatus, type: 'VIDEO' });
    }
    if (url.endsWith('/results')) return json([{ index: 0, fileId: 'video-file-1' }]);
    if (url.endsWith('/access-url')) return json({ url: 'https://cdn.example.test/result.mp4', expiresAt: null });
    if (url.endsWith('/media-references')) {
      const body = JSON.parse(options.body as string);
      expect(body).toMatchObject({ expectedVersion: remote.version, publicId: 'video-file-1', kind: 'VIDEO' });
      remote = { ...remote, version: remote.version + 1, content: { ...remote.content,
        nodes: [...remote.content.nodes, { id: body.nodeId, kind: 'video', properties: { publicId: body.publicId } }] } };
      return json(remote);
    }
    return baseFetch(url, options);
  });
  show();
  await waitFor(() => expect(screen.getByLabelText('提示词')).toBeVisible());
  chooseCanvasOption(screen.getByLabelText('生成类型'), 'VIDEO');
  await waitFor(() => expect(screen.getByRole('button', { name: '提交生成任务' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '提交生成任务' }));
  fireEvent.click(screen.getByRole('button', { name: '提交生成任务' }));
  await waitFor(() => expect(remote.content.nodes[1].properties.taskRef).toBe('task-1'));
  await waitFor(() => expect(screen.getByRole('button', { name: '保存画布' })).toBeEnabled());
  act(() => surfaceState.onReadGeneration!('generation-1'));
  expect(await screen.findByText(/生成服务暂不可用/)).toBeVisible();
  expect(remote.content.nodes[1].properties.taskState).toBe('accepted');
  act(() => surfaceState.onReadGeneration!('generation-1'));
  await waitFor(() => expect(remote.content.nodes.some((node: any) => node.properties.publicId === 'video-file-1' && node.properties.x)).toBe(true));
  expect(screen.getByLabelText('生成视频 1')).toHaveAttribute('src', 'https://cdn.example.test/result.mp4');
  expect(remote.content.nodes[1].properties.taskState).toBe('partial_success');
  expect(remote.content.edges).toHaveLength(2);
  expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/media/tasks'))).toHaveLength(1);
  const submit = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/media/tasks'))!;
  expect(JSON.parse(submit[1].body)).toMatchObject({ type: 'VIDEO', model: 'seedance-2.5', aspectRatio: '16:9', resolution: '720p', count: 1, durationSeconds: 5 });
  expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/media-references'))).toHaveLength(1);
  // Terminal reads cannot add a second copy of the output.
  act(() => surfaceState.onReadGeneration!('generation-1'));
  await waitFor(() => expect(screen.getByRole('button', { name: '保存画布' })).toBeEnabled());
  expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/media-references'))).toHaveLength(1);
});

it('uploads a real multipart File and retains fileId when preview resolution fails', async () => {
  const baseFetch = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  fetchMock.mockImplementation(async (url: string, options: RequestInit) => {
    if (url.endsWith('/reference-images')) {
      expect(options.body).toBeInstanceOf(FormData);
      expect((options.body as FormData).get('file')).toBeInstanceOf(File);
      expect(new Headers(options.headers).has('Content-Type')).toBe(false);
      return json({ fileId: 'uploaded-file', mimeType: 'image/png', sizeBytes: 4, purpose: 'REFERENCE_IMAGE', createdAt: null }, 201);
    }
    if (url.endsWith('/access-url')) return json({}, 502);
    return baseFetch(url, options);
  });
  show();
  await waitFor(() => expect(screen.getByLabelText('提示词')).toBeVisible());
  await waitFor(() => expect(screen.getByLabelText('本地上传文件')).toBeEnabled());
  fireEvent.change(screen.getByLabelText('本地上传文件'), { target: { files: [new File(['test'], 'reference.png', { type: 'image/png' })] } });
  expect(await screen.findByText('预览不可用')).toBeVisible();
  expect(screen.queryByText('reference.png')).not.toBeInTheDocument();
});

it('polls a persisted in-flight task and stops after leaving the canvas', async () => {
  remote.content.nodes.push({ id: 'generation-1', kind: 'generation', properties: { x: '300', y: '30', taskState: 'working', taskRef: 'task-1', message: '生成处理中' } });
  const baseFetch = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  fetchMock.mockImplementation(async (url: string, options: RequestInit) => url.endsWith('/tasks/task-1')
    ? json({ id: 'task-1', status: 'RUNNING', type: 'IMAGE' }) : baseFetch(url, options));
  vi.useFakeTimers();
  try {
    await act(async () => { show(); });
    await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/tasks/task-1'))).toHaveLength(1);
    cleanup();
    await act(async () => { await vi.advanceTimersByTimeAsync(9000); });
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/tasks/task-1'))).toHaveLength(1);
  } finally { vi.useRealTimers(); }
});

it('delegates 401 to the configured Gateway login redirect', async () => {
  const onUnauthorized = vi.fn();
  configureGateway({ onUnauthorized });
  fetchMock.mockResolvedValue(json({}, 401));
  show();
  expect(await screen.findByRole('alert')).toHaveTextContent('正在跳转登录');
  expect(onUnauthorized).toHaveBeenCalledTimes(1);
});

const generationOptions = [
  { type: 'IMAGE', model: 'seedream-5.0-lite', displayName: 'Seedream 5.0 Lite', resolutions: ['2K', '4K'], aspectRatios: ['1:1', '16:9'], maxCount: 4, minDurationSeconds: 0, maxDurationSeconds: 0 },
  { type: 'VIDEO', model: 'seedance-2.5', displayName: 'Seedance 2.5', resolutions: ['720p', '1080p'], aspectRatios: ['16:9', '1:1'], maxCount: 1, minDurationSeconds: 2, maxDurationSeconds: 12, omniReference: { maxTotal: 12, maxImages: 9, maxVideos: 3, maxAudios: 3, maxVideoDurationSeconds: 15, maxAudioDurationSeconds: 15, maxVideoBytes: 209715200, maxAudioBytes: 15728640, audioOnly: false } },
];

it('initializes new nodes from the backend catalog when every model and resolution differs from defaults', async () => {
  const baseFetch = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  fetchMock.mockImplementation(async (url: string, options: RequestInit) => url.endsWith('/generation-options')
    ? json({ models: [
      { type: 'IMAGE', model: 'configured-image', displayName: 'Configured image', resolutions: ['configured-size'], aspectRatios: ['4:3'], maxCount: 2, minDurationSeconds: 0, maxDurationSeconds: 0 },
      { type: 'VIDEO', model: 'configured-video', displayName: 'Configured video', resolutions: ['configured-video-size'], aspectRatios: ['9:16'], maxCount: 1, minDurationSeconds: 8, maxDurationSeconds: 10 },
    ] }) : baseFetch(url, options));
  show();
  await screen.findByDisplayValue('服务端提示词');
  await waitFor(() => expect(screen.getByRole('button', { name: '保存画布' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '新增生成占位节点' }));
  const node = within(screen.getByRole('article', { name: /^生成节点 / }));
  await waitFor(() => expect(node.getByLabelText('模型')).toHaveValue('configured-image'));
  expect(node.getByRole('button', { name: '比例与分辨率' })).toHaveTextContent('configured-size');
  expect(node.getByRole('button', { name: '比例与分辨率' })).toHaveTextContent('4:3');
  chooseCanvasOption(node.getByLabelText('生成类型'), 'VIDEO');
  await waitFor(() => expect(node.getByLabelText('模型')).toHaveValue('configured-video'));
  expect(node.getByRole('button', { name: '比例与分辨率' })).toHaveTextContent('configured-video-size');
  expect(node.getByLabelText('时长设置')).toHaveTextContent('8s');
  await waitFor(() => expect(fetchMock.mock.calls.some(([url, options]) => String(url).endsWith('/quotes')
    && JSON.parse(options.body).model === 'configured-video' && JSON.parse(options.body).durationSeconds === 8)).toBe(true));
});

it('keeps all generation controls inside nodes and creates the production port once across edits', async () => {
  const factory = vi.spyOn(canvasApi, 'createProductionMediaPort');
  show();
  await screen.findByDisplayValue('服务端提示词');
  await waitFor(() => expect(screen.getByRole('button', { name: '保存画布' })).toBeEnabled());
  expect(screen.queryByRole('textbox', { name: '模型' })).not.toBeInTheDocument();
  expect(screen.queryByRole('textbox', { name: '分辨率' })).not.toBeInTheDocument();
  expect(screen.queryByLabelText('生成类型')).not.toBeInTheDocument();
  expect(screen.queryByLabelText('生成数量')).not.toBeInTheDocument();
  expect(screen.queryByLabelText('视频秒数')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '新增生成占位节点' }));
  fireEvent.click(screen.getByRole('button', { name: '新增生成占位节点' }));
  const nodes = screen.getAllByRole('article', { name: /^生成节点 / });
  expect(nodes).toHaveLength(2);
  expect(within(nodes[0]).getByLabelText('生成类型')).toHaveValue('IMAGE');
  chooseCanvasOption(within(nodes[0]).getByLabelText('生成类型'), 'VIDEO');
  expect(within(nodes[1]).getByLabelText('生成类型')).toHaveValue('IMAGE');
  fireEvent.change(screen.getByLabelText('提示词'), { target: { value: '编辑提示词' } });
  expect(factory).toHaveBeenCalledTimes(1);
  expect(factory).toHaveBeenCalledWith();
});

it('submits two independently configured nodes without a separate save click and locks pending requests', async () => {
  for (const id of ['generation-1', 'generation-2']) {
    remote.content.nodes.push({ id, kind: 'generation', properties: { x: '300', y: '30', taskState: 'idle', taskRef: '', message: '' } });
    remote.content.edges.push({ id: `edge-${id}`, source: 'text-1', target: id });
  }
  const baseFetch = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  const pending: Array<() => void> = [];
  fetchMock.mockImplementation(async (url: string, options: RequestInit) => {
    if (url.endsWith('/media/tasks')) return new Promise<Response>(resolve => {
      const index = pending.length + 1;
      pending.push(() => resolve(json({ id: `task-${index}` }, 202)));
    });
    return baseFetch(url, options);
  });
  show();
  await screen.findByDisplayValue('服务端提示词');
  await waitFor(() => expect(screen.getByRole('button', { name: '保存画布' })).toBeEnabled());
  const image = within(screen.getByRole('article', { name: '生成节点 generation-1' }));
  const video = within(screen.getByRole('article', { name: '生成节点 generation-2' }));
  await waitFor(() => expect(image.getByRole('button', { name: '提交生成任务' })).toBeEnabled());
  fireEvent.change(image.getByLabelText('图片数量'), { target: { value: '2' } });
  chooseCanvasOption(video.getByLabelText('生成类型'), 'VIDEO');
  await waitFor(() => expect(image.getByRole('button', { name: '提交生成任务' })).toBeEnabled());
  await waitFor(() => expect(video.getByRole('button', { name: '提交生成任务' })).toBeEnabled());
  const first = image.getByRole('button', { name: '提交生成任务' });
  const second = video.getByRole('button', { name: '提交生成任务' });
  fireEvent.click(first);
  fireEvent.click(first);
  await waitFor(() => expect(pending).toHaveLength(1));
  await waitFor(() => expect(second).toBeEnabled());
  fireEvent.click(second);
  fireEvent.click(second);
  await waitFor(() => expect(pending).toHaveLength(2));
  expect(first).toBeDisabled();
  expect(second).toBeDisabled();
  const submits = fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/media/tasks'));
  expect(submits).toHaveLength(2);
  expect(JSON.parse(submits[0][1].body)).toEqual({ type: 'IMAGE', model: 'seedream-5.0-lite', aspectRatio: '1:1', resolution: '2K', count: 2, durationSeconds: 0, prompt: '服务端提示词', references: [] });
  expect(JSON.parse(submits[1][1].body)).toEqual({ type: 'VIDEO', model: 'seedance-2.5', aspectRatio: '16:9', resolution: '720p', count: 1, durationSeconds: 5, prompt: '服务端提示词', references: [] });
  await act(async () => pending.forEach(resolve => resolve()));
  await waitFor(() => expect(remote.content.nodes.filter((node: any) => node.properties.taskRef)).toHaveLength(2));
  const saved = remote.content.nodes.filter((node: any) => node.kind === 'generation');
  expect(JSON.parse(saved[0].properties.settings).count).toBe(2);
  expect(JSON.parse(saved[1].properties.settings).type).toBe('VIDEO');
});

it('saves a new node and its settings before submission so a lost response can retry the same idempotency key', async () => {
  const baseFetch = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  const keys: string[] = [];
  fetchMock.mockImplementation(async (url: string, options: RequestInit) => {
    if (url.endsWith('/media/tasks')) {
      keys.push(new Headers(options.headers).get('Idempotency-Key')!);
      throw new TypeError('response lost');
    }
    return baseFetch(url, options);
  });
  const router = show();
  await screen.findByDisplayValue('服务端提示词');
  await waitFor(() => expect(screen.getByRole('button', { name: '保存画布' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '新增生成占位节点' }));
  const node = screen.getByRole('article', { name: /^生成节点 / });
  const id = node.getAttribute('aria-label')!.slice('生成节点 '.length);
  act(() => surfaceState.onConnect!('text-1', id));
  await waitFor(() => expect(screen.getByRole('button', { name: '提交生成任务' })).toBeEnabled());
  fireEvent.change(screen.getByLabelText('图片数量'), { target: { value: '2' } });
  await waitFor(() => expect(screen.getByRole('button', { name: '提交生成任务' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '提交生成任务' }));
  expect(await screen.findByText(/网络连接失败/)).toBeVisible();
  expect(JSON.parse(remote.content.nodes.find((item: any) => item.id === id).properties.settings).count).toBe(2);
  expect(remote.content.nodes.find((item: any) => item.id === id).properties.taskState).toBe('submitting');
  await act(() => router.navigate('/canvas'));
  await act(() => router.navigate('/canvas?id=canvas-1'));
  const retry = await screen.findByRole('button', { name: `使用原请求重试 ${id}` });
  expect(screen.getByLabelText('图片数量')).toHaveValue('2');
  expect(screen.getByLabelText('图片数量')).toBeDisabled();
  fireEvent.click(retry);
  await waitFor(() => expect(keys).toHaveLength(2));
  expect(keys[1]).toBe(keys[0]);
});

it('does not submit a charged task when the automatic pre-submit save conflicts', async () => {
  remote.content.nodes.push({ id: 'generation-1', kind: 'generation', properties: { taskState: 'idle' } });
  remote.content.edges.push({ id: 'connected', source: 'text-1', target: 'generation-1' });
  show();
  await waitFor(() => expect(screen.getByRole('button', { name: '提交生成任务' })).toBeEnabled());
  remote = { ...remote, version: remote.version + 1 };
  fireEvent.click(screen.getByRole('button', { name: '提交生成任务' }));
  expect(await screen.findByText(/版本或请求冲突/)).toBeVisible();
  expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/media/tasks'))).toHaveLength(0);
});

it('refreshes real generation eligibility after account recharge without discarding the canvas', async () => {
  const baseFetch=fetchMock.getMockImplementation() as (url:string, options:RequestInit)=>Promise<Response>;
  let paid=false;
  fetchMock.mockImplementation(async(url:string, options:RequestInit)=>url.endsWith('/generation-options')
    ? paid?json({models:generationOptions}):json({code:'MEDIA_REQUEST_REJECTED'},422):baseFetch(url,options));
  show();
  await screen.findByDisplayValue('服务端提示词');
  await waitFor(() => expect(screen.getByRole('button', { name: '保存画布' })).toBeEnabled());
  await waitFor(()=>expect(fetchMock.mock.calls.some(([url])=>String(url).endsWith('/generation-options'))).toBe(true));
  const before=fetchMock.mock.calls.filter(([url])=>String(url).endsWith('/generation-options')).length;
  paid=true;fireEvent(window,new Event('xzwl-account-changed'));
  await waitFor(()=>expect(fetchMock.mock.calls.filter(([url])=>String(url).endsWith('/generation-options')).length).toBeGreaterThan(before));
  expect(screen.getByDisplayValue('服务端提示词')).toBeVisible();
});

it.each(['xzwl-account-changed', 'focus'])('rechecks the exact quote and account revision after saving on %s', async eventName => {
  remote.content.nodes.push({ id: 'generation-1', kind: 'generation', properties: { taskState: 'idle' } });
  remote.content.edges.push({ id: 'connected', source: 'text-1', target: 'generation-1' });
  const baseFetch = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  let save!: () => void;
  fetchMock.mockImplementation((url: string, options: RequestInit) => options.method === 'PUT'
    ? new Promise<Response>(resolve => { save = () => { void baseFetch(url, options).then(resolve); }; }) : baseFetch(url, options));
  show();
  await waitFor(() => expect(screen.getByRole('button', { name: '提交生成任务' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '提交生成任务' }));
  await waitFor(() => expect(save).toBeDefined());
  act(() => window.dispatchEvent(new Event(eventName)));
  await act(async () => save());
  await screen.findByText('账户或报价已变化，请确认最新报价后重新提交');
  expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/media/tasks'))).toHaveLength(0);
  await waitFor(() => expect(screen.getByRole('button', { name: '提交生成任务' })).toBeEnabled());
});

it.each([
  ['REFUND_PENDING', 'FAILED', 'failed'], ['REFUND_PENDING', 'TIMED_OUT', 'timed_out'],
  ['REFUND_PENDING', 'PARTIAL_SUCCESS', 'partial_success'], ['RUNNING', 'SUCCEEDED', 'succeeded'],
])('refreshes account once when %s reaches final %s', async (pending, terminal, state) => {
  remote.content.nodes.push({ id: 'generation-1', kind: 'generation', properties: { taskState: 'working', taskRef: 'task-1' } });
  const baseFetch = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  let status = pending;
  fetchMock.mockImplementation(async (url: string, options: RequestInit) => {
    if (url.endsWith('/tasks/task-1')) return json({ id: 'task-1', status, type: 'IMAGE' });
    if (url.endsWith('/results')) return json([{ index: 0, fileId: 'result-1' }]);
    if (url.endsWith('/access-url')) return json({ url: 'https://cdn.example.test/result.png' });
    if (url.endsWith('/media-references')) return json(remote);
    return baseFetch(url, options);
  });
  const changed = vi.fn();
  window.addEventListener('xzwl-account-changed', changed);
  try {
    show();
    await screen.findByRole('button', { name: '保存画布' });
    act(() => surfaceState.onReadGeneration!('generation-1'));
    await waitFor(() => expect(remote.content.nodes[1].properties.taskState).toBe(pending === 'REFUND_PENDING' ? 'refund_pending' : 'working'));
    expect(changed).not.toHaveBeenCalled();
    status = terminal;
    act(() => surfaceState.onReadGeneration!('generation-1'));
    await waitFor(() => expect(remote.content.nodes[1].properties.taskState).toBe(state));
    expect(changed).toHaveBeenCalledTimes(1);
    const reads = fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/tasks/task-1')).length;
    act(() => surfaceState.onReadGeneration!('generation-1'));
    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/tasks/task-1'))).toHaveLength(reads + 1));
    expect(changed).toHaveBeenCalledTimes(1);
  } finally { window.removeEventListener('xzwl-account-changed', changed); }
});

it('freezes an uncertain original request through edits, account changes, refused retries and reloading', async () => {
  remote.content.nodes.push({ id: 'generation-1', kind: 'generation', properties: { taskState: 'idle' } });
  remote.content.edges.push({ id: 'connected', source: 'text-1', target: 'generation-1' });
  const baseFetch = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  const requests: Array<{ body: string; key: string | null }> = [];
  fetchMock.mockImplementation(async (url: string, options: RequestInit) => {
    if (url.endsWith('/media/tasks')) {
      requests.push({ body: options.body as string, key: new Headers(options.headers).get('Idempotency-Key') });
      if (requests.length === 1) throw new TypeError('response lost');
      if (requests.length === 2) return json({}, 422);
      return json({ id: 'original-task' }, 202);
    }
    return baseFetch(url, options);
  });
  const router = show();
  await waitFor(() => expect(screen.getByRole('button', { name: '提交生成任务' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '提交生成任务' }));
  const retry = await screen.findByRole('button', { name: '使用原请求重试 generation-1' });
  expect(screen.getByLabelText('图片数量')).toBeDisabled();
  fireEvent.change(screen.getByLabelText('图片数量'), { target: { value: '2' } });
  fireEvent.change(screen.getByLabelText('提示词'), { target: { value: '不得替换原提示词' } });
  expect(screen.getByLabelText('提示词')).toHaveValue('服务端提示词');
  act(() => window.dispatchEvent(new Event('xzwl-account-changed')));
  fireEvent.click(retry);
  await waitFor(() => expect(requests).toHaveLength(2));
  expect(await screen.findByRole('button', { name: '使用原请求重试 generation-1' })).toBeEnabled();
  expect(screen.getByLabelText('图片数量')).toBeDisabled();
  await act(() => router.navigate('/canvas'));
  await act(() => router.navigate('/canvas?id=canvas-1'));
  fireEvent.click(await screen.findByRole('button', { name: '使用原请求重试 generation-1' }));
  await waitFor(() => expect(remote.content.nodes[1].properties.taskRef).toBe('original-task'));
  expect(requests).toHaveLength(3);
  expect(requests[1]).toEqual(requests[0]);
  expect(requests[2]).toEqual(requests[0]);
});

it.each([['audio', 'audio/mpeg', 'music.mp3'], ['video', 'video/mp4', 'motion.mp4']])('uploads and saves %s references with trusted server metadata', async (kind, mime, name) => {
  const original = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  fetchMock.mockImplementation(async (url: string, options: RequestInit) => {
    if (url.endsWith(kind === 'video' ? '/processing-videos' : '/reference-media')) {
      expect((options.body as FormData).get('file')).toBeInstanceOf(File);
      return json({ fileId: 'uploaded-media', kind: kind.toUpperCase(), durationSeconds: kind === 'video' ? 44.466667 : 4.5, sizeBytes: 2000 });
    }
    if (url.endsWith('/access-url')) return json({ url: 'https://cdn.example.test/media' });
    if (url.endsWith('/media-references')) { remote = { ...remote, version: remote.version + 1 }; return json(remote); }
    return original(url, options);
  });
  show('/canvas?id=canvas-1');
  await screen.findByDisplayValue('服务端提示词');
  await waitFor(() => expect(screen.getByRole('button', { name: '保存画布' })).toBeEnabled());
  await waitFor(() => expect(screen.getByLabelText('本地上传文件')).toBeEnabled());
  fireEvent.change(screen.getByLabelText('本地上传文件'), { target: { files: [new File(['test'], name, { type: mime })] } });
  await screen.findAllByLabelText(name);
  fireEvent.click(screen.getByRole('button', { name: '保存画布' }));
  await waitFor(() => expect(remote.content.nodes.some((node: any) => node.kind === kind && node.properties.publicId === 'uploaded-media')).toBe(true));
  expect(remote.content.nodes.find((node: any) => node.kind === kind).properties).toMatchObject({ durationSeconds: kind === 'video' ? '44.466667' : '4.5', sizeBytes: '2000', alt: name });
});

it('excludes depth nodes from portraits and selects an ordinary video model in a mixed canvas', async () => {
  remote.content.nodes = [{ id: 'depth-only', kind: 'generation', properties: { taskState: 'idle', settings: JSON.stringify({
    operation: 'DEPTH', type: 'VIDEO', model: 'depth-anything-v2-small', aspectRatio: 'source', resolution: '480p', count: 1, durationSeconds: 0,
  }) } }];
  const baseFetch = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  fetchMock.mockImplementation((url, options) => String(url).includes('/media/portraits')
    ? Promise.resolve(json([{ id: 'ordinary-portrait', fileId: 'portrait-file', model: 'seedance-2.5', status: 'ACTIVE' }]))
    : String(url).endsWith('/access-url') ? Promise.resolve(json({ url: 'https://assets.example/portrait.png' })) : baseFetch(url, options));
  show(); await screen.findByText('已加载 · 版本 4');
  await waitFor(() => expect(screen.getByRole('button', { name: '素材库' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '新增视频' }));
  await waitFor(() => expect(screen.getByRole('button', { name: '素材库' })).toBeEnabled());
  expect(screen.queryByLabelText('素材用于视频节点')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '素材库' }));
  expect(await screen.findByRole('tab', { name: '图片' })).toBeVisible();
});

it('keeps the portrait picker enabled for the established owner during focus refresh', async () => {
  const account = vi.spyOn(userSession, 'useOptionalUserSession');
  account.mockReturnValue({ userId: 'owner-a', authenticated: true, status: 'ready' } as never);
  const baseFetch = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  fetchMock.mockImplementation((url, options) => String(url).includes('/media/portraits') ? Promise.resolve(json([])) : baseFetch(url, options));
  const router = createMemoryRouter([{ path: '/canvas', element: <ProductionCanvas /> }], { initialEntries: ['/canvas?id=canvas-1'] });
  const view = render(<RouterProvider router={router} />);
  await screen.findByText('已加载 · 版本 4');
  fireEvent.click(screen.getByRole('button', { name: '新增视频' }));
  await waitFor(() => expect(screen.getByRole('button', { name: '素材库' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '素材库' }));
  await waitFor(() => expect(screen.getByLabelText('本地上传文件')).toBeEnabled());
  account.mockReturnValue({ userId: null, authenticated: false, status: 'loading' } as never);
  // A normal canvas state update propagates the refreshed account snapshot to the picker.
  fireEvent.change(screen.getByLabelText('画布名称'), { target: { value: '刷新中的画布' } });
  expect(screen.getByLabelText('本地上传文件')).toBeEnabled();
  account.mockReturnValue({ userId: null, authenticated: false, status: 'anonymous' } as never);
  fireEvent.change(screen.getByLabelText('画布名称'), { target: { value: '已退出' } });
  await waitFor(() => expect(screen.queryByRole('dialog', { name: '素材库' })).not.toBeInTheDocument());
  expect(screen.getByLabelText('本地上传文件')).toBeDisabled();
  view.unmount();
});

it('saves and restores a depth request across a lost response, then reuses task polling and downloadable results', async () => {
  remote.content.nodes = [{ id: 'video-source', kind: 'video', properties: { publicId: 'source-file', alt: '原视频', durationSeconds: '30', x: '0', y: '0' } }];
  const baseFetch = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  const requests: Array<{ body: string; key: string | null }> = [];
  fetchMock.mockImplementation(async (url: string, options: RequestInit) => {
    if (url.endsWith('/depth-options')) return json({ enabled: true, resolutions: ['480p', '720p'], highResolutionMaxDurationSeconds: 30 });
    if (url.endsWith('/depth-tasks')) {
      requests.push({ body: options.body as string, key: new Headers(options.headers).get('Idempotency-Key') });
      expect(remote.content.nodes.find((node: any) => node.kind === 'generation').properties.taskState).toBe('submitting');
      if (requests.length === 1) throw new TypeError('response lost');
      return json({ id: 'depth-task' }, 202);
    }
    if (url.endsWith('/tasks/depth-task')) return json({ id: 'depth-task', operation: 'DEPTH', type: 'VIDEO', status: 'SUCCEEDED', references: [{ durationSeconds: 30 }] });
    if (url.endsWith('/results')) return json([{ index: 0, fileId: 'depth-result' }]);
    if (url.endsWith('/access-url')) return json({ url: `https://cdn.example.test/${url.includes('depth-result') ? 'depth' : 'source'}.mp4` });
    if (url.endsWith('/media-references')) { remote = { ...remote, version: remote.version + 1 }; return json(remote); }
    return baseFetch(url, options);
  });
  const changed = vi.fn(); window.addEventListener('xzwl-account-changed', changed);
  try {
    show();
    fireEvent.click(await screen.findByRole('button', { name: '生成深度白模' }));
    await waitFor(() => expect(screen.getByRole('button', { name: '提交生成任务' })).toBeEnabled());
    fireEvent.change(screen.getByLabelText('分辨率'), { target: { value: '720p' } });
    await waitFor(() => expect(screen.getByRole('button', { name: '提交生成任务' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: '提交生成任务' }));
    await screen.findByRole('button', { name: /使用原请求重试/ });
    const savedNode = remote.content.nodes.find((node: any) => node.kind === 'generation');
    expect(JSON.parse(savedNode.properties.settings)).toMatchObject({ operation: 'DEPTH', resolution: '720p', durationSeconds: 0 });
    expect(JSON.parse(requests[0].body)).toEqual({ sourceFileId: 'source-file', resolution: '720p' });
    cleanup();
    show();
    fireEvent.click(await screen.findByRole('button', { name: /使用原请求重试/ }));
    await waitFor(() => expect(remote.content.nodes.find((node: any) => node.id === savedNode.id).properties.taskRef).toBe('depth-task'));
    expect(requests).toHaveLength(2);
    expect(requests[1]).toEqual(requests[0]);
    expect(requests[1].key).toBe(JSON.parse(savedNode.properties.runs).at(-1).request.requestRef);
    cleanup();
    show();
    await screen.findByRole('button', { name: '保存画布' });
    act(() => surfaceState.onReadGeneration!(savedNode.id));
    await waitFor(() => expect(remote.content.nodes.find((node: any) => node.id === savedNode.id).properties.taskState).toBe('succeeded'));
    expect(screen.queryByRole('link', { name: '打开视频并下载' })).not.toBeInTheDocument();
    await waitFor(() => expect(document.querySelector('video[src="https://cdn.example.test/depth.mp4"]')).toHaveAttribute('controls'));
    expect(remote.content.nodes.find((node: any) => node.properties.publicId === 'depth-result').properties.durationSeconds).toBe('30');
    expect(fetchMock.mock.calls.some(([url]) => /\/(quotes|media\/tasks)$/.test(String(url)))).toBe(false);
    expect(changed).not.toHaveBeenCalled();
  } finally { window.removeEventListener('xzwl-account-changed', changed); }
});


it.each([false, true])('uploads images and videos from a context position, keeps batch offsets and excludes audio (audio first=%s)', async audioFirst => {
  URL.createObjectURL = vi.fn(() => 'blob:menu-upload'); URL.revokeObjectURL = vi.fn();
  remote.content.viewport = { x: 900, y: -300, zoom: 0.5 };
  const uploads: string[] = [];
  const base = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  fetchMock.mockImplementation(async (url, options) => {
    if (/\/(reference-images|processing-videos|reference-media)$/.test(url)) {
      const file = (options.body as FormData).get('file') as File;
      uploads.push(file.name);
      return json({ fileId: `menu-file-${uploads.length}`, sizeBytes: 100, durationSeconds: 4 });
    }
    if (url.includes('/media/portraits?')) return json([]);
    if (url.endsWith('/media/portraits')) {
      const entry = JSON.parse(options.body as string);
      return json({ ...entry, id: `review-${entry.fileId}`, kind: entry.kind ?? 'IMAGE', durationSeconds: 4, status: 'PROCESSING' }, 202);
    }
    if (url.endsWith('/access-url')) return json({ url: 'https://cdn.example.test/menu-upload' });
    if (url.endsWith('/media-references')) { remote = { ...remote, version: remote.version + 1 }; return json(remote); }
    return base(url, options);
  });
  show(); await screen.findByDisplayValue('服务端提示词');
  await waitFor(() => expect(screen.getByRole('button', { name: '本地上传' })).toBeEnabled());
  act(() => surfaceState.onUploadAt!({ x: -260, y: 440 }));
  const input = screen.getByLabelText('本地上传文件');
  expect(input).toHaveAttribute('accept', 'image/png,image/jpeg,video/mp4,video/quicktime');
  expect(input).toHaveAttribute('multiple');
  fireEvent(input, new Event('cancel'));
  expect(remote.content.nodes).toHaveLength(1);
  expect(uploads).toEqual([]);
  act(() => surfaceState.onUploadAt!({ x: -260, y: 440 }));
  const selectedFiles = [['one.png', 'image/png'], ['two.mp4', 'video/mp4']];
  if (audioFirst) selectedFiles.unshift(['skip.mp3', 'audio/mpeg']); else selectedFiles.push(['skip.mp3', 'audio/mpeg']);
  fireEvent.change(input, { target: { files: selectedFiles.map(([name, type]) => new File(['media'], name, { type })) } });
  await waitFor(() => expect(remote.content.nodes.filter((node: any) => node.properties.reviewId)).toHaveLength(2));
  const added = remote.content.nodes.filter((node: any) => node.id !== 'text-1');
  expect(uploads).toEqual(['one.png', 'two.mp4']);
  expect(added.map((node: any) => node.kind)).toEqual(['image', 'video']);
  expect(added.map((node: any) => [Number(node.properties.x), Number(node.properties.y)])).toEqual([[-260, 440], [-220, 480]]);
  expect(fetchMock.mock.calls.some(([url]) => /\/(tasks|depth-tasks)$/.test(String(url)))).toBe(false);
  cleanup(); show(); await screen.findByDisplayValue('服务端提示词');
  await waitFor(() => expect(screen.getByRole('button', { name: '保存画布' })).toBeEnabled());
  expect(remote.content.nodes.filter((node: any) => node.properties.reviewId)).toHaveLength(2);
});

it.each([['IMAGE', 'image/png', '/reference-images'], ['VIDEO', 'video/mp4', '/processing-videos'], ['AUDIO', 'audio/mpeg', '/reference-media']] as const)('local %s uploads auto-review while persisting their independent original for refresh', async (kind, mime, uploadPath) => {
  URL.createObjectURL = vi.fn(() => 'blob:local-upload'); URL.revokeObjectURL = vi.fn();
  const row = { id: 'review-upload', fileId: 'local-file', model: 'seedance-2.5', kind, durationSeconds: kind === 'IMAGE' ? null : 4.5, status: 'PROCESSING' };
  const base = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  fetchMock.mockImplementation(async (url, options) => {
    if (url.endsWith(uploadPath)) return json({ fileId: row.fileId, kind, durationSeconds: row.durationSeconds, sizeBytes: 100 });
    if (url.includes('/media/portraits?')) return json([]);
    if (url.endsWith('/media/portraits')) {
      expect(JSON.parse(options.body as string)).toEqual({ fileId: row.fileId, model: row.model, ...(kind === 'IMAGE' ? {} : { kind }) });
      return json(row, 202);
    }
    if (url.endsWith('/access-url')) return json({ url: 'https://cdn.example.test/original' });
    if (url.endsWith('/media-references')) { remote = { ...remote, version: remote.version + 1 }; return json(remote); }
    return base(url, options);
  });
  show(); await screen.findByDisplayValue('服务端提示词');
  await waitFor(() => expect(screen.getByRole('button', { name: '保存画布' })).toBeEnabled());
  await waitFor(() => expect(screen.getByRole('button', { name: '新增视频' })).toBeEnabled());
  // Wait for the existing catalog, without requiring a generation node.
  await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/generation-options'))).toBe(true));
  await waitFor(() => expect(screen.getByLabelText('本地上传文件')).toBeEnabled());
  fireEvent.change(screen.getByLabelText('本地上传文件'), { target: { files: [new File(['sample'], 'local-reference', { type: mime })] } });
  await waitFor(() => expect(remote.content.nodes.some((node: any) => node.properties.reviewId === row.id)).toBe(true));
  const stored = remote.content.nodes.find((node: any) => node.properties.reviewId === row.id);
  expect(stored.kind).toBe(kind.toLowerCase()); expect(stored.properties.publicId).toBe(row.fileId);
  expect(stored.properties.portraitId).toBeUndefined();
  expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/media/portraits'))).toHaveLength(1);
  cleanup(); show(); await screen.findByDisplayValue('服务端提示词');
  await waitFor(() => expect(screen.getByRole('button', { name: '保存画布' })).toBeEnabled());
  expect(remote.content.nodes.find((node: any) => node.id === stored.id).properties.reviewId).toBe(row.id);
  expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/media/portraits'))).toHaveLength(1);
});

it.each([false, true])('uploads a local multi-selection sequentially with distinct canvas positions (first fails=%s)', async firstFails => {
  URL.createObjectURL = vi.fn(() => 'blob:local-upload'); URL.revokeObjectURL = vi.fn();
  const uploads: string[] = [];
  let finishFirst!: (response: Response) => void;
  const base = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  fetchMock.mockImplementation(async (url, options) => {
    if (/\/(reference-images|processing-videos|reference-media)$/.test(url)) {
      const file = (options.body as FormData).get('file') as File;
      uploads.push(file.name);
      if (uploads.length === 1) return new Promise<Response>(resolve => { finishFirst = resolve; });
      return json({ fileId: `file-${uploads.length}`, sizeBytes: 100, durationSeconds: 4 });
    }
    if (url.includes('/media/portraits?')) return json([]);
    if (url.endsWith('/media/portraits')) {
      const entry = JSON.parse(options.body as string);
      return json({ ...entry, id: `review-${entry.fileId}`, kind: entry.kind ?? 'IMAGE', durationSeconds: 4, status: 'PROCESSING' }, 202);
    }
    if (url.endsWith('/access-url')) return json({ url: 'https://cdn.example.test/original' });
    return base(url, options);
  });
  show(); await screen.findByDisplayValue('服务端提示词');
  await waitFor(() => expect(screen.getByRole('button', { name: '保存画布' })).toBeEnabled());
  await waitFor(() => expect(screen.getByRole('button', { name: '本地上传' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '本地上传' }));
  const input = screen.getByLabelText('本地上传文件');
  fireEvent.change(input, { target: { files: [['one.png', 'image/png'], ['two.mp4', 'video/mp4'], ['three.mp3', 'audio/mpeg']].map(([name, type]) => new File(['media'], name, { type })) } });
  await waitFor(() => expect(uploads).toEqual(['one.png']));
  expect(screen.getByRole('button', { name: '本地上传' })).toBeDisabled();
  await act(async () => { finishFirst(firstFails ? json({}, 400) : json({ fileId: 'file-1', sizeBytes: 100 })); });
  await waitFor(() => expect(screen.getByRole('button', { name: '本地上传' })).toBeEnabled());
  expect(uploads).toEqual(firstFails ? ['one.png'] : ['one.png', 'two.mp4', 'three.mp3']);
  if (!firstFails) {
    await waitFor(() => expect(remote.content.nodes.filter((node: any) => node.properties.reviewId)).toHaveLength(3));
    const added = remote.content.nodes.filter((node: any) => node.id !== 'text-1');
    expect(added.map((node: any) => node.kind)).toEqual(['image', 'video', 'audio']);
    expect(added.map((node: any) => node.properties.publicId)).toEqual(['file-1', 'file-2', 'file-3']);
    expect(new Set(added.map((node: any) => `${node.properties.x}:${node.properties.y}`)).size).toBe(3);
  }
  expect(input).toHaveAttribute('multiple');
});

it('does not continue a selected upload under a different account', async () => {
  URL.createObjectURL = vi.fn(() => 'blob:local-upload'); URL.revokeObjectURL = vi.fn();
  const account = vi.spyOn(userSession, 'useOptionalUserSession');
  account.mockReturnValue({ userId: 'owner-a', authenticated: true, status: 'ready' } as never);
  let finishUpload!: (response: Response) => void;
  const base = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  fetchMock.mockImplementation((url, options) => url.endsWith('/reference-images') ? new Promise(resolve => { finishUpload = resolve; })
    : url.includes('/media/portraits?') ? Promise.resolve(json([])) : base(url, options));
  show(); await screen.findByDisplayValue('服务端画布');
  await waitFor(() => expect(screen.getByLabelText('本地上传文件')).toBeEnabled());
  fireEvent.change(screen.getByLabelText('本地上传文件'), { target: { files: [new File(['sample'], 'local.png', { type: 'image/png' })] } });
  await waitFor(() => expect(finishUpload).toBeDefined());
  account.mockReturnValue({ userId: 'owner-b', authenticated: true, status: 'ready' } as never);
  fireEvent.change(screen.getByLabelText('画布名称'), { target: { value: '身份已切换' } });
  await waitFor(() => expect(screen.queryByRole('dialog', { name: '素材库' })).not.toBeInTheDocument());
  await act(async () => { finishUpload(json({ fileId: 'owner-a-file', sizeBytes: 6 })); });
  expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/reference-images'))).toHaveLength(1);
  expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/media/portraits'))).toHaveLength(0);
  expect(remote.content.nodes.some((node: any) => node.properties.publicId === 'owner-a-file')).toBe(false);
});

it('shows upload review on the canvas without a modal, restores polling and shares the reviewed item with the video library', async () => {
  URL.createObjectURL = vi.fn(() => 'blob:local-upload'); URL.revokeObjectURL = vi.fn();
  const row = { id: 'inline-review', fileId: 'inline-file', model: 'seedance-2.5', kind: 'IMAGE', status: 'PROCESSING' };
  const base = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  fetchMock.mockImplementation(async (url, options) => {
    if (url.endsWith('/reference-images')) return json({ fileId: row.fileId, sizeBytes: 100 });
    if (url.includes('/media/portraits?')) return json(row.status === 'ACTIVE' ? [row] : []);
    if (url.endsWith('/media/portraits') || url.endsWith('/media/portraits/inline-review')) return json(row);
    if (url === 'https://cdn.example.test/inline.png') return new Response('image', { headers: { 'Content-Type': 'image/png' } });
    if (url.endsWith('/access-url')) return json({ url: 'https://cdn.example.test/inline.png' });
    return base(url, options);
  });
  show(); await waitFor(() => expect(screen.getByLabelText('本地上传文件')).toBeEnabled());
  fireEvent.change(screen.getByLabelText('本地上传文件'), { target: { files: [new File(['sample'], '男主.png', { type: 'image/png' })] } });
  await waitFor(() => expect(remote.content.nodes.some((node: any) => node.properties.reviewId === row.id)).toBe(true));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.getByRole('status', { name: '男主.png 审核状态' })).toHaveTextContent('审核中');
  const stored = remote.content.nodes.find((node: any) => node.properties.reviewId === row.id);
  expect(stored.properties.reviewStatus).toBe('PROCESSING');
  cleanup(); show();
  await screen.findByRole('status', { name: '男主.png 审核状态' });
  row.status = 'ACTIVE';
  await waitFor(() => expect(screen.getByRole('status', { name: '男主.png 审核状态' })).toHaveTextContent('审核通过'), { timeout: 4500 });
  await waitFor(() => expect(remote.content.nodes.find((node: any) => node.id === stored.id).properties.reviewStatus).toBe('ACTIVE'));
  fireEvent.click(screen.getByRole('button', { name: '素材库' }));
  expect(await screen.findByRole('option', { name: '男主.png' })).toBeInTheDocument();
  cleanup();
  const { PortraitLibraryDialog } = await import('../creator/PortraitLibraryDialog');
  render(<PortraitLibraryDialog owner="user-1" model="seedance-2.5" onClose={() => {}} onPick={() => {}} />);
  expect(await screen.findByRole('option', { name: '人像 inline-review' })).toHaveAttribute('aria-disabled', 'false');
  expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/reference-images'))).toHaveLength(1);
  expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/media/portraits'))).toHaveLength(1);
});

it('keeps the selected upload when the video model changes before storage responds', async () => {
  const { CanvasMaterials } = await import('./CanvasMaterials');
  URL.createObjectURL = vi.fn(() => 'blob:local-upload'); URL.revokeObjectURL = vi.fn();
  let finishUpload!: (response: Response) => void;
  fetchMock.mockImplementation(async (url, options) => {
    if (url.endsWith('/auth/session')) return json(session);
    if (url.includes('/media/portraits?')) return json([]);
    if (url.endsWith('/reference-images')) return new Promise(resolve => { finishUpload = resolve; });
    if (url === 'https://cdn.example.test/inline.png') return new Response('image', { headers: { 'Content-Type': 'image/png' } });
    if (url.endsWith('/access-url')) return json({ url: 'https://cdn.example.test/inline.png' });
    if (url.endsWith('/media/portraits')) return json({ id: 'upload-review', fileId: 'upload-file', model: JSON.parse(options.body as string).model, kind: 'IMAGE', status: 'PROCESSING' });
    throw new Error('unexpected URL');
  });
  const video = generationOptions.find(option => option.type === 'VIDEO')!;
  const options = [{ ...video, model: 'video-a' }, { ...video, model: 'video-b' }];
  let document: any = { projectId: 'canvas-1', nodes: [{ id: 'video', kind: 'generation', settings: { type: 'VIDEO', model: 'video-a' }, position: { x: 0, y: 0 } }], edges: [], viewport: { x: 0, y: 0, zoom: 1 } };
  const update = (transform: any) => { document = transform(document); };
  const view = render(<CanvasMaterials document={document} disabled={false} options={options as any} update={update} />);
  fireEvent.change(screen.getByLabelText('本地上传文件'), { target: { files: [new File(['sample'], '男主.png', { type: 'image/png' })] } });
  await waitFor(() => expect(finishUpload).toBeDefined());
  document = { ...document, nodes: [{ ...document.nodes[0], settings: { type: 'VIDEO', model: 'video-b' } }] };
  view.rerender(<CanvasMaterials document={document} disabled={false} options={options as any} update={update} />);
  await act(async () => { finishUpload(json({ fileId: 'upload-file', sizeBytes: 6 })); });
  await waitFor(() => expect(document.nodes.some((node: any) => node.asset?.assetRef === 'upload-file')).toBe(true));
});

it('retains a file returned while window focus refreshes the generation catalog', async () => {
  URL.createObjectURL = vi.fn(() => 'blob:focus-upload'); URL.revokeObjectURL = vi.fn();
  const base = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  let refreshCatalog = false;
  let finishCatalog!: (response: Response) => void;
  fetchMock.mockImplementation(async (url, options) => {
    if (url.endsWith('/generation-options') && refreshCatalog) return new Promise(resolve => { finishCatalog = resolve; });
    if (url.endsWith('/reference-images')) return json({ fileId: 'focus-file', sizeBytes: 100 });
    if (url.includes('/media/portraits?')) return json([]);
    if (url.endsWith('/media/portraits')) return json({ id: 'focus-review', fileId: 'focus-file', model: 'seedance-2.5', kind: 'IMAGE', status: 'PROCESSING' });
    if (url.endsWith('/access-url')) return json({ url: 'https://cdn.example.test/dog.png' });
    return base(url, options);
  });
  show(); await waitFor(() => expect(screen.getByRole('button', { name: '本地上传' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '本地上传' }));
  refreshCatalog = true; fireEvent.focus(window);
  await waitFor(() => expect(finishCatalog).toBeDefined());
  expect(screen.getByLabelText('本地上传文件')).toBeEnabled();
  fireEvent.change(screen.getByLabelText('本地上传文件'), { target: { files: [new File(['dog'], '狗.png', { type: 'image/png' })] } });
  await act(async () => { finishCatalog(json({ models: generationOptions })); });
  await waitFor(() => expect(remote.content.nodes.some((node: any) => node.properties.reviewId === 'focus-review')).toBe(true));
  expect(screen.getByRole('status', { name: '狗.png 审核状态' })).toHaveTextContent('审核中');
  expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/reference-images'))).toHaveLength(1);
});

it('rejects a file picker result after its account changed', async () => {
  const { CanvasMaterials } = await import('./CanvasMaterials');
  const account = vi.spyOn(userSession, 'useOptionalUserSession');
  account.mockReturnValue({ userId: 'owner-a', authenticated: true, status: 'ready' } as never);
  const document: any = { projectId: 'canvas-1', nodes: [], edges: [], viewport: { x: 0, y: 0, zoom: 1 } };
  const update = vi.fn();
  const view = render(<CanvasMaterials document={document} disabled={false} options={generationOptions as any} update={update} />);
  fireEvent.click(screen.getByRole('button', { name: '本地上传' }));
  account.mockReturnValue({ userId: 'owner-b', authenticated: true, status: 'ready' } as never);
  view.rerender(<CanvasMaterials document={document} disabled={false} options={generationOptions as any} update={update} />);
  fireEvent.change(screen.getByLabelText('本地上传文件'), { target: { files: [new File(['dog'], '狗.png', { type: 'image/png' })] } });
  await act(async () => {});
  expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/reference-images'))).toHaveLength(0);
  expect(update).not.toHaveBeenCalled();
});


it.each([false, true])('shares all library tabs through focus refresh and stops on unavailable review=%s', async failReview => {
  URL.createObjectURL = vi.fn(() => 'blob:shared'); URL.revokeObjectURL = vi.fn();
  const samples = [
    { id: 'shared-dog', name: '狗.png', kind: 'image', mimeType: 'image/png', tab: '图片', path: '/reference-images' },
    { id: 'shared-video', name: '视频.mp4', kind: 'video', mimeType: 'video/mp4', tab: '视频', path: '/processing-videos' },
    { id: 'shared-audio', name: '音乐.mp3', kind: 'audio', mimeType: 'audio/mpeg', tab: '音频', path: '/reference-media' },
  ];
  const records: any[] = samples.map(item => ({ ...item, size: 3, content: new Blob(['dog'], { type: item.mimeType }) }));
  const storage: any = { storageMode: 'session', reason: 'forced', repository: { list: async () => records, put: async (item: any) => { const index = records.findIndex(row => row.id === item.id); if (index < 0) records.push(item); else records[index] = item; }, remove: async () => {}, close() {} } };
  let refreshCatalog = false;
  let finishCatalog!: (response: Response) => void;
  const base = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  fetchMock.mockImplementation(async (url, options) => {
    if (url.endsWith('/generation-options') && refreshCatalog) return new Promise(resolve => { finishCatalog = resolve; });
    const sample = samples.find(item => url.endsWith(item.path));
    if (sample && failReview) return json({}, 500);
    if (sample) return json({ fileId: `${sample.id}-file`, kind: sample.kind.toUpperCase(), sizeBytes: 3, durationSeconds: sample.kind === 'image' ? null : 4.5 });
    if (url.includes('/media/portraits?')) return json(samples.map(item => ({ id: `${item.id}-review`, fileId: item.id, kind: item.kind.toUpperCase(), model: 'seedance-2.5', status: 'ACTIVE', durationSeconds: item.kind === 'image' ? null : 4.5 })));
    const reviewed = samples.find(item => url.endsWith(`/media/portraits/${item.id}-review`));
    if (reviewed) return json({ id: `${reviewed.id}-review`, fileId: reviewed.id, kind: reviewed.kind.toUpperCase(), model: 'seedance-2.5', status: failReview ? 'FAILED' : 'ACTIVE', durationSeconds: reviewed.kind === 'image' ? null : 4.5 });
    if (url.endsWith('/media/portraits')) { const body = JSON.parse(options.body as string); return json({ ...body, id: `${body.fileId}-review`, durationSeconds: body.kind ? 4.5 : null, status: 'ACTIVE' }); }
    if (url.endsWith('/access-url')) return json({ url: 'https://cdn.example.test/shared' });
    return base(url, options);
  });
  const router = createMemoryRouter([{ path: '/canvas', element: <ProductionCanvas /> }], { initialEntries: ['/canvas?id=canvas-1'] });
  render(<CreatorMediaProvider storage={storage}><RouterProvider router={router} /></CreatorMediaProvider>);
  await waitFor(() => expect(screen.getByRole('button', { name: '素材库' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '素材库' }));
  for (const name of ['图片', '视频', '音频', '公开', '个人']) expect(await screen.findByRole('tab', { name })).toBeVisible();
  for (const sample of samples) {
    fireEvent.click(screen.getByRole('tab', { name: sample.tab }));
    fireEvent.click(await screen.findByRole('option', { name: sample.name }));
  }
  refreshCatalog = true; fireEvent.focus(window);
  await waitFor(() => expect(finishCatalog).toBeDefined());
  fireEvent.click(screen.getByRole('button', { name: '确定' }));
  await act(async () => { finishCatalog(json({ models: generationOptions })); });
  if (failReview) {
    expect(await screen.findByRole('alert')).toBeVisible();
    await waitFor(() => expect(screen.getByRole('button', { name: '本地上传' })).toBeEnabled());
    expect(remote.content.nodes).toHaveLength(1);
    expect(fetchMock.mock.calls.filter(([url]) => samples.some(item => String(url).endsWith(item.path)))).toHaveLength(0);
    return;
  }
  await waitFor(() => expect(remote.content.nodes.filter((node: any) => node.properties.reviewStatus === 'ACTIVE')).toHaveLength(3));
  const added = remote.content.nodes.filter((node: any) => node.properties.reviewId);
  expect(added.map((node: any) => node.kind)).toEqual(['image', 'video', 'audio']);
  expect(new Set(added.map((node: any) => `${node.properties.x}:${node.properties.y}`)).size).toBe(3);
  expect(added.slice(1).map((node: any) => node.properties.durationSeconds)).toEqual(['4.5', '4.5']);
  expect(records).toHaveLength(3);
  expect(records.every(item => item.ownerId === undefined)).toBe(true);
  fireEvent.click(screen.getByRole('button', { name: '素材库' }));
  expect(await screen.findByRole('option', { name: '狗.png' })).toBeVisible();
  expect(records).toHaveLength(3);
  expect(fetchMock.mock.calls.some(([url]) => String(url).startsWith('https://cdn.'))).toBe(false);
});

it('stores a canvas upload in the same personal repository opened by the video material dialog', async () => {
  const records = new Map<string, any>();
  const storage: any = { storageMode: 'session', reason: 'forced', repository: { list: async () => [...records.values()], put: async (item: any) => { records.set(item.id, item); }, remove: async () => {}, close() {} } };
  const base = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  fetchMock.mockImplementation(async (url, options) => {
    if (url.endsWith('/reference-images')) return json({ fileId: 'canvas-shared', sizeBytes: 3 });
    if (url.includes('/media/portraits?')) return json([]);
    if (url.endsWith('/media/portraits')) return json({ id: 'canvas-review', fileId: 'canvas-shared', model: 'seedance-2.5', status: 'PROCESSING' });
    if (url.endsWith('/access-url')) return json({ url: 'https://cdn.example.test/dog.png' });
    return base(url, options);
  });
  const router = createMemoryRouter([{ path: '/canvas', element: <ProductionCanvas /> }], { initialEntries: ['/canvas?id=canvas-1'] });
  render(<CreatorMediaProvider storage={storage}><RouterProvider router={router} /></CreatorMediaProvider>);
  await waitFor(() => expect(screen.getByLabelText('本地上传文件')).toBeEnabled());
  fireEvent.change(screen.getByLabelText('本地上传文件'), { target: { files: [new File(['dog'], '狗.png', { type: 'image/png' })] } });
  await waitFor(() => expect(records.get('canvas-shared')?.content?.size).toBe(3));
  cleanup();
  const { MediaLibraryDialog } = await import('../creator/MediaLibraryDialog');
  render(<CreatorMediaProvider storage={storage}><MediaLibraryDialog open capability={{ acceptedKinds: ['image'], acceptedMimeTypes: ['image/png'], acceptedSources: ['library'], maxFileBytes: 100, maxItems: 1, verified: true }} selectionMode="single" initialSource="personal" initialSelection={[]} onConfirm={() => {}} onClose={() => {}} /></CreatorMediaProvider>);
  expect(await screen.findByRole('option', { name: '狗.png' })).toBeVisible();
});

function chooseCanvasOption(trigger: HTMLElement, value: string) {
  fireEvent.click(trigger);
  const option = within(screen.getByRole('dialog')).getAllByRole('button').find(button => button.getAttribute('value') === value);
  expect(option).toBeDefined();
  fireEvent.click(option!);
}

it.each([[false, false], [true, false], [true, true]])('reuses an approved library image in the canvas without uploading or submitting another review (different model=%s)', async (differentModel, legacy) => {
  vi.stubGlobal('crypto', webcrypto);
  const put = vi.fn();
  URL.createObjectURL = vi.fn(() => 'blob:reviewed'); URL.revokeObjectURL = vi.fn();
  const original = { id: legacy ? 'local-old-id' : 'original-file', canvasFileIds: legacy ? undefined : ['original-file'], kind: 'image' as const, name: '已审核.png', mimeType: 'image/png', size: 3, content: new Blob(['png'], { type: 'image/png' }) };
  const receipt = { id: 'existing-review', fileId: 'original-file', model: differentModel ? 'another-compatible-model' : 'seedance-2.5', kind: 'IMAGE', status: 'ACTIVE' };
  const base = fetchMock.getMockImplementation() as (url: string, options: RequestInit) => Promise<Response>;
  fetchMock.mockImplementation((url, options) => String(url).includes('/media/portraits?') ? Promise.resolve(json([receipt]))
    : String(url).endsWith('/files/original-file/match') ? Promise.resolve(json({ matches: true }))
    : String(url).endsWith('/media/portraits/existing-review') ? Promise.resolve(json(receipt))
    : String(url).endsWith('/access-url') ? Promise.resolve(json({ url: 'https://cdn.example.test/original' })) : base(url, options));
  const router = createMemoryRouter([{ path: '/canvas', element: <ProductionCanvas /> }], { initialEntries: ['/canvas?id=canvas-1'] });
  render(<CreatorMediaProvider storage={{ storageMode: 'session', reason: 'forced', repository: { list: async () => [original], put, remove: async () => {}, close() {} } }}><RouterProvider router={router} /></CreatorMediaProvider>);
  await waitFor(() => expect(screen.getByRole('button', { name: '素材库' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '素材库' }));
  fireEvent.click(await screen.findByRole('option', { name: original.name }));
  fireEvent.click(screen.getByRole('button', { name: '确定' }));
  await waitFor(() => expect(remote.content.nodes.some((node: any) => node.properties.publicId === 'original-file' && node.properties.reviewId === 'existing-review' && node.properties.reviewStatus === 'ACTIVE')).toBe(true));
  if (legacy) await waitFor(() => expect(put).toHaveBeenCalledWith(expect.objectContaining({ id: original.id, content: original.content, canvasFileIds: ['original-file'] })));
  expect(screen.getByRole('img', { name: original.name })).toHaveAttribute('src', 'https://cdn.example.test/original');
  expect(fetchMock.mock.calls.some(([url, options]) => options?.method === 'POST' && /reference-images|reference-media|media\/portraits$/.test(String(url)))).toBe(false);
});


it('reviews a target upload with its selected model in a mixed-model canvas', async () => {
  const { CanvasMaterials } = await import('./CanvasMaterials');
  URL.createObjectURL = vi.fn(() => 'blob:target-video'); URL.revokeObjectURL = vi.fn();
  fetchMock.mockImplementation(async (url, options) => {
    if (url.endsWith('/auth/session')) return json(session);
    if (url.includes('/media/portraits?')) return json([]);
    if (url.endsWith('/processing-videos')) return json({ fileId: 'target-file', sizeBytes: 10, durationSeconds: 5 });
    if (url.endsWith('/access-url')) return json({ url: 'https://cdn.example.test/target.mp4' });
    if (url.endsWith('/media/portraits')) return json({ ...JSON.parse(options.body as string), id: 'target-review', status: 'ACTIVE' });
    return json({});
  });
  const video = generationOptions.find(option => option.type === 'VIDEO')!;
  const options = [{ ...video, model: 'review-a' }, { ...video, model: 'review-b' }];
  let document: any = { projectId: 'mixed', nodes: options.map((option, index) => ({ id: `video-${index}`, kind: 'generation', taskState: 'idle', settings: { type: 'VIDEO', model: option.model }, position: { x: index * 300, y: 0 } })), edges: [], viewport: { x: 0, y: 0, zoom: 1 } };
  const uploadMediaRef = { current: null as null | ((id?: string) => void) };
  const update = (transform: any) => { document = transform(document); };
  render(<CanvasMaterials document={document} disabled={false} options={options as any} update={update} uploadMediaRef={uploadMediaRef} />);
  act(() => uploadMediaRef.current!('video-1'));
  fireEvent.change(screen.getByLabelText('本地上传文件'), { target: { files: [new File(['video'], 'target.mp4', { type: 'video/mp4' })] } });
  await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/media/portraits'))).toHaveLength(1));
  const call = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/media/portraits'))!;
  expect(JSON.parse(call[1].body)).toMatchObject({ fileId: 'target-file', model: 'review-b', kind: 'VIDEO' });
  expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/media/tasks'))).toBe(false);
});
