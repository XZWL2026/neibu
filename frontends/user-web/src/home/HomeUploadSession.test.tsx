import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createRef } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as api from '../api';
import { UserAccountProvider, useUserSession } from '../userSession';
import { HomePromptComposer } from './HomePromptComposer';

vi.mock('../api', async original => ({ ...await original<typeof import('../api')>(),
  refreshSession: vi.fn(), userSelfGetApiV1UsersMe: vi.fn(), accessListGetApiV1AccessKeys: vi.fn(),
  paymentOptionsGetApiV1PaymentsRechargeOptions: vi.fn(), paymentGetGetApiV1PaymentsRechargesId: vi.fn(),
  billingBalanceGetApiV1BillingWalletsKeyId: vi.fn(), mediaOptionsGetApiV1MediaGenerationOptions: vi.fn(),
  storageUploadReferenceImagePostApiV1FilesReferenceImages: vi.fn(), storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl: vi.fn(),
  mediaPortraitListGetApiV1MediaPortraits: vi.fn(), mediaPortraitSubmitPostApiV1MediaPortraits: vi.fn(),
}));
const reply = (data: unknown) => ({ data, status: 200, headers: new Headers() }) as never;
const identity = { authenticated: true, csrfToken: 'test', primary: true, roles: ['USER'], user: { id: 'owner', username: 'test' } };
const base = { aspectRatios: ['16:9', '9:16'], resolutions: ['1K'], maxCount: 4, maxReferenceImages: 3,
  minDurationSeconds: 4, maxDurationSeconds: 12, durationStepSeconds: 2, referenceFollowsFirstFrame: false };
const image = { ...base, type: 'IMAGE', model: 'image-test', displayName: 'Image 2' };
const video = { ...base, type: 'VIDEO', model: 'video-test', displayName: 'Seedance 2.5', maxReferenceImages: 2 };
function AccountProbe() {
  const account = useUserSession();
  return <output data-testid="account">{JSON.stringify(account)}</output>;
}
function account() { return JSON.parse(screen.getByTestId('account').textContent!); }
async function mount(kind: 'image' | 'video' = 'image') {
  const view = render(<MemoryRouter><UserAccountProvider><AccountProbe /><HomePromptComposer inputRef={createRef()} prompt="海边晨光"
    kind={kind} modelName={kind === 'image' ? 'Image 2' : 'Seedance 2.5'} error="" choices={[{ name: 'Image 2', kind: 'image' }, { name: 'Seedance 2.5', kind: 'video' }]}
    onPrompt={() => {}} onModel={() => {}} onSubmit={event => event.preventDefault()} /></UserAccountProvider></MemoryRouter>);
  await waitFor(() => expect(account().status).toBe('ready'));
  fireEvent.focus(screen.getByRole('textbox'));
  await waitFor(() => expect(screen.getByRole('button', { name: /参数摘要：/ })).toBeEnabled());
  if (kind === 'video') {
    fireEvent.click(screen.getByRole('button', { name: '视频生成模式' }));
    fireEvent.click(screen.getByRole('menuitemradio', { name: '首尾帧' }));
  }
  return view;
}
function openPicker() {
  fireEvent.click(screen.getByRole('button', { name: '添加素材' }));
  fireEvent.click(screen.getByRole('menuitem', { name: '本地上传' }));
  return screen.getByLabelText('上传素材') as HTMLInputElement;
}
function focusRefresh() {
  let finish!: (value: typeof identity) => void;
  vi.mocked(api.refreshSession).mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
  act(() => window.dispatchEvent(new Event('focus')));
  return finish;
}
beforeEach(() => {
  vi.resetAllMocks(); localStorage.clear(); sessionStorage.clear();
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  vi.mocked(api.refreshSession).mockResolvedValue(identity);
  vi.mocked(api.userSelfGetApiV1UsersMe).mockResolvedValue(reply({ id: 'owner', enabled: true, nickname: 'Test', phone: 'test', avatarFileId: null }));
  vi.mocked(api.paymentOptionsGetApiV1PaymentsRechargeOptions).mockResolvedValue(reply({ userId: 'owner', accountType: 'PERSONAL' }));
  vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValue(reply([{ ownerId: 'owner', keyId: 'key', enabled: true, kind: 'MEDIA', accountType: 'PERSONAL', firstRechargeOrderId: 'paid' }]));
  vi.mocked(api.paymentGetGetApiV1PaymentsRechargesId).mockResolvedValue(reply({ userId: 'owner', orderId: 'paid', fulfilled: true, status: 'PAID' }));
  vi.mocked(api.billingBalanceGetApiV1BillingWalletsKeyId).mockResolvedValue(reply({ walletId: 'key', availablePoints4: '100.0000' }));
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({ models: [image, video] }));
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockResolvedValue(reply({ fileId: 'uploaded' }));
  vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockResolvedValue(reply({ url: 'https://assets.example/image.png' }));
  vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValue(reply([]));
  vi.mocked(api.mediaPortraitSubmitPostApiV1MediaPortraits).mockImplementation(async body => reply({ ...body, id: `review-${body.fileId}`, status: 'ACTIVE', kind: 'IMAGE' }));
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

it.each(['image', 'video'] as const)('keeps the native %s picker mounted, shows the selected file during focus revalidation, and uploads after the same owner is verified', async kind => {
  const { container } = await mount(kind);
  const input = openPicker();
  const finish = focusRefresh();
  expect(account()).toMatchObject({ status: 'loading', authenticated: false, currentKey: null, keys: [], availablePoints4: null, canGenerate: false });
  expect(input.isConnected).toBe(true);
  fireEvent.change(input, { target: { files: [new File(['image'], 'focus-image.png', { type: 'image/png' })] } });
  expect(screen.getByText('focus-image.png')).toBeVisible();
  expect(container.querySelector('form')).toHaveAttribute('data-expanded', 'true');
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).not.toHaveBeenCalled();
  await act(async () => finish(identity));
  await waitFor(() => expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).toHaveBeenCalledTimes(1));
  await waitFor(() => expect(screen.getByText('已添加 1 份素材')).toBeVisible());
  expect(screen.getByRole('button', { name: kind === 'image' ? '图片生成模式' : '视频生成模式' })).toHaveTextContent(kind === 'image' ? '智能图片' : '首尾帧');
  expect(api.mediaOptionsGetApiV1MediaGenerationOptions).toHaveBeenCalledTimes(1);
});

