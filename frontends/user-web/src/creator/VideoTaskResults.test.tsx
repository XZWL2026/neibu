import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { axe } from 'vitest-axe';
import { MediaTaskCard, VideoTaskCard, type VideoSubmission } from './VideoTaskResults';
import { readVideoSubmission, saveVideoSubmission } from './video-submission-recovery';
import * as api from '../api';

vi.mock('../api', async original => ({ ...await original<typeof import('../api')>(),
  mediaGetGetApiV1MediaTasksTaskId: vi.fn(), storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl: vi.fn(),
}));
const reply = (data: unknown) => ({ data }) as never;
const entry: VideoSubmission = { key: 'local-key', taskRef: 'task-one', snapshot: {
  prompt: '海边日落', createdAt: '2026-09-09T08:00:00Z', modelName: '提交时模型', resolution: '720p', aspectRatio: '16:9', durationSeconds: 5,
} };
beforeEach(() => {
  vi.resetAllMocks(); sessionStorage.clear();
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ id: 'task-one', status: 'RUNNING', results: [] }));
  vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockResolvedValue(reply({ url: 'https://assets.example/video.mp4' }));
});
afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });
it('keeps gallery images and refund notices accessible without generation parameters', async () => {
  const task = { id: 'task-one', type: 'IMAGE' as const, status: 'REFUND_PENDING' as const, count: 2,
    results: [{ index: 0, fileId: 'gallery-image' }], failures: [{ index: 1, message: '生成失败，请稍后重试' }], refundStatus: 'PENDING' as const };
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(task));
  render(<MediaTaskCard entry={{ ...entry, snapshot: { ...entry.snapshot, ...task } }} showMetadata={false} showPromptAction={false} />);
  expect(await screen.findByRole('button', { name: '放大图片 1' })).toBeVisible();
  expect(screen.getByText('失败部分退款处理中')).toBeVisible();
  expect(screen.queryByText('提交时模型')).not.toBeInTheDocument();
  expect(screen.queryByText('查看本次生成参数')).not.toBeInTheDocument();
  const attachmentClick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
  fireEvent.click(screen.getByRole('button', { name: '放大图片 1' }));
  const preview = screen.getByRole('dialog', { name: '图片预览' });
  expect(preview).toBeVisible();
  fireEvent.click(within(preview).getByRole('button', { name: '下载图片 1' }));
  expect(attachmentClick).toHaveBeenCalledTimes(1);
  expect(preview).toBeVisible();
  fireEvent(preview, new Event('cancel', { bubbles: false, cancelable: true }));
  expect(screen.queryByRole('dialog', { name: '图片预览' })).not.toBeInTheDocument();
});
it('moves from running to a real video with controls and no standalone download link', async () => {
  vi.useFakeTimers();
  render(<VideoTaskCard entry={entry} />);
  await act(async () => {});
  const card = screen.getByRole('article', { name: '视频任务：海边日落' });
  expect(within(card).getByRole('progressbar', { name: '视频生成进度' })).toBeVisible();
  expect(card).toHaveTextContent(/\d+%/);
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ id: 'task-one', status: 'SUCCEEDED', results: [{ index: 0, fileId: 'real-file' }] }));
  await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
  expect(screen.getByRole('article')).toBe(card);
  expect(screen.getByText('100%')).toBeVisible();
  expect(screen.getByLabelText('生成视频 1')).not.toBeVisible();
  await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
  expect(screen.getByText('100%')).toBeVisible();
  fireEvent.loadedData(screen.getByLabelText('生成视频 1'));
  const video = within(card).getByLabelText('生成视频 1');
  expect(video.tagName).toBe('VIDEO'); expect(video).toHaveAttribute('controls');
  expect(video).toHaveAttribute('src', 'https://assets.example/video.mp4');
  expect(within(card).queryByRole('link', { name: /下载视频/ })).not.toBeInTheDocument();
  expect(within(card).queryByRole('progressbar')).not.toBeInTheDocument();
});
it('keeps partial outputs while refund is pending and only confirms refund after the server does', async () => {
  vi.useFakeTimers();
  const partial = { id: 'task-one', status: 'REFUND_PENDING', results: [{ index: 0, fileId: 'success' }], failures: [{ index: 1, message: '内容审核未通过，请调整提示词或素材后重试' }], refundStatus: 'PENDING' };
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(partial));
  render(<VideoTaskCard entry={entry} />); await act(async () => {});
  fireEvent.loadedData(screen.getByLabelText('生成视频 1'));
  expect(screen.getByLabelText('生成视频 1')).toBeVisible();
  expect(screen.getByText('视频 2 生成失败')).toBeVisible();
  expect(screen.getByText('失败部分退款处理中')).toBeVisible();
  expect(screen.queryByText(/已退回/)).not.toBeInTheDocument();
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ ...partial, status: 'PARTIAL_SUCCESS', refundStatus: 'COMPLETED' }));
  await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
  fireEvent.loadedData(screen.getByLabelText('生成视频 1'));
  expect(screen.getByLabelText('生成视频 1')).toBeVisible();
  expect(screen.getByText('失败部分积分已退回')).toBeVisible();
});
it('keeps an accepted task recoverable through read failures without marking it failed', async () => {
  vi.useFakeTimers();
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockRejectedValueOnce(new Error('private upstream response'));
  render(<VideoTaskCard entry={{ ...entry, snapshot: { ...entry.snapshot, status: 'RUNNING' } }} />); await act(async () => {});
  expect(screen.getByRole('progressbar', { name: '视频生成进度' })).toBeVisible();
  expect(screen.queryByText('生成失败')).not.toBeInTheDocument();
  expect(screen.queryByText(/private upstream/)).not.toBeInTheDocument();
  await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
  expect(screen.queryByText(/任务状态暂不可用/)).not.toBeInTheDocument();
});
it('displays safe mapped failure, expands long reasons and has accessible media status', async () => {
  const message = '火山生成失败。' + '请稍后重试。'.repeat(30);
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ id: 'task-one', status: 'FAILED', failures: [{ index: 0, message }], results: [] }));
  render(<VideoTaskCard entry={entry} />);
  expect(await screen.findByRole('heading', { name: '生成失败' })).toBeVisible();
  expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
  expect(screen.queryByText(/腾讯增强失败|task-one/)).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '展开原因' }));
  expect(screen.getByRole('button', { name: '收起原因' })).toHaveAttribute('aria-expanded', 'true');
  expect((await axe(screen.getByRole('article'), { rules: { 'color-contrast': { enabled: false } } })).violations).toEqual([]);
});
it('keeps the original request per account and restores uncertainty without automatic submission', () => {
  const pending: VideoSubmission = { ...entry, taskRef: undefined, key: 'original-key', state: 'SUBMITTING', request: {
    requestRef: 'original-key', prompt: 'original prompt', inputAssetRefs: ['first-file'], settings: { type: 'VIDEO', model: 'original', resolution: '720p', aspectRatio: '16:9', count: 1, durationSeconds: 5 },
  } };
  saveVideoSubmission('owner-one', pending);
  expect(readVideoSubmission('owner-two')).toBeNull();
  expect(readVideoSubmission('owner-one')).toEqual({ ...pending, state: 'UNCERTAIN' });
  expect(api.mediaGetGetApiV1MediaTasksTaskId).not.toHaveBeenCalled();
});

