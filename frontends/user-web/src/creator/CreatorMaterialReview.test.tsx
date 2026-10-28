import { webcrypto } from 'node:crypto';
import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { ProductionImageComposer } from './ProductionImageComposer';
import { ProductionVideoComposer } from './ProductionVideoComposer';
import * as api from '../api';
import { CreatorMediaProvider } from './CreatorMediaProvider';
import { creationTaskDraft } from './creation-task-draft';
import type { VideoSubmission } from './VideoTaskResults';

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
  vi.resetAllMocks(); sessionStorage.clear();
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
  review.mockResolvedValue(reply(receipt())); get.mockResolvedValue(reply(receipt()));
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
async function upload(type: 'IMAGE' | 'VIDEO', kind = 'IMAGE') {
  render(<MemoryRouter>{type === 'IMAGE' ? <ProductionImageComposer /> : <ProductionVideoComposer />}</MemoryRouter>);
  const label = type === 'IMAGE' ? '图片' : '视频';
  await waitFor(() => expect(screen.getByRole('button', { name: `选择${label}模型：${label}模型` })).toBeEnabled());
  fireEvent.change(screen.getByRole('textbox', { name: `${label}提示词` }), { target: { value: '生成测试' } });
  const mime = kind === 'VIDEO' ? 'video/mp4' : kind === 'AUDIO' ? 'audio/wav' : 'image/png';
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['reference'], '素材', { type: mime })] } });
}
it.each(['IMAGE', 'VIDEO'] as const)('%s enlarges and restores the same draft and pending materials without generating', async type => {
  await upload(type);
  await waitFor(() => expect(review).toHaveBeenCalledTimes(1));
  const prompt = screen.getByRole('textbox', { name: `${type === 'IMAGE' ? '图片' : '视频'}提示词` });
  const expand = screen.getByRole('button', { name: '放大输入框' });
  const composer = expand.closest('section')!;
  const bounds = vi.spyOn(composer, 'getBoundingClientRect').mockReturnValue(new DOMRect(210, 400, 920, 180));
  fireEvent.click(expand);
  expect(composer.style.getPropertyValue('--composer-expanded-width')).toBe('920px');
  expect(composer.style.getPropertyValue('--composer-expanded-left')).toBe('210px');
  expect(expand).toHaveAttribute('aria-expanded', 'true');
  expect(prompt).toHaveFocus();
  expect(screen.getByRole('textbox')).toBe(prompt);
  fireEvent.change(prompt, { target: { value: '放大后继续编辑' } });
  fireEvent.keyDown(prompt, { key: 'Escape' });
  expect(expand).toHaveAttribute('aria-expanded', 'false');
  expect(expand).toHaveFocus();
  expect(prompt).toHaveValue('放大后继续编辑');
  bounds.mockReturnValue(new DOMRect(130, 400, 780, 180));
  fireEvent.click(expand);
  expect(composer.style.getPropertyValue('--composer-expanded-width')).toBe('780px');
  expect(composer.style.getPropertyValue('--composer-expanded-left')).toBe('130px');
  fireEvent.click(screen.getByRole('button', { name: '收起输入框' }));
  expect(expand).toHaveAttribute('aria-expanded', 'false');
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  expect(review).toHaveBeenCalledTimes(1);
  expect(api.mediaSubmitPostApiV1MediaTasks).not.toHaveBeenCalled();
});
it.each([['IMAGE', 'IMAGE'], ['VIDEO', 'IMAGE'], ['VIDEO', 'VIDEO'], ['VIDEO', 'AUDIO']] as const)('%s upload %s waits for review before generation', async (type, kind) => {
  review.mockResolvedValue(reply(receipt(kind))); get.mockResolvedValue(reply(receipt(kind)));
  await upload(type, kind);
  await waitFor(() => expect(review).toHaveBeenCalledTimes(1));
  expect(review).toHaveBeenCalledWith({ fileId: 'file-one', model: video.model, ...(kind !== 'IMAGE' ? { kind } : {}) }, expect.objectContaining({ headers: { 'Idempotency-Key': expect.any(String) } }));
  expect(screen.queryByText('审核中')).not.toBeInTheDocument();
  const reviewSpinner = screen.getByRole('status', { name: '素材 审核中' });
  expect(reviewSpinner.closest('.composer-material')).toHaveAttribute('aria-busy', 'true');
  expect(reviewSpinner.closest('.composer-material')).toHaveAttribute('data-incompatible', 'false');
  const trigger = screen.getByRole('button', { name: '添加素材' });
  fireEvent.blur(trigger, { relatedTarget: screen.getByRole('textbox', { name: `${type === 'IMAGE' ? '图片' : '视频'}提示词` }) });
  expect(screen.getByRole('status', { name: '素材 审核中' }).closest('.composer-material-stack')).toBe(trigger);
  expect(screen.queryByText('素材尚未全部审核通过')).not.toBeInTheDocument();
  fireEvent.focus(trigger);
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  fireEvent.submit(screen.getByRole('button', { name: /^开始生成/ }).closest('form')!);
  expect(api.mediaSubmitPostApiV1MediaTasks).not.toHaveBeenCalled();
  get.mockResolvedValue(reply(receipt(kind, 'ACTIVE')));
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled(), { timeout: 4500 });
  expect(screen.queryByRole('status', { name: '素材 审核中' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await waitFor(() => expect(api.mediaSubmitPostApiV1MediaTasks).toHaveBeenCalledTimes(1));
  if (type === 'VIDEO') expect(vi.mocked(api.mediaSubmitPostApiV1MediaTasks).mock.calls[0][0].references).toEqual([expect.objectContaining({ fileId: 'file-one', kind, portraitId: 'review-one' })]);
  expect(vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mock.calls.length + vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).mock.calls.length).toBe(1);
});
it('keeps failed review blocked and preserves the retry key after an uncertain submission', async () => {
  review.mockRejectedValueOnce(new TypeError('offline'));
  await upload('IMAGE');
  fireEvent.click(await screen.findByRole('button', { name: '确认上次审核提交' }));
  await waitFor(() => expect(review).toHaveBeenCalledTimes(2));
  expect(review.mock.calls[1]).toEqual(review.mock.calls[0]);
  get.mockResolvedValue(reply(receipt('IMAGE', 'FAILED')));
  await waitFor(() => expect(screen.getByText('审核未通过')).toBeInTheDocument(), { timeout: 4500 });
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
});
it('does not submit an unreviewed reference when no review model is configured', async () => {
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({ models: [image] }));
  await upload('IMAGE');
  await screen.findByText('素材', { selector: 'span[title="素材"]' });
  await act(async () => {});
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  expect(review).not.toHaveBeenCalled();
});

