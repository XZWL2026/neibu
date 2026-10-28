import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';
import App from './App.vue';
import { adminRoutes } from './router';
import { createAdminAuth } from './adminAuth';
import * as api from './api';

let authenticated = false;
let roles = ['admin'];
let primary = true;
let sessionReply: (() => Promise<Response>) | undefined;
let apiStatus = 200;
const requests = vi.fn(async (path: string, options: RequestInit = {}) => {
  if (path.endsWith('/auth/session')) return sessionReply ? sessionReply() : Response.json({ authenticated, roles, primary, csrfToken: 'csrf', user: authenticated ? { id: 'administrator', username: 'real.admin' } : null });
  if (path.endsWith('/auth/admin/login')) { authenticated = true; return Response.json({ success: true }); }
  if (path.endsWith('/auth/logout')) { authenticated = false; return Response.json({ success: true }); }
  return Response.json({}, { status: apiStatus });
});
enableAutoUnmount(afterEach);
beforeEach(() => {
  authenticated = false; roles = ['admin']; primary = true; apiStatus = 200; sessionReply = undefined;
  requests.mockClear(); vi.stubGlobal('fetch', requests); api.configureGateway({}); localStorage.clear();
});
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });
async function open(path: string) {
  const history = createMemoryHistory(); history.push(path);
  const router = createRouter({ history, routes: adminRoutes });
  const session = createAdminAuth(router);
  const wrapper = mount(App, { props:{session}, global: { plugins: [router] } });
  await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  return { wrapper, router };
}
async function signIn(wrapper: ReturnType<typeof mount>) {
  await wrapper.get('input[autocomplete="username"]').setValue('real.admin');
  await wrapper.get('input[type="password"]').setValue('password');
  await wrapper.get('form').trigger('submit'); await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
}
it.each(['/admin/overview', '/admin/users/USR-1388/status', '/admin/consumption/VID-0829-063', '/admin/agents/agent', '/admin/pricing', '/admin/admins', '/promoter/overview', '/api/dashboard'])('requires server authentication for direct URL %s', async path => {
  localStorage.setItem('xzwl.admin-next.users', '[{"name":"private-cache"}]');
  localStorage.setItem('isLoggedIn', 'true');
  const { router, wrapper } = await open(path);
  expect(router.currentRoute.value.path).toBe('/admin/login');
  expect(router.currentRoute.value.query.redirect).toBe(path.replace(/\/status$/, ''));
  expect(wrapper.text()).toContain('登录管理后台');
  expect(wrapper.text()).not.toContain('任务受理趋势');
  expect(wrapper.text()).not.toContain('private-cache');
});
it('shows loading without mounting business pages while restoring the real session', async () => {
  let resolve!: (value: Response) => void;
  sessionReply = () => new Promise(r => { resolve = r; });
  const { wrapper } = await open('/admin/users/USR-1388/status');
  expect(wrapper.text()).toContain('正在验证登录状态');
  expect(wrapper.text()).not.toContain('Nova');
  resolve(Response.json({ authenticated: true, roles, primary, csrfToken: 'csrf', user: { id: 'a', username: 'real.admin' } }));
  await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  expect(wrapper.text()).toContain('用户详情'); expect(wrapper.text()).toContain('超级管理员');
});
it('returns to the permitted original detail including query and hash after real login', async () => {
  const target = '/admin/users/USR-1388/status?tab=account#details';
  const { wrapper, router } = await open(target);
  await signIn(wrapper);
  expect(router.currentRoute.value.fullPath).toBe(target.replace('/status?', '?'));
  expect(wrapper.text()).toContain('用户详情');
  expect(requests.mock.calls.some(([path]) => path.endsWith('/auth/admin/login'))).toBe(true);
});
it.each(['//evil.example/path', 'https://evil.example', '/admin/login', '/admin/missing', '/admin/pricing', '/promoter/users', '/admin/users/../login'])('rejects unsafe, forbidden or looping return target %s', async redirect => {
  roles = ['normal_admin']; primary = false;
  const { wrapper, router } = await open('/admin/login?redirect=' + encodeURIComponent(redirect));
  await signIn(wrapper);
  expect(router.currentRoute.value.path).toBe('/admin/overview');
});
it.each([['normal_admin', false], ['admin', false], ['user', false]])('keeps authenticated %s primary=%s separate from missing login', async (role, isPrimary) => {
  authenticated = true; roles = [String(role)]; primary = Boolean(isPrimary);
  const { wrapper, router } = await open('/admin/admins');
  expect(router.currentRoute.value.path).toBe('/admin/admins');
  expect(wrapper.text()).toContain('无权限');
  expect(wrapper.text()).not.toContain('新增管理员');
  expect(wrapper.text()).not.toContain('未登录');
  expect(wrapper.find('a[href="/admin/pricing"]').exists()).toBe(false);
});
it('revalidates history navigation and focus, clearing expired identity and page caches', async () => {
  authenticated = true;
  const { wrapper, router } = await open('/admin/users');
  await router.push('/admin/consumption/VID-0829-063'); await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  localStorage.setItem('xzwl.admin-next.users', '[{"name":"private"}]');
  localStorage.setItem('unrelated', 'keep');
  authenticated = false;
  router.back(); await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  expect(router.currentRoute.value.path).toBe('/admin/login');
  expect(wrapper.text()).not.toContain('Nova');
  expect(api.getSession()?.authenticated).not.toBe(true);
  expect(localStorage.getItem('xzwl.admin-next.users')).toBeNull();
  expect(localStorage.getItem('unrelated')).toBe('keep');
});
it('discards a business response with 401 and redirects without confusing 403 with logout', async () => {
  authenticated = true;
  const { wrapper, router } = await open('/admin/users');
  apiStatus = 403;
  await expect(api.userSelfGetApiV1UsersMe()).rejects.toMatchObject({ status: 403 });
  expect(router.currentRoute.value.path).toBe('/admin/users');
  expect(api.getSession()?.authenticated).toBe(true);
  apiStatus = 401;
  await expect(api.userSelfGetApiV1UsersMe()).rejects.toMatchObject({ status: 401 });
  await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  expect(router.currentRoute.value.path).toBe('/admin/login');
  expect(api.getSession()).toBeNull();
  expect(wrapper.text()).not.toContain('Nova');
});
it('logs out remotely and blocks back and forward navigation to cached pages', async () => {
  authenticated = true;
  const { wrapper, router } = await open('/admin/users');
  await router.push('/admin/overview'); await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  await wrapper.get('[aria-label="打开管理员菜单"]').trigger('click');
  await wrapper.get('[data-testid="admin-logout"] .n-dropdown-option-body').trigger('click'); await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  expect(authenticated).toBe(false);
  expect(router.currentRoute.value.path).toBe('/admin/login');
  router.back(); await flushPromises(); await vi.dynamicImportSettled(); await flushPromises(); router.forward(); await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  expect(router.currentRoute.value.path).toBe('/admin/login');
  expect(wrapper.text()).not.toContain('任务受理趋势');
});
it('blocks content on session network failure and supports retry', async () => {
  sessionReply = async () => { throw new TypeError('offline'); };
  const { wrapper, router } = await open('/admin/users');
  expect(wrapper.text()).toContain('无法验证登录状态');
  expect(wrapper.text()).not.toContain('Nova');
  expect(router.currentRoute.value.path).toBe('/admin/users');
  authenticated = true; sessionReply = undefined;
  await wrapper.get('[data-testid="retry-session"]').trigger('click'); await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  expect(wrapper.text()).toContain('用户管理');
});
it.each(['focus', 'pageshow'])('revalidates an expired session on %s without revealing cached content', async event => {
  authenticated = true;
  const { wrapper, router } = await open('/admin/users');
  window.dispatchEvent(new Event('pagehide')); await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  expect(wrapper.get('[data-testid="protected-content"]').isVisible()).toBe(false);
  authenticated = false;
  window.dispatchEvent(new Event(event)); await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  expect(router.currentRoute.value.path).toBe('/admin/login');
  expect(wrapper.text()).not.toContain('Nova');
});
it('checks idle visible pages for server-side expiry', async () => {
  authenticated = true;
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
  const { wrapper, router } = await open('/admin/users');
  authenticated = false;
  await vi.advanceTimersByTimeAsync(60_000); await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  expect(router.currentRoute.value.path).toBe('/admin/login');
  expect(wrapper.text()).not.toContain('Nova');
});
it('installs the production guard before the initial router navigation', async () => {
  const { createAdminAuth } = await import('./adminAuth');
  const router = createRouter({ history: createMemoryHistory('/ops/'), routes: adminRoutes });
  const session = createAdminAuth(router);
  const wrapper = mount(App, { props: { session }, global: { plugins: [router] } });
  await router.push('/admin/users/USR-1388/status'); await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  expect(router.currentRoute.value.path).toBe('/admin/login');
  expect(router.resolve('/admin/login').href).toBe('/ops/admin/login');
  expect(wrapper.text()).not.toContain('Nova');
});
it('does not let a late session refresh restore identity after logout', async () => {
  authenticated = true;
  const { wrapper, router } = await open('/admin/users');
  let resolve!: (value: Response) => void;
  sessionReply = () => new Promise(r => { resolve = r; });
  window.dispatchEvent(new Event('focus')); await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  await wrapper.get('[aria-label="打开管理员菜单"]').trigger('click');
  // Logout itself refreshes an anonymous CSRF after invalidating the server session.
  sessionReply = undefined;
  await wrapper.get('[data-testid="admin-logout"] .n-dropdown-option-body').trigger('click'); await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  resolve(Response.json({ authenticated: true, roles, primary, csrfToken: 'old', user: { id: 'a', username: 'old' } }));
  await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  expect(router.currentRoute.value.path).toBe('/admin/login');
  expect(api.getSession()).toBeNull();
  expect(wrapper.text()).not.toContain('Nova');
});
it('preserves an active form while temporarily hiding a successful session recheck', async () => {
  authenticated = true;
  const { wrapper } = await open('/admin/users');
  await wrapper.get('input[aria-label="搜索用户"]').setValue('Nova');
  window.dispatchEvent(new Event('focus')); await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  expect((wrapper.get('input[aria-label="搜索用户"]').element as HTMLInputElement).value).toBe('Nova');
});
it('renders the login as a standalone page without the operations shell', async () => {
  const { wrapper } = await open('/admin/login');
  expect(wrapper.find('.admin-auth-layout').exists()).toBe(true);
  expect(wrapper.find('.admin-sidebar').exists()).toBe(false);
  expect(wrapper.find('.workspace-header').exists()).toBe(false);
  expect(wrapper.text()).not.toContain('运营管理台');
  expect(wrapper.find('input[autocomplete="username"]').exists()).toBe(true);
  expect(wrapper.find('input[autocomplete="current-password"]').exists()).toBe(true);
});