it('renders every real image, magnifies, opens and downloads while preserving partial failures and refund state', async () => {
  const partial = { id: 'task-one', type: 'IMAGE', status: 'PARTIAL_SUCCESS', count: 3, results: [{ index: 0, fileId: 'one' }, { index: 2, fileId: 'three' }], failures: [{ index: 1, message: '图片已生成，但保存失败，请联系管理员检查存储配置' }], refundStatus: 'COMPLETED' };
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(partial));
  render(<VideoTaskCard entry={{ ...entry, snapshot: { ...entry.snapshot, type: 'IMAGE', count: 3 } }} />);
  expect(await screen.findByRole('img', { name: '生成图片 1' })).toHaveAttribute('src', 'https://assets.example/video.mp4');
  expect(screen.getByRole('img', { name: '生成图片 3' })).not.toBeVisible();
  expect(screen.getByRole('status', { name: '生成图片 3 加载中' })).toBeInTheDocument();
  fireEvent.load(screen.getByRole('img', { name: '生成图片 3' }));
  expect(screen.getByRole('img', { name: '生成图片 3' })).toBeVisible();
  expect(screen.getByText('图片 2 生成失败')).toBeVisible();
  expect(screen.getByText('失败部分积分已退回')).toBeVisible();
  expect(screen.queryByRole('link', { name: /打开图片|下载图片/ })).not.toBeInTheDocument();
  const more = screen.getByText('•••', { selector: 'summary[aria-label="图片 1 更多操作"]' });
  fireEvent.click(more);
  expect(screen.getByRole('button', { name: '下载图片 1' })).toBeVisible();
  fireEvent.keyDown(more, { key: 'Escape' });
  expect(more.closest('details')).not.toHaveAttribute('open');
  fireEvent.click(screen.getByRole('button', { name: '放大图片 1' }));
  expect(screen.getByRole('dialog', { name: '图片预览' })).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '关闭图片预览' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.queryByText(/task-one|local-key/)).not.toBeInTheDocument();
});

