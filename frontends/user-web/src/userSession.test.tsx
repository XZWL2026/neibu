import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as api from './api';
import * as sessionModule from './userSession';

const options = vi.hoisted(() => vi.fn());
vi.mock('./api', () => ({
  paymentOptionsGetApiV1PaymentsRechargeOptions: options, paymentGetGetApiV1PaymentsRechargesId: vi.fn(),
  refreshSession: vi.fn(), userSelfGetApiV1UsersMe: vi.fn(), accessListGetApiV1AccessKeys: vi.fn(),
  storageAvatarAccessUrlGetApiV1FilesAvatarsFileIdAccessUrl: vi.fn(),
  billingBalanceGetApiV1BillingWalletsKeyId: vi.fn(), accessSecretGetApiV1AccessKeysIdSecret: vi.fn(), logout: vi.fn(),
}));
const reply = <T,>(data: T) => ({ data, status: 200 as const, headers: new Headers() });
const identity = { authenticated: true, csrfToken: 'csrf', primary: true, roles: ['USER'], user: { id: 'u-real', username: '13800138000' } };
const profile = { id: 'u-real', phone: '13800138000', nickname: '真实用户', enabled: true, authVersion: 1, avatarFileId: null };
const key = { ownerId: 'u-real', keyId: 'key-real', enabled: true, kind: 'MEDIA', accountType: 'PERSONAL', firstRechargeOrderId: 'paid-order' , maskedKey: null};
const firstOrder = {
    orderId: 'paid-order', userId: 'u-real', accountType: 'PERSONAL', status: 'PAID', fulfilled: true,
    amount4: '1000.0000', rateSnapshot4: '1.0000', expectedPoints4: '1000.0000', source: 'PROVIDER',
    checkoutUrl: null, paymentMethod: null, expiresAt: '2026-09-09T00:00:00Z', createdAt: '2026-09-08T23:00:00Z', remark: null, submittedBy: null, confirmedBy: null, rejectedAt: null, rejectedBy: null,
  };
function Probe() {
  const session = sessionModule.useUserSession();
  return <><output data-testid="session">{JSON.stringify(session)}</output><button onClick={() => void session.refresh()}>刷新</button><button onClick={() => void session.logout()}>退出</button></>;
}
function snapshot() { return JSON.parse(screen.getByTestId('session').textContent!); }
function mount() {
  expect(sessionModule.UserAccountProvider).toBeTypeOf('function');
  return render(<sessionModule.UserAccountProvider><Probe /></sessionModule.UserAccountProvider>);
}
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.paymentGetGetApiV1PaymentsRechargesId).mockResolvedValue(reply(firstOrder));
  options.mockResolvedValue(reply({ userId: profile.id, accountType: "PERSONAL", firstRecharge: false, pointsPerYuan4: "1.0000", rules: [] }));
  vi.mocked(api.refreshSession).mockResolvedValue(identity);
  vi.mocked(api.userSelfGetApiV1UsersMe).mockResolvedValue(reply(profile));
  vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValue(reply([key]));
  vi.mocked(api.billingBalanceGetApiV1BillingWalletsKeyId).mockResolvedValue(reply({ availablePoints4: '12.3456', walletId: 'key-real', version: 1 }));
  vi.mocked(api.logout).mockResolvedValue(reply({ success: true }));
});
afterEach(cleanup);

