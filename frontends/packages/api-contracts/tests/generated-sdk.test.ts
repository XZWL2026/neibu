import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import {
  configureGateway, getSession, loginAdmin, loginWithOtp, logout, sendOtp,
  billingEntriesGetApiV1BillingWalletsKeyIdLedger,
  storageUploadAvatarPostApiV1FilesAvatars
} from '../src/new-system';

let fetchMock: ReturnType<typeof vi.fn>;
const session = (csrfToken: string, authenticated = false) => Response.json({ authenticated, csrfToken, primary: false, roles: [], user: null });
beforeEach(() => {
  configureGateway({});
  fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

it.each([loginAdmin, loginWithOtp])('generated login serializes credentials and uses the rotated session', async (login) => {
  fetchMock.mockResolvedValueOnce(session('anonymous'))
    .mockResolvedValueOnce(Response.json({ success: true }))
    .mockResolvedValueOnce(session('rotated', true));
  const credentials = login === loginAdmin ? { username: 'admin', password: 'test-password' } : { phone: '13800138000', code: '123456' };
  await login(credentials);
  expect(fetchMock.mock.calls[1][1].body).toBe(JSON.stringify(credentials));
  expect(fetchMock.mock.calls[1][1].headers.get('Content-Type')).toBe('application/json');
  expect(fetchMock.mock.calls[1][1].headers.get('X-CSRF-TOKEN')).toBe('anonymous');
  expect(getSession()?.csrfToken).toBe('rotated');
  fetchMock.mockResolvedValueOnce(Response.json({ success: true })).mockResolvedValueOnce(session('after-logout'));
  await logout();
  expect(getSession()?.authenticated).toBe(false);
});

it('generated OTP send gets anonymous CSRF without making an identity', async () => {
  fetchMock.mockResolvedValueOnce(session('anonymous')).mockResolvedValueOnce(Response.json({ success: true, expiresInSeconds: 300, resendAfterSeconds: 60 }));
  const response = await sendOtp({ phone: '13800138000' });
  expect(response.data.resendAfterSeconds).toBe(60);
  expect(getSession()?.authenticated).toBe(false);
});

it('generated upload sends the actual file under the contract field', async () => {
  fetchMock.mockResolvedValueOnce(session('anonymous')).mockResolvedValueOnce(Response.json({ fileId: 'file-1' }, { status: 201 }));
  const file = new File(['image'], 'reference.png', { type: 'image/png' });
  const result = await storageUploadAvatarPostApiV1FilesAvatars({ file });
  expect(result.status).toBe(201);
  const body = fetchMock.mock.calls[1][1].body as FormData;
  expect(await (body.get('file') as File).text()).toBe('image');
  expect((body.get('file') as File).name).toBe('reference.png');
  expect(fetchMock.mock.calls[1][1].headers.has('Content-Type')).toBe(false);
});

it('generated URLs encode path and query values', async () => {
  fetchMock.mockResolvedValueOnce(Response.json([]));
  await billingEntriesGetApiV1BillingWalletsKeyIdLedger('key/one', { limit: 10 });
  expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/billing/wallets/key%2Fone/ledger?limit=10');
});