it('also gates first and last frame generation until the image review passes', async () => {
  render(<MemoryRouter><ProductionVideoComposer /></MemoryRouter>);
  await screen.findByRole('button', { name: '选择视频模型：视频模型' });
  fireEvent.click(screen.getByRole('button', { name: '视频生成模式' }));
  fireEvent.click(screen.getByRole('menuitemradio', { name: /首尾帧/ }));
  fireEvent.change(screen.getByRole('textbox', { name: '视频提示词' }), { target: { value: '首帧生成' } });
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['png'], '首帧.png', { type: 'image/png' })] } });
  await waitFor(() => expect(review).toHaveBeenCalledTimes(1));
  await act(async () => {});
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  get.mockResolvedValue(reply(receipt('IMAGE', 'ACTIVE')));
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled(), { timeout: 4500 });
  expect(api.mediaQuotePostApiV1MediaQuotes).toHaveBeenLastCalledWith(expect.objectContaining({ references: [{ fileId: 'file-one' }] }));
});

it('keeps a mixed batch blocked until every remaining material is approved', async () => {
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages)
    .mockResolvedValueOnce(reply({ fileId: 'file-one' })).mockResolvedValueOnce(reply({ fileId: 'file-two' }));
  review.mockResolvedValueOnce(reply(receipt('IMAGE', 'ACTIVE')))
    .mockResolvedValueOnce(reply({ ...receipt(), id: 'review-two', fileId: 'file-two' }));
  get.mockResolvedValue(reply({ ...receipt('IMAGE', 'FAILED'), id: 'review-two', fileId: 'file-two' }));
  await upload('IMAGE');
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['png'], '第二张.png', { type: 'image/png' })] } });
  await waitFor(() => expect(review).toHaveBeenCalledTimes(2));
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  await waitFor(() => expect(screen.getByText('审核未通过')).toBeInTheDocument(), { timeout: 4500 });
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  fireEvent.click(screen.getByRole('button', { name: '移除参考图 2' }));
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  expect(api.mediaQuotePostApiV1MediaQuotes).toHaveBeenLastCalledWith(expect.objectContaining({ references: [{ fileId: 'file-one' }] }));
  expect(review).toHaveBeenCalledTimes(2);
});

