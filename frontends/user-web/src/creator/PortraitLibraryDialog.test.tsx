import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { PortraitLibraryDialog } from './PortraitLibraryDialog';
import * as api from '../api';
vi.mock('../api', async original => ({ ...await original<typeof import('../api')>(),
  mediaPortraitListGetApiV1MediaPortraits: vi.fn(), mediaPortraitGetGetApiV1MediaPortraitsPortraitId: vi.fn(),
  mediaPortraitSubmitPostApiV1MediaPortraits: vi.fn(), storageUploadReferenceImagePostApiV1FilesReferenceImages: vi.fn(),
  storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl: vi.fn(), storageUploadReferenceMediaPostApiV1FilesReferenceMedia: vi.fn(), storageUploadProcessingVideoPostApiV1FilesProcessingVideos: vi.fn(),
}));
const reply = (data: unknown) => ({ data, status: 200, headers: new Headers() }) as never;
const portrait = { id: 'portrait-one', fileId: 'file-one', model: 'video-one', status: 'PROCESSING', errorMessage: null };
const list = vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits);
const submit = vi.mocked(api.mediaPortraitSubmitPostApiV1MediaPortraits);
const get = vi.mocked(api.mediaPortraitGetGetApiV1MediaPortraitsPortraitId);
beforeEach(() => {
  vi.clearAllMocks(); sessionStorage.clear();
  URL.createObjectURL = vi.fn(() => 'blob:portrait-upload'); URL.revokeObjectURL = vi.fn();
  list.mockResolvedValue(reply([])); get.mockResolvedValue(reply(portrait)); submit.mockResolvedValue(reply(portrait));
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockResolvedValue(reply({ fileId: 'file-one' }));
  vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockResolvedValue(reply({ url: 'https://assets.example/portrait.png' }));
});
afterEach(cleanup);
it('automatically reviews uploads and permits reference only after a matching ACTIVE response', async () => {
  const pick = vi.fn();
  render(<PortraitLibraryDialog owner="owner-one" model="video-one" onClose={() => {}} onPick={pick} />);
  await screen.findByText('还没有人像素材');
  fireEvent.change(screen.getByLabelText('上传人像'), { target: { files: [new File(['png'], '虚拟角色.png', { type: 'image/png' })] } });
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
  expect(submit).toHaveBeenCalledWith({ fileId: 'file-one', model: 'video-one' }, expect.objectContaining({ headers: { 'Idempotency-Key': expect.any(String) } }));
  expect(await screen.findByRole('option', { name: '人像 portrait-one' })).toHaveAttribute('aria-disabled', 'true');
  get.mockResolvedValue(reply({ ...portrait, status: 'ACTIVE' }));
  await waitFor(() => expect(screen.getByRole('option', { name: '人像 portrait-one' })).toHaveAttribute('aria-disabled', 'false'), { timeout: 4500 });
  fireEvent.click(screen.getByRole('option', { name: '人像 portrait-one' }));
  expect(pick).not.toHaveBeenCalled();
  expect(screen.getByRole('option', { name: '人像 portrait-one' })).toHaveAttribute('aria-selected', 'true');
  fireEvent.click(screen.getByRole('button', { name: /^确认$/ }));
  await waitFor(() => expect(pick).toHaveBeenCalledWith([expect.objectContaining({ fileId: 'file-one', portraitId: 'portrait-one', kind: 'IMAGE', preview: 'https://assets.example/portrait.png' })]));
});
it('restores server-side portraits on reopen and refuses failed or other-model records', async () => {
  list.mockResolvedValue(reply([{ ...portrait, status: 'ACTIVE' }, { ...portrait, id: 'failed', fileId: 'failed-file', status: 'FAILED', errorMessage: '审核未通过' }, { ...portrait, id: 'other-model', model: 'video-two', status: 'ACTIVE' }]));
  render(<PortraitLibraryDialog owner="owner-one" model="video-one" onClose={() => {}} onPick={() => {}} />);
  expect(await screen.findByRole('option', { name: '人像 portrait-one' })).toHaveAttribute('aria-disabled', 'false');
  expect(screen.getByRole('option', { name: '人像 failed' })).toHaveAttribute('aria-disabled', 'true');
  expect(screen.queryByRole('option', { name: '人像 other-model' })).not.toBeInTheDocument();
});
it.each([['IMAGE', 'image/png'], ['VIDEO', 'video/mp4'], ['AUDIO', 'audio/mpeg']] as const)('replays an uncertain %s submit with the original key file and kind', async (kind, mime) => {
  const row = { ...portrait, kind, durationSeconds: kind === 'IMAGE' ? null : 4.5 };
  submit.mockResolvedValue(reply(row));
  vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).mockResolvedValue(reply({ fileId: row.fileId }));
  submit.mockRejectedValueOnce(new Error('connection lost'));
  const view = render(<PortraitLibraryDialog owner="owner-one" model="video-one" onClose={() => {}} onPick={() => {}} />);
  await screen.findByText('还没有人像素材');
  fireEvent.change(screen.getByLabelText('上传人像'), { target: { files: [new File(['media'], '角色', { type: mime })] } });
  await screen.findByRole('button', { name: '确认上次审核提交' });
  const first = submit.mock.calls[0]; view.unmount();
  render(<PortraitLibraryDialog owner="owner-one" model="video-one" onClose={() => {}} onPick={() => {}} />);
  await waitFor(() => expect(screen.getByRole('button', { name: '确认上次审核提交' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '确认上次审核提交' }));
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(2));
  expect(submit.mock.calls[1]).toEqual(first);
  expect(kind === 'IMAGE' ? api.storageUploadReferenceImagePostApiV1FilesReferenceImages : api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).toHaveBeenCalledTimes(1);
  expect(first[0]).toEqual({ fileId: row.fileId, model: row.model, ...(kind === 'IMAGE' ? {} : { kind }) });
});
it('blocks uploads until the initial list settles and during identity refresh', async () => {
  let resolveList!: (value: never) => void;
  list.mockImplementationOnce(() => new Promise(resolve => { resolveList = resolve; }));
  const view = render(<PortraitLibraryDialog owner="owner-one" model="video-one" onClose={() => {}} onPick={() => {}} />);
  expect(screen.getByLabelText('上传人像')).toBeDisabled();
  resolveList(reply([]));
  await waitFor(() => expect(screen.getByLabelText('上传人像')).toBeEnabled());
  view.rerender(<PortraitLibraryDialog owner="owner-one" model="video-one" disabled onClose={() => {}} onPick={() => {}} />);
  expect(screen.getByLabelText('上传人像')).toBeDisabled();
});
it('allows a replacement upload after a definite admission rejection', async () => {
  submit.mockRejectedValueOnce(new api.ApiError(new Response(null, { status: 422 }), { code: 'PORTRAIT_REJECTED' }));
  render(<PortraitLibraryDialog owner="owner-one" model="video-one" onClose={() => {}} onPick={() => {}} />);
  await screen.findByText('还没有人像素材');
  fireEvent.change(screen.getByLabelText('上传人像'), { target: { files: [new File(['png'], '角色.png', { type: 'image/png' })] } });
  await screen.findByRole('alert');
  expect(screen.queryByRole('button', { name: '确认上次审核提交' })).not.toBeInTheDocument();
  expect(screen.getByLabelText('上传人像')).toBeEnabled();
});
it('a late receipt from an unmounted dialog cannot remove the next upload recovery key', async () => {
  let finishOld!: (value: never) => void;
  submit.mockImplementationOnce(() => new Promise(resolve => { finishOld = resolve; }));
  const props = { owner: 'owner-one', model: 'video-one', onClose: () => {}, onPick: () => {} };
  const first = render(<PortraitLibraryDialog {...props} />);
  await screen.findByText('还没有人像素材');
  const upload = () => fireEvent.change(screen.getByLabelText('上传人像'), { target: { files: [new File(['png'], '角色.png', { type: 'image/png' })] } });
  upload(); await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
  first.unmount();
  const second = render(<PortraitLibraryDialog {...props} />);
  await waitFor(() => expect(screen.getByRole('button', { name: '确认上次审核提交' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '确认上次审核提交' }));
  await waitFor(() => expect(screen.getByLabelText('上传人像')).toBeEnabled());
  submit.mockRejectedValueOnce(new Error('second connection lost'));
  upload(); await waitFor(() => expect(submit).toHaveBeenCalledTimes(3));
  await screen.findByRole('alert');
  finishOld(reply(portrait));
  await new Promise(resolve => setTimeout(resolve, 0));
  second.unmount();
  render(<PortraitLibraryDialog {...props} />);
  expect(await screen.findByRole('button', { name: '确认上次审核提交' })).toBeVisible();
});

it('does not replay a recovered submission until the initial list finishes', async () => {
  sessionStorage.setItem('xzwl-portrait-submit:owner-one:video-one', JSON.stringify({ key: 'stable-key', fileId: 'file-one', model: 'video-one' }));
  let finishList!: (value: never) => void;
  list.mockImplementationOnce(() => new Promise(resolve => { finishList = resolve; }));
  render(<PortraitLibraryDialog owner="owner-one" model="video-one" onClose={() => {}} onPick={() => {}} />);
  expect(screen.getByRole('button', { name: '确认上次审核提交' })).toBeDisabled();
  finishList(reply([]));
  await waitFor(() => expect(screen.getByRole('button', { name: '确认上次审核提交' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '确认上次审核提交' }));
  expect(await screen.findByRole('option', { name: '人像 portrait-one' })).toHaveAttribute('aria-disabled', 'true');
});

it('shows dropped portraits immediately beside the upload card with a review spinner and no category buttons', async () => {
  let finish!: (value: never) => void;
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  const view = render(<PortraitLibraryDialog owner="owner-one" model="video-one" onClose={() => {}} onPick={() => {}} />);
  await screen.findByText('还没有人像素材');
  const dialog = screen.getByRole('dialog', { name: '人像库' });
  expect(dialog).toHaveClass('media-library-dialog');
  expect(dialog.parentElement).toBe(document.body);
  expect(screen.queryByRole('button', { name: '官方人像' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '虚拟人像' })).not.toBeInTheDocument();
  fireEvent.drop(screen.getByRole('button', { name: '点击或拖动此处上传' }), { dataTransfer: { files: [new File(['png'], '角色.png', { type: 'image/png' })] } });
  expect(screen.getByAltText('正在上传的人像')).toHaveAttribute('src', 'blob:portrait-upload');
  expect(screen.getByRole('status', { name: '正在上传人像' })).toHaveClass('portrait-library-spinner');
  expect(screen.getByRole('button', { name: /^确认$/ })).toBeDisabled();
  finish(reply({ fileId: 'file-one' }));
  const card = await screen.findByRole('option', { name: '人像 portrait-one' });
  expect(card).toHaveAttribute('aria-disabled', 'true');
  expect(screen.getByRole('status', { name: '审核中' })).toHaveClass('portrait-library-spinner');
  view.unmount();
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:portrait-upload');
});
it('keeps a revoked selection out of the composer at confirmation and restores focus on close', async () => {
  list.mockResolvedValue(reply([{ ...portrait, status: 'ACTIVE' }]));
  get.mockResolvedValue(reply({ ...portrait, status: 'FAILED', errorMessage: '审核未通过' }));
  const pick = vi.fn(); const close = vi.fn();
  const trigger = document.createElement('button'); document.body.append(trigger); trigger.focus();
  const view = render(<PortraitLibraryDialog owner="owner-one" model="video-one" onClose={close} onPick={pick} />);
  const card = await screen.findByRole('option', { name: '人像 portrait-one' });
  fireEvent.keyDown(card, { key: 'Enter' });
  fireEvent.click(screen.getByRole('button', { name: /^确认$/ }));
  await waitFor(() => expect(card).toHaveAttribute('aria-disabled', 'true'));
  expect(pick).not.toHaveBeenCalled();
  expect(screen.getByRole('button', { name: /^确认$/ })).toBeDisabled();
  fireEvent.keyDown(screen.getByRole('dialog', { name: '人像库' }), { key: 'Escape' });
  expect(close).toHaveBeenCalledOnce();
  view.unmount(); expect(trigger).toHaveFocus(); trigger.remove();
});

it.each(['SUBMITTING', 'PROCESSING'])('allows another upload while an existing portrait is %s', async status => {
  list.mockResolvedValue(reply([{ ...portrait, status }]));
  submit.mockResolvedValue(reply({ ...portrait, id: 'portrait-two', fileId: 'file-two', status: 'SUBMITTING' }));
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockResolvedValue(reply({ fileId: 'file-two' }));
  render(<PortraitLibraryDialog owner="owner-one" model="video-one" onClose={() => {}} onPick={() => {}} />);
  await screen.findByRole('option', { name: '人像 portrait-one' });
  expect(screen.getByRole('button', { name: '点击或拖动此处上传' })).toBeEnabled();
  fireEvent.change(screen.getByLabelText('上传人像'), { target: { files: [new File(['png'], '另一个.png', { type: 'image/png' })] } });
  await screen.findByRole('option', { name: '人像 portrait-two' });
  expect(screen.getByRole('button', { name: '点击或拖动此处上传' })).toBeEnabled();
});
it('retries a failed portrait with its stored file and a new key, then replaces the failed card', async () => {
  list.mockResolvedValue(reply([{ ...portrait, status: 'FAILED' }]));
  let finish!: (value: never) => void;
  submit.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  render(<PortraitLibraryDialog owner="owner-one" model="video-one" onClose={() => {}} onPick={() => {}} />);
  const button = await screen.findByRole('button', { name: '重新审核' });
  expect(button.closest('[role="option"]')).toBeNull();
  fireEvent.click(button); fireEvent.click(button);
  expect(submit).toHaveBeenCalledTimes(1);
  expect(submit).toHaveBeenCalledWith({ fileId: portrait.fileId, model: portrait.model }, expect.objectContaining({ headers: { 'Idempotency-Key': expect.any(String) } }));
  expect(screen.getByRole('status', { name: '正在提交审核' })).toBeVisible();
  finish(reply({ ...portrait, id: 'retry-one', status: 'SUBMITTING' }));
  await screen.findByRole('option', { name: '人像 retry-one' });
  expect(screen.queryByRole('option', { name: '人像 portrait-one' })).not.toBeInTheDocument();
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).not.toHaveBeenCalled();
  expect(screen.getByLabelText('上传人像')).toBeEnabled();
});
it('keeps the original retry key across an uncertain reply and reopening', async () => {
  list.mockResolvedValue(reply([{ ...portrait, status: 'FAILED' }]));
  submit.mockRejectedValueOnce(new Error('connection lost'));
  const props = { owner: 'owner-one', model: 'video-one', onClose: () => {}, onPick: () => {} };
  const view = render(<PortraitLibraryDialog {...props} />);
  fireEvent.click(await screen.findByRole('button', { name: '重新审核' }));
  await screen.findByRole('alert'); const first = submit.mock.calls[0]; view.unmount();
  render(<PortraitLibraryDialog {...props} />);
  const recover = await screen.findByRole('button', { name: '确认上次审核提交' });
  await waitFor(() => expect(recover).toBeEnabled());
  expect(screen.getByRole('button', { name: '重新审核' })).toBeDisabled();
  fireEvent.click(recover);
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(2));
  expect(submit.mock.calls[1]).toEqual(first);
});

