import { readPromptInput, editPromptInput } from './mention-test-helpers';
import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { HomeComposer } from '../home/HomeComposer';
import { ProductionVideoComposer } from './ProductionVideoComposer';
import { CreatorMediaProvider } from './CreatorMediaProvider';
import { MemoryRouter, createMemoryRouter } from 'react-router-dom';
import { App } from '../App';
import { userRoutes } from '../router';
import { axe } from 'vitest-axe';
import * as api from '../api';
import * as videoFrames from '../commerce/video-frames';
vi.mock('../commerce/video-frames', () => ({ extractVideoFrames: vi.fn() }));
import * as userSession from '../userSession';

vi.mock('../api', async original => ({ ...await original<typeof import('../api')>(),
  mediaTemplatesGetApiV1MediaProductSuitesTemplates: vi.fn(), mediaVideoBreakdownPostApiV1MediaProductSuitesVideoBreakdown: vi.fn(),
  mediaPortraitListGetApiV1MediaPortraits: vi.fn(), mediaPortraitGetGetApiV1MediaPortraitsPortraitId: vi.fn(), mediaPortraitSubmitPostApiV1MediaPortraits: vi.fn(),
  refreshSession: vi.fn(), billingBalanceGetApiV1BillingWalletsKeyId: vi.fn(),
  userSelfGetApiV1UsersMe: vi.fn(), paymentOptionsGetApiV1PaymentsRechargeOptions: vi.fn(), paymentGetGetApiV1PaymentsRechargesId: vi.fn(),
  accessListGetApiV1AccessKeys: vi.fn(), mediaOptionsGetApiV1MediaGenerationOptions: vi.fn(), mediaQuotePostApiV1MediaQuotes: vi.fn(),
  mediaListGetApiV1MediaTasks: vi.fn(), mediaSubmitPostApiV1MediaTasks: vi.fn(), storageUploadReferenceImagePostApiV1FilesReferenceImages: vi.fn(), storageUploadReferenceMediaPostApiV1FilesReferenceMedia: vi.fn(),
  storageUploadProcessingVideoPostApiV1FilesProcessingVideos: vi.fn(), storageReferenceClipPostApiV1FilesFileIdReferenceClip: vi.fn(),
  storageVideoContentGetApiV1FilesFileIdVideoContent: vi.fn(), storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl: vi.fn(), mediaGetGetApiV1MediaTasksTaskId: vi.fn(),
}));
const models = [
  { type: 'VIDEO', model: 'actual-video-a', displayName: '电影模型 A', resolutions: ['720p', '1080p'], aspectRatios: ['16:9', '9:16', '1:1'], maxCount: 1, minDurationSeconds: 4, maxDurationSeconds: 12, durationStepSeconds: 1, maxReferenceImages: 2, referenceFollowsFirstFrame: false },
  { type: 'VIDEO', model: 'actual-video-b', displayName: '快速模型 B', resolutions: ['480p', '720p'], aspectRatios: ['16:9', '1:1'], maxCount: 1, minDurationSeconds: 6, maxDurationSeconds: 10, durationStepSeconds: 2, maxReferenceImages: 1, referenceFollowsFirstFrame: true },
];
const reviewOption = { ...models[0], model: 'review-model', displayName: '审核模型', omniReference: {} };
const options = vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions);
const quote = vi.mocked(api.mediaQuotePostApiV1MediaQuotes);
const submit = vi.mocked(api.mediaSubmitPostApiV1MediaTasks);
async function enterHomeCreator(video = false) {
  await waitFor(() => expect(screen.getByLabelText('积分余额')).not.toHaveTextContent('—'));
  if (!video) fireEvent.click(screen.getByRole('button', { name: /Image 2/ }));
  fireEvent.change(screen.getByRole('textbox', { name: '创作描述' }), { target: { value: '来自新首页的创意' } });
  fireEvent.click(screen.getByRole('button', { name: '开始创作' }));
}
const reply = (data: unknown) => ({ data, status: 200, headers: new Headers() }) as never;
const mount = async (home = false) => {
  const view = render(<MemoryRouter><CreatorMediaProvider>{home ? <HomeComposer /> : <ProductionVideoComposer />}</CreatorMediaProvider></MemoryRouter>);
  if (home) {
    fireEvent.click(screen.getByRole('button', { name: '展开创作区域' }));
    fireEvent.click(screen.getByRole('tab', { name: '视频' }));
  }
  await waitFor(() => expect(options).toHaveBeenCalled());
  return view;
};
const settings = () => fireEvent.click(screen.getByRole('button', { name: /^视频参数摘要/ }));
const close = () => fireEvent.click(screen.getByRole('button', { name: '关闭视频设置' }));
const chooseB = async () => {
  fireEvent.click(screen.getByRole('button', { name: /选择视频模型/ }));
  fireEvent.click(await screen.findByRole('button', { name: '快速模型 B' }));
};
beforeEach(() => {
  vi.mocked(videoFrames.extractVideoFrames).mockReset();
  vi.mocked(api.mediaVideoBreakdownPostApiV1MediaProductSuitesVideoBreakdown).mockReset();
  vi.mocked(api.storageReferenceClipPostApiV1FilesFileIdReferenceClip).mockReset();
  vi.mocked(api.mediaTemplatesGetApiV1MediaProductSuitesTemplates).mockResolvedValue(reply({ templates: [], copyAvailable: true }));
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  vi.stubGlobal('URL', class extends URL { static createObjectURL() { return 'blob:reference-audio'; } static revokeObjectURL() {} });
  vi.spyOn(HTMLMediaElement.prototype, 'duration', 'get').mockReturnValue(4);
  vi.spyOn(HTMLMediaElement.prototype, 'src', 'set').mockImplementation(function (this: HTMLMediaElement) {
    queueMicrotask(() => this.dispatchEvent(new Event('loadedmetadata')));
  });
  vi.clearAllMocks(); sessionStorage.clear(); localStorage.clear();
  vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).mockReset();
  vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValue(reply([]));
  vi.mocked(api.mediaPortraitSubmitPostApiV1MediaPortraits).mockImplementation(async body => reply({ ...body, id: `review-${body.fileId}`, status: 'ACTIVE', kind: body.kind ?? 'IMAGE', durationSeconds: body.kind && body.kind !== 'IMAGE' ? 4 : null }));
  vi.mocked(api.mediaListGetApiV1MediaTasks).mockResolvedValue(reply([]));
  vi.mocked(api.userSelfGetApiV1UsersMe).mockResolvedValue(reply({ id: 'owner', enabled: true }));
  vi.mocked(api.paymentOptionsGetApiV1PaymentsRechargeOptions).mockResolvedValue(reply({ userId: 'owner', accountType: 'PERSONAL' }));
  vi.mocked(api.paymentGetGetApiV1PaymentsRechargesId).mockResolvedValue(reply({ userId: 'owner', orderId: 'first-order', accountType: 'PERSONAL', status: 'PAID', fulfilled: true }));
  vi.mocked(api.refreshSession).mockResolvedValue({ authenticated: true, user: { id: 'owner' } } as never);
  vi.mocked(api.billingBalanceGetApiV1BillingWalletsKeyId).mockResolvedValue(reply({ walletId: 'owner-key', availablePoints4: '1000.0000' }));
  vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValue(reply([{ keyId: 'owner-key', ownerId: 'owner', firstRechargeOrderId: 'first-order', enabled: true, kind: 'MEDIA', accountType: 'PERSONAL' }]));
  vi.mocked(api.storageUploadProcessingVideoPostApiV1FilesProcessingVideos).mockImplementation(body => api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia(body));
  options.mockResolvedValue(reply({ models: [...models, reviewOption] }));
  quote.mockImplementation(async body => reply({ points: `${body.durationSeconds}.0000` }));
  submit.mockResolvedValue(reply({ id: 'real-task' }));
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ id: 'real-task', status: 'RUNNING' }));
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockResolvedValue(reply({ fileId: 'file-real' }));
  vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockResolvedValue(reply({ url: 'https://assets.example/frame.png' }));
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); });

it.each([[false, 'seedance-2.5'], [true, 'seedance-2.5'], [false, 'seedance-2-5'], [true, 'seedance-2-5']] as const)('orders video models and defaults to 2.5 without replacing a manual selection (home=%s, model=%s)', async (home, preferred) => {
  const ordered = [preferred, 'seedance-2.0', 'seedance-2.0-fast', 'seedance-2.0-mini'];
  const catalog = [models[0], ...[...ordered].reverse().map(model => ({ ...models[0], model, displayName: model }))];
  options.mockResolvedValue(reply({ models: catalog }));
  await mount(home);
  const trigger = await screen.findByRole('button', { name: `选择视频模型：${preferred}` });
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ model: preferred })));
  fireEvent.click(trigger);
  const list = screen.getByRole('dialog', { name: '选择视频模型' }).querySelector('.video-model-list')!;
  expect([...list.querySelectorAll('button')].map(button => button.getAttribute('aria-label'))).toEqual([...ordered, models[0].displayName]);
  fireEvent.click(within(list as HTMLElement).getByRole('button', { name: 'seedance-2.0-mini' }));
  options.mockResolvedValue(reply({ models: [...catalog].reverse() }));
  fireEvent.focus(window);
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ model: 'seedance-2.0-mini' })));
  expect(await screen.findByRole('button', { name: '选择视频模型：seedance-2.0-mini' })).toBeEnabled();
  expect(submit).not.toHaveBeenCalled();
});

it.each([false, true])('starts with 2.5 when an accepted 2.0 submission is cached (home=%s)', async home => {
  vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue({ userId: 'owner', authenticated: true, status: 'ready', availablePoints4: '1000.0000' } as never);
  options.mockResolvedValue(reply({ models: ['seedance-2.0', 'seedance-2-5'].map(model => ({ ...models[0], model, displayName: model })) }));
  const request = { requestRef: 'accepted-request', settings: { type: 'VIDEO', model: 'seedance-2.0', resolution: '720p', aspectRatio: '16:9', count: 1, durationSeconds: 5 }, prompt: '已提交的视频', inputAssetRefs: [] };
  const accepted = { key: request.requestRef, taskRef: 'accepted-task', request, snapshot: { ...request.settings, prompt: request.prompt } };
  sessionStorage.setItem('xzwl-video-submission:owner', JSON.stringify(accepted));
  await mount(home);
  expect(await screen.findByRole('button', { name: '选择视频模型：seedance-2-5' })).toBeEnabled();
  expect(screen.getByRole('textbox', { name: '视频提示词' })).toHaveValue('');
  expect(JSON.parse(sessionStorage.getItem('xzwl-video-submission:owner')!)).toEqual(accepted);
  expect(submit).not.toHaveBeenCalled();
});

it('defaults to the first available ordered model when 2.5 is unavailable', async () => {
  options.mockResolvedValue(reply({ models: ['seedance-2.0-mini', 'seedance-2.0-fast', 'seedance-2.0'].map(model => ({ ...models[0], model, displayName: model })) }));
  await mount();
  expect(await screen.findByRole('button', { name: '选择视频模型：seedance-2.0' })).toBeEnabled();
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ model: 'seedance-2.0' })));
});

it('loads real account options, edits capabilities, resets, and submits the quoted selection', async () => {
  await mount();
  expect(await screen.findByRole('button', { name: '选择视频模型：电影模型 A' })).toBeVisible();
  fireEvent.change(screen.getByRole('textbox', { name: '视频提示词' }), { target: { value: '保留海边日落提示词' } });
  settings();
  const panel = screen.getByRole('dialog', { name: '视频设置' });
  expect(within(panel).getByRole('button', { name: '16:9' }).querySelector('.video-ratio-icon')).toHaveStyle({ width: '28px', height: '15.75px' });
  expect(within(panel).getByRole('button', { name: '9:16' }).querySelector('.video-ratio-icon')).toHaveStyle({ width: '15.75px', height: '28px' });
  fireEvent.click(within(panel).getByRole('button', { name: '9:16' }));
  fireEvent.click(within(panel).getByRole('button', { name: '1080p' }));
  fireEvent.change(within(panel).getByRole('slider', { name: '视频时长' }), { target: { value: '9' } });
  expect(within(panel).getByRole('spinbutton', { name: '时长秒数' })).toHaveValue(9);
  close();
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ model: 'actual-video-a', aspectRatio: '9:16', resolution: '1080p', durationSeconds: 9 })));
  await chooseB();
  expect(screen.queryByText(/不兼容参数已调整/)).not.toBeInTheDocument();
  expect(screen.getByRole('textbox', { name: '视频提示词' })).toHaveValue('保留海边日落提示词');
  settings();
  expect(screen.queryByRole('button', { name: '1080p' })).not.toBeInTheDocument();
  expect(screen.getByRole('slider')).toHaveAttribute('step', '2');
  fireEvent.click(screen.getByRole('button', { name: '1:1' }));
  fireEvent.change(screen.getByRole('spinbutton', { name: '时长秒数' }), { target: { value: '10' } });
  fireEvent.click(screen.getByRole('button', { name: '重置' }));
  expect(screen.getByRole('button', { name: '16:9' })).toHaveAttribute('aria-pressed', 'true');
  expect(screen.getByRole('spinbutton', { name: '时长秒数' })).toHaveValue(6);
  close();
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
  expect(submit.mock.calls[0][0]).toEqual({ type: 'VIDEO', model: 'actual-video-b', aspectRatio: '16:9', resolution: '720p', count: 1, durationSeconds: 6, prompt: '保留海边日落提示词', references: [] });
  expect(submit.mock.calls[0][1]?.headers).toHaveProperty('Idempotency-Key');
  expect(screen.getByRole('textbox', { name: '视频提示词' })).toHaveValue('保留海边日落提示词');
});

