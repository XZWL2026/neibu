import { readPromptInput, editPromptInput } from './mention-test-helpers';
import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { CreatorMediaProvider } from './CreatorMediaProvider';
import { ProductionImageComposer } from './ProductionImageComposer';
import { ProductionVideoComposer } from './ProductionVideoComposer';
import { HomeComposer } from '../home/HomeComposer';
import { createMemoryRouter } from 'react-router-dom';
import { App } from '../App';
import { userRoutes } from '../router';
import { axe } from 'vitest-axe';
import * as api from '../api';
import * as userSession from '../userSession';

vi.mock('../api', async original => ({ ...await original<typeof import('../api')>(),
  mediaPortraitListGetApiV1MediaPortraits: vi.fn(), mediaPortraitSubmitPostApiV1MediaPortraits: vi.fn(), mediaPortraitGetGetApiV1MediaPortraitsPortraitId: vi.fn(),
  refreshSession: vi.fn(), billingBalanceGetApiV1BillingWalletsKeyId: vi.fn(),
  userSelfGetApiV1UsersMe: vi.fn(), paymentOptionsGetApiV1PaymentsRechargeOptions: vi.fn(), paymentGetGetApiV1PaymentsRechargesId: vi.fn(),
  accessListGetApiV1AccessKeys: vi.fn(), mediaOptionsGetApiV1MediaGenerationOptions: vi.fn(), mediaQuotePostApiV1MediaQuotes: vi.fn(),
  mediaSubmitPostApiV1MediaTasks: vi.fn(), storageUploadReferenceImagePostApiV1FilesReferenceImages: vi.fn(),
  storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl: vi.fn(), mediaGetGetApiV1MediaTasksTaskId: vi.fn(),
  mediaResultsGetApiV1MediaTasksTaskIdResults: vi.fn(), mediaListGetApiV1MediaTasks: vi.fn(),
}));
const image = { type: 'IMAGE', model: 'server-image', displayName: '服务端图片模型', resolutions: ['server-resolution'], aspectRatios: ['4:3', '1:1'], maxCount: 2, minDurationSeconds: 0, maxDurationSeconds: 0, durationStepSeconds: 0, maxReferenceImages: 1, referenceFollowsFirstFrame: false };
const reviewOption = { ...image, type: 'VIDEO', model: 'review-model', displayName: '审核模型', minDurationSeconds: 4, maxDurationSeconds: 8, durationStepSeconds: 1, omniReference: {} };
async function enterHomeCreator(video = false) {
  await waitFor(() => expect(screen.getByLabelText('积分余额')).not.toHaveTextContent('—'));
  if (!video) {
    fireEvent.focus(screen.getByRole('textbox', { name: '创作描述' }));
    fireEvent.click(within(screen.getByRole('group', { name: '生成类型' })).getByRole('button', { name: '图片' }));
  }
  fireEvent.change(screen.getByRole('textbox', { name: '创作描述' }), { target: { value: '来自新首页的创意' } });
  fireEvent.click(screen.getByRole('button', { name: '开始创作' }));
}
const reply = (data: unknown) => ({ data, status: 200, headers: new Headers() }) as never;
const options = vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions);
const quote = vi.mocked(api.mediaQuotePostApiV1MediaQuotes);
const submit = vi.mocked(api.mediaSubmitPostApiV1MediaTasks);
const task = vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId);
const open = () => {
  render(<MemoryRouter><ProductionImageComposer /></MemoryRouter>);
  fireEvent.change(screen.getByRole('textbox', { name: '图片提示词' }), { target: { value: '真实图片提示词' } });
};
it.each([ProductionImageComposer, ProductionVideoComposer])('gives signed-out commerce visitors a login action without administrator setup instructions', async Composer => {
  vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue({ status: 'anonymous', authenticated: false, userId: null } as never);
  options.mockResolvedValue(reply({ models: [] }));
  render(<MemoryRouter><Composer entryOnly draftScope="commerce" /></MemoryRouter>);
  expect(await screen.findByRole('link', { name: '登录后开始创作' })).toHaveAttribute('href', '/user/login');
  expect(screen.getByText('登录后查看可用模型与积分，继续完成创作。')).toBeVisible();
  await waitFor(() => expect(options).toHaveBeenCalled());
  expect(screen.queryByText(/管理员.*价格/)).not.toBeInTheDocument();
  expect(submit).not.toHaveBeenCalled();
});

it.each([ProductionImageComposer, ProductionVideoComposer])('lets commerce visitors reload an empty model catalog without losing their prompt', async Composer => {
  options.mockResolvedValueOnce(reply({ models: [] }));
  render(<MemoryRouter><Composer entryOnly draftScope="commerce" /></MemoryRouter>);
  expect(await screen.findByText('暂时没有可用模型。请重新加载；仍不可用时，请联系平台支持。')).toBeVisible();
  const prompt = screen.getByRole('textbox');
  editPromptInput(prompt, '保留我的商品创意');
  fireEvent.click(screen.getByRole('button', { name: '重新加载模型与报价' }));
  await waitFor(() => expect(options).toHaveBeenCalledTimes(2));
  expect(readPromptInput(prompt)).toBe('保留我的商品创意');
  expect(submit).not.toHaveBeenCalled();
});

it.each([ProductionImageComposer, ProductionVideoComposer])('recovers account loading errors before asking commerce visitors to configure a model', async Composer => {
  const refresh = vi.fn().mockResolvedValue(undefined);
  vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue({ status: 'error', authenticated: false, userId: null, refresh } as never);
  options.mockResolvedValue(reply({ models: [] }));
  render(<MemoryRouter><Composer entryOnly draftScope="commerce" /></MemoryRouter>);
  fireEvent.click(screen.getByRole('button', { name: '重新读取账户' }));
  expect(refresh).toHaveBeenCalledTimes(1);
  expect(screen.getByRole('button', { name: '账户读取失败' })).toBeDisabled();
  expect(screen.queryByRole('link', { name: '登录后开始创作' })).not.toBeInTheDocument();
  expect(screen.queryByText(/暂时没有可用模型/)).not.toBeInTheDocument();
});
beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  vi.resetAllMocks(); sessionStorage.clear(); localStorage.clear();
  // Recovery/editor scenarios exercise the full-card layout; grid defaults have their own integration tests.
  localStorage.setItem('xzwl:image-results-columns', '1');
  vi.mocked(api.mediaListGetApiV1MediaTasks).mockResolvedValue(reply([]));
  vi.mocked(api.userSelfGetApiV1UsersMe).mockResolvedValue(reply({ id: 'owner', enabled: true }));
  vi.mocked(api.paymentOptionsGetApiV1PaymentsRechargeOptions).mockResolvedValue(reply({ userId: 'owner', accountType: 'PERSONAL' }));
  vi.mocked(api.paymentGetGetApiV1PaymentsRechargesId).mockResolvedValue(reply({ userId: 'owner', orderId: 'first-order', accountType: 'PERSONAL', status: 'PAID', fulfilled: true }));
  vi.mocked(api.refreshSession).mockResolvedValue({ authenticated: true, user: { id: 'owner' } } as never);
  vi.mocked(api.billingBalanceGetApiV1BillingWalletsKeyId).mockResolvedValue(reply({ walletId: 'owner-key', availablePoints4: '1000.0000' }));
  vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValue(reply([]));
  vi.mocked(api.mediaPortraitSubmitPostApiV1MediaPortraits).mockImplementation(async body => reply({ ...body, id: `review-${body.fileId}`, status: 'ACTIVE', kind: body.kind ?? 'IMAGE', durationSeconds: body.kind && body.kind !== 'IMAGE' ? 4 : null }));
  options.mockResolvedValue(reply({ models: [image, reviewOption] }));
  quote.mockResolvedValue(reply({ points: '12.3456' }));
  submit.mockResolvedValue(reply({ id: 'image-task' }));
  task.mockResolvedValue(reply({ id: 'image-task', type: 'IMAGE', status: 'RUNNING' }));
  vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValue(reply([{ keyId: 'owner-key', ownerId: 'owner', firstRechargeOrderId: 'first-order', enabled: true, kind: 'MEDIA', accountType: 'PERSONAL' }]));
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockResolvedValue(reply({ fileId: 'uploaded-file' }));
  vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockResolvedValue(reply({ url: 'https://assets.example/image.png' }));
  vi.mocked(api.mediaResultsGetApiV1MediaTasksTaskIdResults).mockResolvedValue(reply([{ fileId: 'result-file', index: 0 }]));
});
afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