it('shows only the latest attempt per file after reopening', async () => {
  list.mockResolvedValue(reply([{ ...portrait, id: 'new-attempt', status: 'SUBMITTING' }, { ...portrait, status: 'FAILED' }]));
  render(<PortraitLibraryDialog owner="owner-one" model="video-one" onClose={() => {}} onPick={() => {}} />);
  await screen.findByRole('option', { name: '人像 new-attempt' });
  expect(screen.queryByRole('option', { name: '人像 portrait-one' })).not.toBeInTheDocument();
});

it('does not replace the latest attempt with an older failed page', async () => {
  const current = { ...portrait, id: 'new-attempt', status: 'SUBMITTING' };
  list.mockResolvedValueOnce(reply([current, ...Array.from({ length: 19 }, (_, i) => ({ ...portrait, id: `other-${i}`, fileId: `other-file-${i}` }))]))
    .mockResolvedValueOnce(reply([{ ...portrait, status: 'FAILED' }]));
  render(<PortraitLibraryDialog owner="owner-one" model="video-one" onClose={() => {}} onPick={() => {}} />);
  await screen.findByRole('option', { name: '人像 new-attempt' });
  fireEvent.click(screen.getByRole('button', { name: '加载更多人像' }));
  await waitFor(() => expect(screen.queryByRole('button', { name: '加载更多人像' })).not.toBeInTheDocument());
  expect(screen.getByRole('option', { name: '人像 new-attempt' })).toHaveAttribute('aria-busy', 'true');
  expect(screen.queryByRole('option', { name: '人像 portrait-one' })).not.toBeInTheDocument();
  expect(screen.getAllByRole('option')).toHaveLength(20);
});