it('preserves compatible parameters on model switch and reloads changed admin options on reentry', async () => {
  await mount(true); await screen.findByText('开始生成 · 5 积分');
  settings(); fireEvent.click(screen.getByRole('button', { name: '1:1' }));
  fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '8' } }); close();
  await chooseB();
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ aspectRatio: '1:1', resolution: '720p', durationSeconds: 8 })));
  options.mockResolvedValue(reply({ models: [{ ...models[1], displayName: '后台刚更新的名称' }] }));
  fireEvent.click(screen.getByRole('tab', { name: '图片' }));
  fireEvent.click(screen.getByRole('tab', { name: '视频' }));
  expect(await screen.findByRole('button', { name: '选择视频模型：后台刚更新的名称' })).toBeVisible();
});

it('invalidates old quotes immediately, ignores late responses, and blocks failed quotes', async () => {
  await mount(); await screen.findByText('开始生成 · 5 积分');
  fireEvent.change(screen.getByRole('textbox', { name: '视频提示词' }), { target: { value: '报价并发' } });
  let late!: (value: never) => void;
  quote.mockImplementationOnce(() => new Promise(resolve => { late = resolve; }));
  settings(); fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '6' } }); close();
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  await waitFor(() => expect(late).toBeDefined());
  quote.mockRejectedValueOnce(new Error('试算失败'));
  settings(); fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '7' } }); close();
  await screen.findByRole('alert');
  await act(async () => late(reply({ points: '999.0000' })));
  expect(screen.queryByText(/999.0000/)).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  expect(submit).not.toHaveBeenCalled();
});

it('shows an explicit empty catalog without any fictional fallback', async () => {
  options.mockResolvedValue(reply({ models: [] })); await mount();
  expect(await screen.findByText(/暂无可用视频模型/)).toBeVisible();
  expect(screen.queryByText('Seedance 2.5', { selector: '.video-composer *' })).not.toBeInTheDocument();
  expect(quote).not.toHaveBeenCalled();
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
});

it('uploads real frame IDs, preserves frames across model/mode changes and quotes the actual ordered references', async () => {
  await mount(); await screen.findByText('开始生成 · 5 积分');
  chooseMode('视频生成模式', 'frames');
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['png'], 'frame.png', { type: 'image/png' })] } });
  await screen.findAllByRole('img', { name: 'frame.png' });
  await waitFor(() => expect(within(screen.getByRole('region', { name: '视频生成' })).getByLabelText('上传素材')).toBeEnabled());
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ references: [{ fileId: 'file-real' }] })));
  await chooseB();
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  fireEvent.load(screen.getAllByRole('img', { name: 'frame.png' })[0]);
  expect(screen.getAllByRole('img', { name: 'frame.png' })[0]).toBeVisible();
  expect(screen.queryByRole('button', { name: '首尾帧' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '全能参考' })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: /视频参数摘要/ })).toHaveAccessibleName('视频参数摘要：跟随首帧，720p，6秒');
  chooseMode('视频生成模式', 'text');
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  fireEvent.load(screen.getAllByRole('img', { name: 'frame.png' })[0]);
  expect(screen.getAllByRole('img', { name: 'frame.png' })[0]).toBeVisible();
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  expect(screen.getByText('当前模式不使用素材，请切换模式或移除')).toBeVisible();
});

it('keeps the original idempotency key after ambiguous submission even if a retry gets 422', async () => {
  await mount(); await screen.findByText('开始生成 · 5 积分');
  fireEvent.change(screen.getByRole('textbox', { name: '视频提示词' }), { target: { value: '不能重复扣费' } });
  submit.mockRejectedValueOnce(new TypeError('disconnected'));
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await screen.findByRole('button', { name: '使用原请求重试' });
  submit.mockRejectedValueOnce(new api.ApiError(new Response(null, { status: 422 }), {}));
  fireEvent.click(screen.getByRole('button', { name: '使用原请求重试' }));
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(2));
  expect(await screen.findByRole('button', { name: '使用原请求重试' })).toBeVisible();
  expect(screen.getByRole('textbox', { name: '视频提示词' })).toBeDisabled();
  expect(submit.mock.calls[1]).toEqual(submit.mock.calls[0]);
});

it('recovers from a definite first submission rejection without discarding prompt or frames', async () => {
  await mount(); await screen.findByText('开始生成 · 5 积分');
  fireEvent.change(screen.getByRole('textbox', { name: '视频提示词' }), { target: { value: '修正后继续' } });
  submit.mockRejectedValueOnce(new api.ApiError(new Response(null, { status: 422 }), { code: 'UNSUPPORTED_GENERATION_SELECTION' }));
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await screen.findAllByText(/提交被拒绝/);
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  expect(screen.getByRole('textbox', { name: '视频提示词' })).toHaveValue('修正后继续');
  expect(screen.getByRole('button', { name: /选择视频模型/ })).toBeEnabled();
});

it('does not submit invalid duration or orphan last frame and sends actual ordered IDs', async () => {
  await mount(); await screen.findByText('开始生成 · 5 积分');
  fireEvent.change(screen.getByRole('textbox', { name: '视频提示词' }), { target: { value: '首尾帧' } });
  settings(); fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '3.5' } }); close();
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  settings(); fireEvent.click(screen.getByRole('button', { name: '重置' })); close();
  chooseMode('视频生成模式', 'frames');
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockResolvedValueOnce(reply({ fileId: 'temporary-first' }));
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['png'], 'temporary.png', { type: 'image/png' })] } });
  await screen.findByText('temporary.png');
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['png'], 'last.png', { type: 'image/png' })] } });
  await screen.findByText('last.png');
  await waitFor(() => expect(screen.getByRole('button', { name: /选择视频模型/ })).toBeEnabled());
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  fireEvent.click(screen.getByRole('button', { name: '移除首帧' }));
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockResolvedValueOnce(reply({ fileId: 'first-file' }));
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['png'], 'first.png', { type: 'image/png' })] } });
  await screen.findByText('first.png');
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ references: [{ fileId: 'first-file' }, { fileId: 'file-real' }] }));
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await waitFor(() => expect(submit).toHaveBeenCalled());
  expect(submit.mock.calls[0][0]).toEqual(expect.objectContaining({ references: [{ fileId: 'first-file' }, { fileId: 'file-real' }] }));
});


it('keeps an accepted task and prompt while switching to video history and back', async () => {
  const router = createMemoryRouter(userRoutes, { initialEntries: ['/create/video'] });
  render(<App router={router} />);
  await screen.findByText('开始生成 · 5 积分');
  fireEvent.change(screen.getByRole('textbox', { name: '视频提示词' }), { target: { value: '任务必须保留' } });
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await screen.findByRole('article', { name: '视频任务：任务必须保留' });
  fireEvent.click(screen.getByRole('link', { name: '视频历史' }));
  await screen.findByRole('heading', { name: '视频历史' });
  expect(screen.getByRole('article', { name: '视频任务：任务必须保留' })).toBeVisible();
  fireEvent.click(screen.getByRole('link', { name: '探索' }));
  await screen.findByRole('heading', { name: '点亮视频创作' });
  expect(screen.getByRole('textbox', { name: '视频提示词' })).toHaveValue('任务必须保留');
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  expect(submit).toHaveBeenCalledTimes(1);
});

it('provides an accessible settings dialog with named controls', async () => {
  await mount(true); await screen.findByText('开始生成 · 5 积分');
  settings();
  const dialog = screen.getByRole('dialog', { name: '视频设置' });
  const result = await axe(dialog, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations).toEqual([]);
  fireEvent(dialog, new Event('cancel', { bubbles: false, cancelable: true }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: '展开创作区域' })).toHaveAttribute('aria-expanded', 'true');
});


it.each([
  [422, '当前账户暂不满足视频生成条件，请检查账户开通、密钥状态和可用价格。'],
  [503, '模型服务暂不可用，请稍后重试。'],
  [403, '当前账户无权读取视频模型，请检查账户权限。'],
])('distinguishes catalog HTTP %i from an empty model list and recovers on retry', async (status, message) => {
  options.mockRejectedValueOnce(new api.ApiError(new Response(null, { status }), {}));
  await mount();
  expect(await screen.findByRole('alert')).toHaveTextContent(message);
  expect(screen.getByRole('button', { name: '选择视频模型：模型加载失败' })).toBeDisabled();
  expect(screen.queryByText(/暂无可用视频模型/)).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '重新获取模型与报价' }));
  expect(await screen.findByRole('button', { name: '选择视频模型：电影模型 A' })).toBeEnabled();
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
});


it.each([
  [[], '当前账户尚未开通生成服务，请先完成账户开通与首充。'],
  [[{ enabled: false }], '当前账户密钥已禁用，请联系管理员处理。'],
])('explains rejected eligibility using the current account key metadata', async (keys, message) => {
  options.mockRejectedValueOnce(new api.ApiError(new Response(null, { status: 422 }), {}));
  vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValue(reply(keys));
  await mount();
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(message));
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  expect(quote).not.toHaveBeenCalled();
});

it('refreshes rejected video eligibility on account-changed and never overrides a fresh server refusal', async () => {
  options.mockRejectedValueOnce(new api.ApiError(new Response(null, { status: 422 }), {}));
  await mount(); await screen.findByRole('alert');
  fireEvent.change(screen.getByRole('textbox', { name: '视频提示词' }), { target: { value: '充值后继续' } });
  act(() => window.dispatchEvent(new Event('xzwl-account-changed')));
  await screen.findByText('开始生成 · 5 积分');
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled();
  expect(screen.getByRole('textbox', { name: '视频提示词' })).toHaveValue('充值后继续');
  options.mockRejectedValue(new api.ApiError(new Response(null, { status: 422 }), {}));
  act(() => window.dispatchEvent(new Event('xzwl-account-changed')));
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  await screen.findByRole('alert');
  expect(submit).not.toHaveBeenCalled();
});


it('refreshes the real account balance once when the video task becomes terminal', async () => {
  await mount(); await screen.findByText('开始生成 · 5 积分');
  const dispatch = vi.spyOn(window, 'dispatchEvent');
  fireEvent.change(screen.getByRole('textbox', { name: '视频提示词' }), { target: { value: '失败退款后刷新' } });
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await screen.findByRole('progressbar', { name: '视频生成进度' });
  expect(dispatch.mock.calls.filter(([event]) => event.type === 'xzwl-account-changed')).toHaveLength(0);
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ id: 'real-task', status: 'FAILED' }));
  await screen.findByRole('heading', { name: '生成失败' }, { timeout: 4000 });
  expect(dispatch.mock.calls.filter(([event]) => event.type === 'xzwl-account-changed')).toHaveLength(1);
  vi.useFakeTimers();
  await act(async () => { await vi.advanceTimersByTimeAsync(10000); });
  expect(dispatch.mock.calls.filter(([event]) => event.type === 'xzwl-account-changed')).toHaveLength(1);
  expect(submit).toHaveBeenCalledTimes(1);
});

it('restores the default material card, replaces real uploads, and blocks incompatible retained frames', async () => {
  await mount(); await screen.findByText('开始生成 · 5 积分');
  expect(screen.getByRole('button', { name: '添加素材' })).toBeDisabled();
  expect(screen.getByRole('button', { name: '视频生成模式' })).toHaveValue('text');
  chooseMode('视频生成模式', 'frames');
  expect(screen.queryByText(/已获取最新模型配置/)).not.toBeInTheDocument();
  fireEvent.change(screen.getByRole('textbox', { name: '视频提示词' }), { target: { value: '素材保留' } });
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['png'], 'first.png', { type: 'image/png' })] } });
  await waitFor(() => expect(screen.getByRole('img', { name: 'first.png' })).toHaveAttribute('src', 'https://assets.example/frame.png'));
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ references: [{ fileId: 'file-real' }] })));
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockResolvedValueOnce(reply({ fileId: 'replacement' }));
  fireEvent.change(screen.getByLabelText('替换首帧'), { target: { files: [new File(['png'], 'replaced.png', { type: 'image/png' })] } });
  await screen.findByText('replaced.png');
  await waitFor(() => expect(screen.getByLabelText('上传素材')).toBeEnabled());
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['png'], 'last.png', { type: 'image/png' })] } });
  await screen.findByText('last.png');
  await waitFor(() => expect(screen.getByRole('button', { name: /选择视频模型/ })).toBeEnabled());
  await chooseB();
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  expect(screen.getByText('当前模型不支持尾帧')).toBeVisible();
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: '移除尾帧' }));
  settings(); close();
  expect(screen.getByRole('textbox', { name: '视频提示词' })).toHaveValue('素材保留');
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ references: [{ fileId: 'replacement' }] }));
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
  expect(submit.mock.calls[0][0]).toEqual({ ...quote.mock.calls.at(-1)![0], prompt: '素材保留' });
});