it('accepts an in-flight upload completed during same-owner focus revalidation', async () => {
  let uploaded!: () => void;
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockReturnValueOnce(new Promise(resolve => { uploaded = () => resolve(reply({ fileId: 'uploaded' })); }));
  await mount();
  fireEvent.change(openPicker(), { target: { files: [new File(['image'], 'inflight.png', { type: 'image/png' })] } });
  await waitFor(() => expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).toHaveBeenCalledTimes(1));
  const finish = focusRefresh();
  await act(async () => uploaded());
  await waitFor(() => expect(screen.getByText(/已添加 1 份素材/)).toBeVisible());
  await act(async () => finish(identity));
  await waitFor(() => expect(account().status).toBe('ready'));
  expect(screen.getByText(/已添加 1 份素材/)).toBeVisible();
});

it('keeps queued files through repeated focus events and ignores the superseded session response', async () => {
  await mount();
  const input = openPicker();
  const first = focusRefresh();
  const latest = focusRefresh();
  expect(input.isConnected).toBe(true);
  fireEvent.change(input, { target: { files: [new File(['image'], 'repeat-focus.png', { type: 'image/png' })] } });
  await act(async () => first({ ...identity, authenticated: false, user: null } as never));
  expect(screen.getByText('repeat-focus.png')).toBeVisible();
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).not.toHaveBeenCalled();
  await act(async () => latest(identity));
  await waitFor(() => expect(screen.getByText('已添加 1 份素材')).toBeVisible());
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).toHaveBeenCalledTimes(1);
});

it('queues replacement in its original slot while the file chooser triggers focus revalidation', async () => {
  await mount('video');
  fireEvent.change(openPicker(), { target: { files: [new File(['image'], 'first.png', { type: 'image/png' })] } });
  await waitFor(() => expect(screen.getByText('已添加 1 份素材')).toBeVisible());
  await waitFor(() => expect(screen.getByLabelText('替换首帧')).toBeEnabled());
  const input = screen.getByLabelText('替换首帧');
  fireEvent.click(input);
  const finish = focusRefresh();
  fireEvent.change(input, { target: { files: [new File(['image'], 'replacement.png', { type: 'image/png' })] } });
  expect(screen.getByText('replacement.png')).toBeVisible();
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).toHaveBeenCalledTimes(1);
  await act(async () => finish(identity));
  await waitFor(() => expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).toHaveBeenCalledTimes(2));
  await waitFor(() => expect(screen.getByText('已添加 1 份素材')).toBeVisible());
  expect(screen.getByRole('link', { name: '预览首帧' })).toBeVisible();
  expect(screen.queryByRole('link', { name: '预览尾帧' })).not.toBeInTheDocument();
});

it.each(['anonymous', 'different-owner'] as const)('discards queued selections when focus revalidation returns %s', async result => {
  await mount();
  const input = openPicker();
  const finish = focusRefresh();
  fireEvent.change(input, { target: { files: [new File(['image'], 'private.png', { type: 'image/png' })] } });
  expect(screen.getByText('private.png')).toBeVisible();
  if (result === 'different-owner') vi.mocked(api.userSelfGetApiV1UsersMe).mockReturnValue(new Promise(() => {}));
  await act(async () => finish(result === 'anonymous' ? { ...identity, authenticated: false, user: null } as never
    : { ...identity, user: { id: 'other', username: 'other' } }));
  await waitFor(() => expect(screen.queryByText('private.png')).not.toBeInTheDocument());
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).not.toHaveBeenCalled();
  expect(account().canGenerate).toBe(false);
});