it('hides prompt and reference media until opening the prompt dialog and exposes result actions', async () => {
  const snapshot = { ...entry.snapshot, id: 'task-one', type: 'VIDEO', status: 'SUCCEEDED', references: [{ fileId: 'reference', kind: 'IMAGE' as const, httpsUrl: null, durationSeconds: null }], results: [{ index: 0, fileId: 'output' }] };
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(snapshot));
  const edit = vi.fn(), regenerate = vi.fn(), remove = vi.fn();
  vi.spyOn(window, 'confirm').mockReturnValue(true);
  render(<VideoTaskCard entry={{ ...entry, snapshot }} onEdit={edit} onRegenerate={regenerate} onDelete={remove} />);
  await screen.findByLabelText('生成视频 1');
  expect(screen.queryByText('海边日落')).not.toBeInTheDocument();
  expect(screen.queryByAltText('首帧')).not.toBeInTheDocument();
  expect(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).not.toHaveBeenCalledWith('reference');
  fireEvent.click(screen.getByRole('button', { name: '查看提示词' }));
  expect(within(screen.getByRole('dialog', { name: '提示词' })).getByText('海边日落')).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '关闭提示词' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '重新编辑' }));
  expect(edit).toHaveBeenCalledWith(expect.objectContaining({ taskRef: 'task-one', snapshot: expect.objectContaining({ prompt: '海边日落', references: snapshot.references }) }));
  fireEvent.click(screen.getByRole('button', { name: '重新生成' }));
  expect(regenerate).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', { name: '删除视频' }));
  expect(remove).toHaveBeenCalledTimes(1);
});

it('replaces the polled task in the existing card without showing stale output or keeping the old poll', async () => {
  vi.useFakeTimers();
  const old = { ...entry.snapshot, id: 'task-one', type: 'VIDEO', status: 'SUCCEEDED', results: [{ index: 0, fileId: 'old-output' }] };
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(old));
  const view = render(<VideoTaskCard entry={{ ...entry, snapshot: old }} />); await act(async () => {});
  fireEvent.loadedData(screen.getByLabelText('生成视频 1'));
  expect(screen.getByLabelText('生成视频 1')).toBeVisible();
  const card = screen.getByRole('article');
  const replacement = { ...entry, taskRef: 'task-new', snapshot: { ...entry.snapshot, type: 'VIDEO', status: 'RUNNING', results: [] } };
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ ...replacement.snapshot, id: 'task-new' }));
  view.rerender(<VideoTaskCard entry={replacement} />); await act(async () => {});
  expect(screen.getByRole('article')).toBe(card);
  expect(screen.queryByLabelText('生成视频 1')).not.toBeInTheDocument();
  const refreshed = vi.fn(); window.addEventListener('xzwl-account-changed', refreshed);
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ id: 'task-new', status: 'SUCCEEDED', results: [{ index: 0, fileId: 'new-output' }] }));
  await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
  fireEvent.loadedData(screen.getByLabelText('生成视频 1'));
  await act(async () => { await vi.advanceTimersByTimeAsync(1400); });
  expect(api.mediaGetGetApiV1MediaTasksTaskId).toHaveBeenLastCalledWith('task-new');
  expect(refreshed).toHaveBeenCalledTimes(1);
  window.removeEventListener('xzwl-account-changed', refreshed);
});

it.each(['IMAGE', 'VIDEO'])('shows estimated progress instead of generating text throughout queue stages for %s', async type => {
  for (const status of ['PREPARED', 'SUBMITTING', 'UNCERTAIN', 'ROUTING', 'CHARGE_PENDING', 'SUBMIT_PENDING', 'RUNNING', 'ARCHIVING']) {
    const local = status === 'PREPARED' || status === 'SUBMITTING' || status === 'UNCERTAIN';
    vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ id: 'task-one', type, status, results: [] }));
    const view = render(<VideoTaskCard entry={{ ...entry, taskRef: local ? undefined : entry.taskRef,
      state: local ? status : undefined, snapshot: { ...entry.snapshot, type, status } }} />);
    expect(screen.getByRole('progressbar', { name: `${type === 'IMAGE' ? '图片' : '视频'}生成进度` })).toBeVisible();
    await act(async () => {});
    expect(screen.getByRole('progressbar', { name: `${type === 'IMAGE' ? '图片' : '视频'}生成进度` })).toBeVisible();
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuemin', '0');
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuemax', '100');
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuetext', expect.stringMatching(/^预估 \d+%$/));
    expect(screen.queryByText('生成中')).not.toBeInTheDocument();
    expect(screen.getByRole('article')).toHaveTextContent(/\d+%/);
    expect(screen.queryByText(/预估/)).not.toBeInTheDocument();
    expect(screen.queryByText('排队中')).not.toBeInTheDocument();
    view.unmount();
  }
});

it('downloads the selected attachment without requiring storage CORS', async () => {
  const fetchImage = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
  vi.stubGlobal('fetch', fetchImage);
  const clicked: string[] = [];
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) { clicked.push(this.href, this.download); });
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ id: 'task-one', type: 'IMAGE', status: 'SUCCEEDED', results: [{ index: 0, fileId: 'one' }, { index: 2, fileId: 'three' }] }));
  vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockImplementation(async fileId => reply({ url: `https://assets.example/${fileId}.png` }));
  render(<VideoTaskCard entry={{ ...entry, snapshot: { ...entry.snapshot, type: 'IMAGE', status: 'SUCCEEDED' } }} />);
  await act(async () => {});
  const more = await screen.findByText('•••', { selector: 'summary[aria-label="图片 3 更多操作"]' });
  fireEvent.click(more);
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '下载图片 3' })); });
  expect(clicked).toEqual(['https://assets.example/three.png', 'image-3']);
  expect(fetchImage).not.toHaveBeenCalled();
  fireEvent.blur(more, { relatedTarget: document.body });
  expect(more.closest('details')).not.toHaveAttribute('open');
});