it('keeps submitted snapshots above the form while new tasks update independently', async () => {
  await mount(); await screen.findByText('开始生成 · 5 积分');
  fireEvent.change(screen.getByRole('textbox', { name: '视频提示词' }), { target: { value: '第一段视频' } });
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  const first = await screen.findByRole('article', { name: '视频任务：第一段视频' });
  expect(first.compareDocumentPosition(screen.getByRole('textbox', { name: '视频提示词' })) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(within(first).getByText('电影模型 A')).toBeVisible();
  expect(screen.queryByText(/任务已提交|real-task/)).not.toBeInTheDocument();
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  await chooseB();
  fireEvent.change(screen.getByRole('textbox', { name: '视频提示词' }), { target: { value: '第二段视频' } });
  submit.mockResolvedValueOnce(reply({ id: 'second-task' }));
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockImplementation(async id => reply({ id, status: id === 'real-task' ? 'FAILED' : 'RUNNING', failureMessage: '火山生成失败', results: [] }));
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  const second = await screen.findByRole('article', { name: '视频任务：第二段视频' });
  expect(within(second).getByText('快速模型 B')).toBeVisible();
  expect(within(first).getByText('电影模型 A')).toBeVisible();
  expect(await within(first).findByText('火山生成失败', {}, { timeout: 4000 })).toBeVisible();
  expect(within(second).getByRole('progressbar', { name: '视频生成进度' })).toBeVisible();
  expect(submit).toHaveBeenCalledTimes(2);
});

it('restores accepted video tasks from the real list and never resubmits after remount', async () => {
  vi.mocked(api.mediaListGetApiV1MediaTasks).mockResolvedValue(reply([{ id: 'saved-task', type: 'VIDEO', status: 'RUNNING', prompt: '刷新恢复', modelName: '原提交模型', createdAt: '2026-09-09T08:00:00Z', resolution: '480p', durationSeconds: 4, aspectRatio: '9:16' }]));
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ id: 'saved-task', status: 'RUNNING' }));
  const view = await mount();
  expect(await screen.findByRole('article', { name: '视频任务：刷新恢复' })).toBeVisible();
  view.unmount(); await mount();
  const card = await screen.findByRole('article', { name: '视频任务：刷新恢复' });
  expect(within(card).getByText('原提交模型')).toBeVisible();
  expect(within(card).getByText('比例 9:16')).toBeVisible();
  expect(submit).not.toHaveBeenCalled();
  expect(api.mediaListGetApiV1MediaTasks).toHaveBeenCalledWith({ limit: 20, excludeCanvas: true });
});

it('does not refresh the account in a loop when real account history contains terminal tasks', async () => {
  const finished = { id: 'history-done', type: 'VIDEO', status: 'FAILED', prompt: '完成的历史任务', results: [], failures: [{ index: 0, message: '生成失败，请稍后重试' }] };
  vi.mocked(api.mediaListGetApiV1MediaTasks).mockResolvedValue(reply([finished]));
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(finished));
  const router = createMemoryRouter(userRoutes, { initialEntries: ['/create/video'] });
  render(<App router={router} />);
  await screen.findByRole('article', { name: '视频任务：完成的历史任务' });
  await screen.findByRole('heading', { name: '生成失败' });
  const calls = vi.mocked(api.refreshSession).mock.calls.length;
  vi.useFakeTimers();
  await act(async () => { await vi.advanceTimersByTimeAsync(10000); });
  expect(api.refreshSession).toHaveBeenCalledTimes(calls);
  expect(api.mediaGetGetApiV1MediaTasksTaskId).toHaveBeenCalledTimes(1);
});

it('restores the original uncertain request after refresh and automatically confirms the same request', async () => {
  vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue({ userId: 'owner-a', authenticated: true, status: 'ready', currentKey: { enabled: true, kind: '媒体 Key', relationship: 'Owner' }, availablePoints4: '1000.0000' } as never);
  let view = await mount(); await screen.findByText('开始生成 · 5 积分');
  fireEvent.change(screen.getByRole('textbox', { name: '视频提示词' }), { target: { value: '保留原请求' } });
  submit.mockRejectedValueOnce(new TypeError('disconnected'));
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await screen.findByRole('button', { name: '使用原请求重试' });
  const original = submit.mock.calls[0];
  view.unmount(); view = await mount();
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(2));
  expect(screen.getByRole('textbox', { name: '视频提示词' })).toHaveValue('保留原请求');
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(2));
  expect(submit.mock.calls[1]).toEqual(original);
});
it('ignores a late accepted response after switching accounts', async () => {
  const account = vi.spyOn(userSession, 'useOptionalUserSession');
  const ready = { authenticated: true, status: 'ready', currentKey: { enabled: true, kind: '媒体 Key', relationship: 'Owner' }, availablePoints4: '1000.0000' };
  account.mockReturnValue({ ...ready, userId: 'owner-a' } as never);
  const view = await mount(); await screen.findByText('开始生成 · 5 积分');
  let accepted!: (value: never) => void;
  submit.mockImplementationOnce(() => new Promise(resolve => { accepted = resolve; }));
  fireEvent.change(screen.getByRole('textbox', { name: '视频提示词' }), { target: { value: '账户A私有提示词' } });
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await screen.findByRole('progressbar', { name: '视频生成进度' });
  account.mockReturnValue({ ...ready, userId: 'owner-b' } as never);
  view.rerender(<MemoryRouter><CreatorMediaProvider><ProductionVideoComposer /></CreatorMediaProvider></MemoryRouter>);
  await act(async () => accepted(reply({ id: 'account-a-task' })));
  expect(screen.queryByRole('article', { name: '视频任务：账户A私有提示词' })).not.toBeInTheDocument();
  expect(screen.getByRole('textbox', { name: '视频提示词' })).toHaveValue('');
  expect(screen.getByRole('textbox', { name: '视频提示词' })).toBeEnabled();
  expect(sessionStorage.getItem('xzwl-video-submission:owner-b')).toBeNull();
});

it('blocks an uncertain replay while account identity is being confirmed', async () => {
  const account = vi.spyOn(userSession, 'useOptionalUserSession');
  account.mockReturnValue({ userId: 'owner-a', authenticated: true, status: 'ready', currentKey: { enabled: true, kind: '媒体 Key', relationship: 'Owner' }, availablePoints4: '1000.0000' } as never);
  const view = await mount(); await screen.findByText('开始生成 · 5 积分');
  fireEvent.change(screen.getByRole('textbox', { name: '视频提示词' }), { target: { value: '不允许账户不明确时重试' } });
  submit.mockRejectedValueOnce(new TypeError('disconnected'));
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await screen.findByRole('button', { name: '使用原请求重试' });
  account.mockReturnValue({ userId: null, authenticated: false, status: 'loading' } as never);
  view.rerender(<MemoryRouter><CreatorMediaProvider><ProductionVideoComposer /></CreatorMediaProvider></MemoryRouter>);
  const retry = screen.getByRole('button', { name: '使用原请求重试' });
  expect(retry).toBeDisabled(); fireEvent.click(retry);
  fireEvent.submit(retry.closest('form')!);
  expect(submit).toHaveBeenCalledTimes(1);
});


it('opens real video history directly without demo records or generation controls', async () => {
  const saved = { id: 'saved-video', type: 'VIDEO', status: 'SUCCEEDED', prompt: '我的已生成视频', modelName: '提交模型', results: [{ index: 0, fileId: 'stored-video' }] };
  vi.mocked(api.mediaListGetApiV1MediaTasks).mockResolvedValue(reply([saved]));
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(saved));
  vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockResolvedValue(reply({ url: 'https://assets.example/saved.mp4' }));
  render(<App router={createMemoryRouter(userRoutes, { initialEntries: ['/create/video?tab=history'] })} />);
  const history = await screen.findByRole('region', { name: '视频历史' });
  expect(await within(history).findByRole('article', { name: '视频任务：我的已生成视频' })).toBeVisible();
  expect(screen.getByRole('button', { name: '四宫格' })).toHaveAttribute('aria-pressed', 'true');
  expect(screen.queryByRole('button', { name: '预览视频：我的已生成视频' })).not.toBeInTheDocument();
  await waitFor(() => expect(within(history).getByLabelText('生成视频 1')).toHaveAttribute('src', 'https://assets.example/saved.mp4'));
  expect(within(history).getByLabelText('生成视频 1')).toHaveAttribute('controls');
  expect(within(history).queryByRole('link', { name: /下载视频/ })).not.toBeInTheDocument();
  expect(screen.queryByText(/本地历史示例/)).not.toBeInTheDocument();
  expect(screen.queryByText('VID-0829-063')).not.toBeInTheDocument();
  expect(screen.queryByRole('textbox', { name: '视频提示词' })).not.toBeInTheDocument();
  expect(options).not.toHaveBeenCalled(); expect(quote).not.toHaveBeenCalled(); expect(submit).not.toHaveBeenCalled();
});

it('loads older real videos after a page of image tasks and shows the genuine empty state', async () => {
  vi.mocked(api.mediaListGetApiV1MediaTasks)
    .mockResolvedValueOnce(reply(Array.from({ length: 20 }, (_, i) => ({ id: `image-${i}`, type: 'IMAGE' }))))
    .mockResolvedValueOnce(reply([{ id: 'older-video', type: 'VIDEO', status: 'FAILED', prompt: '更早的视频', failureMessage: '生成超时，请稍后重试' }]));
  const router = createMemoryRouter(userRoutes, { initialEntries: ['/create/video?tab=history'] });
  const view = render(<App router={router} />);
  expect(screen.queryByRole('button', { name: '加载更早任务' })).not.toBeInTheDocument();
  expect(await screen.findByRole('article', { name: '视频任务：更早的视频' })).toBeVisible();
  expect(api.mediaListGetApiV1MediaTasks).toHaveBeenLastCalledWith({ limit: 20, beforeId: 'image-19', excludeCanvas: true });
  expect(screen.queryByText('暂无视频任务')).not.toBeInTheDocument();
  view.unmount();
  vi.mocked(api.mediaListGetApiV1MediaTasks).mockResolvedValue(reply([]));
  render(<App router={createMemoryRouter(userRoutes, { initialEntries: ['/create/video?tab=history'] })} />);
  expect(await screen.findByText('暂无视频任务')).toBeVisible();
  expect(submit).not.toHaveBeenCalled();
});

it('refreshes the newest videos without losing the older cursor and coalesces repeated boundary events', async () => {
  const firstPage = Array.from({ length: 20 }, (_, i) => ({ id: `v-${40 - i}`, type: 'VIDEO', status: 'FAILED', prompt: `视频${40 - i}`, createdAt: new Date(2026, 0, 40 - i).toISOString() }));
  let releaseOlder!: (value: ReturnType<typeof reply>) => void;
  const olderResponse = new Promise<ReturnType<typeof reply>>(resolve => { releaseOlder = resolve; });
  const list = vi.mocked(api.mediaListGetApiV1MediaTasks);
  list.mockResolvedValueOnce(reply(firstPage)).mockReturnValueOnce(olderResponse)
    .mockResolvedValueOnce(reply([{ id: 'newest', type: 'VIDEO', status: 'RUNNING', prompt: '新任务', createdAt: '2026-03-01T00:00:00Z' }, ...firstPage.slice(0, 19)]))
    .mockResolvedValueOnce(reply([{ id: 'oldest', type: 'VIDEO', status: 'FAILED', prompt: '最早视频' }]));
  let y = 1600, boundaryTop = 2000;
  vi.spyOn(document.documentElement, 'scrollHeight', 'get').mockReturnValue(2400);
  vi.stubGlobal('innerHeight', 800);
  vi.spyOn(window, 'scrollY', 'get').mockImplementation(() => y);
  vi.spyOn(window, 'scrollBy').mockImplementation(() => {});
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    const top = this.classList.contains('video-gallery-boundary') ? boundaryTop : 100 - y;
    return { top, bottom: top + 100, width: 900, height: 100 } as DOMRect;
  });
  render(<App router={createMemoryRouter(userRoutes, { initialEntries: ['/create/video?tab=history'] })} />);
  await screen.findByRole('article', { name: '视频任务：视频40' });
  boundaryTop = 100; y = 500;
  fireEvent.scroll(window); fireEvent.scroll(window); fireEvent.scroll(window);
  expect(list).toHaveBeenCalledTimes(2);
  const secondPage = Array.from({ length: 20 }, (_, i) => ({ id: `v-${20 - i}`, type: 'VIDEO', status: 'FAILED', prompt: `视频${20 - i}` }));
  await act(async () => releaseOlder(reply(secondPage)));
  await screen.findByRole('article', { name: '视频任务：视频1' });
  boundaryTop = 2000; y = 1600; fireEvent.scroll(window);
  await screen.findByRole('article', { name: '视频任务：新任务' });
  await waitFor(() => expect(document.querySelector('.video-gallery-grid')).toHaveAttribute('aria-busy', 'false'));
  fireEvent.scroll(window); expect(list).toHaveBeenCalledTimes(3);
  boundaryTop = 100; y = 500; fireEvent.scroll(window);
  await screen.findByRole('article', { name: '视频任务：最早视频' });
  expect(list).toHaveBeenLastCalledWith({ limit: 20, excludeCanvas: true, beforeId: 'v-1' });
  expect(screen.getAllByRole('article', { name: '视频任务：视频40' })).toHaveLength(1);
  expect(screen.getByText('已加载全部')).toBeVisible();
});