const imageVariants = [
  ['gpt-image-2', '1K'], ['gpt-image-2-2K', '2K'], ['gpt-image-2-4K', '4K'],
  ['gpt-image-2.5', '1K'], ['gpt-image-2.5-flare', '1K'], ['gpt-image-2.5-sunburst', '1K'],
  ['nano_banana_pro-1K', '1K'], ['nano_banana_pro-2K', '2K'], ['nano_banana_pro-4K', '4K'],
].map(([model, resolution]) => ({ ...image, model, displayName: model,
  resolutions: model.endsWith('-flare') || model.endsWith('-sunburst') ? ['1K', '2K', '4K'] : [resolution] }));

it('groups only GPT Image 2 and Nano Banana Pro resolution variants and leaves 2.5 options unchanged', async () => {
  options.mockResolvedValue(reply({ models: imageVariants }));
  open(); await screen.findByText('开始生成 · 12.3456 积分');
  fireEvent.click(screen.getByRole('button', { name: '选择图片模型：gpt-image-2' }));
  const menu = screen.getByRole('dialog', { name: '选择图片模型' });
  expect(within(menu).getAllByRole('button', { pressed: false }).map(button => button.getAttribute('aria-label')))
    .toEqual(['gpt-image-2.5', 'gpt-image-2.5-flare', 'gpt-image-2.5-sunburst', 'nano-banana-pro']);
  expect(within(menu).getByRole('button', { name: 'gpt-image-2', pressed: true })).toHaveTextContent('1K / 2K / 4K');
  fireEvent.click(within(menu).getByRole('button', { name: 'gpt-image-2.5-flare' }));
  fireEvent.click(screen.getByRole('button', { name: /^图片参数摘要/ }));
  fireEvent.click(screen.getByRole('button', { name: '4K' }));
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ model: 'gpt-image-2.5-flare', resolution: '4K' })));
});

it.each([
  ['gpt-image-2', '1K', 'gpt-image-2'], ['gpt-image-2', '2K', 'gpt-image-2-2K'], ['gpt-image-2', '4K', 'gpt-image-2-4K'],
  ['nano-banana-pro', '1K', 'nano_banana_pro-1K'], ['nano-banana-pro', '2K', 'nano_banana_pro-2K'], ['nano-banana-pro', '4K', 'nano_banana_pro-4K'],
  ['nano-banana-pro', '1K', 'banana_pro-1K'], ['nano-banana-pro', '2K', 'banana_pro-2K'], ['nano-banana-pro', '4K', 'banana_pro-4K'],
])('quotes and submits %s at %s using the concrete catalog ID %s', async (family, resolution, model) => {
  const catalog = model.startsWith('banana_pro-') ? imageVariants.map(option => ({ ...option, model: option.model.replace('nano_banana_pro-', 'banana_pro-') })) : imageVariants;
  options.mockResolvedValue(reply({ models: catalog }));
  open(); await screen.findByText('开始生成 · 12.3456 积分');
  fireEvent.click(screen.getByRole('button', { name: /^选择图片模型/ }));
  fireEvent.click(screen.getByRole('button', { name: family }));
  fireEvent.click(screen.getByRole('button', { name: /^图片参数摘要/ }));
  fireEvent.click(screen.getByRole('button', { name: resolution }));
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ model, resolution })));
  fireEvent.click(screen.getByRole('button', { name: '关闭图片设置' }));
  expect(screen.getByRole('button', { name: `选择图片模型：${family}` })).toBeEnabled();
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await waitFor(() => expect(submit).toHaveBeenCalledWith(expect.objectContaining({ model, resolution, prompt: '真实图片提示词' }), expect.anything()));
});

it('offers only available family tiers and applies the selected variant limits while retaining the resolution across families', async () => {
  const available = imageVariants.filter(option => !option.model.endsWith('-4K'))
    .map(option => option.model.endsWith('-2K') ? { ...option, aspectRatios: ['1:1'], maxCount: 1 } : option);
  options.mockResolvedValue(reply({ models: available }));
  open(); await screen.findByText('开始生成 · 12.3456 积分');
  fireEvent.click(screen.getByRole('button', { name: /^图片参数摘要/ }));
  expect(screen.queryByRole('button', { name: '4K' })).not.toBeInTheDocument();
  fireEvent.change(screen.getByRole('spinbutton', { name: '生成数量' }), { target: { value: '2' } });
  fireEvent.click(screen.getByRole('button', { name: '2K' }));
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ model: 'gpt-image-2-2K', resolution: '2K', aspectRatio: '1:1', count: 1 })));
  expect(screen.queryByRole('button', { name: '4:3' })).not.toBeInTheDocument();
  expect(screen.getByRole('spinbutton', { name: '生成数量' })).toHaveAttribute('max', '1');
  fireEvent.click(screen.getByRole('button', { name: '关闭图片设置' }));
  fireEvent.click(screen.getByRole('button', { name: /^选择图片模型/ }));
  fireEvent.click(screen.getByRole('button', { name: 'nano-banana-pro' }));
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ model: 'nano_banana_pro-2K', resolution: '2K' })));
  act(() => window.dispatchEvent(new Event('focus')));
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ model: 'nano_banana_pro-2K', resolution: '2K' }));
  expect(submit).not.toHaveBeenCalled();
});

it('keeps the concrete 4K model when editing history and reopening its grouped menu', async () => {
  options.mockResolvedValue(reply({ models: imageVariants }));
  const saved = { id: 'edit-grouped-image', type: 'IMAGE', status: 'SUCCEEDED', prompt: '保留原始4K规格', model: 'nano_banana_pro-4K', resolution: '4K', aspectRatio: '1:1', count: 1, durationSeconds: 0, references: [], results: [{ index: 0, fileId: 'output' }] };
  vi.mocked(api.mediaListGetApiV1MediaTasks).mockResolvedValue(reply([saved]));
  task.mockResolvedValue(reply(saved));
  open();
  fireEvent.click(await screen.findByRole('button', { name: '重新编辑' }));
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ model: saved.model, resolution: '4K' })));
  fireEvent.click(screen.getByRole('button', { name: '选择图片模型：nano-banana-pro' }));
  fireEvent.click(screen.getByRole('button', { name: 'nano-banana-pro', pressed: true }));
  expect(screen.getByRole('button', { name: /^图片参数摘要/ })).toHaveAccessibleName('图片参数摘要：1:1，4K，1张');
  expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ model: saved.model, resolution: '4K' }));
  expect(submit).not.toHaveBeenCalled();
});