it.each([['IMAGE', false, false, false], ['VIDEO', false, false, false], ['IMAGE', true, false, false], ['VIDEO', true, false, false], ['IMAGE', true, true, false], ['VIDEO', true, true, false], ['VIDEO', true, true, true]] as const)('%s reuses approved library images with the original file and no upload or review POST (different model=%s)', async (type, differentModel, legacy, signingFails) => {
  let previewIndex = 0;
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => `blob:preview-${++previewIndex}`);
  if (signingFails) vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockRejectedValue(new Error('unavailable'));
  vi.stubGlobal('crypto', webcrypto);
  const put = vi.fn();
  vi.mocked(api.storageMatchImagePostApiV1FilesFileIdMatch).mockResolvedValue(reply({ matches: true }));
  const original = { id: legacy ? 'local-old-id' : 'file-one', canvasFileIds: legacy ? undefined : ['file-one'], kind: 'image' as const, name: '已审核图片.png', mimeType: 'image/png', size: 9, content: new Blob(['reference'], { type: 'image/png' }) };
  const approved = { ...receipt('IMAGE', 'ACTIVE'), model: differentModel ? 'another-compatible-model' : video.model };
  vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValue(reply([approved]));
  let finishReview!: (value: never) => void;
  get.mockImplementation(() => new Promise(resolve => { finishReview = resolve; }));
  render(<MemoryRouter><CreatorMediaProvider storage={{ storageMode: 'session', reason: 'forced', repository: { list: async () => [original], put, remove: async () => {}, close() {} } }}>
    {type === 'IMAGE' ? <ProductionImageComposer /> : <ProductionVideoComposer />}
  </CreatorMediaProvider></MemoryRouter>);
  const label = type === 'IMAGE' ? '图片' : '视频';
  await screen.findByRole('button', { name: `选择${label}模型：${label}模型` });
  fireEvent.change(screen.getByRole('textbox', { name: `${label}提示词` }), { target: { value: '复用已审核图片' } });
  fireEvent.click(screen.getByRole('button', { name: '添加素材' }));
  fireEvent.click(screen.getByRole('menuitem', { name: '素材库' }));
  const dialog = screen.getByRole('dialog', { name: '素材库' });
  fireEvent.click(await within(dialog).findByRole('option', { name: original.name }));
  fireEvent.click(within(dialog).getByRole('button', { name: '确定' }));
  await waitFor(() => expect(get).toHaveBeenCalledWith('review-one'));
  expect(screen.getByRole('button', { name: /^开始生成/ })).toBeDisabled();
  await act(async () => { finishReview(reply(approved)); });
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  fireEvent.focus(screen.getByRole('button', { name: '添加素材' }));
  const info = screen.getByRole('dialog', { name: '参考素材' }).querySelector('.composer-material-info')!;
  expect(info.textContent).toBe(info.querySelector('strong')!.textContent);
  expect(screen.queryByText('审核已通过')).not.toBeInTheDocument();
  if (signingFails) expect(screen.getAllByRole('img', { name: original.name }).every(img => img.getAttribute('src') === `blob:preview-${previewIndex}`)).toBe(true);
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  await waitFor(() => expect(api.mediaSubmitPostApiV1MediaTasks).toHaveBeenCalledTimes(1));
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).not.toHaveBeenCalled();
  expect(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).not.toHaveBeenCalled();
  expect(review).not.toHaveBeenCalled();
  expect(vi.mocked(api.mediaSubmitPostApiV1MediaTasks).mock.calls[0][0].references).toEqual([expect.objectContaining({ fileId: 'file-one' })]);
  if (legacy) expect(put).toHaveBeenCalledWith(expect.objectContaining({ id: original.id, content: original.content, canvasFileIds: ['file-one'] }));
  if (type === 'VIDEO') expect(vi.mocked(api.mediaSubmitPostApiV1MediaTasks).mock.calls[0][0].references?.[0]).toMatchObject({ portraitId: 'review-one' });
});