it('restores the material library and reuses approved original files through quote APIs without another upload', async () => {
  const content = new File(['local-picture'], '素材库图片.png', { type: 'image/png' });
  const records = [{ id: 'file-real', name: content.name, kind: 'image' as const, mimeType: content.type, size: content.size, content }];
  const reviewed = { id: 'existing-review', fileId: 'file-real', model: 'review-model', kind: 'IMAGE', status: 'ACTIVE' };
  vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValue(reply([reviewed]));
  vi.mocked(api.mediaPortraitGetGetApiV1MediaPortraitsPortraitId).mockResolvedValue(reply(reviewed));
  render(<MemoryRouter><CreatorMediaProvider storage={{ storageMode: 'session', reason: 'forced', repository: {
    list: async () => records, put: async () => {}, remove: async () => {}, close: () => {},
  } }} objectUrls={{ create: () => 'blob:library-preview', revoke: () => {} }}><ProductionVideoComposer /></CreatorMediaProvider></MemoryRouter>);
  await screen.findByText('开始生成 · 5 积分');
  chooseMode('视频生成模式', 'frames');
  await waitFor(() => expect(screen.getByRole('button', { name: '添加素材' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '添加素材' }));
  expect(screen.getByRole('menuitem', { name: '本地上传' })).toBeVisible();
  fireEvent.click(screen.getByRole('menuitem', { name: '素材库' }));
  const library = await screen.findByRole('dialog', { name: '素材库' });
  fireEvent.click(within(library).getByRole('tab', { name: '个人' }));
  fireEvent.click(await within(library).findByRole('option', { name: content.name }));
  fireEvent.click(within(library).getByRole('button', { name: '确定' }));
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ references: [{ fileId: 'file-real' }] })));
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).not.toHaveBeenCalled();
  expect(api.mediaPortraitSubmitPostApiV1MediaPortraits).not.toHaveBeenCalled();
  fireEvent.change(screen.getByRole('textbox', { name: '视频提示词' }), { target: { value: '使用素材库图片生成' } });
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await waitFor(() => expect(submit).toHaveBeenCalledWith(expect.objectContaining({ references: [{ fileId: 'file-real' }] }), expect.anything()));
});

const omni = { maxImages: 9, maxVideos: 3, maxAudios: 3, maxTotal: 12, maxVideoDurationSeconds: 15, maxAudioDurationSeconds: 15, maxVideoBytes: 50 * 1024 * 1024, maxAudioBytes: 15 * 1024 * 1024 };
it('uploads mixed omni references, quotes typed IDs, compiles mentions and preserves incompatibilities across modes', async () => {
  options.mockResolvedValue(reply({ models: [{ ...models[0], omniReference: omni }, models[1]] }));
  const upload = vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia);
  upload.mockImplementation(async ({ file }) => reply({ fileId: (file as File).name.split('.')[0], durationSeconds: file.type.startsWith('image/') ? null : 4 }));
  await mount(); await waitFor(() => expect(screen.getByRole('button', { name: '视频生成模式' })).toHaveValue('omni'));
  expect(screen.getByRole('button', { name: '视频生成模式' })).toHaveValue('omni');
  for (const [name, type] of [['photo.png', 'image/png'], ['motion.mp4', 'video/mp4'], ['sound.wav', 'audio/wav']]) {
    fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['media'], name, { type })] } });
    await waitFor(() => expect(screen.queryAllByRole('status', { name: /上传中$/ })).toHaveLength(0));
  }
  const references = [{ fileId: 'photo', kind: 'IMAGE', durationSeconds: null, portraitId: 'review-photo' }, { fileId: 'motion', kind: 'VIDEO', durationSeconds: 4, portraitId: 'review-motion' }, { fileId: 'sound', kind: 'AUDIO', durationSeconds: 4, portraitId: 'review-sound' }];
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ referenceMode: 'OMNI', references })));
  let input = screen.getByRole('textbox', { name: '视频提示词' });
  editPromptInput(input, '@', 1);
  fireEvent.keyDown(input, { key: 'ArrowDown' }); fireEvent.keyDown(input, { key: 'Enter' });
  expect(submit).not.toHaveBeenCalled();
  input = screen.getByRole('textbox', { name: '视频提示词' });
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  const quoteCount = quote.mock.calls.length;
  const boundText = readPromptInput(input);
  for (const suffix of ['轻', '轻微', '轻微移动']) {
    editPromptInput(input, boundText + suffix);
    expect(screen.getByRole('button', { name: /^开始生成.*积分$/ })).toBeEnabled();
  }
  editPromptInput(input, boundText);
  await act(async () => {});
  expect(quote).toHaveBeenCalledTimes(quoteCount);
  chooseMode('视频生成模式', 'frames');
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  fireEvent.loadedData(screen.getByLabelText('motion.mp4'));
  expect(screen.getByLabelText('motion.mp4')).toBeVisible();
  expect(screen.getByLabelText('替换视频1')).toBeInTheDocument();
  chooseMode('视频生成模式', 'omni');
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await waitFor(() => expect(submit).toHaveBeenCalledWith(expect.objectContaining({ referenceMode: 'OMNI', references, prompt: '视频1' }), expect.anything()));
  const card = await screen.findByRole('article', { name: '视频任务：视频1' });
  expect(within(card).getByText('全能参考')).toBeVisible();
  expect(within(card).queryByText('音频1')).not.toBeInTheDocument();
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).not.toHaveBeenCalled();
});
it('blocks audio-only, duration totals and unsupported models without losing omni media', async () => {
  options.mockResolvedValue(reply({ models: [{ ...models[0], omniReference: omni }, models[1]] }));
  const upload = vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia);
  upload.mockResolvedValueOnce(reply({ fileId: 'sound', durationSeconds: 4 }));
  await mount(); await waitFor(() => expect(screen.getByRole('button', { name: '视频生成模式' })).toHaveValue('omni'));
  fireEvent.change(screen.getByRole('textbox', { name: '视频提示词' }), { target: { value: '测试素材限制' } });
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['wav'], 'sound.wav', { type: 'audio/wav' })] } });
  await waitFor(() => expect(screen.queryAllByRole('status', { name: /上传中$/ })).toHaveLength(0));
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  upload.mockResolvedValueOnce(reply({ fileId: 'long-video', durationSeconds: 16 }));
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['mp4'], 'long.mp4', { type: 'video/mp4' })] } });
  await waitFor(() => expect(screen.queryAllByRole('status', { name: /上传中$/ })).toHaveLength(0));
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  await chooseB();
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  fireEvent.loadedData(screen.getByLabelText('long.mp4'));
  expect(screen.getByLabelText('long.mp4')).toBeVisible();
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  expect(submit).not.toHaveBeenCalled();
});

it.each([
  [{ maxImages: 1 }, [['one.png', 'image/png', null], ['two.png', 'image/png', null]], '最多上传 1 张图片'],
  [{ maxVideos: 1 }, [['one.mp4', 'video/mp4', 4], ['two.mp4', 'video/mp4', 4]], '最多上传 1 个视频'],
  [{ maxAudios: 1, audioOnly: true }, [['one.wav', 'audio/wav', 4], ['two.wav', 'audio/wav', 4]], '最多上传 1 个音频'],
  [{}, [['one.mp4', 'video/mp4', 8], ['two.mp4', 'video/mp4', 8]], '视频总时长不能超过 15 秒'],
  [{}, [['unknown.mp4', 'video/mp4', null]], '素材缺少可信时长，请重新上传'],
] as const)('enforces server type and duration capabilities %j', async (limits, files, issue) => {
  options.mockResolvedValue(reply({ models: [{ ...models[0], omniReference: { ...omni, ...limits } }] }));
  const upload = vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia);
  await mount(); await waitFor(() => expect(screen.getByRole('button', { name: '视频生成模式' })).toHaveValue('omni'));
  fireEvent.change(screen.getByRole('textbox', { name: '视频提示词' }), { target: { value: '校验能力' } });
  for (const [name, type, durationSeconds] of files) {
    upload.mockResolvedValueOnce(reply({ fileId: name.split('.')[0], durationSeconds }));
    fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['media'], name, { type })] } });
    await waitFor(() => expect(screen.queryAllByRole('status', { name: /上传中$/ })).toHaveLength(0));
  }
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  expect(screen.getAllByText(issue)[0]).toBeVisible();
  if (issue.startsWith('最多上传')) {
    expect(upload).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
    expect(quote.mock.calls.at(-1)![0].references).toHaveLength(1);
  } else expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  expect(submit).not.toHaveBeenCalled();
});
it('uses server audio-only capability and total count while rejecting oversized media before uploading', async () => {
  options.mockResolvedValue(reply({ models: [{ ...models[0], omniReference: { ...omni, audioOnly: true, maxTotal: 1, maxAudioBytes: 5 } }] }));
  const upload = vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia);
  await mount(); await waitFor(() => expect(screen.getByRole('button', { name: '视频生成模式' })).toHaveValue('omni'));
  fireEvent.change(screen.getByRole('textbox', { name: '视频提示词' }), { target: { value: '音频参考' } });
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['oversized'], 'large.wav', { type: 'audio/wav' })] } });
  expect(upload).not.toHaveBeenCalled();
  expect(await screen.findByRole('alert')).toHaveTextContent('大小限制');
  upload.mockResolvedValueOnce(reply({ fileId: 'audio-only', durationSeconds: 4 }));
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['wav'], 'sound.wav', { type: 'audio/wav' })] } });
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  await waitFor(() => expect(screen.getByLabelText('上传素材')).toBeDisabled());
  expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ referenceMode: 'OMNI', references: [{ fileId: 'audio-only', kind: 'AUDIO', durationSeconds: 4, portraitId: 'review-audio-only' }] }));
  expect(submit).not.toHaveBeenCalled();
});

it('explains invalid audio/video uploads without telling users to convert them to pictures', async () => {
  options.mockResolvedValue(reply({ models: [{ ...models[0], omniReference: omni }] }));
  vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).mockRejectedValueOnce(new api.ApiError(new Response(null, { status: 422 }), {}));
  await mount(); await waitFor(() => expect(screen.getByRole('button', { name: '视频生成模式' })).toHaveValue('omni'));
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['invalid'], 'video.mp4', { type: 'video/mp4' })] } });
  const error = await screen.findByRole('alert');
  expect(error).toHaveTextContent('媒体格式、时长和大小');
  expect(error).not.toHaveTextContent('检查图片');
});

it('recovers the explicit omni mode and replays exactly the saved typed request after an uncertain submission', async () => {
  vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue({ userId: 'recover-owner', authenticated: true, status: 'ready', currentKey: { enabled: true, kind: '媒体 Key', relationship: 'Owner' }, availablePoints4: '1000.0000' } as never);
  const request = { requestRef: 'recovery-key', settings: { type: 'VIDEO', model: models[0].model, resolution: '720p', aspectRatio: '16:9', count: 1, durationSeconds: 5 }, prompt: '视频1', referenceMode: 'OMNI', inputAssetRefs: ['movie'], references: [{ fileId: 'movie', kind: 'VIDEO', durationSeconds: 3 }] };
  sessionStorage.setItem('xzwl-video-submission:recover-owner', JSON.stringify({ key: 'recovery-key', request, snapshot: { ...request.settings, referenceMode: 'OMNI', prompt: request.prompt, references: request.references } }));
  await mount();
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
  expect(screen.getByRole('button', { name: '视频生成模式' })).toHaveValue('omni');
  await waitFor(() => expect(submit).toHaveBeenCalledWith({ ...request.settings, prompt: request.prompt, referenceMode: 'OMNI', references: request.references }, expect.objectContaining({ headers: { 'Idempotency-Key': 'recovery-key' } })));
});

it('requires reference material before quoting omni and offers an explicit text-only mode', async () => {
  options.mockResolvedValue(reply({ models: [{ ...models[0], omniReference: omni }] }));
  await mount(); await waitFor(() => expect(screen.getByRole('button', { name: '视频生成模式' })).toHaveValue('omni'));
  expect(screen.queryByText('请添加参考素材，或切换文生视频')).not.toBeInTheDocument();
  expect(quote).not.toHaveBeenCalled();
  chooseMode('视频生成模式', 'text');
  await screen.findByText('开始生成 · 5 积分');
  expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ references: [] }));
});

it.each([
  ['constructor', '报价失败，请重试'],
  ['REFERENCE_METADATA_REQUIRED', '素材缺少可信媒体信息，请移除并重新上传'],
  ['REFERENCE_TYPE_MISMATCH', '素材实际类型不匹配，请移除并重新上传'],
  ['INVALID_REFERENCE_TYPE', '素材实际类型不匹配，请移除并重新上传'],
  ['UNSUPPORTED_OMNI_REFERENCE', '全能参考不符合当前模型要求，请检查素材类型、数量和时长，或切换模型'],
  ['REFERENCE_REJECTED', '素材不符合当前模型要求，请检查类型、数量和时长后重试'],
])('explains actionable quote refusal %s', async (code, message) => {
  quote.mockRejectedValueOnce(new api.ApiError(new Response(null, { status: 422 }), { code }));
  await mount();
  expect(await screen.findByText(message)).toBeVisible();
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
});

it('hands homepage input to video creation before configuring real frame references and generation', async () => {
  const router = createMemoryRouter(userRoutes, { initialEntries: ['/home'] });
  render(<App router={router} />);
  await waitFor(() => expect(screen.getByLabelText('积分余额')).not.toHaveTextContent('—'));
  await enterHomeCreator(true);
  await screen.findByText('开始生成 · 5 积分');
  expect(submit).not.toHaveBeenCalled();
  expect(api.mediaListGetApiV1MediaTasks).toHaveBeenCalled();
  chooseMode('视频生成模式', 'frames');
  fireEvent.change(within(screen.getByRole('region', { name: '视频生成' })).getByLabelText('上传素材'), { target: { files: [new File(['image'], 'frame.png', { type: 'image/png' })] } });
  await screen.findByText('frame.png');
  await waitFor(() => expect(within(screen.getByRole('region', { name: '视频生成' })).getByLabelText('上传素材')).toBeEnabled());
  editPromptInput(screen.getByRole('textbox', { name: '视频提示词' }), '依据@', 3);
  fireEvent.click(await screen.findByRole('option', { name: /frame.png/ }));
  const prompt = readPromptInput(screen.getByRole('textbox', { name: '视频提示词' }));
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await waitFor(() => expect(router.state.location.pathname).toBe('/create/video'));
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
  expect(submit.mock.calls[0][0]).toMatchObject({ type: 'VIDEO', prompt: '依据首帧图片', references: [{ fileId: 'file-real' }] });
  expect(readPromptInput(screen.getByRole('textbox', { name: '视频提示词' }))).toBe(prompt);
  expect(screen.getByRole('button', { name: '视频生成模式' })).toHaveValue('frames');
});

