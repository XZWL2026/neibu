import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ComponentType, ReactNode } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import { CanvasWorkspace } from './CanvasWorkspace';
import { addGenerationNode, connectNodes, createCanvasDocument, updateGenerationSettings, updateGenerationTask } from './document';
import { buildMediaTaskRequest } from './media-task-port';
import type { CanvasGenerationOption, MediaTaskPort } from './media-task-port';
import type { CanvasGenerationSettings } from './model';
import { canonicalizeCanvasDocument } from './persistence';
import { reconcileMediaTask } from './task-reconciler';

// Exercise the real Workspace -> Surface -> Nodes callback wiring, replacing only the layout engine.
vi.mock('@xyflow/react', () => ({
  ReactFlow: (props: { nodes: { id: string; type: string; data: unknown }[]; nodeTypes: Record<string, ComponentType<{ data: unknown }>>; children: ReactNode }) => <div>{props.nodes.map(node => {
    const View = props.nodeTypes[node.type]; return <View key={node.id} data={node.data} />;
  })}{props.children}</div>,
  ViewportPortal: ({ children }: { children: ReactNode }) => <>{children}</>, Background: () => null, MiniMap: () => null, Handle: () => null, PanOnScrollMode: { Vertical: "vertical" }, Position: { Left: 'left', Right: 'right' },
}));
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
const depthSettings: CanvasGenerationSettings = { operation: 'DEPTH', type: 'VIDEO', model: 'depth-anything-v2-small', aspectRatio: 'source', resolution: '480p', count: 1, durationSeconds: 0 };
const depthOption: CanvasGenerationOption = { ...depthSettings, displayName: '深度白模', resolutions: ['480p', '720p'], aspectRatios: ['source'], maxCount: 1, minDurationSeconds: 0, maxDurationSeconds: 0, highResolutionMaxDurationSeconds: 30 };
const sourceDocument = (durationSeconds?: number) => {
  const doc = createCanvasDocument('depth-project', 'now');
  doc.nodes.push({ id: 'video-source', kind: 'video', version: 1, position: { x: 0, y: 0 }, sourceTaskRef: null,
    asset: { assetRef: 'file-source', kind: 'video', alt: '源视频', previewRef: 'https://cdn.example.test/source.mp4', ...(durationSeconds === undefined ? {} : { durationSeconds }) } });
  return doc;
};
const port = (options = [depthOption]): MediaTaskPort => ({ options: vi.fn().mockResolvedValue(options), quote: vi.fn().mockResolvedValue({ points: '9.0000' }), submit: vi.fn().mockResolvedValue({ taskRef: 'depth-task' }), read: vi.fn().mockResolvedValue({ state: 'working', taskRef: 'depth-task' }) });

it.each([[30, ['480p', '720p']], [30.001, ['480p']], [undefined, ['480p']]] as const)('offers correct resolution for source duration %s and creates a connected depth node', async (duration, resolutions) => {
  const media = port(); const changed = vi.fn();
  render(<CanvasWorkspace projectId="depth-project" initialDocument={sourceDocument(duration)} storage={null} mediaTaskPort={media} onDocumentChange={changed} />);
  fireEvent.click(await screen.findByRole('button', { name: '生成深度白模' }));
  const resolution = await screen.findByLabelText('分辨率');
  expect(within(resolution).getAllByRole('option').map(option => option.textContent)).toEqual(resolutions);
  expect(screen.queryByLabelText('生成提示词')).not.toBeInTheDocument();
  expect(screen.queryByLabelText('视频时长（秒）')).not.toBeInTheDocument();
  expect(screen.queryByText(/预计.*积分/)).not.toBeInTheDocument();
  expect(await screen.findByText('本地深度处理')).toBeInTheDocument();
  expect(media.quote).not.toHaveBeenCalled();
  await waitFor(() => expect(screen.getByRole('button', { name: '提交生成任务' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '提交生成任务' }));
  fireEvent.click(screen.getByRole('button', { name: '提交生成任务' }));
  await waitFor(() => expect(media.submit).toHaveBeenCalledTimes(1));
  expect(vi.mocked(media.submit).mock.calls[0][0]).toMatchObject({ settings: depthSettings, prompt: '', inputAssetRefs: ['file-source'] });
  const latest = changed.mock.calls.at(-1)![0];
  expect(latest.edges).toEqual([expect.objectContaining({ source: 'video-source' })]);
  expect(canonicalizeCanvasDocument(latest, 'depth-project')?.nodes).toEqual(latest.nodes);
});

it('hides the video action when depth is disabled and does not substitute it for ordinary models', async () => {
  const ordinary: CanvasGenerationOption = { type: 'VIDEO', model: 'ordinary', displayName: '普通视频', resolutions: ['720p'], aspectRatios: ['16:9'], maxCount: 1, minDurationSeconds: 1, maxDurationSeconds: 10 };
  render(<CanvasWorkspace initialDocument={sourceDocument(5)} projectId="depth-project" storage={null} mediaTaskPort={port([ordinary])} />);
  await waitFor(() => expect(screen.queryByRole('button', { name: '生成深度白模' })).not.toBeInTheDocument());
  fireEvent.click(screen.getByRole('button', { name: '新增视频' }));
  expect(await screen.findByRole('button', { name: '模型' })).toHaveTextContent('普通视频');
  expect(screen.getByLabelText('生成提示词')).toBeInTheDocument();
});

it('keeps an unavailable saved depth node disabled rather than converting it to ordinary generation', async () => {
  let doc = addGenerationNode(sourceDocument(5), 'saved-depth', { x: 300, y: 0 }, 'now');
  doc = updateGenerationSettings(doc, 'saved-depth', depthSettings, 'now');
  doc = connectNodes(doc, 'video-source', 'saved-depth', 'now');
  const media = port([]);
  render(<CanvasWorkspace initialDocument={doc} projectId="depth-project" storage={null} mediaTaskPort={media} />);
  expect(await screen.findByText('深度处理未启用或暂不可用')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '提交生成任务' })).toBeDisabled();
  expect(media.quote).not.toHaveBeenCalled();
});

