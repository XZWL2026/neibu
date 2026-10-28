import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as api from '../api';
import { EnhancementQuotePanel } from './EnhancementQuotePanel';

vi.mock('../api', async original => ({ ...await original<typeof import('../api')>(),
  mediaGetGetApiV1MediaTasksTaskId: vi.fn(),
  mediaOptionsGetApiV1MediaTasksTaskIdOutputsOutputIndexEnhancementOptions: vi.fn(),
  mediaSubmitEnhancementPostApiV1MediaTasksTaskIdOutputsOutputIndexEnhancements: vi.fn(),
  storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl: vi.fn(),
}));
const reply = (data: unknown) => ({ data }) as never;
const version = { id: 'enhancement-a', status: 'SUCCEEDED', sourceOutputIndex: 0,
  sourceFileId: 'native-a', targetResolution: '720p', billableSeconds: 6,
  amount: '0.7404', points: '7.4040', results: [{ index: 0, fileId: 'enhanced-a' }],
  refundStatus: 'NOT_REQUIRED', failureMessage: null };
const preview = (id: string) => <video aria-label="恢复视频" src={`https://fixture.example/${id}.mp4`} controls />;
beforeEach(() => {
  vi.resetAllMocks(); sessionStorage.clear();
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ id: 'source-b', type: 'VIDEO',
    operation: 'GENERATION', status: 'SUCCEEDED', results: [{ index: 0, fileId: 'native-b' }], enhancements: [] }));
});
afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

it('reuses enhancement controls without displaying the prior account or source version', async () => {
  const view = render(<EnhancementQuotePanel ownerId="owner-a" taskId="source-a" outputIndex={0}
    fileId="native-a" enhancements={[version]}>{preview}</EnhancementQuotePanel>);
  fireEvent.change(screen.getByRole('combobox', { name: '视频版本' }), { target: { value: 'enhanced-a' } });
  expect(screen.getByLabelText('恢复视频')).toHaveAttribute('src', 'https://fixture.example/enhanced-a.mp4');
  view.rerender(<EnhancementQuotePanel ownerId="owner-b" taskId="source-b" outputIndex={0}
    fileId="native-b" enhancements={[]}>{preview}</EnhancementQuotePanel>);
  await act(async () => {});
  expect(screen.queryByRole('combobox', { name: '视频版本' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '下载720p增强版' })).not.toBeInTheDocument();
  expect(screen.getByLabelText('恢复视频')).toHaveAttribute('src', 'https://fixture.example/native-b.mp4');
  expect(api.mediaSubmitEnhancementPostApiV1MediaTasksTaskIdOutputsOutputIndexEnhancements).not.toHaveBeenCalled();
});

it('merges a server parent refresh for an existing attempt and shows its frozen saved fee through child read failures', async () => {
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockRejectedValue(new Error('temporary child read loss'));
  const pending = { ...version, status: 'RUNNING', amount: '1.2000', points: '12.0000', results: [] };
  const view = render(<EnhancementQuotePanel ownerId="owner-a" taskId="source-a" outputIndex={0}
    fileId="native-a" enhancements={[pending]}>{preview}</EnhancementQuotePanel>);
  await act(async () => {});
  expect(screen.getByText('增强处理中')).toBeVisible();
  view.rerender(<EnhancementQuotePanel ownerId="owner-a" taskId="source-a" outputIndex={0}
    fileId="native-a" enhancements={[version]}>{preview}</EnhancementQuotePanel>);
  await act(async () => {});
  expect(screen.getByText('增强成功')).toBeVisible();
  expect(screen.getByRole('combobox', { name: '视频版本' })).toBeVisible();
  expect(screen.getByText('本次增强 6 秒 · 7.4040 积分 · 0.7404 元')).toBeVisible();
  fireEvent.change(screen.getByRole('combobox', { name: '视频版本' }), { target: { value: 'enhanced-a' } });
  view.rerender(<EnhancementQuotePanel ownerId="owner-a" taskId="source-a" outputIndex={0}
    fileId="native-a" enhancements={[pending]}>{preview}</EnhancementQuotePanel>);
  await act(async () => {});
  expect(screen.getByText('增强成功')).toBeVisible();
  expect(screen.getByLabelText('恢复视频')).toHaveAttribute('src', 'https://fixture.example/enhanced-a.mp4');
  expect(api.mediaSubmitEnhancementPostApiV1MediaTasksTaskIdOutputsOutputIndexEnhancements).not.toHaveBeenCalled();
});