it.each(['frames', 'omni'] as const)('fills a %s library batch according to the active model capabilities', async mode => {
  options.mockResolvedValue(reply({ models: [{ ...models[0], ...(mode === 'omni' ? { omniReference: { ...omni, maxImages: 1, maxVideos: 1, maxAudios: 1, maxTotal: 3 } } : {}) }, reviewOption] }));
  const imageUpload = vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages);
  const mixedUpload = vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia);
  imageUpload.mockImplementation(async ({ file }) => reply({ fileId: (file as File).name.split('.')[0] }));
  mixedUpload.mockImplementation(async ({ file }) => reply({ fileId: (file as File).name.split('.')[0], durationSeconds: 3, sizeBytes: 3 }));
  const media = [
    { id: 'first', name: 'first.png', kind: 'image' as const, mimeType: 'image/png' },
    { id: 'last', name: 'last.png', kind: 'image' as const, mimeType: 'image/png' },
    { id: 'video', name: 'video.mp4', kind: 'video' as const, mimeType: 'video/mp4' },
    { id: 'video2', name: 'video2.mp4', kind: 'video' as const, mimeType: 'video/mp4' },
    { id: 'audio', name: 'audio.mp3', kind: 'audio' as const, mimeType: 'audio/mpeg' },
    { id: 'large', name: 'large.png', kind: 'image' as const, mimeType: 'image/png' },
  ].map(item => ({ ...item, size: item.id === 'large' ? 21 * 1024 * 1024 : 3, content: new Blob(['png']) }));
  const receipts = media.map(item => ({ id: `review-${item.id}`, fileId: item.id, model: models[0].model, kind: item.kind.toUpperCase(), status: 'ACTIVE', durationSeconds: 3 }));
  vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValue(reply(receipts));
  vi.mocked(api.mediaPortraitGetGetApiV1MediaPortraitsPortraitId).mockImplementation(async id => reply(receipts.find(row => row.id === id)));
  render(<MemoryRouter><CreatorMediaProvider publicMedia={media}><ProductionVideoComposer /></CreatorMediaProvider></MemoryRouter>);
  await screen.findByRole('button', { name: '选择视频模型：电影模型 A' });
  chooseMode('视频生成模式', mode);
  fireEvent.click(screen.getByRole('button', { name: '添加素材' }));
  fireEvent.click(screen.getByRole('menuitem', { name: '素材库' })); fireEvent.click(screen.getByRole('tab', { name: '公开' }));
  expect(screen.getByRole('listbox')).toHaveAttribute('aria-multiselectable', 'true');
  fireEvent.click(screen.getByRole('option', { name: 'first.png' }));
  fireEvent.click(screen.getByRole('option', { name: 'last.png' }));
  expect(screen.getByRole('option', { name: 'large.png' })).toHaveAttribute('aria-disabled', 'true');
  if (mode === 'omni') {
    expect(screen.getByRole('option', { name: 'last.png' })).toHaveAttribute('aria-selected', 'false');
    expect(screen.getByText('图片最多选择 1 项')).toBeVisible();
    fireEvent.click(screen.getByRole('tab', { name: '视频' }));
    fireEvent.click(screen.getByRole('option', { name: 'video.mp4' })); fireEvent.click(screen.getByRole('option', { name: 'video2.mp4' }));
    expect(screen.getByText('视频最多选择 1 项')).toBeVisible();
    fireEvent.click(screen.getByRole('tab', { name: '音频' })); fireEvent.click(screen.getByRole('option', { name: 'audio.mp3' }));
  }
  fireEvent.click(screen.getByRole('button', { name: '确定' }));
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ references: mode === 'omni'
    ? [{ fileId: 'first', kind: 'IMAGE', durationSeconds: 3, portraitId: 'review-first' }, { fileId: 'video', kind: 'VIDEO', durationSeconds: 3, portraitId: 'review-video' }, { fileId: 'audio', kind: 'AUDIO', durationSeconds: 3, portraitId: 'review-audio' }]
    : [{ fileId: 'first' }, { fileId: 'last' }] })));
  expect(imageUpload).not.toHaveBeenCalled(); expect(mixedUpload).not.toHaveBeenCalled();
  expect(api.mediaPortraitSubmitPostApiV1MediaPortraits).not.toHaveBeenCalled();
  expect(submit).not.toHaveBeenCalled();
});

const mountAudio = async (limit = 15) => {
  options.mockResolvedValue(reply({ models: [{ ...models[0], omniReference: { ...omni, audioOnly: true, maxAudioDurationSeconds: limit } }, { ...models[1], omniReference: { ...omni, audioOnly: true, maxAudioDurationSeconds: 5 } }] }));
  await mount(); await screen.findByRole('button', { name: '选择视频模型：电影模型 A' });
};
const addAudio = (name = 'sound.wav', label = '上传素材') => fireEvent.change(screen.getByLabelText(label), { target: { files: [new File(['audio'], name, { type: 'audio/wav' })] } });

it('rejects local audio exceeding the selected model limit before uploading', async () => {
  vi.spyOn(HTMLMediaElement.prototype, 'duration', 'get').mockReturnValue(16);
  vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).mockResolvedValue(reply({ fileId: 'long-audio', durationSeconds: 16 }));
  await mountAudio(); addAudio('long.wav');
  expect(await screen.findByRole('alert')).toHaveTextContent('音频总时长不能超过 15 秒');
  expect(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).not.toHaveBeenCalled();
  expect(screen.queryByText(/参考音频合计最多/)).not.toBeInTheDocument();
});

it('counts existing audio and excludes the replaced item while preserving rejected files', async () => {
  vi.spyOn(HTMLMediaElement.prototype, 'duration', 'get').mockReturnValue(8);
  const upload = vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia);
  upload.mockResolvedValueOnce(reply({ fileId: 'first-audio', durationSeconds: 8 }));
  await mountAudio(); addAudio('first.wav');
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ references: [expect.objectContaining({ kind: 'AUDIO', durationSeconds: 8 })] })));
  addAudio('too-long.wav');
  expect(await screen.findByRole('alert')).toHaveTextContent('剩余 7 秒');
  expect(upload).toHaveBeenCalledTimes(1);
  vi.spyOn(HTMLMediaElement.prototype, 'duration', 'get').mockReturnValue(7);
  upload.mockResolvedValueOnce(reply({ fileId: 'replacement-audio', durationSeconds: 7 }));
  addAudio('replacement.wav', '替换音频1');
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ references: [expect.objectContaining({ fileId: 'replacement-audio', durationSeconds: 7 })] })));
  vi.spyOn(HTMLMediaElement.prototype, 'duration', 'get').mockReturnValue(8);
  upload.mockResolvedValueOnce(reply({ fileId: 'last-audio', durationSeconds: 8 }));
  addAudio('last.wav');
  await waitFor(() => expect(upload).toHaveBeenCalledTimes(3));
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ references: [
    { fileId: 'replacement-audio', kind: 'AUDIO', durationSeconds: 7, portraitId: 'review-replacement-audio' }, { fileId: 'last-audio', kind: 'AUDIO', durationSeconds: 8, portraitId: 'review-last-audio' },
  ] })));
});

it.each([16, null, 0, NaN])('rejects untrusted or excessive receipt duration %s without attaching audio', async durationSeconds => {
  vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).mockResolvedValue(reply({ fileId: 'invalid-audio', durationSeconds }));
  await mountAudio(); addAudio('invalid.wav');
  expect(await screen.findByRole('alert')).toHaveTextContent(durationSeconds === 16 ? '音频总时长不能超过 15 秒' : '素材缺少可信时长');
  expect(screen.queryByText('invalid.wav')).not.toBeInTheDocument();
  expect(quote).not.toHaveBeenCalled();
});

it('retains uploaded audio and rechecks it when choosing a lower-limit model', async () => {
  vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).mockResolvedValue(reply({ fileId: 'retained-audio', durationSeconds: 8 }));
  await mountAudio(); addAudio('retained.wav');
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ references: [expect.objectContaining({ kind: 'AUDIO', durationSeconds: 8 })] })));
  await chooseB();
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  expect(screen.getByLabelText('retained.wav')).toBeVisible();
  expect(screen.getByText('音频总时长不能超过 5 秒')).toBeVisible();
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
});

it('rechecks the refreshed model limit while an audio receipt is pending', async () => {
  let finish!: (value: never) => void;
  const upload = vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia);
  upload.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  await mountAudio(); addAudio('pending.wav');
  await waitFor(() => expect(upload).toHaveBeenCalledTimes(1));
  options.mockResolvedValue(reply({ models: [{ ...models[0], omniReference: { ...omni, audioOnly: true, maxAudioDurationSeconds: 3 } }] }));
  await act(async () => { fireEvent.focus(window); });
  await act(async () => finish(reply({ fileId: 'pending-audio', durationSeconds: 4 })));
  expect(await screen.findByRole('alert')).toHaveTextContent('音频总时长不能超过 3 秒');
  expect(screen.queryByText('pending.wav')).not.toBeInTheDocument();
});

it('uses the latest accumulated audio for sequential library batch uploads', async () => {
  vi.spyOn(HTMLMediaElement.prototype, 'duration', 'get').mockReturnValue(8);
  options.mockResolvedValue(reply({ models: [{ ...models[0], omniReference: { ...omni, audioOnly: true } }] }));
  const upload = vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia);
  upload.mockImplementation(async ({ file }) => reply({ fileId: (file as File).name.split('.')[0], durationSeconds: 8 }));
  const media = ['first', 'second'].map(id => ({ id, name: `${id}.wav`, kind: 'audio' as const, mimeType: 'audio/wav', size: 5, content: new Blob(['audio']) }));
  render(<MemoryRouter><CreatorMediaProvider publicMedia={media}><ProductionVideoComposer /></CreatorMediaProvider></MemoryRouter>);
  await screen.findByRole('button', { name: '选择视频模型：电影模型 A' });
  fireEvent.click(screen.getByRole('button', { name: '添加素材' }));
  fireEvent.click(screen.getByRole('menuitem', { name: '素材库' })); fireEvent.click(screen.getByRole('tab', { name: '公开' }));
  fireEvent.click(screen.getByRole('tab', { name: '音频' }));
  fireEvent.click(screen.getByRole('option', { name: 'first.wav' })); fireEvent.click(screen.getByRole('option', { name: 'second.wav' }));
  fireEvent.click(screen.getByRole('button', { name: '确定' }));
  await waitFor(() => expect(screen.getAllByRole('alert').some(item => item.textContent?.includes('剩余 7 秒'))).toBe(true));
  expect(upload).toHaveBeenCalledTimes(1);
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ references: [{ fileId: 'first', kind: 'AUDIO', durationSeconds: 8, portraitId: 'review-first' }] })));
});

it('waits for native metadata and checks refreshed limits before starting the upload', async () => {
  let pendingAudio!: HTMLMediaElement;
  vi.spyOn(HTMLMediaElement.prototype, 'src', 'set').mockImplementation(function (this: HTMLMediaElement) { pendingAudio = this; });
  await mountAudio(); addAudio('waiting.wav');
  expect(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).not.toHaveBeenCalled();
  options.mockResolvedValue(reply({ models: [{ ...models[0], omniReference: { ...omni, audioOnly: true, maxAudioDurationSeconds: 3 } }] }));
  await act(async () => { fireEvent.focus(window); });
  await act(async () => { pendingAudio.dispatchEvent(new Event('loadedmetadata')); });
  expect(await screen.findByRole('alert')).toHaveTextContent('音频总时长不能超过 3 秒');
  expect(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).not.toHaveBeenCalled();
});

it('keeps the composer usable when native audio metadata cannot be read', async () => {
  vi.spyOn(HTMLMediaElement.prototype, 'src', 'set').mockImplementation(function (this: HTMLMediaElement) { queueMicrotask(() => this.dispatchEvent(new Event('error'))); });
  await mountAudio(); addAudio('broken.wav');
  expect(await screen.findByRole('alert')).toHaveTextContent('无法读取音频时长');
  expect(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).not.toHaveBeenCalled();
  expect(screen.getByLabelText('上传素材')).toBeEnabled();
});

it('keeps only local upload and the original personal/public material library in the video menu', async () => {
  options.mockResolvedValue(reply({ models: [{ ...models[0], omniReference: omni }] }));
  await mount();
  await screen.findByRole('button', { name: '选择视频模型：电影模型 A' });
  fireEvent.click(screen.getByRole('button', { name: '添加素材' }));
  expect(screen.getAllByRole('menuitem').map(item => item.textContent?.trim())).toEqual(['＋ 本地上传', '素材库']);
  fireEvent.click(screen.getByRole('menuitem', { name: '素材库' }));
  expect(await screen.findByRole('tab', { name: '个人' })).toBeVisible();
  expect(screen.getByRole('tab', { name: '公开' })).toBeVisible();
  expect(api.mediaPortraitListGetApiV1MediaPortraits).not.toHaveBeenCalled();
});

