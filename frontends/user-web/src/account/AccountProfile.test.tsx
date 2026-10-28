import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as api from '../api';
import { UserAccountProvider } from '../userSession';
import { AccountProfile } from './AccountProfile';

const options = vi.hoisted(() => vi.fn());
vi.mock('../api', () => ({
  userUpdateSelfPatchApiV1UsersMe: vi.fn(), storageUploadAvatarPostApiV1FilesAvatars: vi.fn(), storageAvatarAccessUrlGetApiV1FilesAvatarsFileIdAccessUrl: vi.fn(),
  paymentOptionsGetApiV1PaymentsRechargeOptions: options, paymentGetGetApiV1PaymentsRechargesId: vi.fn(),
  refreshSession: vi.fn(), userSelfGetApiV1UsersMe: vi.fn(), accessListGetApiV1AccessKeys: vi.fn(),
  billingBalanceGetApiV1BillingWalletsKeyId: vi.fn(), accessSecretGetApiV1AccessKeysIdSecret: vi.fn(), logout: vi.fn(),
}));
const reply = <T,>(data: T) => ({ data, status: 200 as const, headers: new Headers() });
const key = { ownerId: 'real-user', keyId: 'real-key', enabled: true, kind: 'MEDIA', accountType: 'ENTERPRISE', firstRechargeOrderId: 'paid-order' , maskedKey: null};
const clipboard = vi.fn();
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.paymentGetGetApiV1PaymentsRechargesId).mockResolvedValue(reply({
    orderId: 'paid-order', userId: 'real-user', accountType: 'ENTERPRISE', status: 'PAID', fulfilled: true,
    amount4: '1000.0000', rateSnapshot4: '1.0000', expectedPoints4: '1000.0000', source: 'PROVIDER',
    checkoutUrl: null, paymentMethod: null, expiresAt: '2026-09-09T00:00:00Z', createdAt: '2026-09-08T23:00:00Z', remark: null, submittedBy: null, confirmedBy: null, rejectedAt: null, rejectedBy: null,
  }));
  options.mockResolvedValue(reply({ userId: "real-user", accountType: "ENTERPRISE", firstRecharge: false, pointsPerYuan4: "1.0000", rules: [] }));
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: clipboard.mockResolvedValue(undefined) } });
  vi.mocked(api.refreshSession).mockResolvedValue({ authenticated: true, csrfToken: 'csrf', primary: true, roles: ['USER'], user: { id: 'real-user', username: '13900139000' } });
  vi.mocked(api.userSelfGetApiV1UsersMe).mockResolvedValue(reply({ id: 'real-user', phone: '13900139000', nickname: '真实企业用户', enabled: true, authVersion: 1, avatarFileId: null }));
  vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValue(reply([key]));
  vi.mocked(api.billingBalanceGetApiV1BillingWalletsKeyId).mockResolvedValue(reply({ availablePoints4: '8.1234', walletId: 'real-key', version: 1 }));
  vi.mocked(api.accessSecretGetApiV1AccessKeysIdSecret).mockResolvedValue(reply({ secret: 'server-returned-secret' }));
});
afterEach(cleanup);
const mount = () => render(<MemoryRouter><UserAccountProvider><AccountProfile /></UserAccountProvider></MemoryRouter>);