it('uses only server image capabilities and quotes, uploads file IDs and shows real assets', async () => {
  open();
  expect(await screen.findByRole('button', { name: '选择图片模型：服务端图片模型' })).toBeEnabled();
  await screen.findByText('开始生成 · 12.3456 积分');
  fireEvent.click(screen.getByRole('button', { name: /^图片参数摘要/ }));
  expect(screen.getByRole('button', { name: '4:3' }).querySelector('.video-ratio-icon')).toHaveStyle({ width: '28px', height: '21px' });
  expect(screen.getByRole('button', { name: '1:1' }).querySelector('.video-ratio-icon')).toHaveStyle({ width: '28px', height: '28px' });
  fireEvent.change(screen.getByRole('spinbutton', { name: '生成数量' }), { target: { value: '2' } });
  fireEvent.click(screen.getByRole('button', { name: '关闭图片设置' }));
  fireEvent.change(within(screen.getByRole('region', { name: '图片生成' })).getByLabelText('上传素材'), { target: { files: [new File(['png'], 'input.png', { type: 'image/png' })] } });
  await screen.findByText('input.png');
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith({ type: 'IMAGE', model: image.model, aspectRatio: '4:3', resolution: 'server-resolution', count: 2, durationSeconds: 0, references: [{ fileId: 'uploaded-file' }] }));
  task.mockResolvedValue(reply({ id: 'image-task', type: 'IMAGE', status: 'SUCCEEDED', results: [{ fileId: 'result-file', index: 0 }] }));
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  const output = await screen.findByAltText('生成图片 1');
  fireEvent.load(output);
  expect(await screen.findByRole('img', { name: '生成图片 1' }, { timeout: 2000 })).toHaveAttribute('src', 'https://assets.example/image.png');
  expect(submit).toHaveBeenCalledTimes(1);
  expect(submit.mock.calls[0][0]).toEqual({ ...quote.mock.calls.at(-1)![0], prompt: '真实图片提示词' });
  expect(submit.mock.calls[0][1]?.headers).toHaveProperty('Idempotency-Key');
  expect(screen.queryByText(/本地创作预览/)).not.toBeInTheDocument();
});

it.each([401, 403, 422])('keeps HTTP %s eligibility rejection disabled then refreshes after recharge without losing the prompt', async status => {
  options.mockRejectedValueOnce(new api.ApiError(new Response(null, { status }), {}));
  open();
  await screen.findByRole('alert');
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  expect(quote).not.toHaveBeenCalled();
  act(() => window.dispatchEvent(new Event('xzwl-account-changed')));
  await screen.findByText('开始生成 · 12.3456 积分');
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled();
  expect(screen.getByRole('textbox', { name: '图片提示词' })).toHaveValue('真实图片提示词');
  expect(submit).not.toHaveBeenCalled();
});

it('invalidates the old quote during account refresh and preserves API catalog kind restrictions', async () => {
  open(); await screen.findByText('开始生成 · 12.3456 积分');
  let resolve!: (value: never) => void;
  options.mockImplementationOnce(() => new Promise(done => { resolve = done; }));
  act(() => window.dispatchEvent(new Event('xzwl-account-changed')));
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  await act(async () => resolve(reply({ models: [{ ...image, type: 'VIDEO' }] })));
  expect(await screen.findByText(/暂无可用图片模型/)).toBeVisible();
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  expect(submit).not.toHaveBeenCalled();
});

it('keeps quote and disabled-key server refusals closed on account events', async () => {
  open(); await screen.findByText('开始生成 · 12.3456 积分');
  quote.mockRejectedValue(new api.ApiError(new Response(null, { status: 422 }), {}));
  act(() => window.dispatchEvent(new Event('xzwl-account-changed')));
  await screen.findByRole('alert');
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  options.mockRejectedValue(new api.ApiError(new Response(null, { status: 422 }), {}));
  vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValue(reply([{ enabled: false }]));
  act(() => window.dispatchEvent(new Event('xzwl-account-changed')));
  await screen.findByText(/密钥已禁用/);
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  expect(submit).not.toHaveBeenCalled();
});

it('replays an ambiguous image submission with the identical idempotency key and payload', async () => {
  open(); await screen.findByText('开始生成 · 12.3456 积分');
  submit.mockRejectedValueOnce(new TypeError('network disconnected'));
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await screen.findByRole('button', { name: '使用原请求重试' });
  act(() => window.dispatchEvent(new Event('xzwl-account-changed')));
  expect(screen.getByRole('textbox', { name: '图片提示词' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: '使用原请求重试' }));
  await screen.findByRole('progressbar', { name: '图片生成进度' });
  expect(submit.mock.calls[1]).toEqual(submit.mock.calls[0]);
});

it('refreshes the balance only once after refund-pending reaches a terminal state, including repeated sync', async () => {
  const dispatch = vi.spyOn(window, 'dispatchEvent');
  task.mockResolvedValueOnce(reply({ id: 'image-task', type: 'IMAGE', status: 'REFUND_PENDING', refundStatus: 'PENDING' }));
  open(); await screen.findByText('开始生成 · 12.3456 积分');
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await screen.findByText('失败部分退款处理中');
  expect(dispatch.mock.calls.filter(([event]) => event.type === 'xzwl-account-changed')).toHaveLength(0);
  expect(screen.queryByRole('button', { name: '继续创作' })).not.toBeInTheDocument();
  expect(screen.queryByText('生成成功')).not.toBeInTheDocument();
  task.mockResolvedValue(reply({ id: 'image-task', type: 'IMAGE', status: 'FAILED', refundStatus: 'COMPLETED' }));
  expect(await screen.findByText('失败部分积分已退回', {}, { timeout: 4000 })).toBeVisible();
  expect(screen.getByRole('textbox', { name: '图片提示词' })).toBeEnabled();
  expect(dispatch.mock.calls.filter(([event]) => event.type === 'xzwl-account-changed')).toHaveLength(1);
  act(() => window.dispatchEvent(new Event('focus')));
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  expect(dispatch.mock.calls.filter(([event]) => event.type === 'xzwl-account-changed')).toHaveLength(1);
  expect(submit).toHaveBeenCalledTimes(1);
});


it('retains the real accepted image task across exploration and history navigation', async () => {
  render(<App router={createMemoryRouter(userRoutes, { initialEntries: ['/create/image'] })} />);
  await screen.findByText('开始生成 · 12.3456 积分');
  fireEvent.change(screen.getByRole('textbox', { name: '图片提示词' }), { target: { value: '真实任务保留' } });
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await screen.findByRole('progressbar', { name: '图片生成进度' });
  fireEvent.click(screen.getByRole('link', { name: '图片历史' }));
  await screen.findByRole('heading', { name: '图片历史' });
  expect(screen.getByRole('article', { name: '图片任务：真实任务保留' })).toBeVisible();
  expect(screen.queryByText(/任务已提交：image-task/)).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('link', { name: '探索' }));
  await screen.findByRole('heading', { name: '点亮图片创作' });
  expect(screen.getByRole('textbox', { name: '图片提示词' })).toHaveValue('真实任务保留');
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  expect(submit).toHaveBeenCalledTimes(1);
});

it('provides accessible named server settings without a local media provider', async () => {
  open(); await screen.findByText('开始生成 · 12.3456 积分');
  const result = await axe(screen.getByRole('region', { name: '图片生成' }), { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations).toEqual([]);
});

it('restores the material card and keeps replacement order, prompt and local incompatibility through settings and modes', async () => {
  open(); await screen.findByText('开始生成 · 12.3456 积分');
  expect(screen.getByRole('button', { name: '添加素材' })).toBeEnabled();
  expect(screen.getByRole('button', { name: /图片参数摘要/ })).toHaveAccessibleName('图片参数摘要：4:3，server-resolution，1张');
  fireEvent.change(within(screen.getByRole('region', { name: '图片生成' })).getByLabelText('上传素材'), { target: { files: [new File(['png'], 'first.png', { type: 'image/png' })] } });
  await screen.findByRole('img', { name: 'first.png' });
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockResolvedValueOnce(reply({ fileId: 'replacement' }));
  fireEvent.change(screen.getByLabelText('替换参考图 1'), { target: { files: [new File(['png'], 'new.png', { type: 'image/png' })] } });
  await screen.findByText('new.png');
  fireEvent.click(screen.getByRole('button', { name: /^图片参数摘要/ }));
  fireEvent.click(screen.getByRole('button', { name: '1:1' }));
  fireEvent.click(screen.getByRole('button', { name: '关闭图片设置' }));
  chooseMode('图片生成模式', 'text');
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  expect(screen.getByText('当前模式不使用素材，请切换模式或移除')).toBeVisible();
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  expect(screen.getByRole('textbox', { name: '图片提示词' })).toHaveValue('真实图片提示词');
  chooseMode('图片生成模式', 'auto');
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ aspectRatio: '1:1', references: [{ fileId: 'replacement' }] }));
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
  expect(submit.mock.calls[0][0]).toEqual({ ...quote.mock.calls.at(-1)![0], prompt: '真实图片提示词' });
});

