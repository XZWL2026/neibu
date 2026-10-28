import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { configureGateway, gatewayRequest, getSession, refreshSession, ApiError } from '../src/gateway';

const session = (authenticated = false, csrfToken = 'csrf-anonymous') => ({ authenticated, csrfToken, primary: false, roles: [], user: null });
const json = (body: unknown, status = 200, headers = {}) => Response.json(body, { status, headers });
let fetchMock: ReturnType<typeof vi.fn>;
let expired: ReturnType<typeof vi.fn<() => void>>;
beforeEach(() => {
  fetchMock = vi.fn();
  expired = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  configureGateway({ baseUrl: 'https://gateway.example.test/', onUnauthorized: expired });
});
afterEach(() => vi.unstubAllGlobals());

describe('Gateway browser transport', () => {
  it('uses the configured Gateway, cookies and exposes response metadata', async () => {
    fetchMock.mockResolvedValue(json({ id: 'u1' }, 200, { 'X-Request-Id': 'req-1' }));
    const result = await gatewayRequest<{ data: { id: string }; headers: Headers; status: number }>('/api/v1/users/me');
    expect(fetchMock.mock.calls[0][0]).toBe('https://gateway.example.test/api/v1/users/me');
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ credentials: 'include' });
    expect(result.data.id).toBe('u1');
    expect(result.headers.get('X-Request-Id')).toBe('req-1');
  });
  it('bootstraps one anonymous session for concurrent writes and preserves JSON/headers', async () => {
    fetchMock.mockResolvedValueOnce(json(session())).mockImplementation(() => Promise.resolve(json({ success: true })));
    await Promise.all([1, 2].map(() => gatewayRequest('/api/v1/access/keys', { method: 'POST', body: '{}', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': 'once' } })));
    expect(fetchMock.mock.calls.filter(([url]) => url.endsWith('/auth/session'))).toHaveLength(1);
    for (const [, init] of fetchMock.mock.calls.slice(1)) {
      expect(init.credentials).toBe('include');
      expect(init.body).toBe('{}');
      expect(init.headers.get('X-CSRF-TOKEN')).toBe('csrf-anonymous');
      expect(init.headers.get('Idempotency-Key')).toBe('once');
    }
  });
  it('keeps FormData intact and lets the browser set its boundary', async () => {
    fetchMock.mockResolvedValueOnce(json(session())).mockResolvedValueOnce(json({}));
    const body = new FormData(); body.append('file', new Blob(['image']), 'image.png');
    await gatewayRequest('/api/v1/files/avatars', { method: 'POST', body, headers: { 'Content-Type': 'multipart/form-data' } });
    const init = fetchMock.mock.calls[1][1];
    expect(init.body).toBe(body);
    expect(init.headers.has('Content-Type')).toBe(false);
    expect(init.headers.get('X-CSRF-TOKEN')).toBe('csrf-anonymous');
  });
  it('does not parse a 204 response as JSON', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    expect(await gatewayRequest('/api/v1/users/me')).toMatchObject({ data: undefined, status: 204 });
  });
  it.each([401, 403, 409, 422, 502])('normalizes HTTP %i without retries or losing details', async (status) => {
    fetchMock.mockResolvedValue(json({ code: 'server-code', message: 'server message', requestId: 'body-id', details: { field: 'value' } }, status, { 'X-Request-Id': 'header-id' }));
    await expect(gatewayRequest('/api/v1/users/me')).rejects.toMatchObject({ status, code: 'server-code', message: 'server message', requestId: 'header-id', details: { field: 'value' } });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(expired).toHaveBeenCalledTimes(status === 401 ? 1 : 0);
  });
  it('preserves body requestId and error-only/string errors', async () => {
    fetchMock.mockResolvedValueOnce(json({ error: 'forbidden', requestId: 'body-id' }, 403));
    await expect(gatewayRequest('/api/v1/users/me')).rejects.toMatchObject({ message: 'forbidden', requestId: 'body-id' });
    fetchMock.mockResolvedValueOnce(new Response('bad upstream', { status: 502 }));
    await expect(gatewayRequest('/api/v1/users/me')).rejects.toMatchObject({ status: 502, body: 'bad upstream' });
  });
  it('clears cached identity/CSRF and redirects once for concurrent expired requests', async () => {
    fetchMock.mockResolvedValueOnce(json(session(true, 'private-token')));
    await refreshSession();
    fetchMock.mockImplementation(() => Promise.resolve(json({ code: 'expired' }, 401)));
    await Promise.allSettled([gatewayRequest('/api/v1/users/me'), gatewayRequest('/api/v1/users/me')]);
    expect(getSession()).toBeNull();
    expect(expired).toHaveBeenCalledTimes(1);
  });
  it.each(['/api/v1/auth/otp/login', '/api/v1/auth/phone/login'])('refreshes the rotated session after %s and logout', async loginPath => {
    fetchMock.mockResolvedValueOnce(json(session())).mockResolvedValueOnce(json({ success: true })).mockResolvedValueOnce(json(session(true, 'rotated')));
    await gatewayRequest(loginPath, { method: 'POST', body: '{}' });
    expect(getSession()?.csrfToken).toBe('rotated');
    fetchMock.mockResolvedValueOnce(json({ success: true })).mockResolvedValueOnce(json(session()));
    await gatewayRequest('/api/v1/auth/logout', { method: 'POST' });
    expect(fetchMock.mock.calls[3][1].headers.get('X-CSRF-TOKEN')).toBe('rotated');
    expect(getSession()?.authenticated).toBe(false);
  });
  it('clears failed login state without navigating away from its error', async () => {
    fetchMock.mockResolvedValueOnce(json(session())).mockResolvedValueOnce(json({ code: 'invalid-credentials' }, 401));
    await expect(gatewayRequest('/api/v1/auth/admin/login', { method: 'POST', body: '{}' })).rejects.toBeInstanceOf(ApiError);
    expect(getSession()).toBeNull();
    expect(expired).not.toHaveBeenCalled();
  });
  it('does not resurrect a stale session response after a 401', async () => {
    let resolveSession!: (response: Response) => void;
    fetchMock.mockImplementationOnce(() => new Promise(resolve => { resolveSession = resolve; })).mockResolvedValueOnce(json({}, 401));
    const pending = expect(refreshSession()).rejects.toThrow(/Session changed/);
    await expect(gatewayRequest('/api/v1/users/me')).rejects.toBeInstanceOf(ApiError);
    resolveSession(json(session(true, 'stale')));
    await pending;
    expect(getSession()).toBeNull();
  });
  it('rejects missing CSRF before sending a write', async () => {
    fetchMock.mockResolvedValue(json({ ...session(), csrfToken: null }));
    await expect(gatewayRequest('/api/v1/access/keys', { method: 'POST' })).rejects.toThrow(/CSRF/);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it('discards a rejected CSRF token without logging out or replaying the write', async () => {
    fetchMock.mockResolvedValueOnce(json(session(true, 'old-token')));
    await refreshSession();
    fetchMock.mockResolvedValueOnce(json({ code: 'FORBIDDEN' }, 403));
    await expect(gatewayRequest('/api/v1/access/keys', { method: 'POST' })).rejects.toMatchObject({ status: 403 });
    expect(getSession()?.authenticated).toBe(true);
    expect(expired).not.toHaveBeenCalled();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    fetchMock.mockResolvedValueOnce(json(session(true, 'fresh-token'))).mockResolvedValueOnce(json({}));
    await gatewayRequest('/api/v1/access/keys', { method: 'POST' });
    expect(fetchMock.mock.calls[3][1].headers.get('X-CSRF-TOKEN')).toBe('fresh-token');
  });
  it('retains network and abort failures without redirecting', async () => {
    const error = new DOMException('Cancelled', 'AbortError');
    fetchMock.mockRejectedValue(error);
    await expect(gatewayRequest('/api/v1/users/me')).rejects.toBe(error);
    expect(expired).not.toHaveBeenCalled();
  });
});