it.each(['api_user','promoter'])('rejects %s accounts in the administrator application', async role => {
  roles = [role]; primary = false;
  const { wrapper, router } = await open('/admin/login');
  await signIn(wrapper);
  expect(router.currentRoute.value.path).toBe('/admin/login');
  expect(wrapper.text()).toContain('登录失败');
  expect(wrapper.find('a[href="/admin/admins"]').exists()).toBe(false);
  await router.push('/admin/pricing'); await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  expect(wrapper.text()).toContain('无权限');
  expect(wrapper.find('input[aria-label="计费倍率"]').exists()).toBe(false);
});
it.each([['api_user','/api/keys?tab=mine#keys'],['promoter','/promoter/users?level=2#list']])('does not open %s portal deep links through admin login', async(role,target) => {
  roles=[role]; primary=false;
  const {wrapper,router}=await open('/admin/login?redirect='+encodeURIComponent(target));
  await signIn(wrapper);
  expect(router.currentRoute.value.path).toBe('/admin/login');
  expect(wrapper.find('[data-testid="protected-content"]').exists()).toBe(false);
});
it('retains password inputs during same-identity focus verification and clears them on identity expiry', async () => {
  authenticated = true;
  const { wrapper, router } = await open('/admin/overview');
  await wrapper.get('[aria-label="打开管理员菜单"]').trigger('click');
  await wrapper.get('[data-testid="admin-change-password"] .n-dropdown-option-body').trigger('click');
  await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  await wrapper.get('[autocomplete="current-password"]').setValue('kept-during-focus');
  let resolve!: (response: Response) => void;
  sessionReply = () => new Promise(r => { resolve = r; });
  window.dispatchEvent(new Event('focus')); await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  resolve(Response.json({ authenticated: true, roles, primary, csrfToken: 'csrf', user: { id: 'administrator', username: 'real.admin' } }));
  await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  expect(wrapper.get('[autocomplete="current-password"]').element).toHaveProperty('value', 'kept-during-focus');
  sessionReply = undefined; authenticated = false;
  window.dispatchEvent(new Event('focus')); await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  expect(router.currentRoute.value.path).toBe('/admin/login');
  expect(wrapper.find('[autocomplete="current-password"]').element).not.toHaveProperty('value', 'kept-during-focus');
});