it('marks each overflow reference after a model switch, preserves failed replacements, and requotes removal', async () => {
  options.mockResolvedValue(reply({ models: [{ ...image, maxReferenceImages: 2 }, { ...image, model: 'smaller-image', displayName: '单素材模型' }, reviewOption] }));
  open(); await screen.findByText('开始生成 · 12.3456 积分');
  const region = within(screen.getByRole('region', { name: '图片生成' }));
  fireEvent.change(region.getByLabelText('上传素材'), { target: { files: [new File(['png'], 'one.png', { type: 'image/png' })] } });
  await screen.findByText('one.png');
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockResolvedValueOnce(reply({ fileId: 'second-file' }));
  fireEvent.change(region.getByLabelText('上传素材'), { target: { files: [new File(['png'], 'two.png', { type: 'image/png' })] } });
  await screen.findByAltText('two.png');
  fireEvent.click(screen.getByRole('button', { name: /选择图片模型/ }));
  fireEvent.click(screen.getByRole('button', { name: '单素材模型' }));
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  expect(screen.getByText('超出当前模型上限（1 张）')).toBeVisible();
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: '移除参考图 1' }));
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ model: 'smaller-image', references: [{ fileId: 'second-file' }] })));
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockRejectedValueOnce(new Error('upload failed'));
  fireEvent.change(region.getByLabelText('替换参考图 1'), { target: { files: [new File(['png'], 'failed.png', { type: 'image/png' })] } });
  await screen.findByText(/上传失败：.*已有素材已保留/);
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  fireEvent.load(screen.getByAltText('two.png'));
  expect(screen.getByAltText('two.png')).toBeVisible();
  expect(screen.queryByAltText('failed.png')).not.toBeInTheDocument();
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
  expect(submit.mock.calls[0][0]).toEqual({ ...quote.mock.calls.at(-1)![0], prompt: '真实图片提示词' });
});

it.each(['wrong-type', 'too-large', 'base64-id'])('rejects %s uploads without submitting a reference', async variant => {
  open(); await screen.findByText('开始生成 · 12.3456 积分');
  const file = new File(['test'], 'invalid.png', { type: variant === 'wrong-type' ? 'image/gif' : 'image/png' });
  if (variant === 'too-large') Object.defineProperty(file, 'size', { value: 20 * 1024 * 1024 + 1 });
  if (variant === 'base64-id') vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockResolvedValueOnce(reply({ fileId: 'data:image/png;base64,AAAA' }));
  fireEvent.change(within(screen.getByRole('region', { name: '图片生成' })).getByLabelText('上传素材'), { target: { files: [file] } });
  await screen.findByRole('alert');
  expect(screen.queryByRole('img', { name: 'invalid.png' })).not.toBeInTheDocument();
  if (variant !== 'base64-id') expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).not.toHaveBeenCalled();
  expect(submit).not.toHaveBeenCalled();
  expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ references: [] }));
});



it('restores the material library and reuses approved original files through quote APIs without another upload', async () => {
  const content = new File(['local-picture'], '素材库图片.png', { type: 'image/png' });
  const records = [{ id: 'uploaded-file', name: content.name, kind: 'image' as const, mimeType: content.type, size: content.size, content }];
  const reviewed = { id: 'existing-review', fileId: 'uploaded-file', model: 'review-model', kind: 'IMAGE', status: 'ACTIVE' };
  vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValue(reply([reviewed]));
  vi.mocked(api.mediaPortraitGetGetApiV1MediaPortraitsPortraitId).mockResolvedValue(reply(reviewed));
  render(<MemoryRouter><CreatorMediaProvider storage={{ storageMode: 'session', reason: 'forced', repository: {
    list: async () => records, put: async () => {}, remove: async () => {}, close: () => {},
  } }} objectUrls={{ create: () => 'blob:library-preview', revoke: () => {} }}><ProductionImageComposer /></CreatorMediaProvider></MemoryRouter>);
  await waitFor(() => expect(screen.getByRole('button', { name: '添加素材' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '添加素材' }));
  expect(screen.getByRole('menuitem', { name: '本地上传' })).toBeVisible();
  fireEvent.click(screen.getByRole('menuitem', { name: '素材库' }));
  const library = await screen.findByRole('dialog', { name: '素材库' });
  fireEvent.click(within(library).getByRole('tab', { name: '个人' }));
  fireEvent.click(await within(library).findByRole('option', { name: content.name }));
  fireEvent.click(within(library).getByRole('button', { name: '确定' }));
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ references: [{ fileId: 'uploaded-file' }] })));
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).not.toHaveBeenCalled();
  expect(api.mediaPortraitSubmitPostApiV1MediaPortraits).not.toHaveBeenCalled();
  fireEvent.change(screen.getByRole('textbox', { name: '图片提示词' }), { target: { value: '使用素材库图片生成' } });
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await waitFor(() => expect(submit).toHaveBeenCalledWith(expect.objectContaining({ references: [{ fileId: 'uploaded-file' }] }), expect.anything()));
});


it('creates an immediate independent snapshot card, unlocks input after admission and retains consecutive tasks', async () => {
  let accept!: (value: never) => void;
  submit.mockImplementationOnce(() => new Promise(resolve => { accept = resolve; }));
  open(); await screen.findByText('开始生成 · 12.3456 积分');
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  const card = screen.getByRole('article', { name: '图片任务：真实图片提示词' });
  expect(within(card).getByRole('progressbar', { name: '图片生成进度' })).toBeVisible();
  expect(card.compareDocumentPosition(screen.getByRole('region', { name: '图片生成' })) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  await act(async () => accept(reply({ id: 'image-task' })));
  await waitFor(() => expect(screen.getByRole('textbox', { name: '图片提示词' })).toBeEnabled());
  fireEvent.change(screen.getByRole('textbox', { name: '图片提示词' }), { target: { value: '第二张' } });
  fireEvent.click(screen.getByRole('button', { name: /^图片参数摘要/ }));
  fireEvent.click(screen.getByRole('button', { name: '1:1' }));
  fireEvent.click(screen.getByRole('button', { name: '关闭图片设置' }));
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  submit.mockResolvedValueOnce(reply({ id: 'image-two' }));
  task.mockImplementation(async id => reply({ id, type: 'IMAGE', status: 'RUNNING' }));
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  expect(await screen.findByRole('article', { name: '图片任务：第二张' })).toBeVisible();
  expect(within(card).getByText('比例 4:3')).toBeVisible();
  expect(within(card).getByText('数量 1 张')).toBeVisible();
  expect(card).not.toHaveTextContent('image-task');
  expect(submit.mock.calls[0][1]?.headers).not.toEqual(submit.mock.calls[1][1]?.headers);
});

it('recovers real image history on remount without submitting and excludes video or demo tasks', async () => {
  const record = { id: 'restored', type: 'IMAGE', status: 'FAILED', prompt: '真实历史', count: 1, createdAt: '2026-09-10T01:00:00Z', failureMessage: '生成文件未能保存', failures: [{ index: 0, message: '生成文件未能保存' }], results: [] };
  vi.mocked(api.mediaListGetApiV1MediaTasks).mockResolvedValue(reply([record, { ...record, id: 'video', type: 'VIDEO', prompt: '不是图片' }]));
  task.mockResolvedValue(reply(record));
  const view = render(<MemoryRouter><ProductionImageComposer /></MemoryRouter>);
  expect(await screen.findByRole('article', { name: '图片任务：真实历史' })).toBeVisible();
  view.unmount();
  render(<MemoryRouter><ProductionImageComposer /></MemoryRouter>);
  expect(await screen.findByRole('article', { name: '图片任务：真实历史' })).toBeVisible();
  expect(screen.queryByText('不是图片')).not.toBeInTheDocument();
  expect(submit).not.toHaveBeenCalled();
  expect(api.mediaListGetApiV1MediaTasks).toHaveBeenCalledWith({ limit: 20, excludeCanvas: true });
});

it('preserves an unfinished prompt and uploaded material through real account-provider refresh errors', async () => {
  render(<App router={createMemoryRouter(userRoutes, { initialEntries: ['/create/image'] })} />);
  await screen.findByText('开始生成 · 12.3456 积分');
  fireEvent.change(screen.getByRole('textbox', { name: '图片提示词' }), { target: { value: '未提交的创作' } });
  fireEvent.change(within(screen.getByRole('region', { name: '图片生成' })).getByLabelText('上传素材'), { target: { files: [new File(['png'], 'keep.png', { type: 'image/png' })] } });
  await screen.findByAltText('keep.png');
  vi.mocked(api.refreshSession).mockRejectedValueOnce(new TypeError('temporary network problem'));
  await act(async () => { window.dispatchEvent(new Event('xzwl-account-changed')); });
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled());
  expect(screen.getByRole('textbox', { name: '图片提示词' })).toHaveValue('未提交的创作');
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  fireEvent.load(screen.getByAltText('keep.png'));
  expect(screen.getByAltText('keep.png')).toBeVisible();
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  act(() => window.dispatchEvent(new Event('xzwl-account-changed')));
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  expect(screen.getByRole('textbox', { name: '图片提示词' })).toHaveValue('未提交的创作');
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  fireEvent.load(screen.getByAltText('keep.png'));
  expect(screen.getByAltText('keep.png')).toBeVisible();
  expect(submit).not.toHaveBeenCalled();
});