it('offers depth for inline output, stops at that video and keeps download tied to its actual preview', async () => {
  let doc = addGenerationNode(sourceDocument(4), 'upstream', { x: 300, y: 0 }, 'now');
  doc = connectNodes(doc, 'video-source', 'upstream', 'now');
  doc = updateGenerationTask(doc, 'upstream', { taskRef: 'upstream-task', taskState: 'working', message: null }, 'now');
  doc = reconcileMediaTask(doc, 'upstream', { taskRef: 'upstream-task', state: 'succeeded', outputs: [{ assetRef: 'file-result', kind: 'video', alt: '生成视频', previewRef: 'https://cdn.example.test/result.mp4', durationSeconds: 5 }] }, 'now');
  const changed = vi.fn();
  render(<CanvasWorkspace initialDocument={doc} projectId="depth-project" storage={null} mediaTaskPort={port()} onDocumentChange={changed} />);
  const upstream = screen.getByTestId('node-upstream');
  fireEvent.click(await within(upstream).findByRole('button', { name: '生成深度白模' }));
  const latest = changed.mock.calls.at(-1)![0];
  const depth = latest.nodes.find((node: { settings?: CanvasGenerationSettings }) => node.settings?.operation === 'DEPTH');
  expect(buildMediaTaskRequest(latest, depth.id, 'key').inputAssetRefs).toEqual(['file-result']);
  expect(within(screen.getAllByLabelText('分辨率').at(-1)!).getAllByRole('option').map(option => option.textContent)).toEqual(['480p', '720p']);
  expect(within(upstream).getByLabelText('生成视频')).toHaveAttribute('src', 'https://cdn.example.test/result.mp4');
  expect(within(upstream).queryByRole('link', { name: '打开视频并下载' })).not.toBeInTheDocument();
  expect(within(upstream).queryByText('打开后通过播放器菜单或“视频另存为”保存。')).not.toBeInTheDocument();
});

it('restores a failed depth task and creates a fresh connected request on explicit regeneration', async () => {
  let doc = addGenerationNode(sourceDocument(8), 'failed-depth', { x: 300, y: 0 }, 'now');
  doc = updateGenerationSettings(doc, 'failed-depth', depthSettings, 'now');
  doc = connectNodes(doc, 'video-source', 'failed-depth', 'now');
  doc = updateGenerationTask(doc, 'failed-depth', { taskRef: 'old-task', taskState: 'failed', message: '处理失败' }, 'now');
  const changed = vi.fn(); const media = port();
  render(<CanvasWorkspace initialDocument={canonicalizeCanvasDocument(doc, 'depth-project')!} projectId="depth-project" storage={null} mediaTaskPort={media} onDocumentChange={changed} />);
  fireEvent.click(await screen.findByRole('button', { name: '重新生成深度白模' }));
  const latest = changed.mock.calls.at(-1)![0];
  const newNode = latest.nodes.at(-1);
  expect(newNode).toMatchObject({ taskRef: null, settings: depthSettings });
  expect(newNode.id).not.toBe('failed-depth');
  expect(buildMediaTaskRequest(latest, newNode.id, 'key').inputAssetRefs).toEqual(['file-source']);
});