const activePair = [1, 2].map(n => ({ ...portrait, id: `portrait-${n}`, fileId: `file-${n}`, status: 'ACTIVE' }));
const selectPair = async () => { for (const row of activePair) fireEvent.click(await screen.findByRole('option', { name: `人像 ${row.id}` })); };
it('toggles multiple portraits and confirms the whole selection once in selection order', async () => {
  list.mockResolvedValue(reply(activePair)); get.mockImplementation(async id => reply(activePair.find(row => row.id === id)));
  const pick = vi.fn(); render(<PortraitLibraryDialog owner="owner-one" model="video-one" onClose={() => {}} onPick={pick} />);
  await selectPair(); expect(screen.getByText('已选：2')).toBeVisible();
  expect(screen.getByRole('listbox')).toHaveAttribute('aria-multiselectable', 'true');
  fireEvent.click(screen.getByRole('option', { name: '人像 portrait-1' })); expect(screen.getByText('已选：1')).toBeVisible();
  fireEvent.keyDown(screen.getByRole('option', { name: '人像 portrait-1' }), { key: ' ' });
  fireEvent.click(screen.getByRole('button', { name: /^确认$/ }));
  await waitFor(() => expect(pick).toHaveBeenCalledOnce());
  expect(pick.mock.calls[0][0].map((m: { fileId: string }) => m.fileId)).toEqual(['file-2', 'file-1']);
});
it('limits new selections but still permits deselecting at the remaining capacity', async () => {
  list.mockResolvedValue(reply(activePair));
  render(<PortraitLibraryDialog owner="owner-one" model="video-one" maxSelection={1} onClose={() => {}} onPick={() => {}} />);
  await selectPair(); expect(screen.getByText('已选：1')).toBeVisible();
  expect(screen.getByRole('option', { name: '人像 portrait-2' })).toHaveAttribute('aria-disabled', 'true');
  fireEvent.click(screen.getByRole('option', { name: '人像 portrait-1' }));
  fireEvent.click(screen.getByRole('option', { name: '人像 portrait-2' }));
  expect(screen.getByRole('option', { name: '人像 portrait-2' })).toHaveAttribute('aria-selected', 'true');
});
it('adds nothing if any selected portrait fails review during confirmation', async () => {
  list.mockResolvedValue(reply(activePair)); get.mockImplementation(async id => reply({ ...activePair.find(row => row.id === id), status: id === 'portrait-2' ? 'FAILED' : 'ACTIVE' }));
  const pick = vi.fn(); render(<PortraitLibraryDialog owner="owner-one" model="video-one" onClose={() => {}} onPick={pick} />);
  await selectPair(); fireEvent.click(screen.getByRole('button', { name: /^确认$/ }));
  await screen.findByRole('alert'); expect(pick).not.toHaveBeenCalled();
});
it('rechecks capacity after pending confirmations before adding the batch', async () => {
  list.mockResolvedValue(reply(activePair)); const finish: (() => void)[] = [];
  get.mockImplementation(id => new Promise(resolve => finish.push(() => resolve(reply(activePair.find(row => row.id === id))))));
  const pick = vi.fn(); const props = { owner: 'owner-one', model: 'video-one', onClose: () => {}, onPick: pick };
  const view = render(<PortraitLibraryDialog {...props} maxSelection={2} />);
  await selectPair(); fireEvent.click(screen.getByRole('button', { name: /^确认$/ }));
  view.rerender(<PortraitLibraryDialog {...props} maxSelection={1} />);
  finish.forEach(resolve => resolve()); await screen.findByRole('alert'); expect(pick).not.toHaveBeenCalled();
});