it('restores server versions and their saved fees after remount with no local request and no automatic paid submission', async () => {
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ id: 'source-a', type: 'VIDEO',
    operation: 'GENERATION', status: 'SUCCEEDED', results: [{ index: 0, fileId: 'native-a' }], enhancements: [version] }));
  const view = render(<EnhancementQuotePanel ownerId="owner-a" taskId="source-a" outputIndex={0}
    fileId="native-a" enhancements={[]}>{preview}</EnhancementQuotePanel>);
  await act(async () => {});
  expect(screen.getByRole('combobox', { name: '视频版本' })).toBeVisible();
  expect(screen.getByText('本次增强 6 秒 · 7.4040 积分 · 0.7404 元')).toBeVisible();
  view.unmount();
  render(<EnhancementQuotePanel ownerId="owner-a" taskId="source-a" outputIndex={0}
    fileId="native-a" enhancements={[]}>{preview}</EnhancementQuotePanel>);
  await act(async () => {});
  expect(screen.getByText('增强成功')).toBeVisible();
  expect(api.mediaSubmitEnhancementPostApiV1MediaTasksTaskIdOutputsOutputIndexEnhancements).not.toHaveBeenCalled();
});

it.each(['accepted', 'rejected'] as const)('keeps a newly confirmed retry recoverable when an older %s response arrives after navigation and browser-record loss', async outcome => {
  let completeOld!: (value: Awaited<ReturnType<typeof api.mediaSubmitEnhancementPostApiV1MediaTasksTaskIdOutputsOutputIndexEnhancements>>) => void;
  let completeNew!: typeof completeOld;
  let rejectOld!: (cause: Error) => void;
  const oldResponse = new Promise<Awaited<ReturnType<typeof api.mediaSubmitEnhancementPostApiV1MediaTasksTaskIdOutputsOutputIndexEnhancements>>>((done, reject) => { completeOld = done; rejectOld = reject; });
  const newResponse = new Promise<Awaited<ReturnType<typeof api.mediaSubmitEnhancementPostApiV1MediaTasksTaskIdOutputsOutputIndexEnhancements>>>(done => { completeNew = done; });
  const failed = { ...version, status: 'FAILED', results: [], refundStatus: 'COMPLETED' };
  let received: typeof version[] = [];
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockImplementation(async id => reply(id === 'source-a'
    ? { id, status: 'SUCCEEDED', results: [{ index: 0, fileId: 'native-a' }], enhancements: received }
    : { ...failed, id, operation: 'ENHANCEMENT' }));
  vi.mocked(api.mediaOptionsGetApiV1MediaTasksTaskIdOutputsOutputIndexEnhancementOptions).mockResolvedValue(reply({
    taskId: 'source-a', outputIndex: 0, fileId: 'native-a', sourceResolution: '480p', aspectRatio: '16:9', billableSeconds: 6,
    targets: [{ quoteId: 'confirmed-quote', targetResolution: '720p', amount: '0.7404', rate: '10.0000', points: '7.4040', executionSupported: true, expiresAt: '2099-10-13T09:00:00Z' }],
  }));
  let posts = 0;
  vi.mocked(api.mediaSubmitEnhancementPostApiV1MediaTasksTaskIdOutputsOutputIndexEnhancements).mockImplementation(() => {
    posts++; received = [failed]; return posts === 1 ? oldResponse : newResponse;
  });
  const panel = () => <EnhancementQuotePanel ownerId="owner-a" taskId="source-a" outputIndex={0} fileId="native-a">{preview}</EnhancementQuotePanel>;
  const original = render(panel());
  await act(async () => {});
  fireEvent.click(screen.getByRole('button', { name: '超分增强' }));
  await act(async () => {});
  fireEvent.click(screen.getByRole('button', { name: '确认费用并增强' }));
  await act(async () => {});
  const key = 'xzwl-enhancement:owner-a:source-a:0';
  const oldKey = JSON.parse(sessionStorage.getItem(key)!).key;
  original.unmount();
  // Browser recovery storage can disappear independently of a still-running navigation request.
  sessionStorage.clear();
  render(panel());
  await act(async () => {});
  expect(screen.getByText('增强失败，原版已保留')).toBeVisible();
  expect(screen.getByText('本次增强积分已退回')).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '超分增强' }));
  await act(async () => {});
  fireEvent.click(screen.getByRole('button', { name: '确认费用并增强' }));
  await act(async () => {});
  const retryKey = JSON.parse(sessionStorage.getItem(key)!).key;
  expect(retryKey).not.toBe(oldKey);
  await act(async () => {
    if (outcome === 'accepted') completeOld(reply({ ...failed, operation: 'ENHANCEMENT' }));
    else rejectOld(new api.ApiError(new Response(null, { status: 409 }), { code: 'REQUEST_CONFLICT', message: 'Older request refused' }));
  });
  expect(JSON.parse(sessionStorage.getItem(key)!).key).toBe(retryKey);
  expect(posts).toBe(2);
  await act(async () => { completeNew(reply({ ...failed, id: 'retry-new', operation: 'ENHANCEMENT' })); });
});