it('keeps the page visible and avoids business reloads while the same session is checked on focus', async () => {
  authenticated = true;
  const { wrapper } = await open('/admin/consumption');
  await wrapper.get('[aria-label="模型标识"]').setValue('kept-model');
  const content = wrapper.get('[data-testid="protected-content"]').element;
  const calls = requests.mock.calls.filter(([path]) => !path.endsWith('/auth/session')).length;
  let resolve!: (value: Response) => void;
  sessionReply = () => new Promise(r => { resolve = r; });
  window.dispatchEvent(new Event('focus')); await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  expect(wrapper.get('[data-testid="protected-content"]').isVisible()).toBe(true);
  resolve(Response.json({ authenticated: true, roles, primary, csrfToken: 'csrf', user: { id: 'administrator', username: 'real.admin' } }));
  await flushPromises(); await vi.dynamicImportSettled(); await flushPromises();
  expect(wrapper.get('[data-testid="protected-content"]').element).toBe(content);
  expect(wrapper.get('[aria-label="模型标识"]').element).toHaveProperty('value', 'kept-model');
  expect(requests.mock.calls.filter(([path]) => !path.endsWith('/auth/session'))).toHaveLength(calls);
});

it('keeps login accessible and toggles password visibility without submitting credentials', async () => {
  const { wrapper } = await open('/admin/login');
  expect(wrapper.get('.brand-word').text()).toContain('YOMIO');
  const password = wrapper.get<HTMLInputElement>('input[autocomplete="current-password"]');
  await password.setValue('private-password');
  await wrapper.get('button[aria-label="显示密码"]').trigger('click');
  expect(password.attributes('type')).toBe('text');
  expect(password.element.value).toBe('private-password');
  expect(requests.mock.calls.some(([path]) => path.endsWith('/auth/admin/login'))).toBe(false);
  await wrapper.get('button[aria-label="隐藏密码"]').trigger('click');
  expect(password.attributes('type')).toBe('password');
  expect(wrapper.get('input[autocomplete="username"]').attributes('required')).toBeDefined();
});
