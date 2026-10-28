import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { VideoTaskCard } from './VideoTaskResults';
import { VideoResultsGallery } from './VideoResultsGallery';
import * as api from '../api';

vi.mock('../api', async original => ({ ...await original<typeof import('../api')>(),
  mediaGetGetApiV1MediaTasksTaskId: vi.fn(), storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl: vi.fn(),
}));
const reply = (data: unknown) => ({ data }) as never;
afterEach(() => { cleanup(); vi.restoreAllMocks(); sessionStorage.clear(); localStorage.clear(); });

it('keeps one interactive version panel and its selected file in every gallery layout', async () => {
  const task = { id: 'history-native', type: 'VIDEO', operation: 'GENERATION' as const, status: 'SUCCEEDED', prompt: '已保存的原生视频',
    results: [{ index: 0, fileId: 'native-file' }], enhancements: ['720p', '1080p', '2K', '4K'].map(target => ({ id: `child-${target}`, status: 'SUCCEEDED',
      sourceOutputIndex: 0, sourceFileId: 'native-file', targetResolution: target, billableSeconds: 6, amount: '0.6000', points: '6.0000',
      results: [{ index: 0, fileId: `enhanced-${target}` }], refundStatus: 'NOT_REQUIRED', failureMessage: null })) };
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(task));
  vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockImplementation(async id => reply({ url: `https://assets.example/${id}.mp4` }));
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  const downloads: string[] = [];
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) { downloads.push(this.href); });
  render(<VideoResultsGallery ready loading={false} error="" hasMore={false} empty={false} submission="" onLatest={vi.fn()} onOlder={vi.fn()} onRetry={vi.fn()}>
    {compact => <VideoTaskCard compact={compact} enhancementOwnerId="owner" entry={{ key: task.id, taskRef: task.id, snapshot: task }} />}
  </VideoResultsGallery>);
  await act(async () => {});
  fireEvent.change(screen.getByRole('combobox', { name: '视频版本' }), { target: { value: 'enhanced-4K' } });
  for (const layout of ['单列', '四宫格', '九宫格']) {
    fireEvent.click(screen.getByRole('button', { name: layout }));
    expect(screen.getAllByRole('button', { name: '超分增强' })).toHaveLength(1);
    expect(screen.getByRole('combobox', { name: '视频版本' })).toHaveValue('enhanced-4K');
    await act(async () => {});
    expect(screen.getByLabelText('生成视频 1')).toHaveAttribute('src', 'https://assets.example/enhanced-4K.mp4');
    fireEvent.click(screen.getByRole('button', { name: '下载1080p增强版' }));
    await act(async () => {});
  }
  expect(downloads).toEqual(Array(3).fill('https://assets.example/enhanced-1080p.mp4'));
});

it('explains why a refund-pending enhancement prevents hiding its original history record', async () => {
  const task = { id: 'history-native', type: 'VIDEO', operation: 'GENERATION' as const, status: 'SUCCEEDED', prompt: '退款中的增强',
    results: [{ index: 0, fileId: 'native-file' }], enhancements: [{ id: 'pending-refund', status: 'REFUND_PENDING', sourceOutputIndex: 0,
      sourceFileId: 'native-file', targetResolution: '720p', billableSeconds: 6, amount: '0.6000', points: '6.0000', results: [], refundStatus: 'PENDING', failureMessage: null }] };
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockImplementation(async id => reply(id === task.id ? task : { id, operation: 'ENHANCEMENT', status: 'REFUND_PENDING', refundStatus: 'PENDING' }));
  vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockResolvedValue(reply({ url: 'https://assets.example/native.mp4' }));
  const hide = vi.fn();
  render(<VideoTaskCard enhancementOwnerId="owner" onDelete={hide} entry={{ key: task.id, taskRef: task.id, snapshot: task }} />);
  await act(async () => {});
  expect(screen.getByRole('button', { name: '删除视频' })).toBeDisabled();
  expect(screen.getByText('增强任务仍在处理或退款中，请完成后再隐藏此视频记录。')).toBeVisible();
  expect(hide).not.toHaveBeenCalled();
});

it.each(['DEPTH', 'ENHANCEMENT'] as const)('preserves the video preview for %s processing without offering native-source enhancement', async operation => {
  const task = { id: 'processing-task', type: 'VIDEO', operation, status: 'SUCCEEDED', prompt: '本人处理结果', results: [{ index: 0, fileId: 'processed-file' }] };
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(task));
  vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockResolvedValue(reply({ url: 'https://assets.example/processed.mp4' }));
  render(<VideoTaskCard enhancementOwnerId="owner" entry={{ key: task.id, taskRef: task.id, snapshot: task }} />);
  await act(async () => {});
  const preview = screen.getByLabelText('生成视频 1');
  expect(preview).toHaveAttribute('src', 'https://assets.example/processed.mp4');
  expect(preview).toHaveAttribute('controls');
  expect(screen.queryByRole('button', { name: '超分增强' })).not.toBeInTheDocument();
  expect(preview.closest('.video-result-outputs')).toHaveAttribute('aria-hidden', 'false');
});