it('opens a real image in a standalone viewer without starting generation or loading its options', async () => {
  render(<App router={createMemoryRouter(userRoutes, { initialEntries: ['/create/image?preview=stored-image'] })} />);
  expect(await screen.findByRole('img', { name: '生成图片 1' })).toHaveAttribute('src', 'https://assets.example/image.png');
  expect(screen.getByRole('link', { name: '返回图片创作' })).toHaveAttribute('href', '/create/image');
  expect(options).not.toHaveBeenCalled();
  expect(submit).not.toHaveBeenCalled();
});

it.each(['/create/image', '/create/video', '/home'])('accepts a file during focus refresh on %s without enabling generation', async path => {
  const video = path.includes('video');
  options.mockResolvedValue(reply({ models: [{ ...image, type: video ? 'VIDEO' : 'IMAGE', minDurationSeconds: video ? 4 : 0, maxDurationSeconds: video ? 8 : 0, durationStepSeconds: video ? 1 : 0 }, reviewOption] }));
  render(<App router={createMemoryRouter(userRoutes, { initialEntries: [path] })} />);
  if (path === '/home') await enterHomeCreator(video);
  await screen.findByText('开始生成 · 12.3456 积分');
  if (video) chooseMode('视频生成模式', 'frames');
  let finish!: (value: never) => void;
  vi.mocked(api.refreshSession).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  act(() => window.dispatchEvent(new Event('focus')));
  const region = within(screen.getByRole('region', { name: video ? '视频生成' : '图片生成' }));
  expect(region.getByLabelText('上传素材')).toBeEnabled();
  fireEvent.change(region.getByLabelText('上传素材'), { target: { files: [new File(['png'], 'focus.png', { type: 'image/png' })] } });
  await screen.findByText('focus.png');
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  await act(async () => finish({ authenticated: true, user: { id: 'owner' } } as never));
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ references: [{ fileId: 'uploaded-file' }] })));
});

it.each([['/create/image', false], ['/create/video', true], ['/home', false], ['/home', true]] as const)('keeps quotes stable while editing material mentions and submits the latest prompt on %s (video=%s)', async (path, video) => {
  const model = { ...image, type: video ? 'VIDEO' : 'IMAGE', maxReferenceImages: 2,
    minDurationSeconds: video ? 4 : 0, maxDurationSeconds: video ? 8 : 0, durationStepSeconds: video ? 1 : 0 };
  options.mockResolvedValue(reply({ models: [model, { ...model, model: 'no-ref', displayName: '无参考模型', maxReferenceImages: 0 }, reviewOption] }));
  const upload = vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages);
  upload.mockResolvedValueOnce(reply({ fileId: 'real-first' })).mockResolvedValueOnce(reply({ fileId: 'real-second' }));
  render(<App router={createMemoryRouter(userRoutes, { initialEntries: [path] })} />);
  if (path === '/home') await enterHomeCreator(video);
  await screen.findByText('开始生成 · 12.3456 积分');
  if (video) chooseMode('视频生成模式', 'frames');
  const label = video ? '视频' : '图片';
  const region = within(screen.getByRole('region', { name: `${label}生成` }));
  for (const name of ['first.png', 'second.jpg']) {
    fireEvent.change(region.getByLabelText('上传素材'), { target: { files: [new File(['image'], name, { type: name.endsWith('jpg') ? 'image/jpeg' : 'image/png' })] } });
    await screen.findByText(name);
    await waitFor(() => expect(screen.queryByText(/正在上传并校验/)).not.toBeInTheDocument());
  }
  let input = screen.getByRole('textbox', { name: `${label}提示词` });
  editPromptInput(input, '把@', 2);
  await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(2));
  fireEvent.keyDown(input, { key: 'ArrowDown' }); fireEvent.keyDown(input, { key: 'Enter' });
  expect(submit).not.toHaveBeenCalled();
  input = screen.getByRole('textbox', { name: `${label}提示词` });
  const text = readPromptInput(input) + '融入@';
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  const quoteCount = quote.mock.calls.length;
  editPromptInput(input, text);
  fireEvent.click(screen.getByRole('option', { name: /first.png/ }));
  const boundText = readPromptInput(input);
  input.focus();
  for (const suffix of ['，', '，镜头', '，镜头缓慢移动']) {
    editPromptInput(input, boundText + suffix);
    expect(screen.getByRole('button', { name: '开始生成 · 12.3456 积分' })).toBeEnabled();
    expect(input).toHaveFocus();
  }
  await act(async () => {});
  expect(quote).toHaveBeenCalledTimes(quoteCount);
  editPromptInput(input, boundText + '@【失效素材】');
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  expect(screen.getByText(/引用已失效/)).toBeVisible();
  editPromptInput(input, boundText + '，镜头缓慢移动');
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  expect(quote.mock.calls.length).toBeGreaterThan(quoteCount);
  expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ references: [{ fileId: 'real-first' }, { fileId: 'real-second' }] }));
  fireEvent.click(screen.getByRole('button', { name: `选择${label}模型：服务端图片模型` }));
  fireEvent.click(screen.getByRole('button', { name: '无参考模型' }));
  expect(screen.getByText(/引用已失效/)).toBeVisible();
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: `选择${label}模型：无参考模型` }));
  fireEvent.click(screen.getByRole('button', { name: '服务端图片模型' }));
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await waitFor(() => expect(submit).toHaveBeenCalledOnce());
  expect(submit.mock.calls[0][0]).toMatchObject({ prompt: video ? '把尾帧图片融入首帧图片，镜头缓慢移动' : '把图2融入图1，镜头缓慢移动', references: [{ fileId: 'real-first' }, { fileId: 'real-second' }] });
  expect(submit.mock.calls[0][1]?.headers).toHaveProperty('Idempotency-Key');
});