it.each(['IMAGE', 'VIDEO'] as const)('%s blocks generation until queued uploads finish library persistence', async type => {
  let releaseWrite!: () => void;
  const put = vi.fn().mockImplementationOnce(() => new Promise<void>(resolve => { releaseWrite = resolve; })).mockResolvedValue(undefined);
  let sequence = 0;
  const uploadFile = () => Promise.resolve(reply({ fileId: `queued-file-${++sequence}` }));
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockImplementation(uploadFile);
  vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).mockImplementation(uploadFile);
  review.mockImplementation(request => Promise.resolve(reply({ ...receipt('IMAGE', 'ACTIVE'), id: `review-${request.fileId}`, fileId: request.fileId })));
  render(<MemoryRouter><CreatorMediaProvider storage={{ storageMode: 'session', reason: 'forced', repository: {
    list: async () => [], put, remove: async () => {}, close: () => {},
  } }}>{type === 'IMAGE' ? <ProductionImageComposer /> : <ProductionVideoComposer />}</CreatorMediaProvider></MemoryRouter>);
  const label = type === 'IMAGE' ? '图片' : '视频';
  await waitFor(() => expect(screen.getByRole('button', { name: `选择${label}模型：${label}模型` })).toBeEnabled());
  fireEvent.change(screen.getByRole('textbox', { name: `${label}提示词` }), { target: { value: 'queue gate regression' } });
  const select = (name: string) => fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['image'], name, { type: 'image/png' })] } });
  select('first.png');
  await waitFor(() => expect(put).toHaveBeenCalledTimes(1));
  select('second.png');
  const submit = screen.getByRole('button', { name: /^开始生成/ });
  await waitFor(() => expect(submit).toHaveTextContent('1 积分'));
  expect(submit).toBeDisabled();
  fireEvent.submit(submit.closest('form')!);
  expect(api.mediaSubmitPostApiV1MediaTasks).not.toHaveBeenCalled();
  await act(async () => { releaseWrite(); });
  await waitFor(() => expect(put).toHaveBeenCalledTimes(2));
  await waitFor(() => expect(submit).toBeEnabled());
  fireEvent.click(submit);
  await waitFor(() => expect(api.mediaSubmitPostApiV1MediaTasks).toHaveBeenCalledTimes(1));
  expect(vi.mocked(api.mediaSubmitPostApiV1MediaTasks).mock.calls[0][0].references).toHaveLength(2);
});

