import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { webcrypto, createHash } from 'node:crypto';
import { flushPromises, mount, enableAutoUnmount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import ApiLogin from './ApiLogin.vue';
import { configureAdminGateway, refreshSession } from './api';
import { loginApiWithPhone } from './apiPhoneLogin';
import { createAdminAuth } from './adminAuth';
import { isApiConsoleSession } from './portalSession';

enableAutoUnmount(afterEach);
let phoneLogged = false, apiLogged = false, failure = '';
const requests = vi.fn(async (url: string, init: RequestInit = {}) => {
  const isApi = url.includes('/api/session/');
  const path = new URL(url, window.location.origin).pathname;
  if (path.endsWith('/auth/session')) return Response.json({
    authenticated: isApi ? apiLogged : phoneLogged, csrfToken: isApi ? 'api-csrf' : 'phone-csrf',
    roles: isApi ? ['api_user'] : ['user'], user: { id: 'user-1', username: '13800138000' }
  });
  expect(new Headers(init.headers).get('X-CSRF-TOKEN')).toBe(isApi ? 'api-csrf' : 'phone-csrf');
  if (path.endsWith('/otp/send')) return Response.json({ success: true, resendAfterSeconds: 60 });
  if (path.endsWith('/otp/login')) {
    if (failure === 'otp') return Response.json({ code: 'OTP_INVALID_OR_EXPIRED' }, { status: 401 });
    phoneLogged = true; return Response.json({ success: true });
  }
  if (path.endsWith('/portal/tickets')) {
    expect(phoneLogged).toBe(true); expect(isApi).toBe(false);
    if (failure === 'permission') return Response.json({ code: 'PORTAL_ACCESS_DENIED' }, { status: 403 });
    return Response.json({ ticket: 'a'.repeat(64) });
  }
  if (path.endsWith('/portal/login')) {
    expect(isApi).toBe(true); apiLogged = true; return Response.json({ role: 'API_USER' });
  }
  if (path.endsWith('/auth/logout')) { expect(isApi).toBe(true); apiLogged = false; return Response.json({ success: true }); }
  throw new Error(`Unexpected request: ${path}`);
});
beforeEach(() => {
  window.history.replaceState(null, '', '/api/login');
  vi.stubGlobal('crypto', webcrypto); vi.stubGlobal('fetch', requests);
  requests.mockClear(); phoneLogged = false; apiLogged = false; failure = '';
  configureAdminGateway();
});
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); window.history.replaceState(null, '', '/'); });
async function open(redirect = '') {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { name: 'api-login', path: '/api/login', component: ApiLogin },
    { name: 'api-dashboard', path: '/api/dashboard', component: { template: '<div>API</div>' } },
    { name: 'api-keys', path: '/api/keys', component: { template: '<div>Keys</div>' } },
    { name: 'api-portal-entry', path: '/api/portal-entry', component: { template: '<div />' } }
  ] });
  await router.push({ path: '/api/login', query: { redirect } });
  const wrapper = mount(ApiLogin, { global: { plugins: [router] } });
  return { wrapper, router };
}
it('validates phone and code before making a request; never renders a password field', async () => {
  const { wrapper } = await open();
  expect(wrapper.find('input[type="password"]').exists()).toBe(false);
  await wrapper.get('form').trigger('submit'); expect(wrapper.text()).toContain('11 位手机号');
  await wrapper.get('#api-login-phone').setValue('13800138000');
  await wrapper.get('form').trigger('submit'); expect(wrapper.text()).toContain('6 位短信验证码');
  expect(requests).not.toHaveBeenCalled();
});
it('sends once, disables resend for 60 seconds and recovers after the deadline', async () => {
  vi.useFakeTimers();
  const { wrapper } = await open(); await wrapper.get('#api-login-phone').setValue('13800138000');
  await wrapper.get('.api-login-send').trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('验证码已发送'); expect(wrapper.get('.api-login-send').attributes('disabled')).toBeDefined();
  expect(requests.mock.calls.filter(([url]) => url.endsWith('/otp/send'))).toHaveLength(1);
  await vi.advanceTimersByTimeAsync(60_000); expect(wrapper.get('.api-login-send').attributes('disabled')).toBeUndefined();
});
it('uses a phone cookie/CSRF then a separate API cookie/CSRF and PKCE proof', async () => {
  await loginApiWithPhone('13800138000', '123456');
  const issuance = requests.mock.calls.find(([url]) => url.endsWith('/portal/tickets'))!;
  const exchange = requests.mock.calls.find(([url]) => url.endsWith('/portal/login'))!;
  const issue = JSON.parse(String(issuance[1]!.body)), redeem = JSON.parse(String(exchange[1]!.body));
  expect(issue.role).toBe('API_USER');
  expect(issue.challenge).toBe(createHash('sha256').update(redeem.verifier).digest('hex'));
  expect(exchange[0]).toContain('/api/session/api/v1/');
  expect(requests.mock.calls.every(([url]) => !url.includes('/admin/') && !url.includes('/auth/phone/login'))).toBe(true);
});
it.each([['otp', '验证码错误或已过期'], ['permission', '暂无 API 访问权限']])('blocks %s failures without redeeming or navigating', async (reason, message) => {
  failure = reason;
  const { wrapper, router } = await open();
  await wrapper.get('#api-login-phone').setValue('13800138000'); await wrapper.get('#api-login-code').setValue('123456');
  await wrapper.get('form').trigger('submit'); await flushPromises();
  await vi.waitFor(() => expect(wrapper.text()).toContain(message)); expect(router.currentRoute.value.path).toBe('/api/login');
  expect(requests.mock.calls.some(([url]) => url.endsWith('/portal/login'))).toBe(false);
  await refreshSession(); expect(requests.mock.calls.at(-1)![0]).toContain('/api/session/api/v1/auth/session');
});
it.each([['/api/keys?tab=mine', '/api/keys?tab=mine'], ['https://evil.test', '/api/dashboard'], ['//evil.test', '/api/dashboard'], ['/api/login', '/api/dashboard'], ['/api/missing', '/api/dashboard'], ['/api/portal-entry#secret', '/api/dashboard'], ['/admin/admins', '/api/dashboard']])('validates redirect %s', async (target, expected) => {
  const { wrapper, router } = await open(target);
  await wrapper.get('#api-login-phone').setValue('13800138000'); await wrapper.get('#api-login-code').setValue('123456');
  await wrapper.get('form').trigger('submit'); await flushPromises();
  await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe(expected));
});
it('anonymous API pages and logout return to API login without touching admin or phone sessions', async () => {
  const { router } = await open(); const auth = createAdminAuth(router);
  try {
    await router.push('/api/keys'); expect(router.currentRoute.value.path).toBe('/api/login');
    expect(router.currentRoute.value.query.redirect).toBe('/api/keys');
    apiLogged = true; phoneLogged = true;
    await router.push('/api/dashboard'); await auth.logout!();
    expect(router.currentRoute.value.path).toBe('/api/login'); expect(phoneLogged).toBe(true);
    expect(requests.mock.calls.every(([url]) => url.includes('/api/session/'))).toBe(true);
    apiLogged = true; await router.push('/api/dashboard');
    expect(router.currentRoute.value.path).toBe('/api/dashboard');
    expect(auth.currentAdmin.value?.role).toBe('API_USER');
  } finally { auth.dispose!(); }
});
it('does not treat public APIs or impersonation tabs as the standalone API console', () => {
  expect(isApiConsoleSession('/api/login')).toBe(true);
  expect(isApiConsoleSession('/api/v1/auth/session')).toBe(false);
  expect(isApiConsoleSession('/agent/' + 'a'.repeat(32) + '/api/dashboard')).toBe(false);
});