it('grows estimated fill proportionally, never retreats and waits for completion below 100%', async () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-09-22T12:00:00Z'));
  render(<VideoTaskCard entry={{ ...entry, snapshot: { ...entry.snapshot, type: 'IMAGE', status: 'RUNNING' } }} />);
  await act(async () => {});
  expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '15');
  await act(async () => { await vi.advanceTimersByTimeAsync(28000); });
  const progress = screen.getByRole('progressbar');
  expect(progress).toHaveAttribute('aria-valuenow', '50');
  expect(screen.getByText('50%')).toBeVisible();
  expect(progress.querySelector('.generation-progress-fill')).toHaveStyle({ width: '50%' });
  await act(async () => { await vi.advanceTimersByTimeAsync(600000); });
  expect(Number(progress.getAttribute('aria-valuenow'))).toBeLessThan(100);
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ id: 'task-one', status: 'ARCHIVING', results: [] }));
  await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
  const archiving = Number(progress.getAttribute('aria-valuenow'));
  expect(archiving).toBeGreaterThanOrEqual(92);
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ id: 'task-one', status: 'RUNNING', results: [] }));
  await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
  expect(Number(progress.getAttribute('aria-valuenow'))).toBeGreaterThanOrEqual(archiving);
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ id: 'task-one', status: 'SUCCEEDED', results: [{ index: 0, fileId: 'done' }] }));
  await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
  expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '100');
  expect(screen.getByText('100%')).toBeVisible();
  expect(screen.getByRole('progressbar').querySelector('.generation-progress-fill')).toHaveStyle({ width: '100%' });
  const output = screen.getByAltText('生成图片 1');
  expect(output).not.toBeVisible();
  fireEvent.load(output);
  await act(async () => { await vi.advanceTimersByTimeAsync(1399); });
  expect(screen.getByText('100%')).toBeVisible();
  expect(output.closest('.video-result-outputs')).toHaveAttribute('aria-hidden', 'true');
  await act(async () => { await vi.advanceTimersByTimeAsync(1); });
  expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
  expect(screen.getByRole('img', { name: '生成图片 1' })).toBeInTheDocument();
});

it('releases the completion layer on preview failure instead of leaving 100% stuck', async () => {
  vi.useFakeTimers();
  render(<VideoTaskCard entry={entry} />); await act(async () => {});
  vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockRejectedValue(new Error('preview unavailable'));
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ id: 'task-one', status: 'SUCCEEDED', results: [{ index: 0, fileId: 'unavailable' }] }));
  await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
  expect(screen.getByText('100%')).toBeVisible();
  await act(async () => { await vi.advanceTimersByTimeAsync(1400); });
  expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: '重新加载视频' })).toBeVisible();
});

it.each(['IMAGE', 'VIDEO'])('resets %s progress when regenerating while the previous media is still loading', async type => {
  vi.useFakeTimers();
  const view = render(<VideoTaskCard entry={{ ...entry, snapshot: { ...entry.snapshot, type } }} />);
  await act(async () => {});
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ id: 'task-one', type, status: 'SUCCEEDED', results: [{ index: 0, fileId: 'old-output' }] }));
  await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
  expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '100');
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ id: 'task-new', type, status: 'RUNNING', results: [] }));
  view.rerender(<VideoTaskCard entry={{ ...entry, taskRef: 'task-new', snapshot: { ...entry.snapshot, type, status: 'RUNNING' } }} />);
  await act(async () => {});
  expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '15');
  expect(screen.queryByText('100%')).not.toBeInTheDocument();
  await act(async () => { await vi.advanceTimersByTimeAsync(60000); });
  expect(Number(screen.getByRole('progressbar').getAttribute('aria-valuenow'))).toBeLessThan(100);
});

it('keeps submission progress when the same request receives its task ID', async () => {
  vi.useFakeTimers();
  const pending = { ...entry, taskRef: undefined, state: 'SUBMITTING' as const, request: { requestRef: 'same-request' } as NonNullable<VideoSubmission['request']> };
  const view = render(<VideoTaskCard entry={pending} />);
  await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
  const before = Number(screen.getByRole('progressbar').getAttribute('aria-valuenow'));
  expect(before).toBeGreaterThan(3);
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockReturnValue(new Promise(() => {}));
  view.rerender(<VideoTaskCard entry={{ ...pending, taskRef: 'accepted-task', state: undefined }} />);
  await act(async () => {});
  expect(Number(screen.getByRole('progressbar').getAttribute('aria-valuenow'))).toBe(before);
});

