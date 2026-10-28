import * as userSession from '../userSession';
import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { ProductionImageComposer } from './ProductionImageComposer';
import { ProductionVideoComposer } from './ProductionVideoComposer';
import * as api from '../api';
import { CreatorMediaProvider } from './CreatorMediaProvider';


vi.mock('../api', async original => ({ ...await original<typeof import('../api')>(),
  storageMatchImagePostApiV1FilesFileIdMatch: vi.fn(),
  mediaOptionsGetApiV1MediaGenerationOptions: vi.fn(), mediaQuotePostApiV1MediaQuotes: vi.fn(), mediaListGetApiV1MediaTasks: vi.fn(),
  mediaSubmitPostApiV1MediaTasks: vi.fn(), mediaGetGetApiV1MediaTasksTaskId: vi.fn(),
  mediaPortraitListGetApiV1MediaPortraits: vi.fn(), mediaPortraitSubmitPostApiV1MediaPortraits: vi.fn(), mediaPortraitGetGetApiV1MediaPortraitsPortraitId: vi.fn(),
  storageUploadReferenceImagePostApiV1FilesReferenceImages: vi.fn(), storageUploadReferenceMediaPostApiV1FilesReferenceMedia: vi.fn(), storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl: vi.fn(),
}));
const reply = (data: unknown) => ({ data, status: 200, headers: new Headers() }) as never;
const base = { resolutions: ['720p'], aspectRatios: ['16:9'], maxCount: 1, minDurationSeconds: 4, maxDurationSeconds: 15, durationStepSeconds: 1, maxReferenceImages: 2, referenceFollowsFirstFrame: false };
const image = { ...base, type: 'IMAGE', model: 'image-model', displayName: '图片模型' };
const video = { ...base, type: 'VIDEO', model: 'review-model', displayName: '视频模型', omniReference: { maxImages: 9, maxVideos: 3, maxAudios: 3, maxTotal: 12, maxVideoDurationSeconds: 15, maxAudioDurationSeconds: 15, maxVideoBytes: 50 * 1024 * 1024, maxAudioBytes: 15 * 1024 * 1024, audioOnly: true } };
const receipt = (kind = 'IMAGE', status = 'PROCESSING') => ({ id: 'review-one', fileId: 'file-one', model: video.model, status, kind, durationSeconds: kind === 'IMAGE' ? null : 4 });
const review = vi.mocked(api.mediaPortraitSubmitPostApiV1MediaPortraits);
const get = vi.mocked(api.mediaPortraitGetGetApiV1MediaPortraitsPortraitId);
beforeEach(() => {
  vi.resetAllMocks(); sessionStorage.clear(); localStorage.clear();
  vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue({ userId: 'owner', accountType: 'PERSONAL', authenticated: true, status: 'ready', currentKey: { enabled: true, kind: '媒体 Key', relationship: 'Owner' }, availablePoints4: '1000.0000' } as never);
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  vi.stubGlobal('URL', class extends URL { static createObjectURL() { return 'blob:review'; } static revokeObjectURL() {} });
  vi.spyOn(HTMLMediaElement.prototype, 'duration', 'get').mockReturnValue(4);
  vi.spyOn(HTMLMediaElement.prototype, 'src', 'set').mockImplementation(function (this: HTMLMediaElement) { queueMicrotask(() => this.dispatchEvent(new Event('loadedmetadata'))); });
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({ models: [image, video] }));
  vi.mocked(api.mediaQuotePostApiV1MediaQuotes).mockResolvedValue(reply({ points: '1.0000' }));
  vi.mocked(api.mediaListGetApiV1MediaTasks).mockResolvedValue(reply([]));
  vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValue(reply([]));
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockResolvedValue(reply({ fileId: 'file-one' }));
  vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).mockResolvedValue(reply({ fileId: 'file-one', durationSeconds: 4 }));
  vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockResolvedValue(reply({ url: 'https://assets.example/reference' }));
  vi.mocked(api.mediaSubmitPostApiV1MediaTasks).mockResolvedValue(reply({ id: 'task-one' }));
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ id: 'task-one', status: 'RUNNING' }));
  review.mockImplementation(async body => reply({ ...body, id: 'review-one', status: 'ACTIVE', kind: body.kind ?? 'IMAGE', durationSeconds: body.kind === 'IMAGE' ? null : 4 })); get.mockResolvedValue(reply(receipt('IMAGE', 'ACTIVE')));
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