it('refills an omni history task with ordered image video audio and waits for manual generation', async () => {
  const references = [{ fileId: 'ref-image', kind: 'IMAGE', portraitId: 'portrait-image', durationSeconds: null }, { fileId: 'ref-video', kind: 'VIDEO', portraitId: 'portrait-video', durationSeconds: 4 }, { fileId: 'ref-audio', kind: 'AUDIO', durationSeconds: 3 }];
  const receipts = references.map(ref => ({ ...ref, id: ref.portraitId ?? 'portrait-audio', model: models[0].model, status: 'ACTIVE' }));
  vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValue(reply(receipts));
  vi.mocked(api.mediaPortraitGetGetApiV1MediaPortraitsPortraitId).mockImplementation(async id => reply(receipts.find(row => row.id === id)));
  const saved = { id: 'edit-video', type: 'VIDEO', status: 'SUCCEEDED', prompt: '图片1配合视频1和音频1', model: models[0].model, aspectRatio: '9:16', resolution: '1080p', count: 1, durationSeconds: 8, referenceMode: 'OMNI', references, results: [{ index: 0, fileId: 'output' }] };
  options.mockResolvedValue(reply({ models: [{ ...models[0], omniReference: omni }] }));
  vi.mocked(api.mediaListGetApiV1MediaTasks).mockResolvedValue(reply([saved]));
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(saved));
  render(<App router={createMemoryRouter(userRoutes, { initialEntries: ['/create/video?tab=history'] })} />);
  fireEvent.click(await screen.findByRole('button', { name: '重新编辑' }));
  await waitFor(() => expect(screen.getByRole('textbox', { name: '视频提示词' })).toHaveValue(saved.prompt));
  expect(screen.getByRole('button', { name: '视频生成模式' })).toHaveValue('omni');
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ aspectRatio: '9:16', resolution: '1080p', durationSeconds: 8, referenceMode: 'OMNI', references: references.map(ref => expect.objectContaining(ref.kind === 'IMAGE' ? { fileId: ref.fileId, kind: ref.kind, portraitId: ref.portraitId } : ref)) })));
  expect(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).not.toHaveBeenCalled();
  expect(submit).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await waitFor(() => expect(submit).toHaveBeenCalledWith(expect.objectContaining({ prompt: saved.prompt, referenceMode: 'OMNI', references: references.map(ref => expect.objectContaining(ref.kind === 'IMAGE' ? { fileId: ref.fileId, kind: ref.kind, portraitId: ref.portraitId } : ref)) }), expect.anything()));
});

function chooseMode(label: string, value: string) {
  fireEvent.click(screen.getByRole('button', { name: label }));
  fireEvent.click(screen.getAllByRole('menuitemradio').find(item => item.getAttribute('value') === value)!);
}

it('displays model resolutions from low to high while preserving the selected value', async () => {
  options.mockResolvedValue(reply({ models: [{ ...models[0], resolutions: ['4K', '1080p', '480p', '720p'] }] }));
  await mount(); await screen.findByText('开始生成 · 5 积分');
  settings();
  const choices = within(screen.getByRole('group', { name: '分辨率' })).getAllByRole('button');
  expect(choices.map(button => button.textContent)).toEqual(['480p', '720p', '1080p', '4K']);
  fireEvent.click(screen.getByRole('button', { name: '1080p' })); close();
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ resolution: '1080p' })));
});

it('opens settings from the summary and quotes the configured 21:9 video ratio', async () => {
  options.mockResolvedValue(reply({ models: [{ ...models[0], aspectRatios: ['16:9','21:9'] }] }));
  await mount(); await screen.findByText('开始生成 · 5 积分');
  expect(screen.queryByRole('button', { name: '视频设置' })).not.toBeInTheDocument();
  settings(); fireEvent.click(screen.getByRole('button', { name: '21:9' }));
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ aspectRatio: '21:9' })));
});


const mountCommerceVideo = async (tool: 'video-replica' | 'seller-video') => {
  const view = render(<MemoryRouter><CreatorMediaProvider><ProductionVideoComposer commerceTool={tool} className="commerce-video-composer" /></CreatorMediaProvider></MemoryRouter>);
  await waitFor(() => expect(options).toHaveBeenCalled());
  return view;
};
it('commerce video requires product and motion references and submits the edited script through the real task SDK', async () => {
  vi.mocked(api.storageUploadProcessingVideoPostApiV1FilesProcessingVideos).mockResolvedValue(reply({ fileId: 'source-reference', durationSeconds: 4 }));
  vi.mocked(api.storageReferenceClipPostApiV1FilesFileIdReferenceClip).mockResolvedValue(reply({ fileId: 'reference', durationSeconds: 4, sizeBytes: 1000 }));
  options.mockResolvedValue(reply({ models: [{ ...models[0], omniReference: omni }, models[1]] }));
  vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).mockImplementation(async ({ file }) => reply({ fileId: (file as File).name.split('.')[0], durationSeconds: file.type.startsWith('image/') ? null : 4 }));
  await mountCommerceVideo('video-replica');
  fireEvent.change(screen.getByRole('textbox', { name: '商品名称' }), { target: { value: '猫砂除臭剂' } });
  fireEvent.change(screen.getByRole('textbox', { name: '商品卖点' }), { target: { value: '无香，280g' } });
  fireEvent.change(screen.getByRole('textbox', { name: '分镜 1 画面' }), { target: { value: '猫砂盆旁展示商品' } });
  fireEvent.click(screen.getByRole('button', { name: '应用到视频脚本' }));
  expect(readPromptInput(screen.getByRole('textbox', { name: '视频脚本' }))).toContain('猫砂盆旁展示商品');
  expect(screen.getByRole('button', { name: 'AI 拆解参考视频' })).toBeDisabled();
  expect(screen.getByRole('button', { name: /^生成复刻视频/ })).toBeDisabled();
  expect(quote).not.toHaveBeenCalled();
  for (const [name, type] of [['product.png', 'image/png'], ['reference.mp4', 'video/mp4']]) {
    fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['media'], name, { type })] } });
    await waitFor(() => expect(screen.queryByText(/正在上传并校验/)).not.toBeInTheDocument());
    if (name === 'product.png') expect(screen.getByRole('button', { name: /^生成复刻视频/ })).toBeDisabled();
  }
  await waitFor(() => expect(screen.getByRole('button', { name: /^生成复刻视频/ })).toBeEnabled());
  fireEvent.change(screen.getByRole('textbox', { name: '视频脚本' }), { target: { value: '使用图片中的商品，参考视频的环绕运镜。' } });
  fireEvent.click(screen.getByRole('button', { name: /^生成复刻视频/ }));
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
  expect(submit.mock.calls[0][0]).toMatchObject({ prompt: '使用图片中的商品，参考视频的环绕运镜。', referenceMode: 'OMNI', references: [
    { fileId: 'product', kind: 'IMAGE' }, { fileId: 'reference', kind: 'VIDEO', durationSeconds: 4 },
  ] });
});
it('commerce replica refuses models without video references and retains manual script editing', async () => {
  options.mockResolvedValue(reply({ models }));
  await mountCommerceVideo('video-replica');
  expect(await screen.findByRole('textbox', { name: '商品名称' })).toBeEnabled();
  expect(screen.getByRole('textbox', { name: '视频脚本' })).toBeEnabled();
  await waitFor(() => expect(screen.getByRole('button', { name: '选择视频模型：暂无可用模型' })).toBeDisabled());
  expect(screen.getByRole('button', { name: /^生成复刻视频/ })).toBeDisabled();
  expect(quote).not.toHaveBeenCalled(); expect(submit).not.toHaveBeenCalled();
});
it('commerce seller uses frame generation with a product image and never submits without it', async () => {
  options.mockResolvedValue(reply({ models: [...models, reviewOption] }));
  await mountCommerceVideo('seller-video');
  fireEvent.change(screen.getByRole('textbox', { name: '视频脚本' }), { target: { value: '展示商品外观和包装文字' } });
  expect(screen.getByRole('button', { name: /^生成带货视频/ })).toBeDisabled();
  expect(screen.getByRole('button', { name: '视频生成模式' })).toHaveValue('frames');
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['image'], 'product.png', { type: 'image/png' })] } });
  await waitFor(() => expect(screen.getByRole('button', { name: /^生成带货视频/ })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: /^生成带货视频/ }));
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
  expect(submit.mock.calls[0][1]).toMatchObject({ headers: { 'Idempotency-Key': expect.stringMatching(/^commerce-/) } });
  expect(submit.mock.calls[0][0]).toMatchObject({ prompt: '展示商品外观和包装文字', referenceMode: 'FIRST_LAST_FRAME', references: [{ fileId: 'file-real' }] });
});
it('commerce video saves separate owner/tool drafts without submitting on remount', async () => {
  localStorage.clear();
  vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue({ userId: 'owner', authenticated: true, status: 'ready', availablePoints4: '1000.0000' } as never);
  const first = await mountCommerceVideo('seller-video');
  fireEvent.change(screen.getByRole('textbox', { name: '商品名称' }), { target: { value: '持久保存的商品' } });
  fireEvent.change(screen.getByRole('textbox', { name: '视频脚本' }), { target: { value: '明天继续编辑' } });
  first.unmount();
  const second = await mountCommerceVideo('video-replica');
  expect(screen.getByRole('textbox', { name: '商品名称' })).toHaveValue('');
  second.unmount();
  const third = await mountCommerceVideo('seller-video');
  expect(screen.getByRole('textbox', { name: '商品名称' })).toHaveValue('持久保存的商品');
  expect(screen.getByRole('textbox', { name: '视频脚本' })).toHaveValue('明天继续编辑');
  third.unmount();
  vi.mocked(userSession.useOptionalUserSession).mockReturnValue({ userId: 'other', authenticated: true, status: 'ready', availablePoints4: '1000.0000' } as never);
  await mountCommerceVideo('seller-video');
  expect(screen.getByRole('textbox', { name: '商品名称' })).toHaveValue('');
  expect(screen.getByRole('textbox', { name: '视频脚本' })).toHaveValue('');
  expect(submit).not.toHaveBeenCalled();
  localStorage.clear();
});


it.each(['video-replica', 'seller-video'] as const)('commerce %s route opens a usable and accessible workspace', async tool => {
  options.mockResolvedValue(reply({ models: [{ ...models[0], omniReference: omni }] }));
  const router = createMemoryRouter(userRoutes, { initialEntries: [`/commerce/${tool}`] });
  const view = render(<App router={router} />);
  expect(await screen.findByRole('heading', { level: 1, name: tool === 'video-replica' ? '视频复刻' : '带货视频' })).toBeVisible();
  await waitFor(() => expect(screen.getByRole('textbox', { name: '商品名称' })).toBeEnabled());
  expect(within(screen.getByRole('navigation', { name: '电商工具' })).getAllByRole('link')).toHaveLength(8);
  fireEvent.click(screen.getByRole('button', { name: '添加分镜' }));
  fireEvent.change(screen.getByRole('textbox', { name: '分镜 2 画面' }), { target: { value: '第二个镜头展示包装' } });
  fireEvent.click(screen.getByRole('button', { name: '删除分镜 1' }));
  expect(screen.getByRole('textbox', { name: '分镜 1 画面' })).toHaveValue('第二个镜头展示包装');
  expect((await axe(view.container)).violations).toEqual([]);
  expect(submit).not.toHaveBeenCalled();
});
it('commerce restores product references and edited parameters with fresh previews without creating a task', async () => {
  localStorage.clear();
  vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue({ userId: 'owner', authenticated: true, status: 'ready', availablePoints4: '1000.0000' } as never);
  const first = await mountCommerceVideo('seller-video');
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['image'], 'saved-product.png', { type: 'image/png' })] } });
  await waitFor(() => expect(quote).toHaveBeenCalledWith(expect.objectContaining({ references: [{ fileId: 'file-real' }] })));
  fireEvent.change(screen.getByRole('textbox', { name: '视频脚本' }), { target: { value: '持续保存脚本' } });
  settings(); fireEvent.click(screen.getByRole('button', { name: '9:16' })); close();
  const stored = localStorage.getItem('xzwl-commerce-video:owner:seller-video')!;
  expect(stored).toContain('file-real'); expect(stored).not.toContain('https://assets.example');
  first.unmount();
  vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockResolvedValue(reply({ url: 'https://assets.example/fresh.png' }));
  await mountCommerceVideo('seller-video');
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ aspectRatio: '9:16', references: [{ fileId: 'file-real' }] })));
  expect(screen.getByRole('textbox', { name: '视频脚本' })).toHaveValue('持续保存脚本');
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).toHaveBeenCalledTimes(1);
  expect(submit).not.toHaveBeenCalled();
  localStorage.clear();
});
it('commerce recovers an uncertain original request instead of submitting a different saved draft', async () => {
  localStorage.clear();
  vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue({ userId: 'owner', authenticated: true, status: 'ready', availablePoints4: '1000.0000' } as never);
  const draftView = await mountCommerceVideo('video-replica');
  fireEvent.change(screen.getByRole('textbox', { name: '视频脚本' }), { target: { value: '独立工具草稿' } });
  draftView.unmount();
  const request = { requestRef: 'commerce-original-paid-key', settings: { type: 'VIDEO', model: models[0].model, resolution: '720p', aspectRatio: '16:9', count: 1, durationSeconds: 5 }, prompt: '原请求脚本', inputAssetRefs: [] };
  sessionStorage.setItem('xzwl-commerce-video-submission:owner', JSON.stringify({ key: request.requestRef, request, state: 'UNCERTAIN', snapshot: { ...request.settings, prompt: request.prompt } }));
  await mountCommerceVideo('video-replica');
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
  expect(submit.mock.calls[0][0]).toMatchObject({ prompt: '原请求脚本', model: models[0].model, references: [] });
  expect(submit.mock.calls[0][1]).toMatchObject({ headers: { 'Idempotency-Key': 'commerce-original-paid-key' } });
  await waitFor(() => expect(screen.getByRole('textbox', { name: '视频脚本' })).toHaveValue('独立工具草稿'));
  expect(JSON.parse(localStorage.getItem('xzwl-commerce-video:owner:video-replica')!).prompt).toBe('独立工具草稿');
  localStorage.clear();
});