it.each(['IMAGE', 'VIDEO'] as const)('skips generation progress when a restored %s task is already complete', async type => {
  let finish!: (value: never) => void;
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockImplementation(() => new Promise(resolve => { finish = resolve; }));
  const restored = { ...entry, snapshot: { ...entry.snapshot, type } };
  const view = render(<VideoTaskCard entry={restored} />);
  expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
  await act(async () => { finish(reply({ id: entry.taskRef, type, status: 'SUCCEEDED', results: [{ index: 0, fileId: 'saved-output' }] })); });
  expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
  expect(screen.queryByText('100%')).not.toBeInTheDocument();
  const output = type === 'IMAGE' ? screen.getByAltText('生成图片 1') : screen.getByLabelText('生成视频 1');
  expect(output.closest('.video-result-outputs')).toHaveAttribute('aria-hidden', 'false');
  if (type === 'IMAGE') fireEvent.load(output); else fireEvent.loadedData(output);
  expect(output).toBeVisible();
  view.unmount();
});

it('still shows 100% for a newly submitted task that succeeds on its first poll', async () => {
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ id: entry.taskRef, status: 'SUCCEEDED', results: [{ index: 0, fileId: 'new-output' }] }));
  const view = render(<VideoTaskCard entry={{ ...entry, taskRef: undefined, state: 'SUBMITTING' }} />);
  expect(screen.getByRole('progressbar')).toBeVisible();
  view.rerender(<VideoTaskCard entry={entry} />);
  await act(async () => {});
  expect(screen.getByText('100%')).toBeVisible();
  expect(screen.getByLabelText('生成视频 1').closest('.video-result-outputs')).toHaveAttribute('aria-hidden', 'true');
});

it.each(['IMAGE', 'VIDEO'] as const)('keeps the %s loading frame independent of the submitted ratio', async type => {
  const { rerender } = render(<VideoTaskCard entry={{ ...entry, snapshot: { ...entry.snapshot, type } }} />);
  await act(async () => {});
  for (const aspectRatio of ['16:9', '9:16', '1:1']) {
    rerender(<VideoTaskCard entry={{ ...entry, snapshot: { ...entry.snapshot, type, aspectRatio } }} />);
    expect(screen.getByRole('progressbar').parentElement?.style.aspectRatio).toBe('');
  }
});

it('does not fetch first-frame dimensions for the loading frame', async () => {
  render(<VideoTaskCard entry={{ ...entry, snapshot: { ...entry.snapshot, type: 'VIDEO', referenceFollowsFirstFrame: true,
    references: [{ fileId: 'first-frame', kind: 'IMAGE', httpsUrl: null, durationSeconds: null }] } }} />);
  await act(async () => {});
  expect(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).not.toHaveBeenCalled();
  expect(screen.getByRole('progressbar').parentElement?.style.aspectRatio).toBe('');
});

it('polls enhancement children after native generation has ended and keeps both versions downloadable', async () => {
  vi.useFakeTimers();
  const child = { id: 'enhancement-one', status: 'RUNNING', sourceOutputIndex: 0, sourceFileId: 'native-file', targetResolution: '720p', billableSeconds: 6, amount: '0.6000', points: '6.0000', results: [], refundStatus: null, failureMessage: null };
  const task = { id: 'task-one', type: 'VIDEO', operation: 'GENERATION' as const, status: 'SUCCEEDED', results: [{ index: 0, fileId: 'native-file' }], enhancements: [child] };
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockImplementation(async id => reply(id === 'task-one' ? task : { ...child, operation: 'ENHANCEMENT', type: 'VIDEO' }));
  vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockImplementation(async id => reply({ url: `https://assets.example/${id}.mp4` }));
  const clicked: string[] = [];
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) { clicked.push(this.href, this.download); });
  render(<VideoTaskCard enhancementOwnerId="owner" onDelete={vi.fn()} entry={{ ...entry, snapshot: { ...entry.snapshot, ...task } }} />);
  await act(async () => {});
  expect(screen.getByText('增强处理中')).toBeVisible();
  expect(screen.getByRole('button', { name: '删除视频' })).toBeDisabled();
  expect(screen.getByLabelText('生成视频 1')).toHaveAttribute('src', 'https://assets.example/native-file.mp4');
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockImplementation(async id => reply(id === 'task-one' ? task : { ...child, operation: 'ENHANCEMENT', type: 'VIDEO', status: 'SUCCEEDED', results: [{ index: 0, fileId: 'enhanced-file' }] }));
  await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
  expect(screen.getByRole('combobox', { name: '视频版本' })).toBeVisible();
  fireEvent.change(screen.getByRole('combobox', { name: '视频版本' }), { target: { value: 'enhanced-file' } });
  await act(async () => {});
  expect(screen.getByLabelText('生成视频 1')).toHaveAttribute('src', 'https://assets.example/enhanced-file.mp4');
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '下载原版' })); });
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '下载720p增强版' })); });
  expect(clicked).toEqual(['https://assets.example/native-file.mp4', 'video-1-native', 'https://assets.example/enhanced-file.mp4', 'video-1-720p']);
  expect(screen.getAllByRole('article')).toHaveLength(1);
});