it.each([['VIDEO', 'video/mp4', '角色.mp4'], ['AUDIO', 'audio/mpeg', '角色.mp3']] as const)('uploads, restores and selects reviewed %s with trusted type and duration', async (kind, mime, name) => {
  const row = { ...portrait, kind, durationSeconds: 4.5, status: 'ACTIVE' };
  const uploadMedia = vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia);
  uploadMedia.mockResolvedValue(reply({ fileId: row.fileId, kind, durationSeconds: 4.5, sizeBytes: 12 }));
  submit.mockResolvedValue(reply(row)); get.mockResolvedValue(reply(row));
  const pick = vi.fn(); const props = { owner: 'owner-one', model: 'video-one', onClose: () => {}, onPick: pick };
  const view = render(<PortraitLibraryDialog {...props} />);
  await screen.findByText('还没有人像素材');
  const file = new File(['media'], name, { type: mime });
  fireEvent.change(screen.getByLabelText('上传人像'), { target: { files: [file] } });
  await screen.findByRole('option', { name: '人像 portrait-one' });
  expect(uploadMedia).toHaveBeenCalledWith({ file });
  expect(submit).toHaveBeenCalledWith({ fileId: row.fileId, model: row.model, kind }, expect.anything());
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).not.toHaveBeenCalled();
  view.unmount(); list.mockResolvedValue(reply([row]));
  render(<PortraitLibraryDialog {...props} />);
  fireEvent.click(await screen.findByRole('option', { name: '人像 portrait-one' }));
  fireEvent.click(screen.getByRole('button', { name: '确认' }));
  await waitFor(() => expect(pick).toHaveBeenCalledWith([expect.objectContaining({ portraitId: row.id, fileId: row.fileId, kind, durationSeconds: 4.5 })]));
});