it.each(['720p', '1080p', '2K', '4K'])('manually restores an uncertain %s submission with the original identity after navigation and an eligible account switch', async target => {
  const confirmed = { quoteId: 'original-confirmed-quote', targetResolution: target, amount: '1.2000', rate: '10.0000', points: '12.0000', executionSupported: true, expiresAt: '2099-10-13T09:00:00Z' };
  let received: typeof version[] = [];
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockImplementation(async id => reply(id === 'source-a'
    ? { id, status: 'SUCCEEDED', results: [{ index: 0, fileId: 'native-a' }], enhancements: received }
    : { id: 'source-b', status: 'SUCCEEDED', results: [{ index: 0, fileId: 'native-b' }], enhancements: [] }));
  vi.mocked(api.mediaOptionsGetApiV1MediaTasksTaskIdOutputsOutputIndexEnhancementOptions).mockResolvedValue(reply({
    taskId: 'source-a', outputIndex: 0, fileId: 'native-a', sourceResolution: '480p', aspectRatio: '16:9', billableSeconds: 6, targets: [confirmed],
  }));
  vi.mocked(api.mediaSubmitEnhancementPostApiV1MediaTasksTaskIdOutputsOutputIndexEnhancements)
    .mockRejectedValueOnce(new Error('accepted response lost'))
    .mockImplementationOnce(async () => {
      received = [{ ...version, targetResolution: target }];
      return reply({ ...version, targetResolution: target, operation: 'ENHANCEMENT' });
    });
  const panel = () => <EnhancementQuotePanel ownerId="owner-a" taskId="source-a" outputIndex={0} fileId="native-a">{preview}</EnhancementQuotePanel>;
  const view = render(panel());
  await act(async () => {});
  fireEvent.click(screen.getByRole('button', { name: '超分增强' }));
  await act(async () => {});
  const pay = screen.getByRole('button', { name: '确认费用并增强' });
  fireEvent.click(pay); fireEvent.click(pay);
  await act(async () => {});
  expect(api.mediaSubmitEnhancementPostApiV1MediaTasksTaskIdOutputsOutputIndexEnhancements).toHaveBeenCalledTimes(1);
  const original = vi.mocked(api.mediaSubmitEnhancementPostApiV1MediaTasksTaskIdOutputsOutputIndexEnhancements).mock.calls[0];
  expect(screen.getByText('增强请求可能已受理，请使用原请求确认；请勿重新付费提交。')).toBeVisible();
  view.rerender(<EnhancementQuotePanel ownerId="owner-b" taskId="source-b" outputIndex={0} fileId="native-b">{preview}</EnhancementQuotePanel>);
  await act(async () => {});
  expect(screen.queryByRole('button', { name: '使用原增强请求确认' })).not.toBeInTheDocument();
  expect(screen.getByLabelText('恢复视频')).toHaveAttribute('src', 'https://fixture.example/native-b.mp4');
  view.unmount();
  render(panel());
  await act(async () => {});
  expect(api.mediaSubmitEnhancementPostApiV1MediaTasksTaskIdOutputsOutputIndexEnhancements).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', { name: '使用原增强请求确认' }));
  await act(async () => {});
  expect(api.mediaSubmitEnhancementPostApiV1MediaTasksTaskIdOutputsOutputIndexEnhancements).toHaveBeenCalledTimes(2);
  expect(vi.mocked(api.mediaSubmitEnhancementPostApiV1MediaTasksTaskIdOutputsOutputIndexEnhancements).mock.calls[1]).toEqual(original);
  expect(screen.getByText('本次增强 6 秒 · 7.4040 积分 · 0.7404 元')).toBeVisible();
  expect(screen.queryByText('本次增强 6 秒 · 12.0000 积分 · 1.2000 元')).not.toBeInTheDocument();
  expect(screen.getByText('增强成功')).toBeVisible();
  expect(screen.getByRole('button', { name: `下载${target}增强版` })).toBeVisible();
});