it('preserves native generation and polls the actual enhancement refund through transient reads', async () => {
  vi.useFakeTimers();
  const child = { id: 'refund-child', status: 'REFUND_PENDING', sourceOutputIndex: 0, sourceFileId: 'native-file', targetResolution: '720p', billableSeconds: 6, amount: '0.6000', points: '6.0000', results: [], refundStatus: 'PENDING', failureMessage: null };
  const task = { id: 'task-one', type: 'VIDEO', operation: 'GENERATION' as const, status: 'SUCCEEDED', results: [{ index: 0, fileId: 'native-file' }], enhancements: [child] };
  let recover = false;
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockImplementation(async id => {
    if (id === 'task-one') return reply(task);
    if (!recover) throw new Error('private provider content');
    return reply({ ...child, operation: 'ENHANCEMENT', type: 'VIDEO', status: 'FAILED', refundStatus: 'COMPLETED' });
  });
  render(<VideoTaskCard enhancementOwnerId="owner" entry={{ ...entry, snapshot: { ...entry.snapshot, ...task } }} />);
  await act(async () => {});
  expect(screen.getByText('增强费用退款处理中')).toBeVisible();
  expect(screen.getByText('增强状态暂不可用，将继续同步；请勿重复提交。')).toBeVisible();
  expect(screen.queryByText('本次增强积分已退回')).not.toBeInTheDocument();
  expect(screen.queryByText('private provider content')).not.toBeInTheDocument();
  expect(screen.getByLabelText('生成视频 1')).toHaveAttribute('src', 'https://assets.example/video.mp4');
  recover = true;
  await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
  expect(screen.getByText('本次增强积分已退回')).toBeVisible();
  expect(screen.getByText('增强失败，原版已保留')).toBeVisible();
  expect(screen.queryByText('生成失败')).not.toBeInTheDocument();
});

it('restores an uncertain paid enhancement by its owner and replays only the originally confirmed quote and request key', async () => {
  const task = { id: 'task-one', type: 'VIDEO', operation: 'GENERATION' as const, status: 'SUCCEEDED', results: [{ index: 0, fileId: 'native-file' }] };
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(task));
  sessionStorage.setItem('xzwl-enhancement:owner:task-one:0', JSON.stringify({ owner: 'owner', taskId: 'task-one', outputIndex: 0, fileId: 'native-file', target: '720p', quoteId: 'original-quote', key: 'original-paid-key', billableSeconds: 6, amount: '0.6000', points: '6.0000', state: 'SUBMITTING' }));
  const paid: { key: string | null; body: unknown }[] = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith('/auth/session')) return new Response(JSON.stringify({ authenticated: true, csrfToken: 'csrf', user: { id: 'owner' } }), { headers: { 'Content-Type': 'application/json' } });
    paid.push({ key: new Headers(init?.headers).get('Idempotency-Key'), body: JSON.parse(String(init?.body)) });
    return new Response(JSON.stringify({ message: 'quote expired' }), { status: 422, headers: { 'Content-Type': 'application/json' } });
  }));
  const view = render(<VideoTaskCard enhancementOwnerId="owner" entry={{ ...entry, snapshot: { ...entry.snapshot, ...task } }} />);
  await act(async () => {});
  expect(paid).toEqual([]);
  fireEvent.click(screen.getByRole('button', { name: '使用原增强请求确认' }));
  await act(async () => {});
  expect(paid).toEqual([{ key: 'original-paid-key', body: { quoteId: 'original-quote' } }]);
  view.unmount();
  render(<VideoTaskCard enhancementOwnerId="owner" entry={{ ...entry, snapshot: { ...entry.snapshot, ...task } }} />);
  await act(async () => {});
  fireEvent.click(screen.getByRole('button', { name: '使用原增强请求确认' }));
  await act(async () => {});
  expect(paid).toEqual([{ key: 'original-paid-key', body: { quoteId: 'original-quote' } }, { key: 'original-paid-key', body: { quoteId: 'original-quote' } }]);
});

it('blocks uncertain confirmation while identity is being checked and never restores another owner request', async () => {
  const task = { id: 'task-one', type: 'VIDEO', operation: 'GENERATION' as const, status: 'SUCCEEDED', results: [{ index: 0, fileId: 'native-file' }] };
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(task));
  sessionStorage.setItem('xzwl-enhancement:owner:task-one:0', JSON.stringify({ owner: 'owner', taskId: 'task-one', outputIndex: 0, fileId: 'native-file', target: '720p', quoteId: 'original-quote', key: 'original-paid-key', billableSeconds: 6, amount: '0.6000', points: '6.0000', state: 'UNCERTAIN' }));
  const requests = vi.fn(); vi.stubGlobal('fetch', requests);
  const restored = { ...entry, snapshot: { ...entry.snapshot, ...task } };
  const view = render(<VideoTaskCard enhancementOwnerId="owner" actionsDisabled entry={restored} />);
  await act(async () => {});
  expect(screen.getByRole('button', { name: '使用原增强请求确认' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: '使用原增强请求确认' }));
  expect(requests).not.toHaveBeenCalled();
  view.rerender(<VideoTaskCard enhancementOwnerId="another-owner" entry={restored} />);
  await act(async () => {});
  expect(screen.queryByRole('button', { name: '使用原增强请求确认' })).not.toBeInTheDocument();
  expect(requests).not.toHaveBeenCalled();
});

