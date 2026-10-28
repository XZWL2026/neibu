import { afterEach, expect, it, vi } from 'vitest';

afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.resetModules(); });

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