it('keeps separate video and audio selection limits and rechecks a changed limit', async () => {
  const rows = ['VIDEO', 'VIDEO', 'AUDIO'].map((kind, index) => ({ ...portrait, id: `portrait-${index}`, fileId: `file-${index}`, kind, durationSeconds: 4, status: 'ACTIVE' }));
  list.mockResolvedValue(reply(rows)); get.mockImplementation(async id => reply(rows.find(row => row.id === id)));
  const pick = vi.fn();
  const props = { owner: 'owner-one', model: 'video-one', onClose: () => {}, onPick: pick, maxSelection: 3 };
  const view = render(<PortraitLibraryDialog {...props} maxSelectionByKind={{ VIDEO: 1, AUDIO: 1 }} />);
  fireEvent.click(await screen.findByRole('option', { name: '人像 portrait-0' }));
  expect(screen.getByRole('option', { name: '人像 portrait-1' })).toHaveAttribute('aria-disabled', 'true');
  expect(screen.getByRole('option', { name: '人像 portrait-2' })).toHaveAttribute('aria-disabled', 'false');
  fireEvent.click(screen.getByRole('option', { name: '人像 portrait-2' }));
  view.rerender(<PortraitLibraryDialog {...props} maxSelectionByKind={{ VIDEO: 0, AUDIO: 1 }} />);
  fireEvent.click(screen.getByRole('button', { name: '确认' }));
  await screen.findByText('可用名额已变化，请调整选择后重试');
  expect(pick).not.toHaveBeenCalled();
  expect(screen.getByRole('option', { name: '人像 portrait-0' })).toHaveAttribute('aria-disabled', 'false');
});

