import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { ref } from 'vue';
import PortalAccounts from './PortalAccounts.vue';
import { adminSessionKey, type AdminIdentity } from './adminSession';
import { configureGateway } from './api';
enableAutoUnmount(afterEach);

it('reserves the account table during a slow request and only shows empty after success', async () => {
  let finish!: (response: Response) => void;
  calls.mockImplementationOnce(() => new Promise<Response>(resolve => { finish = resolve; }));
  const w = open(); await flushPromises();
  expect(w.findAll('.table-loading-row')).toHaveLength(6);
  expect(w.get('[aria-label="登录账号列表"]').attributes('aria-busy')).toBe('true');
  expect(w.text()).not.toContain('暂无账号');
  finish(Response.json([])); await flushPromises();
  expect(w.find('.table-loading-row').exists()).toBe(false);
  expect(w.get('[aria-label="登录账号列表"]').attributes('aria-busy')).toBe('false');
  expect(w.text()).toContain('暂无账号');
});

it('replaces a slow failed request with retry rather than an empty result', async () => {
  let finish!: (response: Response) => void;
  calls.mockImplementationOnce(() => new Promise<Response>(resolve => { finish = resolve; }));
  const w = open(); await flushPromises();
  finish(Response.json({ code: 'UNAVAILABLE' }, { status: 503 })); await flushPromises();
  expect(w.find('.table-loading-row').exists()).toBe(false);
  expect(w.get('[role="alert"]').text()).toContain('重试');
  expect(w.text()).not.toContain('暂无账号');
});
afterEach(() => vi.unstubAllGlobals());
const calls = vi.fn();
const identity = ref<AdminIdentity | null>(null);
beforeEach(() => {
  identity.value = { username: 'primary', role: 'ADMIN', primary: true }; configureGateway({});
  calls.mockReset().mockImplementation(async (path: string, init: RequestInit = {}) => Response.json(path.endsWith('/auth/session')
    ? { authenticated: true, csrfToken: 'csrf' }
    : init.method === 'POST' ? { id: 'credential', username: 'api.real', role: 'API_USER', boundUserId: 'owner', enabled: true, primary: false }
    : [{ id: 'credential', username: 'agent.real', role: 'PROMOTER', boundUserId: 'owner', enabled: true, primary: false }]));
  vi.stubGlobal('fetch', calls);
});
const open = () => mount(PortalAccounts, { global: { provide: { [adminSessionKey as symbol]: { currentAdmin: identity } } } });
it('creates a real bound portal credential and resets/disables it through the SDK', async () => {
  const w = open(); await flushPromises();
  expect(w.text()).toContain('agent.real'); expect(w.text()).toContain('owner');
  await w.get('[data-testid="credential-username"]').setValue('api.new');
  await w.get('[data-testid="credential-password"]').setValue('SecurePassword123');
  await w.get('[data-testid="credential-role"]').setValue('API_USER');
  await w.get('[data-testid="credential-owner"]').setValue('business-owner');
  await w.get('form[aria-label="创建登录账号"]').trigger('submit'); await flushPromises();
  expect(JSON.parse(calls.mock.calls.find(([, i]) => i.method === 'POST' && i.body?.includes('api.new'))![1].body)).toEqual({ username: 'api.new', password: 'SecurePassword123', role: 'API_USER', boundUserId: 'business-owner' });
  expect(w.get('[data-testid="credential-password"]').element).toHaveProperty('value', '');
  await w.get('[aria-label="筛选账号角色"]').setValue('');
  await w.findAll('button').find(b => b.text() === '重置密码')!.trigger('click');
  await w.get('[data-testid="reset-password"]').setValue('ResetPassword123');
  await w.get('form[aria-label="重置登录密码"]').trigger('submit'); await flushPromises();
  expect(calls.mock.calls.some(([p]) => p.endsWith('/credential/password'))).toBe(true);
  await w.findAll('button').find(b => b.text() === '停用')!.trigger('click'); await flushPromises();
  expect(calls.mock.calls.some(([p, i]) => p.endsWith('/credential/status') && JSON.parse(i.body).enabled === false)).toBe(true);
});
it('makes no credential request for a nonprimary role and clears drafts on identity change', async () => {
  identity.value = { username: 'normal', role: 'NORMAL_ADMIN', primary: false };
  const w = open(); await flushPromises(); expect(calls).not.toHaveBeenCalled();
  expect(w.text()).toContain('需要主管理员');
});
it('offers retry on list failure without showing fake accounts', async () => {
  calls.mockImplementation(async () => Response.json({ code: 'UNAVAILABLE' }, { status: 503 }));
  const w = open(); await flushPromises();
  expect(w.get('[role="alert"]').text()).toContain('加载失败');
  expect(w.text()).not.toContain('api.real');
  await w.findAll('button').find(b => b.text() === '重试')!.trigger('click'); await flushPromises();
  expect(calls).toHaveBeenCalledTimes(2);
});
it('retains drafts for an unchanged identity refresh and removes them when identity expires', async () => {
  const w = open(); await flushPromises();
  await w.get('[data-testid="credential-password"]').setValue('PrivateDraft123');
  identity.value = { ...identity.value! }; await flushPromises();
  expect(w.get('[data-testid="credential-password"]').element).toHaveProperty('value', 'PrivateDraft123');
  identity.value = null; await flushPromises();
  expect(w.find('[data-testid="credential-password"]').exists()).toBe(false);
  expect(w.text()).not.toContain('api.real');
});
it.each([
  ['INVALID_PORTAL_BINDING', 'PROMOTER', '绑定失败：请使用已启用的真实业务用户，并确认已开通且启用推广资格。'],
  ['INVALID_PORTAL_BINDING', 'API_USER', '绑定失败：请使用已启用的真实业务用户，并确认已开通 API 身份。'],
  ['USERNAME_EXISTS', 'PROMOTER', '用户名已存在，请更换用户名。'],
  ['PORTAL_ACCOUNT_EXISTS', 'PROMOTER', '该业务用户已有此角色的登录账号，请在列表中管理已有账号。'],
  ['PORTAL_DIRECTORY_UNAVAILABLE', 'PROMOTER', '暂时无法核验业务用户或角色资格，请稍后重新提交。'],
  ['UNEXPECTED', 'PROMOTER', '创建失败，请稍后重新提交。'],
])('explains creation failure %s for %s and retains the draft', async (code, role, message) => {
  const w = open(); await flushPromises();
  await w.get('[data-testid="credential-username"]').setValue('aa');
  await w.get('[data-testid="credential-password"]').setValue('PrivateDraft123');
  await w.get('[data-testid="credential-role"]').setValue(role);
  await w.get('[data-testid="credential-owner"]').setValue('business-owner');
  calls.mockImplementation(async (path: string) => Response.json(path.endsWith('/auth/session')
    ? { authenticated: true, csrfToken: 'csrf' } : { code },
  { status: path.endsWith('/auth/session') ? 200 : 400 }));
  await w.get('form[aria-label="创建登录账号"]').trigger('submit'); await flushPromises();
  expect(w.get('[role="alert"]').text()).toBe(message);
  expect(w.get('[role="alert"]').find('button').exists()).toBe(false);
  expect(w.get('[data-testid="credential-password"]').element).toHaveProperty('value', 'PrivateDraft123');
  expect(w.get('[data-testid="credential-owner"]').element).toHaveProperty('value', 'business-owner');
  expect(w.text()).toContain('agent.real');
});