const mount = async (type: 'IMAGE' | 'VIDEO', scope: 'home' | 'commerce' | 'create') => {
  const Component = type === 'IMAGE' ? ProductionImageComposer : ProductionVideoComposer;
  const view = render(<MemoryRouter><CreatorMediaProvider><Component draftScope={scope} entryOnly={scope !== 'create'} /></CreatorMediaProvider></MemoryRouter>);
  await waitFor(() => expect(screen.getByRole('button', { name: `选择${type === 'IMAGE' ? '图片' : '视频'}模型：${type === 'IMAGE' ? '图片' : '视频'}模型` })).toBeEnabled());
  return view;
};
it.each([['IMAGE', 'IMAGE'], ['VIDEO', 'IMAGE'], ['VIDEO', 'VIDEO'], ['VIDEO', 'AUDIO']] as const)('%s retains unsent %s and prompt across pages/remounts without sharing or uploading again', async (type, kind) => {
  const view = await mount(type, 'home');
  fireEvent.change(screen.getByRole('textbox'), { target: { value: '首页独立草稿' } });
  const mime = kind === 'VIDEO' ? 'video/mp4' : kind === 'AUDIO' ? 'audio/wav' : 'image/png';
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['reference'], '我的素材', { type: mime })] } });
  await waitFor(() => expect(review).toHaveBeenCalledOnce());
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  view.unmount();
  const commerce = await mount(type, 'commerce');
  expect(screen.getByRole('textbox')).toHaveValue('');
  expect(screen.queryByText('我的素材')).not.toBeInTheDocument();
  fireEvent.change(screen.getByRole('textbox'), { target: { value: '电商独立草稿' } });
  commerce.unmount();
  const create = await mount(type, 'create');
  expect(screen.getByRole('textbox')).toHaveValue(''); create.unmount();
  const restored = await mount(type, 'home');
  expect(screen.getByRole('textbox')).toHaveValue('首页独立草稿');
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  await waitFor(() => expect(within(screen.getByRole('group', { name: '已添加素材（按提交顺序）' })).getByRole('button', { name: /^移除/ })).toBeEnabled());
  expect(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).toHaveBeenCalledWith('file-one');
  expect(review).toHaveBeenCalledOnce();
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).toHaveBeenCalledTimes(type === 'IMAGE' ? 1 : 0);
  expect(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).toHaveBeenCalledTimes(type === 'IMAGE' ? 0 : 1);
  expect(api.mediaSubmitPostApiV1MediaTasks).not.toHaveBeenCalled();
  restored.unmount();
  const revisited = await mount(type, 'commerce');
  expect(screen.getByRole('textbox')).toHaveValue('电商独立草稿');
  fireEvent.change(screen.getByRole('textbox'), { target: { value: '' } }); revisited.unmount();
  await mount(type, 'commerce'); expect(screen.getByRole('textbox')).toHaveValue('');
});
it.each(['IMAGE', 'VIDEO'] as const)('%s never fills entry editors from an accepted task', async type => {
  const model = type === 'IMAGE' ? image : video;
  const settings = { type, model: model.model, resolution: '720p', aspectRatio: '16:9', count: 1, durationSeconds: type === 'IMAGE' ? 0 : 5 };
  sessionStorage.setItem(`xzwl-${type.toLowerCase()}-submission:owner`, JSON.stringify({ key: 'previous', taskRef: 'task-one', request: { requestRef: 'previous', settings, prompt: '生成一个美短小猫', inputAssetRefs: [] }, snapshot: { ...settings, prompt: '生成一个美短小猫' } }));
  for (const scope of ['home', 'commerce', 'create'] as const) {
    const view = await mount(type, scope);
    expect(screen.getByRole('textbox')).toHaveValue(''); view.unmount();
  }
});