it.each(['IMAGE', 'VIDEO'] as const)('%s re-edit restores approved references without review POSTs or uploads', async type => {
  const kinds = type === 'IMAGE' ? ['IMAGE'] : ['IMAGE', 'VIDEO', 'AUDIO'];
  const receipts = kinds.map((kind, index) => ({ ...receipt(kind, 'ACTIVE'), id: `approved-${index}`, fileId: `original-${index}` }));
  const task = { id: 'history-task', type, status: 'SUCCEEDED', prompt: '历史任务素材', model: type === 'IMAGE' ? image.model : video.model,
    resolution: '720p', aspectRatio: '16:9', count: 1, durationSeconds: type === 'IMAGE' ? 0 : 4, referenceMode: type === 'VIDEO' ? 'OMNI' : null,
    references: receipts.map(row => ({ fileId: row.fileId, kind: row.kind, durationSeconds: row.durationSeconds,
      ...(type === 'VIDEO' ? { portraitId: row.id } : {}) })), results: [] };
  vi.mocked(api.mediaListGetApiV1MediaTasks).mockResolvedValue(reply([task]));
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply(task));
  vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValue(reply(receipts));
  get.mockImplementation(async id => reply(receipts.find(row => row.id === id)));
  render(<MemoryRouter>{type === 'IMAGE' ? <ProductionImageComposer /> : <ProductionVideoComposer />}</MemoryRouter>);
  const edit = await screen.findByRole('button', { name: '重新编辑' });
  await waitFor(() => expect(edit).toBeEnabled());
  fireEvent.click(edit);
  await waitFor(() => expect(screen.getByRole('textbox', { name: `${type === 'IMAGE' ? '图片' : '视频'}提示词` })).toHaveValue(task.prompt));
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  expect(document.querySelectorAll('.composer-material-info[data-approved=true]')).toHaveLength(kinds.length);
  for (const row of receipts) expect(get).toHaveBeenCalledWith(row.id);
  expect(review).not.toHaveBeenCalled();
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).not.toHaveBeenCalled();
  expect(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).not.toHaveBeenCalled();
});


it.each(['IMAGE', 'VIDEO', 'AUDIO'] as const)('preserves the saved %s receipt when the task snapshot omits it', async kind => {
  const entry: VideoSubmission = { key: 'saved', snapshot: { type: 'VIDEO', prompt: '原提示', model: video.model,
    resolution: '720p', aspectRatio: '16:9', count: 1, durationSeconds: 4, referenceMode: 'OMNI',
    references: [{ fileId: 'file-one', httpsUrl: null, kind, durationSeconds: 4 }] },
    draft: { prompt: '原提示', mode: 'omni', bindings: [], materials: [{ fileId: 'file-one', name: '原素材', preview: 'https://assets.example/expired',
      kind, reviewId: 'review-one', reviewStatus: 'ACTIVE', reviewPending: false, sizeBytes: 1234 }] } };
  get.mockResolvedValue(reply(receipt(kind, 'ACTIVE')));
  const draft = await creationTaskDraft(entry);
  expect(draft.materials[0]).toMatchObject({ fileId: 'file-one', name: '原素材', kind, reviewId: 'review-one',
    reviewStatus: 'ACTIVE', reviewPending: false, sizeBytes: 1234, preview: 'https://assets.example/reference' });
  expect(get).toHaveBeenCalledWith('review-one');
  expect(review).not.toHaveBeenCalled();
});

it.each([{ status: 'FAILED' }, { status: 'PROCESSING' }, { fileId: 'another-file' }, { kind: 'AUDIO' }, { id: 'another-review' }])(
  'does not approve or resubmit an unavailable/mismatched review %j', async change => {
    get.mockResolvedValue(reply({ ...receipt('IMAGE', 'ACTIVE'), ...change }));
    await expect(creationTaskDraft({ key: 'history', snapshot: { type: 'VIDEO', prompt: '原提示', model: video.model,
      resolution: '720p', aspectRatio: '16:9', count: 1, durationSeconds: 4,
      references: [{ fileId: 'file-one', httpsUrl: null, kind: 'IMAGE', durationSeconds: null, portraitId: 'review-one' }] } })).rejects.toThrow('审核记录');
    expect(review).not.toHaveBeenCalled();
});

it.each(['IMAGE', 'VIDEO', 'AUDIO'] as const)('never starts review for a historical %s reference with no receipt', async kind => {
  await expect(creationTaskDraft({ key: 'history', snapshot: { type: 'VIDEO', prompt: '原提示', model: video.model,
    resolution: '720p', aspectRatio: '16:9', count: 1, durationSeconds: 4,
    references: [{ fileId: 'file-one', httpsUrl: null, kind, durationSeconds: null }] } })).rejects.toThrow('审核记录');
  expect(review).not.toHaveBeenCalled();
});