it('releases the old owner picker lock and ignores its late upload after account switching', async () => {
  render(<App router={createMemoryRouter(userRoutes, { initialEntries: ['/create/image'] })} />);
  await screen.findByText('开始生成 · 12.3456 积分');
  let finishOld!: (value: never) => void;
  const upload = vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages);
  upload.mockImplementationOnce(() => new Promise(resolve => { finishOld = resolve; }));
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['png'], 'old-owner.png', { type: 'image/png' })] } });
  await waitFor(() => expect(upload).toHaveBeenCalledOnce());
  vi.mocked(api.refreshSession).mockResolvedValue({ authenticated: true, user: { id: 'new-owner' } } as never);
  vi.mocked(api.userSelfGetApiV1UsersMe).mockResolvedValue(reply({ id: 'new-owner', enabled: true }));
  vi.mocked(api.paymentOptionsGetApiV1PaymentsRechargeOptions).mockResolvedValue(reply({ userId: 'new-owner', accountType: 'PERSONAL' }));
  vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValue(reply([]));
  act(() => window.dispatchEvent(new Event('xzwl-account-changed')));
  await waitFor(() => expect(screen.getByLabelText('上传素材')).toBeEnabled());
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['png'], 'new-owner.png', { type: 'image/png' })] } });
  await screen.findByAltText('new-owner.png');
  expect(upload).toHaveBeenCalledTimes(2);
  await act(async () => finishOld(reply({ fileId: 'old-owner-file' })));
  expect(screen.queryByAltText('old-owner.png')).not.toBeInTheDocument();
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  fireEvent.load(screen.getByAltText('new-owner.png'));
  expect(screen.getByAltText('new-owner.png')).toBeVisible();
});

it('hands homepage input to the image route before material editing and preserves accepted task recovery', async () => {
  const router = createMemoryRouter(userRoutes, { initialEntries: ['/home'] });
  const view = render(<App router={router} />);
  await waitFor(() => expect(screen.getByLabelText('积分余额')).not.toHaveTextContent('—'));
  await enterHomeCreator();
  await screen.findByRole('button', { name: '选择图片模型：服务端图片模型' });
  expect(submit).not.toHaveBeenCalled();
  expect(api.mediaListGetApiV1MediaTasks).toHaveBeenCalled();
  fireEvent.change(within(screen.getByRole('region', { name: '图片生成' })).getByLabelText('上传素材'), { target: { files: [new File(['image'], 'handoff.png', { type: 'image/png' })] } });
  await screen.findByAltText('handoff.png');
  await waitFor(() => expect(within(screen.getByRole('region', { name: '图片生成' })).queryByRole('status', { name: 'handoff.png 上传中' })).not.toBeInTheDocument());
  editPromptInput(screen.getByRole('textbox', { name: '图片提示词' }), '照着@', 3);
  fireEvent.click(await screen.findByRole('option', { name: /handoff.png/ }));
  const originalPrompt = readPromptInput(screen.getByRole('textbox', { name: '图片提示词' }));
  const button = await screen.findByRole('button', { name: '开始生成 · 12.3456 积分' });
  await waitFor(() => expect(button).toBeEnabled());
  fireEvent.doubleClick(button); fireEvent.click(button); fireEvent.click(button);
  await waitFor(() => expect(router.state.location.pathname).toBe('/create/image'));
  await screen.findByRole('progressbar', { name: '图片生成进度' });
  expect(submit).toHaveBeenCalledTimes(1);
  expect(submit.mock.calls[0][0]).toMatchObject({ prompt: '照着图1', references: [{ fileId: 'uploaded-file' }], model: image.model });
  expect(readPromptInput(screen.getByRole('textbox', { name: '图片提示词' }))).toBe(originalPrompt);
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  fireEvent.load(screen.getByAltText('handoff.png'));
  expect(screen.getByAltText('handoff.png')).toBeVisible();
  view.unmount();
  render(<App router={createMemoryRouter(userRoutes, { initialEntries: ['/create/image'] })} />);
  await screen.findByRole('progressbar', { name: '图片生成进度' });
  expect(submit).toHaveBeenCalledTimes(1);
  expect(readPromptInput(screen.getByRole('textbox', { name: '图片提示词' }))).toBe(originalPrompt);
});

it('shares the in-flight request across route remounts and persists acceptance after the first component leaves', async () => {
  let accept!: (value: never) => void;
  submit.mockImplementationOnce(() => new Promise(resolve => { accept = resolve; }));
  const view = render(<App router={createMemoryRouter(userRoutes, { initialEntries: ['/create/image'] })} />);
  await screen.findByText('开始生成 · 12.3456 积分');
  fireEvent.change(screen.getByRole('textbox', { name: '图片提示词' }), { target: { value: '重挂载期间只提交一次' } });
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
  const original = submit.mock.calls[0];
  view.unmount();
  render(<App router={createMemoryRouter(userRoutes, { initialEntries: ['/create/image'] })} />);
  await screen.findByRole('button', { name: '生成中' });
  expect(submit).toHaveBeenCalledTimes(1);
  await act(async () => accept(reply({ id: 'image-task' })));
  await screen.findByRole('progressbar', { name: '图片生成进度' });
  expect(submit).toHaveBeenCalledTimes(1);
  expect(JSON.parse(sessionStorage.getItem('xzwl-image-submission:owner')!)).toMatchObject({ taskRef: 'image-task', request: { requestRef: new Headers(original[1]!.headers).get('Idempotency-Key') } });
});

it('fails before dispatch when durable request recovery cannot be saved', async () => {
  render(<App router={createMemoryRouter(userRoutes, { initialEntries: ['/create/image'] })} />);
  await screen.findByText('开始生成 · 12.3456 积分');
  fireEvent.change(screen.getByRole('textbox', { name: '图片提示词' }), { target: { value: '存储失败保留输入' } });
  const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('unavailable', 'SecurityError'); });
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  expect(await screen.findByRole('alert')).toHaveTextContent('无法保存请求恢复信息');
  expect(submit).not.toHaveBeenCalled();
  expect(screen.getByRole('textbox', { name: '图片提示词' })).toHaveValue('存储失败保留输入');
  storage.mockRestore();
  fireEvent.change(screen.getByRole('textbox', { name: '图片提示词' }), { target: { value: '修正后的输入' } });
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
  expect(submit.mock.calls[0][0]).toMatchObject({ prompt: '修正后的输入' });
});

it('keeps an unknown prior outcome locked when continued through home and a later confirmation is refused', async () => {
  const request = { requestRef: 'unknown-original', settings: { type: 'IMAGE', model: image.model, resolution: image.resolutions[0], aspectRatio: '4:3', count: 1, durationSeconds: 0 }, prompt: '未知结果', inputAssetRefs: [] };
  sessionStorage.setItem('xzwl-image-submission:owner', JSON.stringify({ key: request.requestRef, request, state: 'UNCERTAIN', snapshot: { ...request.settings, prompt: request.prompt } }));
  submit.mockRejectedValue(new api.ApiError(new Response(null, { status: 422 }), {}));
  const router = createMemoryRouter(userRoutes, { initialEntries: ['/home'] });
  render(<App router={router} />);
  await waitFor(() => expect(screen.getByLabelText('积分余额')).not.toHaveTextContent('—'));
  await enterHomeCreator();
  await waitFor(() => expect(router.state.location.pathname).toBe('/create/image'));
  await screen.findByRole('button', { name: '使用原请求重试' });
  expect(screen.getByRole('textbox', { name: '图片提示词' })).toBeDisabled();
  expect(JSON.parse(sessionStorage.getItem('xzwl-image-submission:owner')!)).toMatchObject({ state: 'UNCERTAIN', request });
  expect(submit).toHaveBeenCalledTimes(1);
});