it('shows all credential roles without changing accounts', async () => {
  calls.mockImplementation(async () => Response.json([
    { id: 'api', username: 'api.hidden', role: 'API_USER', boundUserId: 'api-owner', enabled: true },
    { id: 'admin', username: 'admin.visible', role: 'ADMIN', primary: true },
    { id: 'normal', username: 'normal.visible', role: 'NORMAL_ADMIN' },
    { id: 'agent', username: 'agent.visible', role: 'PROMOTER', boundUserId: 'agent-owner' },
  ]));
  const w = open(); await flushPromises();
  const table = w.get('table');
  expect(table.text()).toContain('api.hidden');
  expect(table.text()).toContain('api-owner');
  expect(table.text()).toContain('API 用户');
  for (const name of ['admin.visible', 'normal.visible', 'agent.visible']) expect(table.text()).toContain(name);
  expect(calls).toHaveBeenCalledTimes(1);
  expect(calls.mock.calls[0][1].method).toBe('GET');
});
it('keeps API credentials visible and allows role filtering', async () => {
  calls.mockImplementation(async () => Response.json([{ id: 'api', username: 'api.hidden', role: 'API_USER' }]));
  const w = open(); await flushPromises();
  expect(w.get('table').text()).toContain('api.hidden');
  await w.get('[aria-label="筛选账号角色"]').setValue('NORMAL_ADMIN');
  expect(w.get('table').text()).toContain('暂无账号');
});

it('paginates login accounts by ten and clears records on identity change', async () => {
 const accounts = Array.from({length: 12}, (_, i) => ({id: 'account-'+i, username: 'account.'+i, role: 'NORMAL_ADMIN', enabled: true, primary: false}));
 calls.mockResolvedValueOnce(Response.json(accounts));
 const w = open(); await flushPromises();
 expect(w.findAll('tbody tr')).toHaveLength(10);
 const pager = w.get('[aria-label="登录账号分页"]');
 await pager.findAll('button')[1]!.trigger('click');
 expect(w.findAll('tbody tr')).toHaveLength(2);
 expect(w.get('tbody').text()).toContain('account.11');
 identity.value = null; await flushPromises();
 expect(w.text()).not.toContain('account.11');
});

it('defaults to a normal administrator and locates a created API account in its role list',async()=>{
 let rows=[{id:'existing',username:'existing.admin',role:'NORMAL_ADMIN',enabled:true}];
 calls.mockImplementation(async(path:string,options:RequestInit={})=>{
  if(path.endsWith('/auth/session'))return Response.json({authenticated:true,csrfToken:'csrf'});
  if(options.method==='POST'){const data=JSON.parse(String(options.body));rows.push({id:'new',...data,enabled:true});return Response.json(rows.at(-1));}
  return Response.json(rows);
 });
 const w=open();await flushPromises();expect(w.get<HTMLSelectElement>('[data-testid="credential-role"]').element.value).toBe('NORMAL_ADMIN');
 await w.get('[data-testid="credential-username"]').setValue('api.created');await w.get('[data-testid="credential-password"]').setValue('TestPassword123');await w.get('[data-testid="credential-role"]').setValue('API_USER');await w.get('[data-testid="credential-owner"]').setValue('owner');
 await w.get('form[aria-label="创建登录账号"]').trigger('submit');await flushPromises();
 expect(w.get('tbody').text()).toContain('api.created');expect(w.get('tbody').text()).toContain('API 用户');expect(w.get('tbody').text()).not.toContain('existing.admin');
});