it('exports a real account provider and selects only the owner key without retrieving its secret', async () => {
  vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValue(reply([{ ...key, keyId: 'foreign', ownerId: 'someone-else' }, key]));
  mount();
  await waitFor(() => expect(snapshot().status).toBe('ready'));
  expect(snapshot()).toMatchObject({ userId: 'u-real', phone: profile.phone, nickname: profile.nickname, accountType: 'PERSONAL', availablePoints4: '12.3456', canGenerate: true, currentKey: { id: 'key-real', relationship: 'Owner' } });
  expect(snapshot().keys).toHaveLength(1);
  expect(api.accessSecretGetApiV1AccessKeysIdSecret).not.toHaveBeenCalled();
});
it('bootstraps anonymous IAM before protected reads and refreshes after login event', async () => {
  vi.mocked(api.refreshSession).mockResolvedValueOnce({ ...identity, authenticated: false, user: null });
  mount();
  await waitFor(() => expect(snapshot().status).toBe('anonymous'));
  expect(snapshot().currentKey).toBeNull();
  expect(api.userSelfGetApiV1UsersMe).not.toHaveBeenCalled();
  expect(api.accessListGetApiV1AccessKeys).not.toHaveBeenCalled();
  act(() => window.dispatchEvent(new Event('xzwl-account-changed')));
  await waitFor(() => expect(snapshot().userId).toBe('u-real'));
});
it('retains a real zero-balance key without granting generation and refreshes payment changes', async () => {
  vi.mocked(api.billingBalanceGetApiV1BillingWalletsKeyId).mockResolvedValueOnce(reply({ availablePoints4: '0.0000', walletId: 'key-real', version: 1 }));
  mount();
  await waitFor(() => expect(snapshot().status).toBe('ready'));
  expect(snapshot()).toMatchObject({ canGenerate: false, currentKey: { id: 'key-real' }, availablePoints4: '0.0000' });
  act(() => window.dispatchEvent(new Event('xzwl-account-changed')));
  await waitFor(() => expect(snapshot().canGenerate).toBe(true));
});
it.each(['key', 'user'])('does not bind or grant eligibility when %s is disabled', async (disabled) => {
  if (disabled === 'key') vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValue(reply([{ ...key, enabled: false }]));
  else vi.mocked(api.userSelfGetApiV1UsersMe).mockResolvedValue(reply({ ...profile, enabled: false }));
  mount();
  await waitFor(() => expect(snapshot().status).toBe('disabled'));
  expect(snapshot()).toMatchObject({ currentKey: null, canGenerate: false, userId: profile.id });
  expect(api.billingBalanceGetApiV1BillingWalletsKeyId).not.toHaveBeenCalled();
});
it('shows not-open without inventing a key and allows retry after a read failure', async () => {
  options.mockResolvedValueOnce(reply({ userId: profile.id, accountType: null, firstRecharge: true, pointsPerYuan4: '1.0000', rules: [] }));
  vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValueOnce(reply([]));
  mount();
  await waitFor(() => expect(snapshot().status).toBe('not-open'));
  expect(snapshot().keys).toEqual([]);
  expect(snapshot().currentKey).toBeNull();
  vi.mocked(api.userSelfGetApiV1UsersMe).mockRejectedValueOnce(new Error('offline'));
  fireEvent.click(screen.getByText('刷新'));
  await waitFor(() => expect(snapshot().status).toBe('error'));
  expect(snapshot().canGenerate).toBe(false);
  fireEvent.click(screen.getByText('刷新'));
  await waitFor(() => expect(snapshot().status).toBe('ready'));
});
it('clears the account after real logout and rejects a late balance response', async () => {
  let resolve!: (value: ReturnType<typeof reply<{ availablePoints4: string; walletId: string; version: number }>>) => void;
  vi.mocked(api.billingBalanceGetApiV1BillingWalletsKeyId).mockReturnValueOnce(new Promise(done => { resolve = done; }));
  mount();
  await waitFor(() => expect(api.billingBalanceGetApiV1BillingWalletsKeyId).toHaveBeenCalled());
  fireEvent.click(screen.getByText('退出'));
  await waitFor(() => expect(snapshot().status).toBe('anonymous'));
  expect(api.logout).toHaveBeenCalledTimes(1);
  await act(async () => resolve(reply({ availablePoints4: '99.0000', walletId: 'key-real', version: 2 })));
  expect(snapshot()).toMatchObject({ currentKey: null, userId: null, canGenerate: false });
});
it('keeps legacy state inside the explicit fixture provider', () => {
  render(<sessionModule.UserFixtureProvider><Probe /></sessionModule.UserFixtureProvider>);
  expect(snapshot().nickname).toBe('Nova');
  expect(api.refreshSession).not.toHaveBeenCalled();
});