it('unlocks corrected input after the original in-flight request is definitively rejected across a remount', async () => {
  let reject!: (cause: Error) => void;
  submit.mockImplementationOnce(() => new Promise((_, no) => { reject = no; }));
  const view = render(<App router={createMemoryRouter(userRoutes, { initialEntries: ['/create/image'] })} />);
  await screen.findByText('开始生成 · 12.3456 积分');
  fireEvent.change(screen.getByRole('textbox', { name: '图片提示词' }), { target: { value: '需要修正' } });
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
  view.unmount();
  render(<App router={createMemoryRouter(userRoutes, { initialEntries: ['/create/image'] })} />);
  await screen.findByRole('button', { name: '生成中' });
  await act(async () => reject(new api.ApiError(new Response(null, { status: 422 }), {})));
  await waitFor(() => expect(screen.getByRole('textbox', { name: '图片提示词' })).toBeEnabled());
  expect(sessionStorage.getItem('xzwl-image-submission:owner')).toBeNull();
  expect(submit).toHaveBeenCalledTimes(1);
});

it('ignores a second click in the same generation burst even when admission and requote finish immediately', async () => {
  open(); await screen.findByText('开始生成 · 12.3456 积分');
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }), { detail: 1 });
  await screen.findByRole('progressbar', { name: '图片生成进度' });
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }), { detail: 2 });
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }), { detail: 1 });
  expect(submit).toHaveBeenCalledTimes(1);
});

it('unlocks an unsent home handoff after storage failure and handles a corrected first submission refusal', async () => {
  const request = { requestRef: 'prepared-only', settings: { type: 'IMAGE', model: image.model, resolution: image.resolutions[0], aspectRatio: '4:3', count: 1, durationSeconds: 0 }, prompt: '未发送', inputAssetRefs: [] };
  sessionStorage.setItem('xzwl-image-submission:owner', JSON.stringify({ key: request.requestRef, request, state: 'PREPARED', snapshot: { ...request.settings, prompt: request.prompt } }));
  const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('unavailable', 'SecurityError'); });
  render(<App router={createMemoryRouter(userRoutes, { initialEntries: ['/create/image'] })} />);
  await screen.findByText('无法保存请求恢复信息，请允许当前站点存储后重试。');
  expect(submit).not.toHaveBeenCalled();
  await waitFor(() => expect(screen.getByRole('textbox', { name: '图片提示词' })).toBeEnabled());
  storage.mockRestore();
  submit.mockRejectedValueOnce(new api.ApiError(new Response(null, { status: 422 }), {}));
  fireEvent.change(screen.getByRole('textbox', { name: '图片提示词' }), { target: { value: '修正后的首页输入' } });
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
  await waitFor(() => expect(screen.getByRole('textbox', { name: '图片提示词' })).toBeEnabled());
  expect(submit.mock.calls[0][0]).toMatchObject({ prompt: '修正后的首页输入' });
  expect(sessionStorage.getItem('xzwl-image-submission:owner')).toBeNull();
});

it('uses the image model capacity for library multi-select and appends all selected references in order', async () => {
  options.mockResolvedValue(reply({ models: [{ ...image, maxReferenceImages: 3 }, reviewOption] }));
  const upload = vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages);
  let finishFirst!: () => void;
  upload.mockImplementation(async ({ file }) => reply({ fileId: (file as File).name.replace('.png', '') }));
  const media = ['one', 'two', 'three'].map(id => ({ id, name: `${id}.png`, kind: 'image' as const, mimeType: 'image/png', size: 3, content: new Blob(['png']) }));
  const receipts = media.map(item => ({ id: `review-${item.id}`, fileId: item.id, model: reviewOption.model, kind: 'IMAGE', status: 'ACTIVE' }));
  vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValue(reply(receipts));
  vi.mocked(api.mediaPortraitGetGetApiV1MediaPortraitsPortraitId).mockImplementation(async id => {
    if (id === 'review-two') await new Promise<void>(resolve => { finishFirst = resolve; });
    return reply(receipts.find(row => row.id === id));
  });
  render(<MemoryRouter><CreatorMediaProvider publicMedia={media}><ProductionImageComposer /></CreatorMediaProvider></MemoryRouter>);
  await waitFor(() => expect(screen.getByRole('button', { name: '添加素材' })).toBeEnabled());
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['png'], 'existing.png', { type: 'image/png' })] } });
  await waitFor(() => expect(screen.queryByText('正在上传并校验素材…')).not.toBeInTheDocument());
  fireEvent.click(screen.getByRole('button', { name: '添加素材' }));
  fireEvent.click(screen.getByRole('menuitem', { name: '素材库' })); fireEvent.click(screen.getByRole('tab', { name: '公开' }));
  fireEvent.click(screen.getByRole('option', { name: 'two.png' })); fireEvent.click(screen.getByRole('option', { name: 'one.png' })); fireEvent.click(screen.getByRole('option', { name: 'three.png' }));
  expect(screen.getByText('已选：2')).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '确定' }));
  await waitFor(() => expect(finishFirst).toBeDefined());
  expect(screen.getByRole('button', { name: '图片生成模式' })).toBeDisabled();
  expect(screen.getByRole('button', { name: '选择图片模型：服务端图片模型' })).toBeDisabled();
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  finishFirst();
  expect(upload).toHaveBeenCalledTimes(1);
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ references: [{ fileId: 'existing' }, { fileId: 'two' }, { fileId: 'one' }] })));
  expect(screen.queryByRole('dialog', { name: '素材库' })).not.toBeInTheDocument();
  expect(submit).not.toHaveBeenCalled();
});

it('refills an image history task and requotes without submitting until the user generates', async () => {
  const receipt = { id: 'original-review', fileId: 'original-image', model: 'review-model', kind: 'IMAGE', status: 'ACTIVE' };
  vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValue(reply([receipt]));
  vi.mocked(api.mediaPortraitGetGetApiV1MediaPortraitsPortraitId).mockResolvedValue(reply(receipt));
  const saved = { id: 'edit-image', type: 'IMAGE', status: 'SUCCEEDED', prompt: '回填的原图片提示词', model: image.model, resolution: image.resolutions[0], aspectRatio: '1:1', count: 2, durationSeconds: 0, references: [{ fileId: 'original-image', kind: 'IMAGE' }], results: [{ index: 0, fileId: 'output' }] };
  vi.mocked(api.mediaListGetApiV1MediaTasks).mockResolvedValue(reply([saved])); task.mockResolvedValue(reply(saved));
  const router = createMemoryRouter(userRoutes, { initialEntries: ['/create/image?tab=history'] });
  render(<App router={router} />);
  fireEvent.click(await screen.findByRole('button', { name: '重新编辑' }));
  await waitFor(() => expect(screen.getByRole('textbox', { name: '图片提示词' })).toHaveValue(saved.prompt));
  expect(router.state.location.search).toBe('');
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ model: image.model, aspectRatio: '1:1', count: 2, references: [{ fileId: 'original-image' }] })));
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).not.toHaveBeenCalled();
  expect(submit).not.toHaveBeenCalled();
  fireEvent.click(await screen.findByRole('button', { name: '开始生成 · 12.3456 积分' }));
  await waitFor(() => expect(submit).toHaveBeenCalledWith(expect.objectContaining({ prompt: saved.prompt, count: 2, references: [{ fileId: 'original-image' }] }), expect.anything()));
});

function chooseMode(label: string, value: string) {
  fireEvent.click(screen.getByRole('button', { name: label }));
  fireEvent.click(screen.getAllByRole('menuitemradio').find(item => item.getAttribute('value') === value)!);
}

it('opens settings from the summary and quotes the configured 21:9 ratio without the extra settings button', async () => {
  options.mockResolvedValue(reply({ models: [{ ...image, aspectRatios: ['1:1','21:9'] }, reviewOption] }));
  open(); await screen.findByText('开始生成 · 12.3456 积分');
  expect(screen.queryByRole('button', { name: '图片设置' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: /^图片参数摘要/ }));
  fireEvent.click(screen.getByRole('button', { name: '21:9' }));
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ aspectRatio: '21:9' })));
});