it('commerce never restores or displays ordinary video submissions', async () => {
  vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue({ userId: 'owner', authenticated: true, status: 'ready', availablePoints4: '1000.0000' } as never);
  const request = { requestRef: 'ordinary-pending', settings: { ...models[0], count: 1, durationSeconds: 5, resolution: '720p', aspectRatio: '16:9' }, prompt: '普通待提交视频', inputAssetRefs: [] };
  sessionStorage.setItem('xzwl-video-submission:owner', JSON.stringify({ key: request.requestRef, request, state: 'UNCERTAIN', snapshot: { ...request.settings, prompt: request.prompt } }));
  await mountCommerceVideo('seller-video');
  await waitFor(() => expect(api.mediaListGetApiV1MediaTasks).toHaveBeenCalledWith({ limit: 20, excludeCanvas: true, commerceOnly: true }));
  expect(submit).not.toHaveBeenCalled();
  expect(sessionStorage.getItem('xzwl-video-submission:owner')).toContain('ordinary-pending');
});

it('API账户保留视频历史预览，但禁用生成并显示指定提示', async () => {
  vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue({ userId: 'owner', accountType: 'API', authenticated: true, status: 'ready', currentKey: { enabled: true, kind: '媒体 Key', relationship: 'Owner' }, availablePoints4: '1000.0000' } as never);
  const saved = { id: 'api-history', type: 'VIDEO', status: 'SUCCEEDED', prompt: 'API用户的历史视频', results: [{ fileId: 'saved-file', index: 0 }] };
  vi.mocked(api.mediaListGetApiV1MediaTasks).mockResolvedValue(reply([saved]));
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(saved));
  render(<MemoryRouter><CreatorMediaProvider><ProductionVideoComposer /></CreatorMediaProvider></MemoryRouter>);
  expect(await screen.findByRole('article', { name: '视频任务：API用户的历史视频' })).toBeVisible();
  expect(await screen.findByText('当前用户为api用户，可在查看媒体记录，但不能生成视频。')).toBeVisible();
  editPromptInput(screen.getByRole('textbox', { name: '视频提示词' }), '不应生成');
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  expect(screen.queryByRole('button', { name: '重新生成' })).not.toBeInTheDocument();
  fireEvent.submit(screen.getByRole('button', { name: /^开始生成/ }).closest('form')!);
  expect(api.mediaSubmitPostApiV1MediaTasks).not.toHaveBeenCalled();
  await waitFor(() => expect(screen.getByLabelText('生成视频 1')).toHaveAttribute('src', 'https://assets.example/frame.png'));
});

it('切换为API账户后不会重试未确认的视频生成', async () => {
  const ready = { userId: 'owner', accountType: 'PERSONAL', authenticated: true, status: 'ready', currentKey: { enabled: true, kind: '媒体 Key', relationship: 'Owner' }, availablePoints4: '1000.0000' };
  const session = vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue(ready as never);
  const view = render(<MemoryRouter><CreatorMediaProvider><ProductionVideoComposer /></CreatorMediaProvider></MemoryRouter>);
  await screen.findByText(/^开始生成 · [0-9.]+ 积分$/);
  editPromptInput(screen.getByRole('textbox', { name: '视频提示词' }), '保留未确认请求');
  submit.mockRejectedValueOnce(new TypeError('disconnected'));
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await screen.findByRole('button', { name: '使用原请求重试' });
  session.mockReturnValue({ ...ready, accountType: 'API' } as never);
  view.rerender(<MemoryRouter><CreatorMediaProvider><ProductionVideoComposer /></CreatorMediaProvider></MemoryRouter>);
  expect(screen.getByRole('button', { name: '使用原请求重试' })).toBeDisabled();
  fireEvent.submit(screen.getByRole('button', { name: '使用原请求重试' }).closest('form')!);
  expect(submit).toHaveBeenCalledTimes(1);
  view.unmount();
  render(<MemoryRouter><CreatorMediaProvider><ProductionVideoComposer /></CreatorMediaProvider></MemoryRouter>);
  await screen.findByText('当前用户为api用户，可在查看媒体记录，但不能生成视频。');
  await act(async () => {});
  expect(submit).toHaveBeenCalledTimes(1);
});

it('AI video breakdown uploads ordered frames, fills target-product shots and explicitly applies the script', async () => {
  localStorage.clear();
  vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue({ userId: 'owner', authenticated: true, status: 'ready', availablePoints4: '1000.0000' } as never);
  options.mockResolvedValue(reply({ models: [{ ...models[0], omniReference: omni }] }));
  vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).mockResolvedValue(reply({ fileId: 'reference', durationSeconds: 4 }));
  vi.mocked(api.storageVideoContentGetApiV1FilesFileIdVideoContent).mockResolvedValue(reply({ mimeType: 'video/mp4', base64: 'dmlkZW8=' }));
  vi.mocked(videoFrames.extractVideoFrames).mockResolvedValue({ durationSeconds: 4, frames: [{ timestamp: 0.5, file: new File(['jpeg'], 'frame.jpg', { type: 'image/jpeg' }) }] });
  vi.mocked(api.mediaVideoBreakdownPostApiV1MediaProductSuitesVideoBreakdown).mockResolvedValue(reply({ shots: [{ startSeconds: 0.5, visual: '一只手握商品图中的香水瓶，保持原镜头构图。', narration: '' }] }));
  const view = await mountCommerceVideo('video-replica');
  expect(screen.getByRole('button', { name: 'AI 拆解参考视频' })).toBeDisabled();
  fireEvent.change(screen.getByRole('textbox', { name: '商品名称' }), { target: { value: '香水' } });
  fireEvent.change(screen.getByRole('textbox', { name: '视频脚本' }), { target: { value: '保留原脚本' } });
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['media'], 'reference.mp4', { type: 'video/mp4' })] } });
  await waitFor(() => expect(screen.getByRole('button', { name: 'AI 拆解参考视频' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: 'AI 拆解参考视频' }));
  await waitFor(() => expect(screen.getByRole('textbox', { name: '分镜 1 画面' })).toHaveValue('一只手握商品图中的香水瓶，保持原镜头构图。'));
  expect(api.storageVideoContentGetApiV1FilesFileIdVideoContent).toHaveBeenCalledWith('reference');
  expect(videoFrames.extractVideoFrames).toHaveBeenCalledWith('blob:reference-audio');
  expect(api.mediaVideoBreakdownPostApiV1MediaProductSuitesVideoBreakdown).toHaveBeenCalledWith(expect.objectContaining({ productName: '香水', timestamps: [0.5], durationSeconds: 4, fileIds: ['file-real'] }));
  expect(readPromptInput(screen.getByRole('textbox', { name: '视频脚本' }))).toBe('保留原脚本');
  fireEvent.click(screen.getByRole('button', { name: '替换当前视频脚本' }));
  const applied = readPromptInput(screen.getByRole('textbox', { name: '视频脚本' }));
  expect(applied).toContain('从第一帧开始');
  expect(applied).toContain('每个镜头');
  expect(applied).toContain('商品图片');
  expect(applied).toContain('商品：香水');
  expect(applied).toContain('分镜 1：一只手握商品图中的香水瓶');
  expect(applied).not.toContain('0.50s');
  expect(submit).not.toHaveBeenCalled();
  vi.mocked(api.mediaVideoBreakdownPostApiV1MediaProductSuitesVideoBreakdown).mockResolvedValue(reply({ shots: [{ startSeconds: 0.5, visual: null, narration: '' }] }));
  fireEvent.click(screen.getByRole('button', { name: 'AI 拆解参考视频' }));
  await screen.findByText('参考视频拆解失败，请重试或手动编辑分镜。');
  expect(screen.getByRole('textbox', { name: '分镜 1 画面' })).toHaveValue('一只手握商品图中的香水瓶，保持原镜头构图。');
  expect(readPromptInput(screen.getByRole('textbox', { name: '视频脚本' }))).toBe(applied);
  vi.mocked(api.mediaVideoBreakdownPostApiV1MediaProductSuitesVideoBreakdown).mockRejectedValue(new Error('upstream'));
  fireEvent.click(screen.getByRole('button', { name: 'AI 拆解参考视频' }));
  await screen.findByText('参考视频拆解失败，请重试或手动编辑分镜。');
  expect(screen.getByRole('textbox', { name: '分镜 1 画面' })).toHaveValue('一只手握商品图中的香水瓶，保持原镜头构图。');
  expect(readPromptInput(screen.getByRole('textbox', { name: '视频脚本' }))).toBe(applied);
  let finish!: (value: ReturnType<typeof reply>) => void;
  vi.mocked(api.mediaVideoBreakdownPostApiV1MediaProductSuitesVideoBreakdown).mockImplementation(() => new Promise(resolve => { finish = resolve; }));
  fireEvent.click(screen.getByRole('button', { name: 'AI 拆解参考视频' }));
  await waitFor(() => expect(api.mediaVideoBreakdownPostApiV1MediaProductSuitesVideoBreakdown).toHaveBeenCalledTimes(4));
  vi.mocked(userSession.useOptionalUserSession).mockReturnValue({ userId: 'other-owner', authenticated: true, status: 'ready', availablePoints4: '1000.0000' } as never);
  view.rerender(<MemoryRouter><CreatorMediaProvider><ProductionVideoComposer commerceTool="video-replica" className="commerce-video-composer" /></CreatorMediaProvider></MemoryRouter>);
  await act(async () => finish(reply({ shots: [{ startSeconds: 0.5, visual: '旧账号迟到结果', narration: '' }] })));
  expect(screen.getByRole('textbox', { name: '分镜 1 画面' })).toHaveValue('');
});

it('stops frame uploads permanently when account identity refreshes during extraction', async () => {
  localStorage.clear();
  const ready = { userId: 'owner', authenticated: true, status: 'ready', availablePoints4: '1000.0000' } as const;
  vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue(ready as never);
  options.mockResolvedValue(reply({ models: [{ ...models[0], omniReference: omni }] }));
  vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).mockResolvedValue(reply({ fileId: 'reference', durationSeconds: 4 }));
  vi.mocked(api.storageVideoContentGetApiV1FilesFileIdVideoContent).mockResolvedValue(reply({ mimeType: 'video/mp4', base64: 'dmlkZW8=' }));
  let finish!: (value: Awaited<ReturnType<typeof videoFrames.extractVideoFrames>>) => void;
  vi.mocked(videoFrames.extractVideoFrames).mockImplementation(() => new Promise(resolve => { finish = resolve; }));
  const view = await mountCommerceVideo('video-replica');
  await waitFor(() => expect(screen.getByLabelText('上传素材')).toBeEnabled());
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['media'], 'reference.mp4', { type: 'video/mp4' })] } });
  await waitFor(() => expect(screen.getByRole('button', { name: 'AI 拆解参考视频' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: 'AI 拆解参考视频' }));
  await waitFor(() => expect(videoFrames.extractVideoFrames).toHaveBeenCalled());
  vi.mocked(userSession.useOptionalUserSession).mockReturnValue({ ...ready, status: 'loading' } as never);
  const rerender = () => view.rerender(<MemoryRouter><CreatorMediaProvider><ProductionVideoComposer commerceTool="video-replica" className="commerce-video-composer" /></CreatorMediaProvider></MemoryRouter>);
  rerender();
  vi.mocked(userSession.useOptionalUserSession).mockReturnValue(ready as never);
  rerender();
  await act(async () => finish({ durationSeconds: 4, frames: [{ timestamp: 0.5, file: new File(['jpeg'], 'frame.jpg', { type: 'image/jpeg' }) }] }));
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).not.toHaveBeenCalled();
  expect(api.mediaVideoBreakdownPostApiV1MediaProductSuitesVideoBreakdown).not.toHaveBeenCalled();
});