it('preserves Payment paid identity while fulfillment has not yet created a primary key', async () => {
  options.mockResolvedValue(reply({ userId: profile.id, accountType: 'ENTERPRISE', firstRecharge: false, pointsPerYuan4: '1.0000', rules: [] }));
  vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValue(reply([]));
  mount();
  await waitFor(() => expect(snapshot().status).toBe('pending-activation'));
  expect(snapshot()).toMatchObject({ accountType: 'ENTERPRISE', currentKey: null, canGenerate: false });
});
it('does not grant media eligibility to a real API account', async () => {
  const first = firstOrder;
  vi.mocked(api.paymentGetGetApiV1PaymentsRechargesId).mockResolvedValue(reply({ ...first, accountType: 'API' }));
  options.mockResolvedValue(reply({ userId: profile.id, accountType: 'API', firstRecharge: false, pointsPerYuan4: '1.0000', rules: [] }));
  vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValue(reply([{ ...key, accountType: 'API', kind: 'API' }]));
  mount();
  await waitFor(() => expect(snapshot().status).toBe('ready'));
  expect(snapshot()).toMatchObject({ accountType: 'API', canGenerate: false, currentKey: { id: key.keyId, kind: 'API Key' } });
});
it('does not bind a mismatched or ambiguous owner primary', async () => {
  vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValueOnce(reply([{ ...key, kind: 'API' }])).mockResolvedValueOnce(reply([key, { ...key, keyId: 'another' }]));
  mount();
  await waitFor(() => expect(snapshot().status).toBe('error'));
  expect(snapshot().currentKey).toBeNull();
  fireEvent.click(screen.getByText('刷新'));
  await waitFor(() => expect(api.accessListGetApiV1AccessKeys).toHaveBeenCalledTimes(2));
  await waitFor(() => expect(snapshot().status).toBe('error'));
  expect(snapshot().currentKey).toBeNull();
  expect(api.billingBalanceGetApiV1BillingWalletsKeyId).not.toHaveBeenCalled();
});

it.each(['owner', 'type'])('refuses conflicting Payment %s metadata without binding a key', async (field) => {
  options.mockResolvedValue(reply({ userId: field === 'owner' ? 'foreign-user' : profile.id,
    accountType: field === 'type' ? 'ENTERPRISE' : 'PERSONAL', firstRecharge: false, pointsPerYuan4: '1.0000', rules: [] }));
  mount();
  await waitFor(() => expect(snapshot().status).toBe('error'));
  expect(snapshot().currentKey).toBeNull();
  expect(api.billingBalanceGetApiV1BillingWalletsKeyId).not.toHaveBeenCalled();
});