it('shows only real read-only profile and masked key until explicit no-store owner retrieval, copy and hide', async () => {
  mount();
  expect(await screen.findByText('真实企业用户')).toBeVisible();
  expect(screen.getByText('+86 139 **** 9000')).toBeVisible();
  expect(screen.getByText('企业账户')).toBeVisible();
  expect(screen.getByText('可用积分').parentElement).toHaveTextContent('8.1234 积分');
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  expect(screen.queryByText(/Nova|绑定新密钥|保存昵称/)).not.toBeInTheDocument();
  expect(api.accessSecretGetApiV1AccessKeysIdSecret).not.toHaveBeenCalled();
  expect(screen.getByLabelText('平台 Key')).toHaveTextContent('••••••••');
  fireEvent.click(screen.getByRole('button', { name: '显示 Key' }));
  expect(await screen.findByText('server-returned-secret')).toBeVisible();
  expect(api.accessSecretGetApiV1AccessKeysIdSecret).toHaveBeenCalledWith('real-key', expect.objectContaining({ cache: 'no-store', signal: expect.any(AbortSignal) }));
  fireEvent.click(screen.getByRole('button', { name: '复制 Key' }));
  await waitFor(() => expect(clipboard).toHaveBeenCalledWith('server-returned-secret'));
  fireEvent.click(screen.getByRole('button', { name: '隐藏 Key' }));
  expect(screen.queryByText('server-returned-secret')).not.toBeInTheDocument();
});
it('hides a revealed secret immediately on account refresh and ignores late retrieval', async () => {
  let resolve!: (value: ReturnType<typeof reply<{secret: string}>>) => void;
  vi.mocked(api.accessSecretGetApiV1AccessKeysIdSecret).mockReturnValueOnce(new Promise(done => { resolve = done; }));
  mount();
  fireEvent.click(await screen.findByRole('button', { name: '显示 Key' }));
  act(() => window.dispatchEvent(new Event('xzwl-account-changed')));
  await act(async () => resolve(reply({ secret: 'late-secret' })));
  expect(screen.queryByText('late-secret')).not.toBeInTheDocument();
  fireEvent.click(await screen.findByRole('button', { name: '显示 Key' }));
  expect(await screen.findByText('server-returned-secret')).toBeVisible();
  act(() => window.dispatchEvent(new Event('xzwl-account-changed')));
  expect(screen.queryByText('server-returned-secret')).not.toBeInTheDocument();
});
it('cannot retrieve a disabled key and distinguishes unopened accounts', async () => {
  options.mockResolvedValueOnce(reply({ userId: 'real-user', accountType: 'ENTERPRISE', firstRecharge: false, pointsPerYuan4: '1.0000', rules: [] })).mockResolvedValueOnce(reply({ userId: 'real-user', accountType: null, firstRecharge: true, pointsPerYuan4: '1.0000', rules: [] }));
  vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValueOnce(reply([{ ...key, enabled: false }])).mockResolvedValueOnce(reply([]));
  mount();
  expect(await screen.findByText('账户或密钥已禁用')).toBeVisible();
  expect(screen.queryByRole('button', { name: '显示 Key' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '刷新账户' }));
  expect(await screen.findByText('账户待开通')).toBeVisible();
  expect(api.accessSecretGetApiV1AccessKeysIdSecret).not.toHaveBeenCalled();
});
it('reports retrieval and clipboard failures without claiming success', async () => {
  vi.mocked(api.accessSecretGetApiV1AccessKeysIdSecret).mockRejectedValueOnce(new Error('denied'));
  mount();
  fireEvent.click(await screen.findByRole('button', { name: '显示 Key' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('密钥读取失败');
  fireEvent.click(screen.getByRole('button', { name: '显示 Key' }));
  await screen.findByText('server-returned-secret');
  clipboard.mockRejectedValueOnce(new Error('clipboard denied'));
  fireEvent.click(screen.getByRole('button', { name: '复制 Key' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('复制失败');
  expect(screen.queryByText('Key 已复制')).not.toBeInTheDocument();
});

it('focuses and scrolls the key section for the profile deep link', async () => {
  const scroll = vi.fn();
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: scroll });
  render(<MemoryRouter initialEntries={['/account/profile#keys']}><UserAccountProvider><AccountProfile /></UserAccountProvider></MemoryRouter>);
  await waitFor(() => expect(screen.getByRole('region', { name: '平台密钥' })).toHaveFocus());
  expect(scroll).toHaveBeenCalled();
});

it('copies the real user id and copies a hidden key only on explicit request', async () => {
  mount();
  await screen.findByText('真实企业用户');
  fireEvent.click(screen.getByRole('button', { name: '复制用户 ID' }));
  await waitFor(() => expect(clipboard).toHaveBeenCalledWith('real-user'));
  expect(api.accessSecretGetApiV1AccessKeysIdSecret).not.toHaveBeenCalled();
  fireEvent.click(await screen.findByRole('button', { name: '复制 Key' }));
  await waitFor(() => expect(clipboard).toHaveBeenCalledWith('server-returned-secret'));
  expect(screen.getByLabelText('平台 Key')).not.toHaveTextContent('server-returned-secret');
});

it('edits nickname and uploads an avatar before saving, then refreshes the account', async () => {
  mount(); await screen.findByText('真实企业用户');
  fireEvent.click(screen.getByRole('button', { name: '编辑资料' }));
  fireEvent.change(screen.getByLabelText('昵称'), { target: { value: '新的昵称' } });
  const file = new File(['image'], 'avatar.png', { type: 'image/png' });
  fireEvent.change(screen.getByLabelText('选择头像'), { target: { files: [file] } });
  vi.mocked(api.storageUploadAvatarPostApiV1FilesAvatars).mockResolvedValue({ data: { fileId: 'avatar-owned' }, status: 201, headers: new Headers() } as Awaited<ReturnType<typeof api.storageUploadAvatarPostApiV1FilesAvatars>>);
  vi.mocked(api.userUpdateSelfPatchApiV1UsersMe).mockResolvedValue(reply({ id: 'real-user', phone: '13900139000', nickname: '新的昵称', enabled: true, authVersion: 1, avatarFileId: 'avatar-owned' }));
  vi.mocked(api.userSelfGetApiV1UsersMe).mockResolvedValue(reply({ id: 'real-user', phone: '13900139000', nickname: '新的昵称', enabled: true, authVersion: 1, avatarFileId: 'avatar-owned' }));
  vi.mocked(api.storageAvatarAccessUrlGetApiV1FilesAvatarsFileIdAccessUrl).mockResolvedValue(reply({ url: 'https://example.com/avatar.png', expiresAt: '2027-01-01T00:00:00Z' }));
  fireEvent.click(screen.getByRole('button', { name: '保存资料' }));
  await waitFor(() => expect(api.userUpdateSelfPatchApiV1UsersMe).toHaveBeenCalledWith({ nickname: '新的昵称', avatarFileId: 'avatar-owned' }, expect.objectContaining({ signal: expect.any(AbortSignal) })));
  expect(await screen.findByText('新的昵称')).toBeVisible();
  expect(await screen.findByRole('img', { name: '用户头像' })).toHaveAttribute('src', 'https://example.com/avatar.png');
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});
it('keeps failed edits for retry and does not save profile after upload rejection', async () => {
  mount(); await screen.findByText('真实企业用户');
  fireEvent.click(screen.getByRole('button', { name: '编辑资料' }));
  fireEvent.change(screen.getByLabelText('昵称'), { target: { value: '未保存昵称' } });
  fireEvent.change(screen.getByLabelText('选择头像'), { target: { files: [new File(['image'], 'avatar.jpg', { type: 'image/jpeg' })] } });
  vi.mocked(api.storageUploadAvatarPostApiV1FilesAvatars).mockRejectedValue(new Error('offline'));
  fireEvent.click(screen.getByRole('button', { name: '保存资料' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('保存失败');
  expect(screen.getByLabelText('昵称')).toHaveValue('未保存昵称');
  expect(api.userUpdateSelfPatchApiV1UsersMe).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '取消' }));
  expect(screen.getByText('真实企业用户')).toBeVisible();
});
it('discards an editing session when the signed-in user changes', async () => {
  mount(); await screen.findByText('真实企业用户');
  fireEvent.click(screen.getByRole('button', { name: '编辑资料' }));
  fireEvent.change(screen.getByLabelText('昵称'), { target: { value: '旧用户草稿' } });
  vi.mocked(api.refreshSession).mockResolvedValue({ authenticated: true, csrfToken: 'csrf', primary: true, roles: ['USER'], user: { id: 'other-user', username: 'other' } });
  vi.mocked(api.userSelfGetApiV1UsersMe).mockResolvedValue(reply({ id: 'other-user', phone: '13900139001', nickname: '另一用户', enabled: true, authVersion: 1, avatarFileId: null }));
  act(() => window.dispatchEvent(new Event('xzwl-account-changed')));
  await screen.findByText('另一用户');
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(api.userUpdateSelfPatchApiV1UsersMe).not.toHaveBeenCalled();
  expect(api.storageUploadAvatarPostApiV1FilesAvatars).not.toHaveBeenCalled();
});

it('blocks an in-flight avatar edit while account refresh has not confirmed the identity', async () => {
  mount(); await screen.findByText('真实企业用户');
  fireEvent.click(screen.getByRole('button', { name: '编辑资料' }));
  fireEvent.change(screen.getByLabelText('选择头像'), { target: { files: [new File(['image'], 'avatar.png', { type: 'image/png' })] } });
  let finishUpload!: () => void;
  vi.mocked(api.storageUploadAvatarPostApiV1FilesAvatars).mockReturnValue(new Promise(resolve => {
    finishUpload = () => resolve({ data: { fileId: 'avatar-owned' }, status: 201, headers: new Headers() } as Awaited<ReturnType<typeof api.storageUploadAvatarPostApiV1FilesAvatars>>);
  }));
  fireEvent.click(screen.getByRole('button', { name: '保存资料' }));
  vi.mocked(api.refreshSession).mockReturnValue(new Promise(() => {}));
  act(() => window.dispatchEvent(new Event('focus')));
  await act(async () => finishUpload());
  expect(await screen.findByRole('alert')).toHaveTextContent('保存失败');
  expect(api.userUpdateSelfPatchApiV1UsersMe).not.toHaveBeenCalled();
});