it.each(['IMAGE', 'VIDEO'] as const)('%s isolates accounts and retains text typed before initial identity resolves', async type => {
  const session = vi.mocked(userSession.useOptionalUserSession);
  const owner = session();
  const first = await mount(type, 'home');
  fireEvent.change(screen.getByRole('textbox'), { target: { value: '账号一的旧草稿' } }); first.unmount();
  session.mockReturnValue({ ...owner, userId: 'second-owner' } as never);
  const second = await mount(type, 'home');
  expect(screen.getByRole('textbox')).toHaveValue('');
  fireEvent.change(screen.getByRole('textbox'), { target: { value: '账号二的草稿' } }); second.unmount();
  session.mockReturnValue({ status: 'loading', userId: null, authenticated: false } as never);
  const Component = type === 'IMAGE' ? ProductionImageComposer : ProductionVideoComposer;
  const tree = <MemoryRouter><CreatorMediaProvider><Component draftScope="home" entryOnly /></CreatorMediaProvider></MemoryRouter>;
  const delayed = render(tree);
  fireEvent.change(screen.getByRole('textbox'), { target: { value: '加载期间刚输入的内容' } });
  session.mockReturnValue(owner);
  delayed.rerender(<MemoryRouter><CreatorMediaProvider><Component draftScope="home" entryOnly /></CreatorMediaProvider></MemoryRouter>);
  expect(screen.getByRole('textbox')).toHaveValue('加载期间刚输入的内容');
  // Refreshing account eligibility must not prevent saving edits for its settled owner.
  session.mockReturnValue({ status: 'loading', userId: null, authenticated: false } as never);
  delayed.rerender(<MemoryRouter><CreatorMediaProvider><Component draftScope="home" entryOnly /></CreatorMediaProvider></MemoryRouter>);
  fireEvent.change(screen.getByRole('textbox'), { target: { value: '账户刷新期间的编辑' } });
  delayed.unmount(); session.mockReturnValue(owner);
  const restored = await mount(type, 'home');
  expect(screen.getByRole('textbox')).toHaveValue('账户刷新期间的编辑'); restored.unmount();
  session.mockReturnValue({ ...owner, userId: 'second-owner' } as never);
  await mount(type, 'home'); expect(screen.getByRole('textbox')).toHaveValue('账号二的草稿');
});

it('retains the prompt and reference while the image quantity is temporarily blank', async () => {
  const view = await mount('IMAGE', 'home');
  fireEvent.change(screen.getByRole('textbox'), { target: { value: '数量尚未填完的草稿' } });
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['image'], 'quantity.png', { type: 'image/png' })] } });
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: /^图片参数摘要/ }));
  fireEvent.change(screen.getByRole('spinbutton', { name: '生成数量' }), { target: { value: '' } });
  view.unmount();
  await mount('IMAGE', 'home');
  expect(screen.getByRole('textbox')).toHaveValue('数量尚未填完的草稿');
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  expect(screen.getByRole('button', { name: '移除参考图 1' })).toBeEnabled();
  expect(api.mediaSubmitPostApiV1MediaTasks).not.toHaveBeenCalled();
});

it('restores material mention bindings and keeps removals removed after reopening', async () => {
  const view = await mount('IMAGE', 'home');
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['image'], 'mention.png', { type: 'image/png' })] } });
  await waitFor(() => expect(review).toHaveBeenCalledOnce());
  fireEvent.change(screen.getByRole('textbox'), { target: { value: '参考@', selectionStart: 3 } });
  await screen.findByRole('option');
  fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  view.unmount();
  const restored = await mount('IMAGE', 'home');
  expect(screen.getByRole('textbox')).toHaveTextContent('图像1');
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  fireEvent.click(screen.getByRole('button', { name: '移除参考图 1' }));
  restored.unmount();
  await mount('IMAGE', 'home');
  expect(screen.queryByAltText('mention.png')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  expect(api.mediaSubmitPostApiV1MediaTasks).not.toHaveBeenCalled();
});