it('waits for first recharge fulfillment before binding, then refreshes disabled and enabled keys on window focus', async () => {
  const first = firstOrder;
  vi.mocked(api.paymentGetGetApiV1PaymentsRechargesId).mockResolvedValueOnce(reply({ ...first, fulfilled: false }));
  mount();
  await waitFor(() => expect(snapshot().status).toBe('awaiting-credit'));
  expect(snapshot()).toMatchObject({ accountType: 'PERSONAL', currentKey: null, canGenerate: false });
  expect(api.billingBalanceGetApiV1BillingWalletsKeyId).not.toHaveBeenCalled();
  vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValueOnce(reply([{ ...key, enabled: false }]));
  act(() => window.dispatchEvent(new Event('focus')));
  await waitFor(() => expect(snapshot().status).toBe('disabled'));
  act(() => window.dispatchEvent(new Event('focus')));
  await waitFor(() => expect(snapshot().status).toBe('ready'));
  expect(snapshot().canGenerate).toBe(true);
});
it('keeps a refunded fulfilled account ready with its real zero balance', async () => {
  const first = firstOrder;
  vi.mocked(api.paymentGetGetApiV1PaymentsRechargesId).mockResolvedValue(reply({ ...first, status: 'REFUNDED' }));
  vi.mocked(api.billingBalanceGetApiV1BillingWalletsKeyId).mockResolvedValue(reply({ availablePoints4: '0.0000', walletId: key.keyId, version: 2 }));
  mount();
  await waitFor(() => expect(snapshot().status).toBe('ready'));
  expect(snapshot()).toMatchObject({ accountType: 'PERSONAL', currentKey: { id: key.keyId }, availablePoints4: '0.0000', canGenerate: false });
});
it('does not restart account reads while logout is in flight', async () => {
  let finishLogout!: () => void;
  vi.mocked(api.logout).mockReturnValueOnce(new Promise(resolve => { finishLogout = () => resolve(reply({ success: true as const })); }));
  mount();
  await waitFor(() => expect(snapshot().status).toBe('ready'));
  const reads = vi.mocked(api.refreshSession).mock.calls.length;
  fireEvent.click(screen.getByText('退出'));
  await act(async () => { window.dispatchEvent(new Event('focus')); window.dispatchEvent(new Event('xzwl-account-changed')); });
  expect(api.refreshSession).toHaveBeenCalledTimes(reads);
  await act(async () => finishLogout());
  expect(snapshot()).toMatchObject({ status: 'anonymous', currentKey: null, userId: null, canGenerate: false });
});
it('keeps the original key after an administrator changes personal to enterprise without rewriting the paid snapshot', async () => {
 options.mockResolvedValue(reply({userId:profile.id,accountType:'ENTERPRISE',firstRecharge:false,pointsPerYuan4:'10.0000',rules:[]}));
 vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValue(reply([{...key,accountType:'ENTERPRISE'}]));
 mount();await waitFor(()=>expect(snapshot().status).toBe('ready'));
 expect(snapshot()).toMatchObject({accountType:'ENTERPRISE',currentKey:{id:key.keyId},availablePoints4:'12.3456'});
});
it('recognizes an administrator-created unpaid primary key as pending activation',async()=>{
 options.mockResolvedValue(reply({userId:profile.id,accountType:'PERSONAL',firstRecharge:true,pointsPerYuan4:'10.0000',rules:[]}));
 vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValue(reply([{...key,firstRechargeOrderId:null}]));
 mount();await waitFor(()=>expect(snapshot().status).toBe('pending-activation'));
 expect(snapshot().keys[0].id).toBe(key.keyId);expect(snapshot().canGenerate).toBe(false);
 expect(api.paymentGetGetApiV1PaymentsRechargesId).not.toHaveBeenCalled();
});

it.each(['sk-realBoundValue****', 'sk-unmaskedSecret', 'internal-key-id'])('only admits a server masked value for automatic key display: %s', async maskedKey => {
  vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValue(reply([{ ...key, maskedKey }]));
  mount();
  await waitFor(() => expect(snapshot().status).toBe('ready'));
  expect(snapshot().currentKey.maskedKey).toBe(maskedKey.endsWith('****') ? maskedKey : null);
  expect(api.accessSecretGetApiV1AccessKeysIdSecret).not.toHaveBeenCalled();
});