it.each([['IMAGE', 'image/png'], ['VIDEO', 'video/mp4'], ['AUDIO', 'audio/mpeg']] as const)('automatically reviews a locally selected %s file once when the library becomes ready', async (kind, mime) => {
  const row = { ...portrait, kind, durationSeconds: kind === 'IMAGE' ? null : 4.5 };
  submit.mockResolvedValue(reply(row));
  vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).mockResolvedValue(reply({ fileId: row.fileId }));
  const file = new File(['media'], 'local-media', { type: mime });
  const props = { owner: 'owner-one', model: 'video-one', title: '素材库', initialFile: file, onClose: () => {}, onPick: () => {} };
  const view = render(<PortraitLibraryDialog {...props} />);
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
  expect(submit).toHaveBeenCalledWith({ fileId: row.fileId, model: row.model, ...(kind === 'IMAGE' ? {} : { kind }) }, expect.anything());
  view.rerender(<PortraitLibraryDialog {...props} />);
  expect(submit).toHaveBeenCalledTimes(1);
  expect(screen.getByRole('option', { name: '人像 portrait-one' })).toHaveAttribute('aria-disabled', 'true');
});

it('preserves the original upload when canvas review admission fails', async () => {
  submit.mockRejectedValue(new api.ApiError(new Response(null, { status: 422 }), {}));
  const uploaded = vi.fn();
  render(<PortraitLibraryDialog owner="owner-one" model="video-one" title="素材库" initialFile={new File(['png'], '原图.png', { type: 'image/png' })}
    canvas={{ materials: [], onUploaded: uploaded, onReview: vi.fn() }} onClose={() => {}} onPick={() => {}} />);
  await screen.findByRole('alert');
  expect(uploaded).toHaveBeenCalledWith(expect.objectContaining({ fileId: 'file-one', name: '原图.png', kind: 'IMAGE', reviewPending: true }), expect.any(File));
});
it('keeps long canvas videos for depth without submitting an incompatible review', async () => {
  vi.mocked(api.storageUploadProcessingVideoPostApiV1FilesProcessingVideos).mockResolvedValue(reply({ fileId: 'long-video', durationSeconds: 44, sizeBytes: 100 }));
  const uploaded = vi.fn();
  render(<PortraitLibraryDialog owner="owner-one" model="video-one" title="素材库" initialFile={new File(['video'], 'long.mp4', { type: 'video/mp4' })}
    canvas={{ materials: [], onUploaded: uploaded, onReview: vi.fn() }} onClose={() => {}} onPick={() => {}} />);
  await screen.findByText(/长视频已保存/);
  expect(uploaded).toHaveBeenCalledWith(expect.objectContaining({ fileId: 'long-video', kind: 'VIDEO', durationSeconds: 44 }), expect.any(File));
  expect(submit).not.toHaveBeenCalled();
});
it('selects a pending canvas material as an original with separate review identity', async () => {
  list.mockResolvedValue(reply([portrait]));
  const pick = vi.fn();
  render(<PortraitLibraryDialog owner="owner-one" model="video-one" title="素材库" canvas={{ materials: [], onUploaded: vi.fn(), onReview: vi.fn() }} onClose={() => {}} onPick={pick} />);
  fireEvent.click(await screen.findByRole('option', { name: '人像 portrait-one' }));
  fireEvent.click(screen.getByRole('button', { name: '确认' }));
  await waitFor(() => expect(pick).toHaveBeenCalledWith([expect.objectContaining({ fileId: 'file-one', reviewId: 'portrait-one' })]));
  expect(pick.mock.calls[0][0][0]).not.toHaveProperty('portraitId');
});