it.each([5, 45])('prepares a %s second replica original before review and keeps it for breakdown', async (duration) => {
  localStorage.clear();
  vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue({ userId: 'owner', authenticated: true, status: 'ready', currentKey: { enabled: true, kind: '媒体 Key', relationship: 'Owner' }, availablePoints4: '1000.0000' } as never);
  options.mockResolvedValue(reply({ models: [{ ...models[0], omniReference: omni }] }));
  vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).mockResolvedValue(reply({ fileId: 'product' }));
  vi.mocked(api.storageUploadProcessingVideoPostApiV1FilesProcessingVideos).mockResolvedValue(reply({ fileId: 'full-original', durationSeconds: duration }));
  let finishClip!: (value: never) => void;
  vi.mocked(api.storageReferenceClipPostApiV1FilesFileIdReferenceClip).mockImplementation(() => new Promise(resolve => { finishClip = resolve; }));
  vi.mocked(api.storageVideoContentGetApiV1FilesFileIdVideoContent).mockResolvedValue(reply({ mimeType: 'video/mp4', base64: 'dmlkZW8=' }));
  vi.mocked(videoFrames.extractVideoFrames).mockResolvedValue({ durationSeconds: duration, frames: [{ timestamp: duration - 3, file: new File(['jpeg'], 'late-frame.jpg', { type: 'image/jpeg' }) }] });
  vi.mocked(api.mediaVideoBreakdownPostApiV1MediaProductSuitesVideoBreakdown).mockResolvedValue(reply({ shots: [{ startSeconds: duration - 3, visual: '香水展示', narration: '' }] }));
  await mountCommerceVideo('video-replica');
  fireEvent.change(screen.getByRole('textbox', { name: '视频脚本' }), { target: { value: '保留手工脚本' } });
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['image'], 'product.png', { type: 'image/png' })] } });
  await waitFor(() => expect(api.mediaPortraitSubmitPostApiV1MediaPortraits).toHaveBeenCalledWith(expect.objectContaining({ fileId: 'product' }), expect.anything()));
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['video'], 'long.mp4', { type: 'video/mp4' })] } });
  await waitFor(() => expect(api.storageReferenceClipPostApiV1FilesFileIdReferenceClip).toHaveBeenCalledWith('full-original', { maxDurationSeconds: 15 }));
  expect(api.mediaPortraitSubmitPostApiV1MediaPortraits).not.toHaveBeenCalledWith(expect.objectContaining({ fileId: 'full-original' }), expect.anything());
  expect(quote).not.toHaveBeenCalled();
  expect(screen.getByRole('button', { name: /^生成复刻视频/ })).toBeDisabled();
  await act(async () => finishClip(reply({ fileId: 'bounded-clip', durationSeconds: Math.min(duration, 15), sizeBytes: 1000 })));
  await waitFor(() => expect(api.mediaPortraitSubmitPostApiV1MediaPortraits).toHaveBeenCalledWith(expect.objectContaining({ fileId: 'bounded-clip' }), expect.anything()));
  await waitFor(() => expect(screen.getByRole('button', { name: /^生成复刻视频/ })).toBeEnabled());
  expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ references: expect.arrayContaining([expect.objectContaining({ fileId: 'bounded-clip', kind: 'VIDEO', durationSeconds: Math.min(duration, 15) })]) }));
  expect(api.mediaPortraitSubmitPostApiV1MediaPortraits).toHaveBeenCalledWith(expect.objectContaining({ fileId: 'bounded-clip' }), expect.anything());
  expect(localStorage.getItem('xzwl-commerce-video:owner:video-replica')).toContain('"sourceFileId":"full-original"');
  fireEvent.click(screen.getByRole('button', { name: 'AI 拆解参考视频' }));
  await waitFor(() => expect(api.mediaVideoBreakdownPostApiV1MediaProductSuitesVideoBreakdown).toHaveBeenCalledWith(expect.objectContaining({ durationSeconds: duration, timestamps: [duration - 3] })));
  expect(api.storageVideoContentGetApiV1FilesFileIdVideoContent).toHaveBeenCalledWith('full-original');
  expect(readPromptInput(screen.getByRole('textbox', { name: '视频脚本' }))).toBe('保留手工脚本');
  expect(submit).not.toHaveBeenCalled();
});

it('reclips restored replicas from their originals and shares the total budget after a model switch', async () => {
  localStorage.clear();
  vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue({ userId: 'owner', authenticated: true, status: 'ready', currentKey: { enabled: true, kind: '媒体 Key', relationship: 'Owner' }, availablePoints4: '1000.0000' } as never);
  options.mockResolvedValue(reply({ models: [{ ...models[0], omniReference: omni }, { ...models[1], omniReference: { ...omni, maxVideoDurationSeconds: 6 } }] }));
  localStorage.setItem('xzwl-commerce-video:owner:video-replica', JSON.stringify({ brief: { product: '香水', sellingPoints: '', platform: '抖音电商', language: '中文', style: '商品展示', shots: [{ visual: '', narration: '' }] }, prompt: '按原分镜展示香水',
    selection: null, mode: 'omni', bindings: [], materials: [{ fileId: 'product', name: 'product.png', preview: '', kind: 'IMAGE', reviewStatus: 'ACTIVE', reviewId: 'product-review' },
      ...['first', 'second'].map(name => ({ fileId: name, sourceFileId: `original-${name}`, name: `${name}.mp4`, preview: '', kind: 'VIDEO', durationSeconds: 20, reviewStatus: 'ACTIVE', reviewId: `old-${name}` }))] }));
  vi.mocked(api.storageReferenceClipPostApiV1FilesFileIdReferenceClip).mockImplementation(async (fileId, body) => reply({ fileId: `${fileId}-${body.maxDurationSeconds}`, durationSeconds: body.maxDurationSeconds, sizeBytes: 1000 }));
  await mountCommerceVideo('video-replica');
  await waitFor(() => expect(quote).toHaveBeenCalledWith(expect.objectContaining({ references: expect.arrayContaining([
    expect.objectContaining({ fileId: 'original-first-7', durationSeconds: 7 }), expect.objectContaining({ fileId: 'original-second-7', durationSeconds: 7 })]) })));
  expect(api.storageReferenceClipPostApiV1FilesFileIdReferenceClip).toHaveBeenCalledTimes(2);
  await chooseB();
  await waitFor(() => expect(quote).toHaveBeenCalledWith(expect.objectContaining({ model: models[1].model, references: expect.arrayContaining([
    expect.objectContaining({ fileId: 'original-first-3', durationSeconds: 3 }), expect.objectContaining({ fileId: 'original-second-3', durationSeconds: 3 })]) })));
  expect(api.storageReferenceClipPostApiV1FilesFileIdReferenceClip).toHaveBeenCalledWith('original-first', { maxDurationSeconds: 3 });
  expect(api.storageReferenceClipPostApiV1FilesFileIdReferenceClip).toHaveBeenCalledWith('original-second', { maxDurationSeconds: 3 });
  expect(api.storageReferenceClipPostApiV1FilesFileIdReferenceClip).toHaveBeenCalledTimes(4);
  expect(readPromptInput(screen.getByRole('textbox', { name: '视频脚本' }))).toBe('按原分镜展示香水');
  expect(submit).not.toHaveBeenCalled();
});

it('retains the original on clip failure and discards a retry result after changing accounts', async () => {
  localStorage.clear();
  const session = vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue({ userId: 'owner', authenticated: true, status: 'ready' } as never);
  options.mockResolvedValue(reply({ models: [{ ...models[0], omniReference: omni }] }));
  localStorage.setItem('xzwl-commerce-video:owner:video-replica', JSON.stringify({ brief: { product: '香水', sellingPoints: '', platform: '抖音电商', language: '中文', style: '商品展示', shots: [{ visual: '', narration: '' }] }, prompt: '保留手改脚本',
    selection: null, mode: 'omni', bindings: [], materials: [{ fileId: 'long-original', name: 'long.mp4', preview: '', kind: 'VIDEO', durationSeconds: 45 }] }));
  const clip = vi.mocked(api.storageReferenceClipPostApiV1FilesFileIdReferenceClip);
  clip.mockRejectedValueOnce(new Error('clip failed'));
  const view = await mountCommerceVideo('video-replica');
  await screen.findByText('参考视频处理失败，请重试');
  expect(readPromptInput(screen.getByRole('textbox', { name: '视频脚本' }))).toBe('保留手改脚本');
  expect(localStorage.getItem('xzwl-commerce-video:owner:video-replica')).toContain('long-original');
  expect(api.mediaPortraitSubmitPostApiV1MediaPortraits).not.toHaveBeenCalled();
  expect(quote).not.toHaveBeenCalled();
  let finish!: (value: never) => void;
  clip.mockImplementation(() => new Promise(resolve => { finish = resolve; }));
  fireEvent.click(screen.getByRole('button', { name: '重新获取模型与报价' }));
  await waitFor(() => expect(clip).toHaveBeenCalledTimes(2));
  session.mockReturnValue({ userId: 'other-owner', authenticated: true, status: 'ready' } as never);
  view.rerender(<MemoryRouter><CreatorMediaProvider><ProductionVideoComposer commerceTool="video-replica" className="commerce-video-composer" /></CreatorMediaProvider></MemoryRouter>);
  await act(async () => finish(reply({ fileId: 'stale-clip', durationSeconds: 15, sizeBytes: 1000 })));
  expect(localStorage.getItem('xzwl-commerce-video:other-owner:video-replica')).not.toContain('stale-clip');
  expect(readPromptInput(screen.getByRole('textbox', { name: '视频脚本' }))).toBe('');
  expect(api.mediaPortraitSubmitPostApiV1MediaPortraits).not.toHaveBeenCalled();
});

it('finishes a long-video upload batch and reuses its unreviewed original from the personal library', async () => {
  localStorage.clear();
  vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue({ userId: 'owner', authenticated: true, status: 'ready', currentKey: { enabled: true, kind: '媒体 Key', relationship: 'Owner' }, availablePoints4: '1000.0000' } as never);
  options.mockResolvedValue(reply({ models: [{ ...models[0], omniReference: omni }] }));
  const records = new Map<string, import('./types').CreatorMedia>();
  render(<MemoryRouter><CreatorMediaProvider storage={{ storageMode: 'session', reason: 'forced', repository: {
    list: async () => [...records.values()], put: async media => { records.set(media.id, media); }, remove: async id => { records.delete(id); }, close: () => {},
  } }} objectUrls={{ create: () => 'blob:library-preview', revoke: () => {} }}><ProductionVideoComposer commerceTool="video-replica" className="commerce-video-composer" /></CreatorMediaProvider></MemoryRouter>);
  vi.mocked(api.storageUploadProcessingVideoPostApiV1FilesProcessingVideos).mockResolvedValue(reply({ fileId: 'library-original', durationSeconds: 45 }));
  vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).mockResolvedValue(reply({ fileId: 'batch-product' }));
  const clip = vi.mocked(api.storageReferenceClipPostApiV1FilesFileIdReferenceClip);
  clip.mockResolvedValueOnce(reply({ fileId: 'library-clip', durationSeconds: 15, sizeBytes: 1000 })).mockResolvedValueOnce(reply({ fileId: 'library-reclip', durationSeconds: 15, sizeBytes: 1000 }));
  await waitFor(() => expect(screen.getByLabelText('上传素材')).toBeEnabled());
  fireEvent.change(screen.getByRole('textbox', { name: '视频脚本' }), { target: { value: '长片复刻' } });
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['video'], '长原片.mp4', { type: 'video/mp4' }), new File(['image'], '商品.png', { type: 'image/png' })] } });
  await waitFor(() => expect(screen.getByRole('button', { name: /^生成复刻视频/ })).toBeEnabled());
  await waitFor(() => expect(records.get('library-original')?.canvasFileIds).toEqual(['library-original']));
  fireEvent.click(screen.getByRole('button', { name: '添加素材' }));
  fireEvent.click(screen.getByRole('button', { name: '移除视频1' }));
  fireEvent.click(screen.getByRole('menuitem', { name: '素材库' }));
  const library = await screen.findByRole('dialog', { name: '素材库' });
  fireEvent.click(within(library).getByRole('tab', { name: '个人' }));
  fireEvent.click(within(library).getByRole('tab', { name: '视频' }));
  fireEvent.click(await within(library).findByRole('option', { name: '长原片.mp4' }));
  fireEvent.click(within(library).getByRole('button', { name: '确定' }));
  await waitFor(() => expect(clip).toHaveBeenCalledTimes(2));
  expect(clip).toHaveBeenLastCalledWith('library-original', { maxDurationSeconds: 15 });
  await waitFor(() => expect(screen.getByRole('button', { name: /^生成复刻视频/ })).toBeEnabled());
  expect(api.storageUploadProcessingVideoPostApiV1FilesProcessingVideos).toHaveBeenCalledTimes(1);
  expect(api.mediaPortraitSubmitPostApiV1MediaPortraits).not.toHaveBeenCalledWith(expect.objectContaining({ fileId: 'library-original' }), expect.anything());
  expect(submit).not.toHaveBeenCalled();
});

it('reviews new material with the selected omni model', async () => {
  vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).mockResolvedValue(reply({ fileId: 'selected-audio', durationSeconds: 4 }));
  await mountAudio();
  await chooseB();
  addAudio('selected-model.wav');
  await waitFor(() => expect(api.mediaPortraitSubmitPostApiV1MediaPortraits).toHaveBeenCalledWith(
    expect.objectContaining({ model: models[1].model }), expect.anything()));
});

it.each(['/create/video', '/commerce/seller-video', '/create/video?tab=history'])('restores enhancement on video generation and history on %s', async route => {
  const saved = { id: 'delivered-video', type: 'VIDEO', operation: 'GENERATION', status: 'SUCCEEDED', prompt: '已增强的成品视频',
    modelName: '提交模型', results: [{ index: 0, fileId: 'enhanced-output' }] };
  vi.mocked(api.mediaListGetApiV1MediaTasks).mockResolvedValue(reply([saved]));
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(saved));
  render(<App router={createMemoryRouter(userRoutes, { initialEntries: [route] })} />);
  await screen.findByRole('article', { name: '视频任务：已增强的成品视频' });
  await waitFor(() => expect(screen.getByLabelText('生成视频 1')).toHaveAttribute('src'));
  if (route.startsWith('/commerce/')) expect(screen.queryByRole('button', { name: '超分增强' })).not.toBeInTheDocument();
  else expect(screen.getByRole('button', { name: '超分增强' })).toBeVisible();
});
