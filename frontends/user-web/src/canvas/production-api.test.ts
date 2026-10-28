import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { configureGateway } from '../api';
import { createCanvasDocument, addTextNode, addGenerationNode } from './document';
import { canvasContent, readCanvasDocument, createProductionMediaPort, persistCanvas, canvasError } from './production-api';
import type { ResponseCanvasInterfacesCanvasControllerCanvasResponse as CanvasResponse } from '../api';
import { createGroup, renameGroup } from './groups';
import { beginRun } from './workflow';
import { buildMediaTaskRequest } from './media-task-port';

const record = (): CanvasResponse => ({ id: 'canvas-1', name: '画布', version: 1, ownerId: 'user-1', deleted: false,
  createdAt: '2026-09-08', updatedAt: '2026-09-08', content: { nodes: [], edges: [], viewport: { x: 0, y: 0, zoom: 1 } } });
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
let fetchMock: ReturnType<typeof vi.fn>;
beforeEach(() => {
  configureGateway({});
  fetchMock = vi.fn(async (url: string) => {
    if (url.includes('/auth/session')) return json({ authenticated: true, csrfToken: 'csrf', roles: ['USER'], primary: false, user: null });
    return json(record());
  });
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

it('round trips the existing editor through the generated Canvas content contract', async () => {
  const draft = addTextNode(createCanvasDocument('canvas-1', 'now'), 'text-1', { x: 123, y: -20 }, 'now');
  draft.nodes[0] = { ...draft.nodes[0], kind: 'text', text: '雨夜' };
  const content = canvasContent(draft);
  expect(content.nodes[0]).toMatchObject({ id: 'text-1', kind: 'text', properties: { x: '123', y: '-20', text: '雨夜' } });
  const restored = await readCanvasDocument({ ...record(), content });
  expect(restored.nodes).toEqual(draft.nodes);
});

it('persists groups, stable rich edge ids and immutable requests using only contracted string properties', async () => {
  let draft = addTextNode(createCanvasDocument('canvas-1', 'now'), 'brief', { x: 0, y: 0 }, 'now');
  draft = addGenerationNode(draft, 'image', { x: 500, y: 0 }, 'now');
  draft.nodes[0] = { ...draft.nodes[0], kind: 'text', text: '自然光的香氛瓶' };
  draft.edges = [{ id: 'brief->image', source: 'brief', target: 'image', role: 'reference' }];
  draft = renameGroup(createGroup(draft, ['image', 'brief'], 'brand', 'now'), 'brand', '品牌视觉', 'now');
  draft = beginRun(draft, 'image', buildMediaTaskRequest(draft, 'image', 'run-first'), 'now');
  const content = canvasContent(draft);
  expect(Object.keys(content).sort()).toEqual(['edges', 'nodes', 'viewport']);
  expect(content.edges).toEqual([{ id: 'edge-0', source: 'brief', target: 'image' }]);
  expect(content.nodes.flatMap(node => Object.values(node.properties)).every(value => typeof value === 'string')).toBe(true);
  const restored = await readCanvasDocument({ ...record(), content });
  expect(restored.groups).toEqual(draft.groups);
  expect(restored.edges).toEqual(draft.edges);
  expect(restored.nodes[1]).toMatchObject({ taskState: 'submitting', runs: draft.nodes[1].kind === 'generation' ? draft.nodes[1].runs : [] });
  expect(buildMediaTaskRequest(restored, 'image', 'run-first')).toEqual(buildMediaTaskRequest(draft, 'image', 'run-first'));
});

it('restores the exact selected output and frame roles after reload without persisting signed previews', async () => {
  let draft = addGenerationNode(createCanvasDocument('canvas-1', 'now'), 'image', { x: 0, y: 0 }, 'now');
  draft = addGenerationNode(draft, 'video', { x: 500, y: 0 }, 'now');
  draft.nodes = draft.nodes.map(node => node.kind === 'generation' && node.id === 'image' ? { ...node, taskState: 'succeeded', taskRef: 'batch', selectedOutputId: 'result' } : node);
  draft.nodes.push({ id: 'result', kind: 'image', version: 1, position: { x: 0, y: 0 }, sourceTaskRef: 'batch', asset: { assetRef: 'file-result', alt: '香氛瓶', kind: 'image', previewRef: 'https://cdn.example.test/signed?token=temporary' } });
  draft.edges = [{ id: 'image->result', source: 'image', target: 'result' }, { id: 'image->video:result', source: 'image', target: 'video', outputId: 'result', role: 'start', assetRef: 'file-result' }];
  fetchMock.mockImplementation(async () => json({ url: 'https://cdn.example.test/fresh.png' }));
  const content = canvasContent(draft);
  expect(JSON.stringify(content)).not.toContain('token=temporary');
  const restored = await readCanvasDocument({ ...record(), content });
  expect(restored.edges).toEqual(draft.edges);
  expect(restored.nodes[0]).toMatchObject({ selectedOutputId: 'result' });
});

it('rejects damaged editor metadata and oversized history before writing anything', async () => {
  const draft = addTextNode(createCanvasDocument('canvas-1', 'now'), 'a', { x: 0, y: 0 }, 'now');
  const content = canvasContent(draft);
  content.nodes[0].properties.editorReferences = JSON.stringify([{ wireId: 'missing', id: 'a->b', source: 'a', target: 'b' }]);
  await expect(readCanvasDocument({ ...record(), content })).rejects.toThrow('避免覆盖数据');
  draft.nodes[0] = { ...draft.nodes[0], kind: 'text', text: 'a'.repeat(65537) };
  await expect(persistCanvas(record(), draft, vi.fn())).rejects.toThrow('保存容量');
  expect(fetchMock).not.toHaveBeenCalled();
});

it('round trips both generation prompts and directional connections in the properties map', async () => {
  let draft = addGenerationNode(createCanvasDocument('canvas-1', 'now'), 'image', { x: 123, y: -20 }, 'now');
  draft = addGenerationNode(draft, 'video', { x: -40, y: 10 }, 'now');
  draft = addTextNode(draft, 'text', { x: 50, y: 20 }, 'now');
  draft.nodes = draft.nodes.map(node => node.kind === 'generation' ? {
    ...node, prompt: `  ${node.id} 自身提示词\n第二行  `,
    settings: { ...node.settings, type: node.id === 'video' ? 'VIDEO' : 'IMAGE' },
  } : { ...node, text: '保留旧文本' });
  draft.edges = [{ id: 'edge-0', source: 'image', target: 'video' }, { id: 'edge-1', source: 'video', target: 'text' }];
  const content = canvasContent(draft);
  const restored = await readCanvasDocument({ ...record(), content });
  expect(restored.nodes).toEqual(draft.nodes);
  expect(restored.edges).toEqual(draft.edges);
});

it('saves with current version and never retries or overwrites a conflict', async () => {
  fetchMock.mockImplementation(async (url: string) => url.includes('/auth/session')
    ? json({ authenticated: true, csrfToken: 'csrf', roles: [], primary: false, user: null }) : json({ message: 'stale' }, 409));
  const accept = vi.fn();
  await expect(persistCanvas(record(), createCanvasDocument('canvas-1', 'now'), accept)).rejects.toMatchObject({ status: 409 });
  expect(accept).not.toHaveBeenCalled();
  const calls = fetchMock.mock.calls.filter(([url]) => !String(url).includes('/auth/session'));
  expect(calls).toHaveLength(1);
  expect(JSON.parse(calls[0][1].body)).toMatchObject({ expectedVersion: 1 });
  expect(calls[0][1].credentials).toBe('include');
  expect(calls[0][1].headers.get('X-CSRF-TOKEN')).toBe('csrf');
});

it('adds generated file IDs with incrementing versions before saving their positions and edges', async () => {
  let remote = record();
  fetchMock.mockImplementation(async (url: string, options: RequestInit) => {
    if (url.includes('/auth/session')) return json({ authenticated: true, csrfToken: 'csrf', roles: [], primary: false, user: null });
    const body = JSON.parse(options.body as string);
    expect(body.expectedVersion).toBe(remote.version);
    remote = { ...remote, version: remote.version + 1 };
    if (url.endsWith('/media-references')) {
      expect(body).toMatchObject({ publicId: 'file-1', kind: 'VIDEO', nodeId: 'output-1' });
      remote.content.nodes.push({ id: body.nodeId, kind: 'video', properties: { publicId: body.publicId } });
    } else remote.content = body.content;
    return json(remote);
  });
  const draft = createCanvasDocument('canvas-1', 'now');
  draft.nodes.push({ id: 'output-1', kind: 'video', version: 1, position: { x: 2, y: 3 }, sourceTaskRef: null,
    asset: { assetRef: 'file-1', kind: 'video', alt: '视频', previewRef: 'https://cdn.example.test/video.mp4' } });
  const accept = vi.fn();
  await persistCanvas(record(), draft, accept);
  expect(accept.mock.calls.map(([r]) => r.version)).toEqual([2, 3]);
  expect(remote.content.nodes[0].properties).not.toHaveProperty('previewRef');
});

it('submits real Media DTO and reads partial results through Storage', async () => {
  fetchMock.mockImplementation(async (url: string) => {
    if (url.includes('/auth/session')) return json({ authenticated: true, csrfToken: 'csrf', roles: [], primary: false, user: null });
    if (url.endsWith('/results')) return json([{ index: 0, fileId: 'file-1' }]);
    if (url.endsWith('/access-url')) return json({ url: 'https://cdn.example.test/result.png', expiresAt: null });
    return json({ id: 'task-1', status: 'PARTIAL_SUCCESS', type: 'IMAGE' }, url.endsWith('/tasks') ? 202 : 200);
  });
  const port = createProductionMediaPort();
  expect(await port.submit({ requestRef: 'request-1', prompt: '雨夜', inputAssetRefs: ['file-input', 'https://cdn.example.test/input.png'], settings })).toEqual({ taskRef: 'task-1' });
  const submit = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/tasks'))!;
  expect(submit[1].headers.get('Idempotency-Key')).toBe('request-1');
  expect(JSON.parse(submit[1].body)).toMatchObject({ ...settings, prompt: '雨夜', references: [{ fileId: 'file-input' }, { httpsUrl: 'https://cdn.example.test/input.png' }] });
  expect(await port.read({ taskRef: 'task-1' })).toMatchObject({ state: 'partial_success', outputs: [{ assetRef: 'file-1', previewRef: 'https://cdn.example.test/result.png' }] });
});

it.each([['ROUTING', 'accepted'], ['RUNNING', 'working'], ['REFUND_PENDING', 'refund_pending'], ['FAILED', 'failed'], ['TIMED_OUT', 'timed_out']])('maps %s without inventing results', async (status, state) => {
  fetchMock.mockResolvedValue(json({ id: 'task-1', status, type: 'IMAGE' }));
  const port = createProductionMediaPort();
  const result = await port.read({ taskRef: 'task-1' });
  expect(result.state).toBe(state);
  if (status === 'ROUTING') expect(result).toMatchObject({ message: expect.stringContaining('生成服务暂不可用') });
  expect(result).not.toHaveProperty('outputs');
});

it('rejects inline media before any network write', async () => {
  const port = createProductionMediaPort();
  await expect(port.submit({ requestRef: 'key', prompt: 'text', inputAssetRefs: ['data:image/png;base64,AA=='], settings })).rejects.toThrow();
  expect(fetchMock).not.toHaveBeenCalled();
});

it.each([401, 403, 409, 422, 502, 503])('explains recoverable HTTP %s', async status => {
  fetchMock.mockResolvedValue(json({}, status));
  try { await createProductionMediaPort().read({ taskRef: 'task-1' }); }
  catch (error) { expect(canvasError(error)).toMatch(/登录|权限|冲突|参数|暂不可用/); }
});

it('recovers a media-reference-only node after an interrupted final content save', async () => {
  const recordWithReference = record();
  recordWithReference.content.nodes = [
    { id: 'generation-1', kind: 'generation', properties: { x: '20', y: '30', taskRef: 'task-1', taskState: 'working' } },
    { id: 'task-1-output-0', kind: 'image', properties: { publicId: 'file-1' } },
  ];
  fetchMock.mockResolvedValue(json({ url: 'https://cdn.example.test/result.png', expiresAt: null }));
  const document = await readCanvasDocument(recordWithReference);
  const { reconcileMediaTask } = await import('./task-reconciler');
  const next = reconcileMediaTask(document, 'generation-1', { state: 'succeeded', taskRef: 'task-1', outputs: [{
    kind: 'image', assetRef: 'file-1', alt: '生成图片 1', previewRef: 'https://cdn.example.test/result.png',
  }] }, 'now');
  expect(next.nodes).toHaveLength(2);
  expect(next.nodes[0]).toMatchObject({ taskState: 'succeeded' });
  expect(next.nodes[1]).toMatchObject({ sourceTaskRef: 'task-1', position: { x: 380, y: 30 } });
  expect(next.edges).toHaveLength(1);
});

const settings = { type: 'IMAGE' as const, model: 'seedream-5.0-lite', aspectRatio: '1:1', resolution: '2K', count: 2, durationSeconds: 0 };

it('reads generation options and quotes all node settings through the SDK', async () => {
  const option = { type: 'IMAGE', model: settings.model, displayName: 'Seedream 5.0 Lite', resolutions: ['2K'], aspectRatios: ['1:1'], maxCount: 4, minDurationSeconds: 0, maxDurationSeconds: 0 };
  fetchMock.mockImplementation(async (url: string) => {
    if (url.endsWith('/auth/session')) return json({ authenticated: true, csrfToken: 'csrf', roles: [], primary: false, user: null });
    if (url.endsWith('/generation-options')) return json({ models: [option] });
    return json({ points: '12.3400' });
  });
  const port = createProductionMediaPort();
  expect(await port.options()).toEqual([option]);
  expect(await port.quote(settings)).toEqual({ points: '12.3400' });
  const quote = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/quotes'))!;
  expect(JSON.parse(quote[1].body)).toEqual(settings);
  expect(quote[1].headers.get('X-CSRF-TOKEN')).toBe('csrf');
});

it('persists each generation setting in server content and restores it', async () => {
  const draft = addGenerationNode(createCanvasDocument('canvas-1', 'now'), 'generation-1', { x: 300, y: 10 }, 'now');
  Object.assign(draft.nodes[0], { settings });
  const restored = await readCanvasDocument({ ...record(), content: canvasContent(draft) });
  expect(restored.nodes).toEqual(draft.nodes);
});

it('upgrades partial video settings from server content without overwriting saved choices', async () => {
  const previous = record();
  previous.content.nodes.push({ id: 'generation-1', kind: 'generation', properties: {
    taskState: 'idle', settings: JSON.stringify({ type: 'VIDEO', model: 'saved-model', durationSeconds: 9 }),
  } });
  const document = await readCanvasDocument(previous);
  expect(document.nodes[0]).toMatchObject({ settings: {
    type: 'VIDEO', model: 'saved-model', aspectRatio: '16:9', resolution: '720p', count: 1, durationSeconds: 9,
  } });
});

it('passes typed omni references through the generated SDK and rejects URL IDs before a network write', async () => {
  fetchMock.mockImplementation(async (url: string) => url.includes('/auth/session') ? json({ authenticated: true, csrfToken: 'csrf', roles: [], primary: false, user: null }) : json({ id: 'task-omni' }));
  const request = { requestRef: 'typed-key', prompt: '图片1 视频1 音频1', inputAssetRefs: [], referenceMode: 'OMNI' as const,
    settings: { ...settings, type: 'VIDEO' as const, durationSeconds: 5, count: 1 }, references: [
      { fileId: 'image-real', kind: 'IMAGE' as const, durationSeconds: null },
      { fileId: 'video-real', kind: 'VIDEO' as const, durationSeconds: 3 },
      { fileId: 'audio-real', kind: 'AUDIO' as const, durationSeconds: 4 },
    ] };
  await createProductionMediaPort().submit(request);
  const write = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/tasks'))!;
  expect(JSON.parse(write[1].body)).toMatchObject({ referenceMode: 'OMNI', references: request.references });
  fetchMock.mockClear();
  await expect(createProductionMediaPort().submit({ ...request, references: [{ fileId: 'https://assets.example/a.mp4', kind: 'VIDEO', durationSeconds: 3 }] })).rejects.toThrow('文件 ID');
  expect(fetchMock).not.toHaveBeenCalled();
});

it('preserves audio metadata and per-node reference mode through canvas content', async () => {
  let draft = addGenerationNode(createCanvasDocument('canvas-1', 'now'), 'video', { x: 0, y: 0 }, 'now');
  draft.nodes = draft.nodes.map(node => node.kind === 'generation' ? { ...node, settings: { ...node.settings, type: 'VIDEO', referenceMode: 'OMNI', durationSeconds: 5 } } : node);
  draft.nodes.push({ id: 'audio-ref', kind: 'audio', version: 1, position: { x: 0, y: 100 }, sourceTaskRef: null,
    asset: { assetRef: 'audio-file', kind: 'audio', alt: '背景音乐', previewRef: 'https://cdn.example.test/audio.mp3', durationSeconds: 4.5, sizeBytes: 2000 } });
  draft.edges.push({ id: 'edge-0', source: 'audio-ref', target: 'video' });
  fetchMock.mockImplementation(async (url: string) => url.endsWith('/access-url') ? json({ url: 'https://cdn.example.test/audio.mp3' }) : json(record()));
  const restored = await readCanvasDocument({ ...record(), content: canvasContent(draft) });
  expect(restored.nodes).toEqual(draft.nodes);
});

it('quotes and submits identical multimodal references and preserves idempotency headers', async () => {
  const references = [{ fileId: 'image-file', kind: 'IMAGE' as const, durationSeconds: null }, { fileId: 'audio-file', kind: 'AUDIO' as const, durationSeconds: 4 }];
  fetchMock.mockImplementation(async (url: string) => url.endsWith('/auth/session') ? json({ authenticated: true, csrfToken: 'csrf', roles: ['USER'], primary: false, user: null })
    : url.endsWith('/quotes') ? json({ points: '2.0000' }) : json({ id: 'task-1' }));
  const settings = { type: 'VIDEO' as const, model: 'seedance-2.5', aspectRatio: '16:9', resolution: '720p', durationSeconds: 5, count: 1 };
  const port = createProductionMediaPort();
  await port.quote({ ...settings, referenceMode: 'OMNI', references });
  await port.submit({ requestRef: 'stable-request', settings, referenceMode: 'OMNI', references, inputAssetRefs: ['image-file', 'audio-file'], prompt: '自身与上游文本' });
  const quote = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/quotes'))!;
  const submit = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/tasks'))!;
  expect(JSON.parse(quote[1].body)).toEqual({ ...settings, referenceMode: 'OMNI', references });
  expect(JSON.parse(submit[1].body)).toEqual({ ...settings, referenceMode: 'OMNI', references, prompt: '自身与上游文本' });
  expect(submit[1].headers.get('Idempotency-Key')).toBe('stable-request');
});

it.each(['image', 'video', 'audio'] as const)('preserves %s portrait identity through canvas save/load and quote/submit without exposing an asset URL', async kind => {
  const { buildMediaTaskRequest, referenceIssue } = await import('./media-task-port');
  let draft = addGenerationNode(createCanvasDocument('canvas-1', 'now'), 'video', { x: 80, y: 0 }, 'now');
  draft.nodes = draft.nodes.map(node => node.kind === 'generation' ? { ...node, settings: { ...node.settings, type: 'VIDEO', model: 'video-one', durationSeconds: 5, referenceMode: 'OMNI' }, prompt: '角色走路' } : node);
  draft.nodes.push({ id: 'portrait-node', version: 1, kind, position: { x: 0, y: 0 }, sourceTaskRef: null,
    asset: { assetRef: 'file-one', portraitId: 'portrait-one', kind, alt: '虚拟角色', previewRef: '', ...(kind !== 'image' ? { durationSeconds: 4.5 } : {}) } });
  draft.edges.push({ id: 'reference', source: 'portrait-node', target: 'video' });
  fetchMock.mockImplementation(async (url: string) => url.includes('/auth/session') ? json({ authenticated: true, csrfToken: 'csrf' })
    : url.endsWith('/access-url') ? json({ url: 'https://assets.example/portrait.png' })
    : url.endsWith('/quotes') ? json({ points: '5.0000' }) : json({ id: 'task-portrait' }, 202));
  const restored = await readCanvasDocument({ ...record(), content: canvasContent(draft) });
  const request = buildMediaTaskRequest(restored, 'video', 'stable-portrait-generation');
  expect(request.references).toEqual([{ fileId: 'file-one', portraitId: 'portrait-one', kind: kind.toUpperCase(), durationSeconds: kind === 'image' ? null : 4.5 }]);
  const port = createProductionMediaPort();
  await port.quote({ ...request.settings, referenceMode: request.referenceMode, references: request.references });
  await port.submit(request);
  const writes = fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/quotes') || String(url).endsWith('/tasks'));
  expect(writes).toHaveLength(2);
  writes.forEach(([, options]) => expect(JSON.parse(options.body).references).toEqual(request.references));
  restored.nodes = restored.nodes.map(node => node.kind === 'generation' ? { ...node, settings: { ...node.settings, referenceMode: 'FIRST_LAST_FRAME' } } : node);
  expect(referenceIssue(restored, 'video')).toContain('已审核人像仅支持视频全能参考');
  expect(buildMediaTaskRequest(restored, 'video', 'other').references?.[0].portraitId).toBe('portrait-one');
});

it('submits depth with only sourceFileId and resolution, and never requests billing', async () => {
  fetchMock.mockImplementation(async (url: string) => url.includes('/auth/session')
    ? json({ authenticated: true, csrfToken: 'csrf', roles: [], primary: false, user: null }) : json({ id: 'depth-task' }));
  const depth = { ...settings, operation: 'DEPTH' as const, type: 'VIDEO' as const, resolution: '720p', durationSeconds: 0, count: 1 };
  const port = createProductionMediaPort();
  await expect(port.submit({ requestRef: 'depth-key', prompt: 'ignored', inputAssetRefs: ['file-video'], settings: depth })).resolves.toEqual({ taskRef: 'depth-task' });
  const write = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/depth-tasks'))!;
  expect(JSON.parse(write[1].body)).toEqual({ sourceFileId: 'file-video', resolution: '720p' });
  expect(write[1].headers.get('Idempotency-Key')).toBe('depth-key');
  expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/quotes'))).toBe(false);
});

it.each([true, false])('loads depth independently of a failed ordinary catalog (enabled=%s)', async enabled => {
  fetchMock.mockImplementation(async (url: string) => url.endsWith('/depth-options')
    ? json({ enabled, resolutions: ['480p', '720p'], highResolutionMaxDurationSeconds: 30 }) : json({}, 503));
  const options = await createProductionMediaPort().options();
  expect(options.filter(option => option.operation === 'DEPTH')).toHaveLength(enabled ? 1 : 0);
});

it('does not suppress an unauthorized catalog response', async () => {
  fetchMock.mockImplementation(async (url: string) => url.endsWith('/depth-options')
    ? json({ enabled: true, resolutions: ['480p', '720p'], highResolutionMaxDurationSeconds: 30 }) : json({}, 401));
  await expect(createProductionMediaPort().options()).rejects.toMatchObject({ status: 401 });
});

it('restores depth task settings and returns trusted source duration on reusable results', async () => {
  const depth = { ...settings, operation: 'DEPTH' as const, type: 'VIDEO' as const, resolution: '480p', aspectRatio: 'source', durationSeconds: 0, count: 1 };
  const draft = addGenerationNode(createCanvasDocument('canvas-1', 'now'), 'depth-1', { x: 0, y: 0 }, 'now');
  Object.assign(draft.nodes[0], { settings: depth, taskRef: 'depth-task', taskState: 'working' });
  expect((await readCanvasDocument({ ...record(), content: canvasContent(draft) })).nodes[0]).toMatchObject({ settings: depth, taskRef: 'depth-task' });
  fetchMock.mockImplementation(async (url: string) => url.endsWith('/results') ? json([{ index: 0, fileId: 'depth-file' }])
    : url.endsWith('/access-url') ? json({ url: 'https://cdn.example.test/depth.mp4' })
      : json({ id: 'depth-task', type: 'VIDEO', operation: 'DEPTH', status: 'SUCCEEDED', references: [{ durationSeconds: 29.99 }] }));
  expect(await createProductionMediaPort().read({ taskRef: 'depth-task' })).toMatchObject({ state: 'succeeded', outputs: [{ kind: 'video', assetRef: 'depth-file', durationSeconds: 29.99 }] });
});

it.each([{ reviewPending: true }, { reviewId: 'review-one', reviewPending: false }])('round trips server review association %j as editor metadata', async metadata => {
  const draft = createCanvasDocument('canvas-1', 'now');
  draft.nodes.push({ id: 'original', kind: 'image', version: 1, position: { x: 0, y: 0 }, sourceTaskRef: null,
    asset: { assetRef: 'original-file', kind: 'image', alt: '原文件', previewRef: '', ...metadata } });
  fetchMock.mockResolvedValue(json({}, 503));
  const content = canvasContent(draft);
  expect(content.nodes[0].properties.reviewPending).toBe(String(metadata.reviewPending));
  if ('reviewId' in metadata) expect(content.nodes[0].properties.reviewId).toBe(metadata.reviewId);
  expect((await readCanvasDocument({ ...record(), content })).nodes).toEqual(draft.nodes);
});

it.each<Record<string, string>>([{ reviewId: '' }, { reviewId: 'bad/id' }, { reviewPending: 'yes' }, { reviewPending: '' }])('rejects malformed server review metadata %j', async metadata => {
  const remote = record();
  remote.content.nodes.push({ id: 'original', kind: 'image', properties: { publicId: 'original-file', ...metadata } });
  fetchMock.mockResolvedValue(json({}, 503));
  await expect(readCanvasDocument(remote)).rejects.toThrow('画布内容无法安全读取');
});

it.each([403, 422])('leaves linked review authorization and Active/model validation to the server (%s)', async status => {
  const { buildMediaTaskRequest, canvasQuoteParameters } = await import('./media-task-port');
  const draft = addGenerationNode(createCanvasDocument('canvas-1', 'now'), 'video', { x: 300, y: 0 }, 'now');
  draft.nodes = draft.nodes.map(node => node.kind === 'generation' ? { ...node,
    settings: { ...node.settings, type: 'VIDEO', count: 1, durationSeconds: 5, referenceMode: 'OMNI' } } : node);
  draft.nodes.push({ id: 'original', kind: 'image', version: 1, position: { x: 0, y: 0 }, sourceTaskRef: null,
    asset: { assetRef: 'original-file', kind: 'image', alt: '原文件', previewRef: '', reviewId: 'review-one', reviewPending: false } });
  draft.edges.push({ id: 'input', source: 'original', target: 'video' });
  fetchMock.mockImplementation(async (url: string) => url.endsWith('/auth/session')
    ? json({ authenticated: true, csrfToken: 'csrf' }) : json({}, status));
  const port = createProductionMediaPort();
  await expect(port.quote(canvasQuoteParameters(draft, 'video'))).rejects.toMatchObject({ status });
  await expect(port.submit(buildMediaTaskRequest(draft, 'video', 'key'))).rejects.toMatchObject({ status });
  const requests = fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/quotes') || String(url).endsWith('/tasks'));
  expect(requests).toHaveLength(2);
  requests.forEach(([, options]) => expect(JSON.parse(options.body).references).toEqual([
    { fileId: 'original-file', portraitId: 'review-one', kind: 'IMAGE', durationSeconds: null },
  ]));
});