it('reviews an existing original for a different model without uploading it again', async () => {
  const onReview = vi.fn();
  submit.mockResolvedValue(reply({ ...portrait, id: 'review-b', model: 'video-b' }));
  render(<PortraitLibraryDialog owner="owner-one" model="video-b" title="素材库" canvas={{
    materials: [{ fileId: 'file-one', reviewId: 'review-a', kind: 'IMAGE', name: '原图', preview: '' }], onUploaded: vi.fn(), onReview,
  }} onClose={() => {}} onPick={() => {}} />);
  fireEvent.click(await screen.findByRole('button', { name: '为当前模型审核' }));
  await waitFor(() => expect(submit).toHaveBeenCalledWith({ fileId: 'file-one', model: 'video-b' }, expect.anything()));
  await waitFor(() => expect(onReview).toHaveBeenCalledWith('file-one', 'review-b', portrait.status));
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).not.toHaveBeenCalled();
});

it('restores another-model review status without hiding the current-model re-review action', async () => {
  const onReview = vi.fn();
  get.mockResolvedValue(reply({ ...portrait, id: 'review-a', model: 'video-a', status: 'ACTIVE' }));
  render(<PortraitLibraryDialog owner="owner-one" model="video-b" title="素材库" canvas={{
    materials: [{ fileId: 'file-one', reviewId: 'review-a', reviewStatus: 'PROCESSING', kind: 'IMAGE', name: '原图', preview: '' }], onUploaded: vi.fn(), onReview,
  }} onClose={() => {}} onPick={() => {}} />);
  await waitFor(() => expect(get).toHaveBeenCalledWith('review-a'), { timeout: 4500 });
  await waitFor(() => expect(onReview).toHaveBeenCalledWith('file-one', 'review-a', 'ACTIVE'));
  expect(screen.getByRole('button', { name: '为当前模型审核' })).toBeEnabled();
  expect(screen.queryByRole('option', { name: '人像 review-a' })).not.toBeInTheDocument();
});

it('shares approved materials across models as original first-last frame files', async () => {
  const pick = vi.fn();
  list.mockResolvedValue(reply([{ ...portrait, status: 'ACTIVE' }]));
  get.mockResolvedValue(reply({ ...portrait, status: 'ACTIVE' }));
  render(<PortraitLibraryDialog owner="owner-one" model="other-model" originals onClose={() => {}} onPick={pick} />);
  fireEvent.click(await screen.findByRole('option', { name: '人像 portrait-one' }));
  fireEvent.click(screen.getByRole('button', { name: /^确认$/ }));
  await waitFor(() => expect(pick).toHaveBeenCalled());
  expect(pick.mock.calls[0][0][0]).toMatchObject({ fileId: 'file-one', kind: 'IMAGE' });
  expect(pick.mock.calls[0][0][0]).not.toHaveProperty('portraitId');
  expect(pick.mock.calls[0][0][0]).not.toHaveProperty('reviewId');
});
