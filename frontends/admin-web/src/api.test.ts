import { afterEach, expect, it, vi } from 'vitest';

afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.resetModules(); });

it.each(['http://192.168.3.26:18080', 'http://127.0.0.1:18080', 'https://192.168.3.26:18080'])('keeps admin session requests on the page origin %s', async (origin) => {
  vi.stubEnv('VITE_GATEWAY_BASE_URL', '/admin');
  vi.stubGlobal('window', { location: { origin, pathname: '/admin/login' } });
  const fetchMock = vi.fn().mockResolvedValue(Response.json({ authenticated: false, csrfToken: 'token' }));
  vi.stubGlobal('fetch', fetchMock);
  const api = await import('./api');
  await api.refreshSession();
  expect(fetchMock.mock.calls[0][0]).toBe(`${origin}/admin/api/v1/auth/session`);
  expect(fetchMock.mock.calls[0][1].credentials).toBe('include');
});

it('routes generated SDK calls through configured Gateway and clears session before login redirect', async () => {
  vi.stubEnv('VITE_GATEWAY_BASE_URL', 'https://gateway.example.test');
  vi.stubEnv('VITE_LOGIN_URL', 'https://login.example.test/user/login');
  const replace = vi.fn();
  vi.stubGlobal('window', { location: { replace } });
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(Response.json({ authenticated: true, csrfToken: 'token', roles: [], primary: false, user: { id: 'u1', username: 'user' } }))
    .mockResolvedValueOnce(Response.json({ code: 'expired' }, { status: 401 }));
  vi.stubGlobal('fetch', fetchMock);
  const api = await import('./api');
  await api.refreshSession();
  expect(api.getSession()?.authenticated).toBe(true);
  await expect(api.userSelfGetApiV1UsersMe()).rejects.toMatchObject({ status: 401 });
  expect(fetchMock.mock.calls[1][0]).toBe('https://gateway.example.test/api/v1/users/me');
  expect(fetchMock.mock.calls[1][1].credentials).toBe('include');
  expect(api.getSession()).toBeNull();
  expect(replace).toHaveBeenCalledExactlyOnceWith('https://login.example.test/user/login');
});