it.each(['image', 'video'] as const)('shows newest %s history at the bottom and prepends older pages', async kind => {
  const records = Array.from({ length: 21 }, (_, i) => ({
    id: `history-${i}`, type: kind === 'image' ? 'IMAGE' : 'VIDEO', status: 'FAILED',
    prompt: `记录${i}`, createdAt: `2026-09-${String(i + 1).padStart(2, '0')}T10:00:00Z`,
  }));
  vi.mocked(api.mediaListGetApiV1MediaTasks)
    .mockResolvedValueOnce(reply(records.slice(1)))
    .mockResolvedValueOnce(reply([records[0]]));
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockImplementation(async id => reply(records.find(item => item.id === id)));
  render(<App router={createMemoryRouter(userRoutes, { initialEntries: [`/create/${kind}?tab=history`] })} />);
  await screen.findByRole('article', { name: /任务：记录20$/ });
  expect(screen.getAllByRole('article').map(el => el.getAttribute('data-task-key'))).toEqual(records.slice(1).map(item => item.id));
  await waitFor(() => expect(document.querySelector('.video-gallery-grid')).toHaveAttribute('aria-busy', 'false'));
  expect(screen.queryByRole('button', { name: '加载更早任务' })).not.toBeInTheDocument();
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ top: 100, bottom: 200 } as DOMRect);
  fireEvent.scroll(window);
  await screen.findByRole('article', { name: /任务：记录0$/ });
  expect(screen.getAllByRole('article').map(el => el.getAttribute('data-task-key'))).toEqual(records.map(item => item.id));
});


it.each([['IMAGE', false], ['VIDEO', false], ['IMAGE', true], ['VIDEO', true]] as const)('allows prompt input during slow loading for %s (home=%s)', async (type, home) => {
  const loading = { userId: null, authenticated: false, status: 'loading' };
  const ready = { userId: 'owner', authenticated: true, status: 'ready', accountType: 'PERSONAL',
    currentKey: { enabled: true, kind: '媒体 Key', relationship: 'Owner' }, availablePoints4: '1000.0000' };
  const session = vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue(loading as never);
  let finishModels!: (value: never) => void;
  options.mockImplementationOnce(() => new Promise(resolve => { finishModels = resolve; }));
  const tree = () => <MemoryRouter><CreatorMediaProvider>{home ? <HomeComposer />
    : type === 'IMAGE' ? <ProductionImageComposer /> : <ProductionVideoComposer />}</CreatorMediaProvider></MemoryRouter>;
  const view = render(tree());
  if (home) {
    fireEvent.click(screen.getByRole('button', { name: '展开创作区域' }));
    if (type === 'VIDEO') fireEvent.click(screen.getByRole('tab', { name: '视频' }));
  }
  const input = () => screen.getByRole('textbox', { name: type === 'IMAGE' ? '图片提示词' : '视频提示词' });
  const generate = () => screen.getByRole('button', { name: /^开始生成/ });
  expect(input()).toBeEnabled();
  editPromptInput(input(), '账户加载时输入');
  expect(generate()).toBeDisabled();
  fireEvent.submit(generate().closest('form')!);
  expect(submit).not.toHaveBeenCalled();

  session.mockReturnValue(ready as never); view.rerender(tree());
  await waitFor(() => expect(options).toHaveBeenCalled());
  expect(readPromptInput(input())).toBe('账户加载时输入');
  editPromptInput(input(), '模型加载时继续输入');
  expect(generate()).toBeDisabled();
  const available = reply({ models: [{ ...image, type, minDurationSeconds: 4, maxDurationSeconds: 8, durationStepSeconds: 1 }] });
  options.mockResolvedValue(available);
  await act(async () => finishModels(available));
  await waitFor(() => expect(generate()).toBeEnabled());
  expect(readPromptInput(input())).toBe('模型加载时继续输入');

  session.mockReturnValue(loading as never); view.rerender(tree());
  expect(input()).toBeEnabled();
  editPromptInput(input(), '后台刷新时继续输入');
  expect(generate()).toBeDisabled();
  fireEvent.submit(generate().closest('form')!);
  expect(submit).not.toHaveBeenCalled();
  session.mockReturnValue(ready as never); view.rerender(tree());
  await waitFor(() => expect(generate()).toBeEnabled());
  expect(readPromptInput(input())).toBe('后台刷新时继续输入');
  session.mockReturnValue({ ...ready, userId: 'other-owner' } as never); view.rerender(tree());
  expect(readPromptInput(input())).toBe('');
});

it('API账户保留图片历史预览，但禁用生成并显示指定提示', async () => {
  vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue({ userId: 'owner', accountType: 'API', authenticated: true, status: 'ready', currentKey: { enabled: true, kind: '媒体 Key', relationship: 'Owner' }, availablePoints4: '1000.0000' } as never);
  const saved = { id: 'api-history', type: 'IMAGE', status: 'SUCCEEDED', prompt: 'API用户的历史图片', results: [{ fileId: 'saved-file', index: 0 }] };
  vi.mocked(api.mediaListGetApiV1MediaTasks).mockResolvedValue(reply([saved]));
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(saved));
  render(<MemoryRouter><CreatorMediaProvider><ProductionImageComposer /></CreatorMediaProvider></MemoryRouter>);
  expect(await screen.findByRole('article', { name: '图片任务：API用户的历史图片' })).toBeVisible();
  expect(await screen.findByText('当前用户为api用户，可在查看媒体记录，但不能生成图片。')).toBeVisible();
  editPromptInput(screen.getByRole('textbox', { name: '图片提示词' }), '不应生成');
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  expect(screen.queryByRole('button', { name: '重新生成' })).not.toBeInTheDocument();
  fireEvent.submit(screen.getByRole('button', { name: /^开始生成/ }).closest('form')!);
  expect(api.mediaSubmitPostApiV1MediaTasks).not.toHaveBeenCalled();
  await waitFor(() => expect(screen.getByRole('img', { name: '生成图片 1' })).toHaveAttribute('src', 'https://assets.example/image.png'));
});

it('切换为API账户后不会重试未确认的图片生成', async () => {
  const ready = { userId: 'owner', accountType: 'PERSONAL', authenticated: true, status: 'ready', currentKey: { enabled: true, kind: '媒体 Key', relationship: 'Owner' }, availablePoints4: '1000.0000' };
  const session = vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue(ready as never);
  const view = render(<MemoryRouter><CreatorMediaProvider><ProductionImageComposer /></CreatorMediaProvider></MemoryRouter>);
  await screen.findByText(/^开始生成 · [0-9.]+ 积分$/);
  editPromptInput(screen.getByRole('textbox', { name: '图片提示词' }), '保留未确认请求');
  submit.mockRejectedValueOnce(new TypeError('disconnected'));
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await screen.findByRole('button', { name: '使用原请求重试' });
  session.mockReturnValue({ ...ready, accountType: 'API' } as never);
  view.rerender(<MemoryRouter><CreatorMediaProvider><ProductionImageComposer /></CreatorMediaProvider></MemoryRouter>);
  expect(screen.getByRole('button', { name: '使用原请求重试' })).toBeDisabled();
  fireEvent.submit(screen.getByRole('button', { name: '使用原请求重试' }).closest('form')!);
  expect(submit).toHaveBeenCalledTimes(1);
  view.unmount();
  render(<MemoryRouter><CreatorMediaProvider><ProductionImageComposer /></CreatorMediaProvider></MemoryRouter>);
  await screen.findByText('当前用户为api用户，可在查看媒体记录，但不能生成图片。');
  await act(async () => {});
  expect(submit).toHaveBeenCalledTimes(1);
});