it('refuses paid dispatch when durable browser recovery cannot be saved', async () => {
  const task = { id: 'task-one', type: 'VIDEO', operation: 'GENERATION' as const, status: 'SUCCEEDED', results: [{ index: 0, fileId: 'native-file' }] };
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(task));
  const requests = vi.fn(async () => new Response(JSON.stringify({ taskId: 'task-one', outputIndex: 0, fileId: 'native-file', sourceResolution: '480p', aspectRatio: '16:9', actualDurationSeconds: 5.03, billableSeconds: 6,
    targets: [{ quoteId: 'paid-quote', targetResolution: '720p', amount: '0.6000', rate: '10.0000', points: '6.0000', executionSupported: true, expiresAt: '2099-10-13T09:00:00Z' }] }), { headers: { 'Content-Type': 'application/json' } }));
  vi.stubGlobal('fetch', requests);
  render(<VideoTaskCard enhancementOwnerId="owner" entry={{ ...entry, snapshot: { ...entry.snapshot, ...task } }} />);
  fireEvent.click(await screen.findByRole('button', { name: '超分增强' }));
  const confirm = await screen.findByRole('button', { name: '确认费用并增强' });
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('Quota exceeded'); });
  fireEvent.click(confirm);
  expect(screen.getByText('无法保存增强请求，请允许浏览器存储后重试。')).toBeVisible();
  expect(requests).toHaveBeenCalledTimes(1);
});

it('does not offer paid execution when the server marks even a 720p quote as unsupported', async () => {
  const task = { id: 'task-one', type: 'VIDEO', operation: 'GENERATION' as const, status: 'SUCCEEDED', results: [{ index: 0, fileId: 'native-file' }] };
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(task));
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ taskId: 'task-one', outputIndex: 0, fileId: 'native-file', sourceResolution: '480p', aspectRatio: '16:9', billableSeconds: 6,
    targets: [{ quoteId: 'unsupported-quote', targetResolution: '720p', amount: '0.6000', rate: '10.0000', points: '6.0000', executionSupported: false, expiresAt: '2099-10-13T09:00:00Z' }] }), { headers: { 'Content-Type': 'application/json' } })));
  render(<VideoTaskCard enhancementOwnerId="owner" entry={{ ...entry, snapshot: { ...entry.snapshot, ...task } }} />);
  fireEvent.click(await screen.findByRole('button', { name: '超分增强' }));
  expect(await screen.findByText('此组合暂未开放付费增强。')).toBeVisible();
  expect(screen.queryByRole('button', { name: '确认费用并增强' })).not.toBeInTheDocument();
});
afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it.each([false, true])('shows source-specific enhancement quotation in the %s compact view without paid submission', async compact => {
  const task = {id:'task-one',type:'VIDEO',operation:'GENERATION' as const,status:'SUCCEEDED',results:[{index:0,fileId:'native-file'}]};
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(task));
  const requests=vi.fn(async()=>new Response(JSON.stringify({taskId:'task-one',outputIndex:0,fileId:'native-file',sourceResolution:'480p',aspectRatio:'16:9',actualDurationSeconds:5.03,billableSeconds:6,targets:[{quoteId:'quote-one',targetResolution:'1080p',amount:'1.5000',rate:'10.0000',points:'15.0000',executionSupported:false,expiresAt:'2026-10-13T09:00:00Z'}]}),{headers:{'Content-Type':'application/json'}}));
  vi.stubGlobal('fetch',requests);
  render(<VideoTaskCard compact={compact} entry={{...entry,snapshot:{...entry.snapshot,...task}}}/>);
  if(compact) fireEvent.click(await screen.findByRole('button',{name:/预览视频/}));
  fireEvent.click(await screen.findByRole('button',{name:'超分增强'}));
  expect(await screen.findByText('计费时长 6 秒')).toBeVisible();
  expect(screen.getByText('15.0000 积分')).toBeVisible();
  expect(screen.getByText('此组合暂未开放付费增强。')).toBeVisible();
  expect(screen.queryByRole('button',{name:/确认.*增强/})).not.toBeInTheDocument();
  expect(requests.mock.calls.every(call=>!((call as unknown[])[1] as RequestInit)?.method || ((call as unknown[])[1] as RequestInit)?.method==='GET')).toBe(true);
});