const avatarReply = (url = 'https://assets.example/avatar.jpg', expiresAt = new Date(Date.now() + 3600000).toISOString()) => reply({ url, expiresAt });
async function mountAvatar() {
  vi.mocked(api.userSelfGetApiV1UsersMe).mockResolvedValue(reply({ ...profile, avatarFileId: 'avatar-a' }));
  vi.mocked(api.storageAvatarAccessUrlGetApiV1FilesAvatarsFileIdAccessUrl).mockResolvedValue(avatarReply());
  mount();
  await waitFor(() => expect(snapshot().avatarUrl).toBe('https://assets.example/avatar.jpg'));
}
it('retains the avatar during focus refresh without retaining credentials or fetching a new URL', async () => {
  await mountAvatar();
  let finish!: (value: typeof identity) => void;
  vi.mocked(api.refreshSession).mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
  act(() => window.dispatchEvent(new Event('focus')));
  expect(snapshot()).toMatchObject({ status: 'loading', userId: 'u-real', avatarUrl: 'https://assets.example/avatar.jpg', currentKey: null, keys: [], availablePoints4: null, canGenerate: false, authenticated: false });
  await act(async () => finish(identity));
  await waitFor(() => expect(snapshot().status).toBe('ready'));
  expect(api.storageAvatarAccessUrlGetApiV1FilesAvatarsFileIdAccessUrl).toHaveBeenCalledTimes(1);
});
it.each(['remove', 'switch', 'anonymous', 'failure', 'logout'])('clears the previous avatar on %s', async action => {
  await mountAvatar();
  if (action === 'remove') vi.mocked(api.userSelfGetApiV1UsersMe).mockResolvedValue(reply(profile));
  if (action === 'switch') {
    vi.mocked(api.refreshSession).mockResolvedValue({ ...identity, user: { ...identity.user, id: 'other' } });
    vi.mocked(api.userSelfGetApiV1UsersMe).mockReturnValue(new Promise(() => {}));
  }
  if (action === 'anonymous') vi.mocked(api.refreshSession).mockResolvedValue({ ...identity, authenticated: false, user: null });
  if (action === 'failure') vi.mocked(api.refreshSession).mockRejectedValue(new Error('offline'));
  fireEvent.click(screen.getByText(action === 'logout' ? '退出' : '刷新'));
  await waitFor(() => expect(snapshot().avatarUrl).toBeNull());
  expect(snapshot().canGenerate).toBe(action === 'remove');
});
it('fetches a replacement avatar and renews expired URLs on refresh', async () => {
  await mountAvatar();
  vi.mocked(api.userSelfGetApiV1UsersMe).mockResolvedValue(reply({ ...profile, avatarFileId: 'avatar-b' }));
  vi.mocked(api.storageAvatarAccessUrlGetApiV1FilesAvatarsFileIdAccessUrl).mockResolvedValueOnce(avatarReply('https://assets.example/new.jpg', new Date(Date.now() - 1).toISOString()));
  fireEvent.click(screen.getByText('刷新'));
  await waitFor(() => expect(snapshot().avatarUrl).toBe('https://assets.example/new.jpg'));
  vi.mocked(api.storageAvatarAccessUrlGetApiV1FilesAvatarsFileIdAccessUrl).mockResolvedValue(avatarReply('https://assets.example/renewed.jpg'));
  fireEvent.click(screen.getByText('刷新'));
  await waitFor(() => expect(snapshot().avatarUrl).toBe('https://assets.example/renewed.jpg'));
  expect(api.storageAvatarAccessUrlGetApiV1FilesAvatarsFileIdAccessUrl).toHaveBeenCalledTimes(3);
});

it('restores a still-valid cached avatar after a failed replacement is reverted', async () => {
  await mountAvatar();
  vi.mocked(api.userSelfGetApiV1UsersMe).mockResolvedValue(reply({ ...profile, avatarFileId: 'avatar-b' }));
  vi.mocked(api.storageAvatarAccessUrlGetApiV1FilesAvatarsFileIdAccessUrl).mockRejectedValue(new Error('offline'));
  fireEvent.click(screen.getByText('刷新'));
  await waitFor(() => expect(api.storageAvatarAccessUrlGetApiV1FilesAvatarsFileIdAccessUrl).toHaveBeenCalledTimes(2));
  expect(snapshot().avatarUrl).toBeNull();
  vi.mocked(api.userSelfGetApiV1UsersMe).mockResolvedValue(reply({ ...profile, avatarFileId: 'avatar-a' }));
  fireEvent.click(screen.getByText('刷新'));
  await waitFor(() => expect(snapshot().avatarUrl).toBe('https://assets.example/avatar.jpg'));
  expect(api.storageAvatarAccessUrlGetApiV1FilesAvatarsFileIdAccessUrl).toHaveBeenCalledTimes(2);
});
it('ignores a late avatar URL response after logout', async () => {
  let finish!: () => void;
  vi.mocked(api.userSelfGetApiV1UsersMe).mockResolvedValue(reply({ ...profile, avatarFileId: 'avatar-a' }));
  vi.mocked(api.storageAvatarAccessUrlGetApiV1FilesAvatarsFileIdAccessUrl).mockReturnValue(new Promise(resolve => { finish = () => resolve(avatarReply()); }));
  mount();
  await waitFor(() => expect(api.storageAvatarAccessUrlGetApiV1FilesAvatarsFileIdAccessUrl).toHaveBeenCalledTimes(1));
  fireEvent.click(screen.getByText('退出'));
  await waitFor(() => expect(snapshot().status).toBe('anonymous'));
  await act(async () => finish());
  expect(snapshot().avatarUrl).toBeNull();
});
