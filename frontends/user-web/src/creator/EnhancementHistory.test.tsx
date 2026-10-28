import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { createMemoryRouter } from 'react-router-dom';
import { App } from '../App';
import { userRoutes } from '../router';
import * as api from '../api';

vi.mock('../api', async original => ({ ...await original<typeof import('../api')>(),
  refreshSession: vi.fn(), userSelfGetApiV1UsersMe: vi.fn(), accessListGetApiV1AccessKeys: vi.fn(),
  paymentOptionsGetApiV1PaymentsRechargeOptions: vi.fn(), paymentGetGetApiV1PaymentsRechargesId: vi.fn(), billingBalanceGetApiV1BillingWalletsKeyId: vi.fn(),
  mediaListGetApiV1MediaTasks: vi.fn(), mediaGetGetApiV1MediaTasksTaskId: vi.fn(), mediaDeleteHistoryDeleteApiV1MediaTasksTaskId: vi.fn(),
  storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl: vi.fn(),
  mediaOptionsGetApiV1MediaTasksTaskIdOutputsOutputIndexEnhancementOptions: vi.fn(),
}));
const reply = (data: unknown) => ({ data, status: 200, headers: new Headers() }) as never;
afterEach(() => { cleanup(); vi.restoreAllMocks(); sessionStorage.clear(); localStorage.clear(); });

it('keeps a paid enhancement source in history when the server refuses hiding it while processing', async () => {
  const saved = { id: 'saved-video', type: 'VIDEO', operation: 'GENERATION', status: 'SUCCEEDED', prompt: '正在增强的原生视频', results: [{ index: 0, fileId: 'stored-video' }],
    enhancements: [{ id: 'saved-enhancement', status: 'SUCCEEDED', sourceOutputIndex: 0, sourceFileId: 'stored-video', targetResolution: '720p', billableSeconds: 6, amount: '0.6000', points: '6.0000', results: [{ index: 0, fileId: 'stored-enhanced' }], refundStatus: 'NOT_REQUIRED' }] };
  vi.mocked(api.refreshSession).mockResolvedValue({ authenticated: true, user: { id: 'owner' } } as never);
  vi.mocked(api.userSelfGetApiV1UsersMe).mockResolvedValue(reply({ id: 'owner', enabled: true }));
  vi.mocked(api.paymentOptionsGetApiV1PaymentsRechargeOptions).mockResolvedValue(reply({ userId: 'owner', accountType: 'PERSONAL' }));
  vi.mocked(api.paymentGetGetApiV1PaymentsRechargesId).mockResolvedValue(reply({ userId: 'owner', orderId: 'order', status: 'PAID', fulfilled: true, accountType: 'PERSONAL' }));
  vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValue(reply([{ keyId: 'key', ownerId: 'owner', firstRechargeOrderId: 'order', enabled: true, kind: 'MEDIA', accountType: 'PERSONAL' }]));
  vi.mocked(api.billingBalanceGetApiV1BillingWalletsKeyId).mockResolvedValue(reply({ walletId: 'key', availablePoints4: '1000.0000' }));
  vi.mocked(api.mediaListGetApiV1MediaTasks).mockResolvedValue(reply([saved]));
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(saved));
  vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockResolvedValue(reply({ url: 'https://assets.example/native.mp4' }));
  vi.mocked(api.mediaOptionsGetApiV1MediaTasksTaskIdOutputsOutputIndexEnhancementOptions).mockResolvedValue(reply({ taskId: 'saved-video', outputIndex: 0, fileId: 'stored-video', billableSeconds: 6,
    targets: [{ quoteId: 'new-history-quote', targetResolution: '1080p', amount: '1.5000', rate: '10.0000', points: '15.0000', executionSupported: true, expiresAt: '2099-10-13T09:00:00Z' }] }));
  vi.mocked(api.mediaDeleteHistoryDeleteApiV1MediaTasksTaskId).mockRejectedValueOnce(new api.ApiError(new Response('', { status: 409 }), { code: 'ENHANCEMENT_PROCESSING' }));
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  vi.spyOn(window, 'confirm').mockReturnValue(true);
  render(<App router={createMemoryRouter(userRoutes, { initialEntries: ['/create/video?tab=history'] })} />);
  await screen.findByRole('article', { name: '视频任务：正在增强的原生视频' });
  fireEvent.change(await screen.findByRole('combobox', { name: '视频版本' }), { target: { value: 'stored-enhanced' } });
  expect(screen.getByRole('combobox', { name: '视频版本' })).toHaveValue('stored-enhanced');
  expect(screen.getByRole('button', { name: '下载原版' })).toBeVisible();
  expect(screen.getByRole('button', { name: '下载720p增强版' })).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '超分增强' }));
  expect(await screen.findByText('15.0000 积分')).toBeVisible();
  expect(screen.getByRole('button', { name: '确认费用并增强' })).toBeEnabled();
  fireEvent.click(await screen.findByRole('button', { name: '删除视频' }));
  const preview = screen.getByRole('article', { name: '视频任务：正在增强的原生视频' });
  expect(await within(preview).findByRole('alert')).toHaveTextContent('增强任务仍在处理或退款中，请完成后再隐藏此视频记录。');
  expect(screen.getByRole('article', { name: '视频任务：正在增强的原生视频' })).toBeVisible();
});