it('requires the displayed fee confirmation before submitting one independently paid enhancement', async () => {
  const task = { id: 'task-one', type: 'VIDEO', operation: 'GENERATION' as const, status: 'SUCCEEDED', results: [{ index: 0, fileId: 'native-file' }] };
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(task));
  const accepted: { key: string | null; body: unknown }[] = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith('/auth/session')) return new Response(JSON.stringify({ authenticated: true, csrfToken: 'csrf', user: { id: 'owner' } }), { headers: { 'Content-Type': 'application/json' } });
    if (init?.method === 'POST') {
      accepted.push({ key: new Headers(init.headers).get('Idempotency-Key'), body: JSON.parse(String(init.body)) });
      return new Response(JSON.stringify({ id: 'enhancement-one', type: 'VIDEO', operation: 'ENHANCEMENT', status: 'RUNNING', results: [] }), { headers: { 'Content-Type': 'application/json' } });
    }
    return new Response(JSON.stringify({ taskId: 'task-one', outputIndex: 0, fileId: 'native-file', sourceResolution: '480p', aspectRatio: '16:9', actualDurationSeconds: 5.03, billableSeconds: 6,
      targets: [{ quoteId: 'paid-quote', targetResolution: '720p', amount: '0.6000', rate: '10.0000', points: '6.0000', executionSupported: true, expiresAt: '2099-10-13T09:00:00Z' }] }), { headers: { 'Content-Type': 'application/json' } });
  }));
  render(<VideoTaskCard enhancementOwnerId="owner" entry={{ ...entry, snapshot: { ...entry.snapshot, ...task } }} />);
  fireEvent.click(await screen.findByRole('button', { name: '超分增强' }));
  expect(await screen.findByText('6.0000 积分')).toBeVisible();
  expect(accepted).toEqual([]);
  fireEvent.click(screen.getByRole('button', { name: '确认费用并增强' }));
  await act(async () => {});
  expect(accepted).toEqual([{ key: expect.any(String), body: { quoteId: 'paid-quote' } }]);
  expect(screen.getByText('增强处理中')).toBeVisible();
  expect(screen.getByLabelText('生成视频 1')).toHaveAttribute('src', 'https://assets.example/video.mp4');
});


it('keeps the native video usable while empty quotes and a failed quote read remain nonpayable', async () => {
  const task={id:'task-one',type:'VIDEO',operation:'GENERATION' as const,status:'SUCCEEDED',results:[{index:0,fileId:'native-file'}]};
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(task));
  const requests=vi.fn().mockRejectedValueOnce(new Error('private supplier failure')).mockResolvedValue(new Response(JSON.stringify({taskId:'task-one',outputIndex:0,fileId:'native-file',billableSeconds:6,targets:[]}),{headers:{'Content-Type':'application/json'}}));
  vi.stubGlobal('fetch',requests);
  render(<VideoTaskCard entry={{...entry,snapshot:{...entry.snapshot,...task}}}/>);
  fireEvent.click(await screen.findByRole('button',{name:'超分增强'}));
  expect(await screen.findByText('增强报价暂不可用，请重新读取。')).toBeVisible();
  expect(screen.queryByText('private supplier failure')).not.toBeInTheDocument();
  expect(screen.getByLabelText('生成视频 1')).toHaveAttribute('src','https://assets.example/video.mp4');
  fireEvent.click(screen.getByRole('button',{name:'重新读取增强报价'}));
  expect(await screen.findByText('此视频暂无可用增强目标，请联系管理员配置适用模板和价格。')).toBeVisible();
  expect(screen.queryByRole('combobox',{name:'增强目标分辨率'})).not.toBeInTheDocument();
  expect(screen.queryByRole('button',{name:/确认.*增强/})).not.toBeInTheDocument();
});


it('offers quote recovery after a malformed successful response without losing the native video', async () => {
  const task={id:'task-one',type:'VIDEO',operation:'GENERATION' as const,status:'SUCCEEDED',results:[{index:0,fileId:'native-file'}]};
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(task));
  vi.stubGlobal('fetch',vi.fn(async()=>new Response('null',{headers:{'Content-Type':'application/json'}})));
  render(<VideoTaskCard entry={{...entry,snapshot:{...entry.snapshot,...task}}}/>);
  fireEvent.click(await screen.findByRole('button',{name:'超分增强'}));
  expect(await screen.findByRole('button',{name:'重新读取增强报价'})).toBeVisible();
  expect(screen.getByLabelText('生成视频 1')).toHaveAttribute('src','https://assets.example/video.mp4');
  expect(screen.queryByRole('combobox',{name:'增强目标分辨率'})).not.toBeInTheDocument();
});


it('keeps the shared business result surface free of generation-page enhancement controls', async () => {
  const task={id:'task-one',type:'VIDEO',operation:'GENERATION' as const,status:'SUCCEEDED',results:[{index:0,fileId:'native-file'}]};
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(task));
  render(<MediaTaskCard showPromptAction={false} entry={{...entry,snapshot:{...entry.snapshot,...task}}}/>);
  const video=await screen.findByLabelText('生成视频 1'); fireEvent.loadedData(video);
  expect(video).toBeVisible();
  expect(screen.queryByRole('button',{name:'超分增强'})).not.toBeInTheDocument();
});