it('preserves a successful version during transient parent and child failures and resumes independent enhancement polling', async () => {
  vi.useFakeTimers();
  const pending = { ...version, id: 'enhancement-new', targetResolution: '1080p', status: 'RUNNING', results: [] };
  let available = false;
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockImplementation(async id => {
    if (!available) throw new Error('temporary query failure');
    return reply(id === 'source-a' ? { id, status: 'SUCCEEDED', results: [{ index: 0, fileId: 'native-a' }], enhancements: [version, { ...pending, status: 'SUCCEEDED', results: [{ index: 0, fileId: 'enhanced-new' }] }] }
      : { ...pending, operation: 'ENHANCEMENT', status: 'SUCCEEDED', results: [{ index: 0, fileId: 'enhanced-new' }] });
  });
  render(<EnhancementQuotePanel ownerId="owner-a" taskId="source-a" outputIndex={0} fileId="native-a" enhancements={[version, pending]}>{preview}</EnhancementQuotePanel>);
  fireEvent.change(screen.getByRole('combobox', { name: '视频版本' }), { target: { value: 'enhanced-a' } });
  await act(async () => {});
  expect(screen.getByText('增强状态暂不可用，将继续同步；请勿重复提交。')).toBeVisible();
  expect(screen.getByText('增强处理中')).toBeVisible();
  expect(screen.queryByText('增强失败，原版已保留')).not.toBeInTheDocument();
  expect(screen.getByLabelText('恢复视频')).toHaveAttribute('src', 'https://fixture.example/enhanced-a.mp4');
  available = true;
  await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
  expect(screen.getAllByText('增强成功')).toHaveLength(2);
  expect(screen.getByRole('button', { name: '下载1080p增强版' })).toBeVisible();
  expect(screen.getByLabelText('恢复视频')).toHaveAttribute('src', 'https://fixture.example/enhanced-a.mp4');
  expect(api.mediaSubmitEnhancementPostApiV1MediaTasksTaskIdOutputsOutputIndexEnhancements).not.toHaveBeenCalled();
});
