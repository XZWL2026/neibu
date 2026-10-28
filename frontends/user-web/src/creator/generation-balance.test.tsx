import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import * as api from '../api';
import { useOptionalUserSession, type UserSession } from '../userSession';
import { ProductionImageComposer } from './ProductionImageComposer';
import { ProductionVideoComposer } from './ProductionVideoComposer';

vi.mock('../userSession', async original => ({ ...await original<typeof import('../userSession')>(), useOptionalUserSession: vi.fn() }));
vi.mock('../api', async original => ({ ...await original<typeof import('../api')>(),
  refreshSession: vi.fn(), accessListGetApiV1AccessKeys: vi.fn(), billingBalanceGetApiV1BillingWalletsKeyId: vi.fn(),
  mediaOptionsGetApiV1MediaGenerationOptions: vi.fn(), mediaQuotePostApiV1MediaQuotes: vi.fn(),
  mediaSubmitPostApiV1MediaTasks: vi.fn(), mediaGetGetApiV1MediaTasksTaskId: vi.fn(),
}));
const reply = (data: unknown) => ({ data, status: 200, headers: new Headers() }) as never;
const key = { id: 'owner-key', name: '媒体 Key', owner: 'owner', balance: '20.0000 积分', enabled: true, kind: '媒体 Key', relationship: 'Owner' } as const;
const context = (availablePoints4: string | null = '20.0000') => ({ fixture: false, authenticated: true, userId: 'owner', status: 'ready', currentKey: key, availablePoints4 } as UserSession);
let account: UserSession | null;
const wallet = vi.mocked(api.billingBalanceGetApiV1BillingWalletsKeyId);
const submit = vi.mocked(api.mediaSubmitPostApiV1MediaTasks);
beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  vi.resetAllMocks(); account = context();
  vi.mocked(useOptionalUserSession).mockImplementation(() => account);
  // A second wallet aggregation must not replace the already verified account context.
  vi.mocked(api.refreshSession).mockResolvedValue({ authenticated: true, user: { id: 'owner' } } as never);
  vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValue(reply([{ keyId: 'owner-key', ownerId: 'owner', enabled: true, kind: 'MEDIA', accountType: 'PERSONAL' }]));
  wallet.mockResolvedValue(reply({ walletId: 'owner-key', availablePoints4: '1000.0000' }));
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({ models: [
    { type: 'IMAGE', model: 'server-image', displayName: '图片模型', aspectRatios: ['1:1'], resolutions: ['2K'], maxCount: 1, maxReferenceImages: 0 },
    { type: 'VIDEO', model: 'server-video', displayName: '视频模型', aspectRatios: ['16:9'], resolutions: ['720p'], maxCount: 1, minDurationSeconds: 5, maxDurationSeconds: 5, durationStepSeconds: 1, maxReferenceImages: 0 },
  ] }));
  vi.mocked(api.mediaQuotePostApiV1MediaQuotes).mockResolvedValue(reply({ points: '5.0000' }));
  submit.mockResolvedValue(reply({ id: 'accepted-task' }));
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ id: 'accepted-task', status: 'RUNNING' }));
});
afterEach(cleanup);

for (const [name, Composer] of [['图片', ProductionImageComposer], ['视频', ProductionVideoComposer]] as const) {
  describe(`${name} account-context wallet eligibility`, () => {
    const open = async () => {
      const view = render(<Composer />, { wrapper: MemoryRouter });
      fireEvent.change(screen.getByRole('textbox', { name: `${name}提示词` }), { target: { value: '真实余额门禁' } });
      await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).not.toHaveTextContent('报价中'));
      await waitFor(() => expect(api.mediaQuotePostApiV1MediaQuotes).toHaveBeenCalled());
      return view;
    };
    it.each(['0.0000', '4.9999'])('blocks context balance %s even when the real quote succeeds', async available => {
      account = context(available); await open();
      expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
      expect(await screen.findByText(/可用积分不足/)).toBeVisible();
      fireEvent.submit(screen.getByRole('button', { name: /^开始生成/ }).closest('form')!);
      expect(submit).not.toHaveBeenCalled(); expect(wallet).not.toHaveBeenCalled();
    });
    it('enables the exact affordable amount after recharge without remount or another wallet request', async () => {
      account = context('0.0000'); const view = await open();
      await screen.findByText(/可用积分不足/);
      account = context('5.0000');
      view.rerender(<Composer />);
      await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
      expect(screen.getByRole('textbox', { name: `${name}提示词` })).toHaveValue('真实余额门禁');
      expect(screen.queryByText(/^可用 .* 积分$/)).not.toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
      await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
      expect(wallet).not.toHaveBeenCalled();
    });
    it.each([
      { ...key, enabled: false }, { ...key, kind: 'API Key' as const }, { ...key, relationship: 'Member' as const }, null,
    ])('never enables an unusable context key: %j', async currentKey => {
      account = { ...context(), currentKey }; await open();
      expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
      expect(submit).not.toHaveBeenCalled(); expect(wallet).not.toHaveBeenCalled();
    });
    it.each([null, '-1.0000', '5.00001'])('fails closed for unavailable or malformed context balance: %s', async available => {
      account = context(available); await open();
      expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
      expect(await screen.findByText(/余额暂不可用/)).toBeVisible();
      expect(submit).not.toHaveBeenCalled();
    });
    it('disables while the real account context is refreshing', async () => {
      const view = await open();
      await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
      account = { ...context(null), status: 'loading', currentKey: null };
      view.rerender(<Composer />);
      expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
      account = context('0.0000'); view.rerender(<Composer />);
      expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
      expect(wallet).not.toHaveBeenCalled();
    });
    it('does not round a very large balance up to the quote', async () => {
      vi.mocked(api.mediaQuotePostApiV1MediaQuotes).mockResolvedValue(reply({ points: '9007199254740993.0001' }));
      account = context('9007199254740993.0000'); render(<Composer />, { wrapper: MemoryRouter });
      fireEvent.change(screen.getByRole('textbox', { name: `${name}提示词` }), { target: { value: '四位原值比较' } });
      await screen.findByText(/预计需要 9007199254740993.0001/);
      expect(await screen.findByText(/可用积分不足/)).toBeVisible();
      expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
    });
    it('keeps standalone server validation without manufacturing account data', async () => {
      account = null; await open();
      await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
      expect(screen.queryByText(/可用 .* 积分/)).not.toBeInTheDocument();
      expect(wallet).not.toHaveBeenCalled();
      vi.mocked(api.mediaQuotePostApiV1MediaQuotes).mockRejectedValue(new api.ApiError(new Response(null, { status: 422 }), {}));
      act(() => window.dispatchEvent(new Event('xzwl-account-changed')));
      await screen.findByRole('alert');
      expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
    });
  });
}
