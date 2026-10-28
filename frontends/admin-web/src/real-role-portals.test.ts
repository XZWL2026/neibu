import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import ApiPage from './ApiPage.vue';
import PromoterPage from './PromoterPage.vue';
import { adminSessionKey, type AdminIdentity } from './adminSession';
import { configureGateway } from './api';

enableAutoUnmount(afterEach);
afterEach(() => { vi.unstubAllGlobals(); localStorage.clear(); });
beforeEach(() => configureGateway({ onUnauthorized: () => {} }));
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } });
const page = (items: unknown[], total = items.length) => ({ items, total, page: 0, size: 20 });
const summary = { totalCalls: 17, chargedAmount4: '12.3456', totalRechargeAmount4: '9007199254740993.1234', debitedPoints4: '123.4560', refundedPoints4: '0.0000', netPoints4: '123.4560' };
const overview = (owner: string) => ({ promoter: { userId: owner, invitationCode: `${owner}-code`, status: 'ENABLED' }, commission: { promoterUserId: owner, available: '500.1234', frozen: '12.3456', withdrawing: '0.0000', withdrawn: '100.0000', cashDebt: '0.0000', version: 1 }, downlineCount: 1, paidAmount4: '1000.1234', settledPaidAmount4: '123.4560', refundedPaidAmount4: '0.0000' });
const payout = { id: 'account-a', payoutType: 'BANK_CARD', maskedPayoutAccountName: '****', maskedPayoutAccountReference: '****5678', bankName: '真实银行', qrCodeConfigured: false, sourceVersion: 1, status: 'ACTIVE' };
function respond(path: string, owner: string): unknown {
  if (path === '/api/v1/auth/session') return { authenticated: true, csrfToken: 'csrf', roles: ['promoter'], user: { id: owner } };
  if (path === '/api/v1/access/keys') return [{ keyId: `${owner}-key`, ownerId: owner, maskedKey: `sk-${owner}****`, enabled: true, accountType: 'API', kind: 'PRIMARY' }];
  if (path === `/api/v1/access/keys/${owner}-key/secret`) return { secret: `sk-${owner}-full-secret` };
  if (path === '/api/v1/billing/portal/summary') return summary;
  if (path === '/api/v1/billing/portal/consumptions') return page([{ id: `${owner}-entry`, ownerId: owner, taskId: `${owner}-task`, keyId: `${owner}-key`, model: '真实模型', type: 'IMAGE', amount4: '12.3456', debitedPoints4: '123.4560', refundedPoints4: '0.0000', netPoints4: '123.4560', createdAt: '2026-09-11T00:00:00Z' }]);
  if (path.endsWith('/ledger')) return [{ kind: 'CONSUME', requestId: `${owner}-task`, points4: '-123.4560', balanceAfter4: '876.5440' }];
  if (path.startsWith('/api/v1/billing/wallets/')) return { walletId: `${owner}-key`, availablePoints4: '876.5440' };
  if (path === '/api/v1/payments/recharge-options') return { userId: owner, accountType: 'API', firstRecharge: false, pointsPerYuan4: '10.0000', rules: [{ accountType: 'API', minimumAmount4: '50.0000', presetAmounts4: ['50.0000'] }] };
  if (path === '/api/v1/payments/recharges') return [{ orderId: `${owner}-order`, userId: owner, amount4: '9007199254740993.1234', status: 'PAID', fulfilled: true, source: 'ONLINE', createdAt: '2026-09-11T00:00:00Z' }];
  if (path === '/api/v1/users/me') return { id: owner, nickname: `${owner}-真实账户`, phone: '+8613812345678', enabled: true };
  if (path === '/api/v1/promotions/portal/overview') return overview(owner);
  if (path.endsWith('/downlines')) return page([{ maskedUserId: '****1234', nickname: `${owner}-下级`, maskedPhone: '138****5678', accountType: 'API', accountStatus: 'ENABLED', level: 1, registeredAt: '2026-01-01T00:00:00Z', totalRechargeAmount4: '12.3456' }]);
  if (path.endsWith('/consumptions')) return page([{ allocationId: `${owner}-allocation`, maskedUserId: '****1234', nickname: `${owner}-下级`, maskedPhone: '138****5678', type: 'IMAGE', paidAmount4: '12.3456', commissionAmount4: '1.2345', points4: '123.4560', balanceBeforePoints4: '1000.0000', balanceAfterPoints4: '876.5440', occurredAt: '2026-09-11T00:00:00Z' }]);
  if (path.endsWith('/recharges')) return page([{ orderId: `${owner}-recharge`, nickname: `${owner}-下级`, maskedPhone: '138****5678', paidAmount4: '100.1234', commissionAmount4: '10.0123', occurredAt: '2026-09-11T00:00:00Z' }]);
  if (path.endsWith('/payout-accounts')) return page([payout]);
  if (path.endsWith('/withdrawals')) return page([]);
  if (path.endsWith('/paid-months')) return { ...page([]), totalAmount4: '0.0000' };
  throw new Error(`Unexpected SDK request ${path}`);
}
async function open(kind: 'API_USER' | 'PROMOTER', path: string, owner = 'owner-a', role = kind) {
  const names = ['api-dashboard', 'api-keys', 'api-consumption', 'api-billing', 'api-transfer', 'api-docs', 'api-account', 'promoter-overview', 'promoter-users', 'promoter-consumption', 'promoter-settlements', 'promoter-account'];
  const router = createRouter({ history: createMemoryHistory(), routes: names.map(name => ({ path: `/${name.replace('-', '/')}`, name, component: { template: '<div />' } })).concat([{ path: '/admin/login', name: 'admin-login', component: { template: '<div />' } }]) });
  await router.push(path); await router.isReady();
  const identity = ref<AdminIdentity | null>({ username: owner, userId: owner, role });
  const wrapper = mount(kind === 'API_USER' ? ApiPage : PromoterPage, { global: { plugins: [router], provide: { [adminSessionKey as symbol]: { currentAdmin: identity, authenticate: () => false } } } });
  return { wrapper, router, identity };
}

it('documents only existing counterparts of legacy public APIs and makes no requests', async () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
  const { wrapper } = await open('API_USER', '/api/docs');
  const categories = [
    ['图片生成', ['/api/v1/media/tasks'], 'type: IMAGE'],
    ['视频生成', ['/api/v1/media/tasks'], 'type: VIDEO'],
    ['人像库审核', ['/api/v1/media/portraits', '/api/v1/media/portraits/{portraitId}'], 'ACTIVE'],
    ['任务查询', ['/api/v1/media/tasks/{taskId}'], 'results']
  ] as const;
  const buttons = wrapper.get('nav[aria-label="API 文档分类"]').findAll('a').filter(link=>categories.some(([name])=>name===link.text()));
  expect(buttons.map(button => button.text())).toEqual(categories.map(([name]) => name));
  expect(buttons[0].attributes('aria-current')).toBe('page');
  for (const [index, [name, paths, detail]] of categories.entries()) {
    await buttons[index].trigger('click'); await flushPromises();
    expect(wrapper.get('#api-category-title').text()).toBe(name);
    expect(wrapper.findAll('.api-doc-content table:not([aria-label]) tbody tr').map(row => row.findAll('td')[1].text())).toEqual(paths);
    expect(wrapper.get('.api-doc-content').text()).toContain(detail);
    expect(wrapper.text()).not.toContain('/api/v1/media/quotes');
    expect(wrapper.text()).not.toContain('报价');
    expect(buttons.filter(button => button.attributes('aria-current') === 'page')).toHaveLength(1);
  }
  expect(wrapper.text()).toContain('Authorization: Bearer 平台Key');
  expect(wrapper.text()).not.toContain('素材资产 API');
  expect(wrapper.text()).not.toContain('/api/external/v1/');
  expect(fetch).not.toHaveBeenCalled();
});

it('loads API data through the SDK, preserves accounting strings and never invents success statistics', async () => {
  const fetch = vi.fn(async (url: string, _init?: RequestInit) => json(respond(new URL(url, 'http://test').pathname, 'owner-a'))); vi.stubGlobal('fetch', fetch);
  const { wrapper, router } = await open('API_USER', '/api/dashboard'); await flushPromises();
  expect(wrapper.findAll('.api-summary-card')).toHaveLength(4);
  await wrapper.get('button[aria-label="显示 API 密钥"]').trigger('click'); await flushPromises();
  expect(wrapper.get('.api-key-secret').text()).toBe('sk-owner-a-full-secret');
  expect(wrapper.find('a[href="/api/keys"]').exists()).toBe(false);
  expect(localStorage.length).toBe(0);
  expect(wrapper.text()).toContain('9007199254740993.1234'); expect(wrapper.text()).toContain('已记账');
  expect(wrapper.text()).not.toContain('成功率'); expect(wrapper.text()).not.toMatch(/fixture|零网络/);
  await router.push('/api/consumption'); await flushPromises();
  expect(wrapper.text()).toContain('12.3456'); expect(wrapper.text()).toContain('876.544'); expect(wrapper.text()).toContain('1000');
  await router.push('/api/billing'); await flushPromises(); expect(wrapper.text()).toContain('owner-a-order');
  expect(fetch.mock.calls.every(([, init]) => !init || (init as RequestInit).method === 'GET')).toBe(true);
});

it('does not fetch for other roles and clears delayed API responses across owners and unmount', async () => {
  const denied = vi.fn(); vi.stubGlobal('fetch', denied);
  const forbidden = await open('API_USER', '/api/dashboard', 'owner-a', 'PROMOTER'); await flushPromises();
  expect(forbidden.wrapper.text()).toContain('无权限'); expect(denied).not.toHaveBeenCalled(); forbidden.wrapper.unmount();
  let resolve!: (response: Response) => void; let owner = 'owner-a';
  vi.stubGlobal('fetch', vi.fn(async (url: string) => new URL(url, 'http://test').pathname === '/api/v1/billing/portal/summary' && owner === 'owner-a'
    ? new Promise<Response>(done => { resolve = done; }) : json(respond(new URL(url, 'http://test').pathname, owner))));
  const active = await open('API_USER', '/api/dashboard'); await flushPromises(); owner = 'owner-b';
  active.identity.value = { username: owner, userId: owner, role: 'API_USER' }; await flushPromises();
  resolve(json({ ...summary, totalRechargeAmount4: '111111.1111' })); await flushPromises();
  await active.wrapper.get('button[aria-label="显示 API 密钥"]').trigger('click'); await flushPromises();
  expect(active.wrapper.get('.api-key-secret').text()).toBe('sk-owner-b-full-secret');
  expect(active.wrapper.text()).not.toContain('111111.1111'); expect(active.wrapper.text()).not.toContain('sk-owner-a');
  active.wrapper.unmount(); await flushPromises();
});

it('clears revealed keys on leaving the dashboard and never requests another owner secret', async () => {
  let owner = 'owner-a';
  const fetch = vi.fn(async (url: string) => json(respond(new URL(url, 'http://test').pathname, owner))); vi.stubGlobal('fetch', fetch);
  const active = await open('API_USER', '/api/dashboard'); await flushPromises();
  await active.wrapper.get('button[aria-label="显示 API 密钥"]').trigger('click'); await flushPromises();
  expect(active.wrapper.get('.api-key-secret').text()).toBe('sk-owner-a-full-secret');
  await active.router.push('/api/docs'); await flushPromises(); expect(active.wrapper.text()).not.toContain('sk-owner-a-full-secret');
  owner = 'other-owner'; fetch.mockClear();
  await active.router.push('/api/dashboard'); await flushPromises();
  expect(active.wrapper.text()).toContain('密钥归属不匹配'); expect(active.wrapper.text()).not.toContain('full-secret');
  expect(fetch.mock.calls.some(([url]) => url.includes('/secret'))).toBe(false);
});

it('does not render masked data as plaintext after a secret read fails and retries explicitly', async () => {
  let fail = true;
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const path = new URL(url, 'http://test').pathname;
    return path.endsWith('/secret') && fail ? json({ message: '不可读取' }, 403) : json(respond(path, 'owner-a'));
  }));
  const active = await open('API_USER', '/api/dashboard'); await flushPromises();
  await active.wrapper.get('button[aria-label="显示 API 密钥"]').trigger('click'); await flushPromises();
  expect(active.wrapper.get('.api-key-secret').text()).toContain('••'); expect(active.wrapper.find('[role="status"]').exists()).toBe(true);
  fail = false; await active.wrapper.get('button[aria-label="显示 API 密钥"]').trigger('click'); await flushPromises();
  expect(active.wrapper.get('.api-key-secret').text()).toBe('sk-owner-a-full-secret');
});

it('shows load errors with retry and genuine empty promoter results', async () => {
  let fail = true; const fetch = vi.fn(async (url: string) => fail ? json({ error: '暂时不可用' }, 503) : json(new URL(url, 'http://test').pathname.endsWith('/downlines') ? page([]) : respond(new URL(url, 'http://test').pathname, 'owner-a'))); vi.stubGlobal('fetch', fetch);
  const { wrapper } = await open('PROMOTER', '/promoter/users'); await flushPromises();
  expect(wrapper.get('[role="alert"]').text()).toContain('无法读取'); expect(wrapper.text()).not.toContain('推广团队');
  fail = false; await wrapper.get('[data-testid="portal-retry"]').trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('暂无邀请用户');
});

it.each(['/promoter/account', '/promoter/settlements'])('keeps withdrawal records on the commission page and bank settings on %s', async path => {
  const fetch = vi.fn(async (url: string, init?: RequestInit) => json(respond(new URL(url, 'http://test').pathname, 'owner-a'))); vi.stubGlobal('fetch', fetch);
  const { wrapper } = await open('PROMOTER', path); await flushPromises();
  expect(wrapper.text()).not.toMatch(/发起提现申请|提交提现申请|最低 100 元|暂无有效收款账户/);
  expect(wrapper.find('[data-testid="withdrawal-account"]').exists()).toBe(false);
  expect(wrapper.find('.record-card').exists()).toBe(path.endsWith('/settlements'));
  if (path.endsWith('/account')) {
    expect(wrapper.find('[data-testid="save-payout-method"]').exists()).toBe(true);
    expect(wrapper.get('.payout-card h2').text()).toBe('收款账户');
  }
  expect(fetch.mock.calls.every(([, init]) => !init || init.method === 'GET')).toBe(true);
});

it('saves validated payout information only after server confirmation, preserving retry IDs and masked remounts', async () => {
  const writes: { id: string; body: Record<string, unknown> }[] = []; let stored: typeof payout | undefined; let failures = 1;
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const path = new URL(url, 'http://test').pathname;
    if (init?.method === 'PUT') {
      const body = JSON.parse(String(init.body)); const id = path.split('/').at(-1)!; writes.push({ id, body });
      stored = { ...payout, id, payoutType: 'BANK_CARD', bankName: '真实银行', maskedPayoutAccountReference: '****5678' };
      if (failures--) throw new TypeError('lost save response'); return json(stored);
    }
    return json(path.endsWith('/payout-accounts') && stored ? page([stored]) : respond(path, 'owner-a'));
  }));
  const first = await open('PROMOTER', '/promoter/account'); await flushPromises();
  await first.wrapper.get('[data-testid="payout-account-name"]').setValue('真实收款人');
  await first.wrapper.get('[data-testid="payout-bank-name"]').setValue('真实银行');
  await first.wrapper.get('[data-testid="payout-bank-card"]').setValue('invalid');
  await first.wrapper.get('[data-testid="save-payout-method"]').trigger('click'); await flushPromises(); expect(writes).toHaveLength(0);
  await first.wrapper.get('[data-testid="payout-bank-card"]').setValue('6222000012345678');
  await first.wrapper.get('[data-testid="save-payout-method"]').trigger('click'); await flushPromises();
  expect(first.wrapper.text()).not.toContain('收款方式已保存'); expect(first.wrapper.get('fieldset').attributes('disabled')).toBeDefined();
  await first.wrapper.get('[data-testid="payout-retry"]').trigger('click'); await flushPromises();
  expect(writes[1]).toEqual(writes[0]); expect(writes[0]?.body).toMatchObject({ payoutType: 'BANK_CARD', payoutAccountName: '真实收款人', payoutAccountReference: '6222000012345678', expectedVersion: 1 });
  expect(writes[0]?.body).not.toHaveProperty('ownerUserId'); expect(first.wrapper.text()).toContain('收款方式已保存');
  first.wrapper.unmount(); const second = await open('PROMOTER', '/promoter/account'); await flushPromises();
  expect(second.wrapper.get('[data-testid="payout-bank-card"]').attributes('placeholder')).toBe('****5678'); expect(second.wrapper.text()).not.toContain('6222000012345678');
  expect(second.wrapper.get('[data-testid="payout-bank-card"]').element).toHaveProperty('value', ''); expect(localStorage.length).toBe(0);
});

it('does not let a delayed promoter write follow identity changes, including returning to the same owner', async () => {
  let owner = 'owner-a'; let finish!: (response: Response) => void; let written: Record<string, unknown> = {};
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const path = new URL(url, 'http://test').pathname;
    if (init?.method === 'PUT') { written = JSON.parse(String(init.body)); return new Promise<Response>(resolve => { finish = resolve; }); }
    return json(respond(path, owner));
  }); vi.stubGlobal('fetch', fetch);
  const active = await open('PROMOTER', '/promoter/account'); await flushPromises();
  await active.wrapper.get('[data-testid="payout-account-name"]').setValue('收款人'); await active.wrapper.get('[data-testid="payout-bank-card"]').setValue('6222000012345678'); await active.wrapper.get('[data-testid="payout-bank-name"]').setValue('银行'); await active.wrapper.get('[data-testid="save-payout-method"]').trigger('click'); await flushPromises();
  owner = 'owner-b'; active.identity.value = { userId: owner, username: owner, role: 'PROMOTER' }; await flushPromises();
  expect(active.wrapper.text()).toContain('owner-b-真实账户'); expect(active.wrapper.text()).not.toContain('owner-a-真实账户');
  owner = 'owner-a'; active.identity.value = { userId: owner, username: owner, role: 'PROMOTER' }; await flushPromises();
  const before = fetch.mock.calls.length; finish(json({ ...payout, ...written, id: 'account-a' })); await flushPromises();
  expect(active.wrapper.text()).not.toContain('收款方式已保存'); expect(fetch.mock.calls).toHaveLength(before);
});

it('pages real lists and keeps unknown CNY balances distinct from matched point balances', async () => {
  const fetch = vi.fn(async (url: string) => {
    const request = new URL(url, 'http://test'); const data = respond(request.pathname, 'owner-a');
    if (request.pathname.endsWith('/downlines')) return json({ ...data as object, total: 21, items: request.searchParams.get('page') === '1' ? [] : (data as { items: unknown[] }).items });
    return json(data);
  }); vi.stubGlobal('fetch', fetch);
  const active = await open('PROMOTER', '/promoter/users'); await flushPromises();
  expect(active.wrapper.text()).toContain('12.3456'); expect(active.wrapper.findAll('tbody tr')).toHaveLength(1);
  await active.wrapper.get('nav[aria-label="推广用户分页"] button:last-child').trigger('click'); await flushPromises();
  expect(fetch.mock.calls.some(([url]) => url.includes('page=1'))).toBe(true); expect(active.wrapper.get('.page-number').text()).toBe('2');
  await active.router.push('/promoter/consumption'); await flushPromises();
  const cells = active.wrapper.findAll('tbody tr:first-child td').map(cell => cell.text());
  expect(cells[2]).toBe('¥ 12.3456'); expect(cells[4]).toBe('123.456 积分');
  expect(active.wrapper.get('[data-testid=balance-before]').text()).toBe('—');
  expect(active.wrapper.get('[data-testid=balance-after]').text()).toBe('—');
  expect(active.wrapper.get('tbody details').text()).toContain('1000 积分'); expect(active.wrapper.get('tbody details').text()).toContain('876.544 积分');
});

it('keeps API docs and denied promoter routes free of network access', async () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
  const docs = await open('API_USER', '/api/docs'); await flushPromises();
  expect(docs.wrapper.text()).toContain('/api/v1/media/tasks'); expect(docs.wrapper.text()).toContain('Authorization: Bearer 平台Key'); expect(fetch).not.toHaveBeenCalled(); docs.wrapper.unmount();
  const denied = await open('PROMOTER', '/promoter/account', 'owner-a', 'API_USER'); await flushPromises();
  expect(denied.wrapper.text()).toContain('无权限'); expect(fetch).not.toHaveBeenCalled();
});

it('preserves forms on a same-identity session refresh and ignores write completion after unmount', async () => {
  let finish!: (response: Response) => void; let written: Record<string, unknown> = {};
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const path = new URL(url, 'http://test').pathname;
    if (init?.method === 'PUT') {
      written = JSON.parse(String(init.body)); return new Promise<Response>(resolve => { finish = resolve; });
    }
    return json(respond(path, 'owner-a'));
  }); vi.stubGlobal('fetch', fetch);
  const active = await open('PROMOTER', '/promoter/account'); await flushPromises();
  await active.wrapper.get('[data-testid="payout-account-name"]').setValue('收款人'); await active.wrapper.get('[data-testid="payout-bank-card"]').setValue('6222000012345678'); await active.wrapper.get('[data-testid="payout-bank-name"]').setValue('银行');
  const count = fetch.mock.calls.length;
  active.identity.value = { ...active.identity.value! }; await flushPromises();
  expect(fetch.mock.calls).toHaveLength(count);
  expect(active.wrapper.get('[data-testid="payout-account-name"]').element).toHaveProperty('value', '收款人');
  await active.wrapper.get('[data-testid="save-payout-method"]').trigger('click'); await flushPromises();
  active.wrapper.unmount(); const afterUnmount = fetch.mock.calls.length;
  finish(json({ ...payout, ...written, id: 'account-a' })); await flushPromises();
  expect(fetch.mock.calls).toHaveLength(afterUnmount);
});

it('does not start old-owner ledger reads when a delayed consumption page finishes', async () => {
  let owner = 'owner-a'; let finish!: (response: Response) => void;
  const fetch = vi.fn(async (url: string) => {
    const path = new URL(url, 'http://test').pathname;
    if (path === '/api/v1/billing/portal/consumptions' && owner === 'owner-a') return new Promise<Response>(resolve => { finish = resolve; });
    return json(respond(path, owner));
  }); vi.stubGlobal('fetch', fetch);
  const active = await open('API_USER', '/api/consumption'); await flushPromises();
  owner = 'owner-b'; active.identity.value = { userId: owner, username: owner, role: 'API_USER' }; await flushPromises();
  const count = fetch.mock.calls.length;
  finish(json(respond('/api/v1/billing/portal/consumptions', 'owner-a'))); await flushPromises();
  expect(fetch.mock.calls).toHaveLength(count);
  expect(active.wrapper.text()).not.toContain('owner-a-entry');
});

it('loads today consumption with browser-local day bounds independently of all-time totals', async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  const now = new Date(2026, 8, 11, 15, 24, 30); vi.setSystemTime(now);
  try {
    const fetch = vi.fn(async (url: string) => {
      const request = new URL(url, 'http://test');
      return json(request.pathname.endsWith('/billing/portal/summary') && request.searchParams.has('from')
        ? { ...summary, chargedAmount4: '7.1234', netPoints4: '71.2340' } : respond(request.pathname, 'owner-a'));
    }); vi.stubGlobal('fetch', fetch);
    const active = await open('API_USER', '/api/dashboard'); await flushPromises();
    const requests = fetch.mock.calls.map(([url]) => new URL(url, 'http://test')).filter(url => url.pathname.endsWith('/billing/portal/summary'));
    expect(requests.length).toBeGreaterThan(3);
    expect(requests.some(url => !url.searchParams.has('from') && !url.searchParams.has('to'))).toBe(true);
    const today = requests.find(url => url.searchParams.has('from'))!;
    expect(today.searchParams.get('from')).toBe(new Date(2026, 8, 11).toISOString());
    expect(today.searchParams.get('to')).toBe(now.toISOString());
    expect(requests.some(url=>url.searchParams.get('from')===new Date(2026,8,10).toISOString()&&url.searchParams.get('to')===new Date(2026,8,11).toISOString())).toBe(true);
    expect(active.wrapper.text()).toContain('今日消费'); expect(active.wrapper.text()).toContain('¥ 7.1234');
    expect(active.wrapper.text()).toContain('9007199254740993.1234');
  } finally { vi.useRealTimers(); }
});

it('shows one direct invitation count and exact net commission totals including frozen pending settlement', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const path = new URL(url, 'http://test').pathname;
    return json(path.endsWith('/overview') ? { ...overview('owner-a'), downlineCount: 21, levelOneCount: 21, levelTwoCount: 13,
      releasedCommissionAmount4: '321.1234', totalCommissionAmount4: '654.4321' } : respond(path, 'owner-a'));
  }));
  const active = await open('PROMOTER', '/promoter/overview'); await flushPromises();
  const invitationCards = active.wrapper.findAll('.promoter-context-grid article').filter(card => card.text().includes('邀请人数'));
  expect(invitationCards).toHaveLength(1); expect(invitationCards[0]!.get('strong').text()).toBe('21');
  expect(active.wrapper.text()).not.toMatch(/一级|二级|推广用户总数/);
  expect(active.wrapper.text()).toContain('¥ 321.1234');
  expect(active.wrapper.text()).toContain('¥ 654.4321'); expect(active.wrapper.text()).not.toContain('累计佣金未返回');
  await active.router.push('/promoter/settlements'); await flushPromises();
  const cards = active.wrapper.findAll('.metrics-grid article').map(item => item.text());
  expect(cards[0]).toContain('¥ 654.4321'); expect(cards[1]).toContain('今日佣金'); expect(cards[2]).toContain('昨日佣金');
});

it('filters withdrawal months on the server before pagination and resets the page for every month change', async () => {
  const fetch = vi.fn(async (url: string) => {
    const request = new URL(url, 'http://test');
    if (request.pathname.endsWith('/withdrawals')) return json(page([{ id: request.searchParams.has('from') ? 'older-month-match' : 'recent-withdrawal',
      promoterUserId: 'owner-a', amount: '100.0000', status: 'PAID', requestedAt: '2026-08-31T23:30:00Z' }], 21));
    return json(respond(request.pathname, 'owner-a'));
  }); vi.stubGlobal('fetch', fetch);
  const active = await open('PROMOTER', '/promoter/settlements'); await flushPromises();
  await active.wrapper.get('nav[aria-label="发放记录分页"] button:last-child').trigger('click'); await flushPromises();
  await active.wrapper.get('[data-testid="settlement-withdrawal-month"]').setValue('2026-08'); await flushPromises();
  const query = () => new URL(fetch.mock.calls.map(([url]) => url).filter(url => new URL(url, 'http://test').pathname.endsWith('/withdrawals')).at(-1)!, 'http://test').searchParams;
  expect(query().get('from')).toBe(new Date(2026, 7, 1).toISOString()); expect(query().get('to')).toBe(new Date(2026, 8, 1).toISOString()); expect(query().get('page')).toBe('0');
  expect(active.wrapper.text()).toContain('older-month-match'); expect(active.wrapper.text()).toContain('21 条');
  await active.wrapper.get('nav[aria-label="发放记录分页"] button:last-child').trigger('click'); await flushPromises();
  expect(query().get('page')).toBe('1'); expect(query().get('from')).toBe(new Date(2026, 7, 1).toISOString());
  await active.wrapper.get('[data-testid="settlement-withdrawal-month"]').setValue(''); await flushPromises();
  expect(query().has('from')).toBe(false); expect(query().has('to')).toBe(false); expect(query().get('page')).toBe('0');

});

it.each([400, 409])('unlocks rejected payout edits and refreshes a confirmed stale version (%s)', async status => {
  const writes: Record<string, unknown>[] = []; let version = 1;
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const path = new URL(url, 'http://test').pathname;
    if (init?.method === 'PUT') {
      writes.push(JSON.parse(String(init.body)));
      if (writes.length === 1) { version = 2; return json({ error: status === 409 ? 'payout account version conflict' : 'holderName exceeds maximum length' }, status); }
      return json({ ...payout, sourceVersion: version + 1 });
    }
    return json(path.endsWith('/payout-accounts') ? page([{ ...payout, sourceVersion: version }]) : respond(path, 'owner-a'));
  }));
  const active = await open('PROMOTER', '/promoter/account'); await flushPromises();
  await active.wrapper.get('[data-testid="payout-account-name"]').setValue('Initial Name');
  await active.wrapper.get('[data-testid="payout-bank-card"]').setValue('6222000012345678');
  await active.wrapper.get('[data-testid="payout-bank-name"]').setValue('Real Bank');
  await active.wrapper.get('[data-testid="save-payout-method"]').trigger('click'); await flushPromises();
  expect(active.wrapper.get('fieldset').attributes('disabled')).toBeUndefined();
  expect(active.wrapper.find('[data-testid="payout-retry"]').exists()).toBe(false);
  expect(active.wrapper.get('[data-testid="payout-bank-card"]').element).toHaveProperty('value', '6222000012345678');
  expect(active.wrapper.text()).not.toContain('收款方式已保存');
  await active.wrapper.get('[data-testid="payout-account-name"]').setValue('Corrected Name');
  await active.wrapper.get('[data-testid="save-payout-method"]').trigger('click'); await flushPromises();
  expect(writes[1]?.commandId).not.toBe(writes[0]?.commandId);
  expect(writes[1]?.payoutAccountName).toBe('Corrected Name');
  expect(writes[1]?.expectedVersion).toBe(status === 409 ? 2 : 1);
  expect(active.wrapper.text()).toContain('收款方式已保存');
});

it.each([409, 503])('retains exact payout retry semantics for an ambiguous error (%s)', async status => {
  const writes: Record<string, unknown>[] = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const path = new URL(url, 'http://test').pathname;
    if (init?.method === 'PUT') { writes.push(JSON.parse(String(init.body))); return json({ error: status === 409 ? 'idempotency key was replayed with different semantics' : 'service unavailable' }, status); }
    return json(respond(path, 'owner-a'));
  }));
  const active = await open('PROMOTER', '/promoter/account'); await flushPromises();
  await active.wrapper.get('[data-testid="payout-account-name"]').setValue('Original Name');
  await active.wrapper.get('[data-testid="payout-bank-card"]').setValue('6222000012345678');
  await active.wrapper.get('[data-testid="payout-bank-name"]').setValue('Real Bank');
  await active.wrapper.get('[data-testid="save-payout-method"]').trigger('click'); await flushPromises();
  expect(active.wrapper.get('fieldset').attributes('disabled')).toBeDefined();
  await active.wrapper.get('[data-testid="payout-retry"]').trigger('click'); await flushPromises();
  expect(writes[1]).toEqual(writes[0]); expect(active.wrapper.text()).not.toContain('收款方式已保存');
});

it('blocks resubmission if the stale payout version cannot be refreshed and preserves the draft for recovery', async () => {
  const writes: Record<string, unknown>[] = []; let failRefresh = false;
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const path = new URL(url, 'http://test').pathname;
    if (init?.method === 'PUT') {
      writes.push(JSON.parse(String(init.body)));
      if (writes.length === 1) { failRefresh = true; return json({ error: 'payout account version conflict' }, 409); }
      return json({ ...payout, sourceVersion: 3 });
    }
    if (path.endsWith('/payout-accounts')) return failRefresh ? json({ error: 'service unavailable' }, 503) : json(page([{ ...payout, sourceVersion: writes.length ? 2 : 1 }]));
    return json(respond(path, 'owner-a'));
  }));
  const active = await open('PROMOTER', '/promoter/account'); await flushPromises();
  await active.wrapper.get('[data-testid="payout-account-name"]').setValue('Saved Draft');
  await active.wrapper.get('[data-testid="payout-bank-card"]').setValue('6222000012345678');
  await active.wrapper.get('[data-testid="payout-bank-name"]').setValue('Real Bank');
  await active.wrapper.get('[data-testid="save-payout-method"]').trigger('click'); await flushPromises();
  expect(active.wrapper.get('[role="alert"]').text()).toContain('service unavailable');
  expect(active.wrapper.get('[data-testid="save-payout-method"]').attributes('disabled')).toBeDefined();
  expect(writes).toHaveLength(1); expect(active.wrapper.text()).not.toContain('收款方式已保存');
  expect(active.wrapper.get('[data-testid="payout-account-name"]').element).toHaveProperty('value', 'Saved Draft');
  failRefresh = false; await active.wrapper.get('[data-testid="portal-retry"]').trigger('click'); await flushPromises();
  await active.wrapper.get('[data-testid="save-payout-method"]').trigger('click'); await flushPromises();
  expect(writes[1]?.expectedVersion).toBe(2); expect(writes[1]?.payoutAccountName).toBe('Saved Draft');
  expect(active.wrapper.text()).toContain('收款方式已保存');
});

 it('keeps recharge and consumption date queries separate from monthly paid history with independent pages', async () => {
  const fetch = vi.fn(async (url: string) => {
    const request = new URL(url, 'http://test');
    if (['recharges', 'consumptions', 'paid-months'].some(kind => request.pathname.endsWith('/' + kind))) {
      const data = respond(request.pathname, 'owner-a') as { items: unknown[] };
      return json({ ...page(data.items, 45), totalAmount4: '9007199254740993.1234' });
    }
    return json(respond(request.pathname, 'owner-a'));
  }); vi.stubGlobal('fetch', fetch);
  const active = await open('PROMOTER', '/promoter/overview'); await flushPromises();
  const query = (kind: string) => new URL(fetch.mock.calls.map(([url]) => url).filter(url => new URL(url, 'http://test').pathname.endsWith('/' + kind)).at(-1)!, 'http://test').searchParams;
  const form = active.wrapper.get('form[aria-label="记录日期筛选"]');
  await form.get('[name="from"]').setValue('2026-08-13');
  await form.get('[name="to"]').setValue('2026-09-12');
  await form.trigger('submit'); await flushPromises();
  for (const kind of ['recharges', 'consumptions']) {
    expect(query(kind).get('from')).toBe(new Date(2026, 7, 13).toISOString());
    expect(query(kind).get('to')).toBe(new Date(2026, 8, 13).toISOString());
    expect(query(kind).get('page')).toBe('0');
  }
  expect(active.wrapper.findAll('.financial-record-card')).toHaveLength(3);
  expect(active.wrapper.findAll('.record-total').every(card => card.text().includes('9007199254740993.1234'))).toBe(true);
  for (const label of ['充值记录分页', '首页消费分页', '发放记录分页']) {
    await active.wrapper.get(`nav[aria-label="${label}"] button:last-child`).trigger('click'); await flushPromises();
  }
  expect(query('recharges').get('page')).toBe('1'); expect(query('consumptions').get('page')).toBe('1'); expect(query('paid-months').get('page')).toBe('1');
  await form.get('[name="from"]').setValue('2026-08-01');
  await active.wrapper.get('nav[aria-label="充值记录分页"] button:last-child').trigger('click'); await flushPromises();
  expect(query('recharges').get('from')).toBe(new Date(2026, 7, 13).toISOString());
  await form.trigger('submit'); await flushPromises();
  for (const kind of ['recharges', 'consumptions']) expect(query(kind).get('page')).toBe('0');
  expect(query('paid-months').get('page')).toBe('1');
  await form.get('[name="from"]').setValue('2026-10-01');
  const count = fetch.mock.calls.length;
  await form.trigger('submit'); await flushPromises();
  expect(fetch.mock.calls.length).toBe(count); expect(active.wrapper.get('[role="alert"]').text()).toContain('开始日期不能晚于结束日期');
  await form.get('[name="from"]').setValue(''); await form.get('[name="to"]').setValue('');
  await form.trigger('submit'); await flushPromises();
  for (const kind of ['recharges', 'consumptions']) { expect(query(kind).has('from')).toBe(false); expect(query(kind).has('to')).toBe(false); }
});

it('keeps the latest record query when an older range returns late and shows unknown totals as unavailable', async () => {
  let finish!: (response: Response) => void;
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const request = new URL(url, 'http://test');
    if (request.pathname.endsWith('/consumptions') && request.searchParams.get('from') === new Date(2026, 6, 1).toISOString()) {
      return new Promise<Response>(resolve => { finish = resolve; });
    }
    return json({ ...respond(request.pathname, 'owner-a') as object, totalAmount4: null });
  }));
  const active = await open('PROMOTER', '/promoter/overview'); await flushPromises();
  const form = active.wrapper.get('form[aria-label="记录日期筛选"]');
  await form.get('[name="from"]').setValue('2026-07-01'); await form.get('[name="to"]').setValue('2026-07-31');
  await form.trigger('submit'); await flushPromises();
  await form.get('[name="from"]').setValue('2026-08-01'); await form.get('[name="to"]').setValue('2026-08-31');
  await form.trigger('submit'); await flushPromises();
  expect(active.wrapper.get('.consumption-records .record-total').text()).toContain('—');
  finish(json({ ...page([{ allocationId: 'stale-result', paidAmount4: '999.0000' }]), totalAmount4: '999.0000' })); await flushPromises();
  expect(active.wrapper.text()).not.toContain('stale-result');
  expect(active.wrapper.get('.consumption-records .record-total').text()).toContain('—');
});


it.each(['/promoter/overview'])('shows the current agent promotion link on %s', async (path) => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => json(respond(new URL(url, 'http://test').pathname, 'owner-a'))));
  const { wrapper } = await open('PROMOTER', path); await flushPromises();
  expect(wrapper.get('.promotion-link input').element).toHaveProperty('value', `${window.location.origin}/home?ref=owner-a-code`);
  expect(wrapper.get('.promotion-link button').text()).toBe('复制链接');
});

it('labels settlement disbursements with small read-only paid and pending statuses', async () => {
  const fetch = vi.fn(async (url: string) => {
    const path = new URL(url, 'http://test').pathname;
    return json(path.endsWith('/withdrawals') ? page([
      { id: 'paid-row', promoterUserId: 'owner-a', amount: '123.4567', status: 'PAID', requestedAt: '2026-08-05T00:00:00Z' },
      { id: 'pending-row', promoterUserId: 'owner-a', amount: '100.0000', status: 'PENDING_PAYOUT', requestedAt: '2026-09-01T00:00:00Z' },
      { id: 'review-row', promoterUserId: 'owner-a', amount: '100.0000', status: 'WITHDRAWING', requestedAt: '2026-09-02T00:00:00Z' },
      { id: 'rejected-row', promoterUserId: 'owner-a', amount: '100.0000', status: 'REJECTED', requestedAt: '2026-09-03T00:00:00Z' }
    ]) : respond(path, 'owner-a'));
  }); vi.stubGlobal('fetch', fetch);
  const active = await open('PROMOTER', '/promoter/settlements'); await flushPromises();
  const records = active.wrapper.get('.record-card');
  expect(records.get('h2').text()).toBe('发放记录');
  expect(records.get('.disbursement-paid').text()).toBe('已发放');
  expect(records.get('.disbursement-pending').text()).toBe('待发放');
  expect(records.get('.disbursement-paid').attributes('disabled')).toBeDefined();
  expect(records.get('.disbursement-pending').attributes('disabled')).toBeDefined();
  expect(records.text()).toContain('待审核'); expect(records.text()).toContain('已拒绝');
  expect(records.text()).toContain('¥ 123.4567');
  await active.router.push('/promoter/account'); await flushPromises();
  expect(active.wrapper.find('.record-card').exists()).toBe(false);
});


it('shows monthly paid history and carried unpaid commission independently from the consumption date filter', async () => {
  vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-09-14T04:00:00Z'));
  try {
    const fetch = vi.fn(async (url: string) => {
      const request = new URL(url, 'http://test');
      if (request.pathname.endsWith('/overview')) return json({ ...overview('owner-a'), commission: { ...overview('owner-a').commission, available: '500.1234', withdrawing: '100.0000', frozen: '999.0000', cashDebt: '20.0000' } });
      if (request.pathname.endsWith('/paid-months')) return json({ ...page([{ month: request.searchParams.get('page') === '1' ? '2024-12' : '2026-08', amount4: '9007199254740993.1200' }], 21), totalAmount4: '9007199254741993.1200' });
      return json(respond(request.pathname, 'owner-a'));
    }); vi.stubGlobal('fetch', fetch);
    const { wrapper } = await open('PROMOTER', '/promoter/overview'); await flushPromises();
    const card = wrapper.get('.withdrawal-records');
    expect(card.get('h2').text()).toBe('发放记录');
    expect(card.get('.disbursement-pending-row').text()).toContain('2026年9月');
    expect(card.get('.disbursement-pending-row').text()).toContain('¥ 580.1234');
    expect(card.get('.disbursement-pending').text()).toBe('待发放');
    expect(card.text()).toContain('2026年8月'); expect(card.get('.disbursement-paid').text()).toBe('已发放');
    expect(card.text()).toContain('9007199254740993.12');
    expect(card.get('.record-total').text()).toContain('已发放总额');
    expect(card.get('.record-total').text()).toContain('9007199254741993.12');
    const form = wrapper.get('form[aria-label="记录日期筛选"]');
    await form.get('[name="from"]').setValue('2026-01-01'); await form.get('[name="to"]').setValue('2026-01-31'); await form.trigger('submit'); await flushPromises();
    expect(card.get('.disbursement-pending-row').text()).toContain('¥ 580.1234');
    await card.get('nav[aria-label="发放记录分页"] button:last-child').trigger('click'); await flushPromises();
    expect(card.text()).toContain('2024年12月');
    const requests = fetch.mock.calls.map(([url]) => new URL(url, 'http://test')).filter(url => url.pathname.endsWith('/paid-months'));
    expect(requests.at(-1)!.searchParams.get('page')).toBe('1');
    expect(requests.every(url => !url.searchParams.has('from') && !url.searchParams.has('to'))).toBe(true);
    expect(fetch.mock.calls.some(([url]) => new URL(url, 'http://test').pathname.endsWith('/withdrawals'))).toBe(false);
  } finally { vi.useRealTimers(); }
});

it.each([
  { available: '9007199254740993.1234', withdrawing: '100.0000', cashDebt: '0.0000', shown: '¥ 9007199254741093.1234' },
  { available: '0.0000', withdrawing: '100.0000', cashDebt: '120.0000', shown: '¥ 0' },
  { available: null, withdrawing: '100.0000', cashDebt: '0.0000', shown: '—' }
])('keeps carried pending amounts exact, nonnegative and unknown-safe: $shown', async data => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const path = new URL(url, 'http://test').pathname;
    return json(path.endsWith('/overview') ? { ...overview('owner-a'), commission: { ...overview('owner-a').commission, ...data } } : respond(path, 'owner-a'));
  }));
  const { wrapper } = await open('PROMOTER', '/promoter/overview'); await flushPromises();
  expect(wrapper.get('.disbursement-pending-row .record-amount').text()).toBe(data.shown);
});

it('shows authoritative historical yuan balances and keeps missing amounts unknown', async () => {
  let amounts: Record<string, string | null> = {balanceBeforeAmount4:'100.0000',balanceAfterAmount4:'87.6544'};
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const path=new URL(url,'http://test').pathname;
    const data=respond(path,'owner-a');
    return json(path.endsWith('/consumptions') ? {...data as object,items:[{...(data as {items:object[]}).items[0],...amounts}]} : data);
  }));
  const {wrapper,router}=await open('PROMOTER','/promoter/consumption');await flushPromises();
  expect(wrapper.findAll('[data-testid=balance-before], [data-testid=balance-after]').map(cell=>cell.text())).toEqual(['¥ 100','¥ 87.6544']);
  amounts={balanceBeforeAmount4:null,balanceAfterAmount4:null};
  await router.push('/promoter/users');await flushPromises();await router.push('/promoter/consumption');await flushPromises();
  expect(wrapper.findAll('[data-testid=balance-before], [data-testid=balance-after]').map(cell=>cell.text())).toEqual(['—','—']);
});

it('renders one invitation user list and keeps server pagination', async () => {
  const fetch = vi.fn(async (url: string) => {
    const request = new URL(url, 'http://test');
    return json(request.pathname.endsWith('/downlines')
      ? page([{ nickname: request.searchParams.get('page') === '1' ? '第二页邀请用户' : '直接邀请用户', level: 1 }], 21)
      : respond(request.pathname, 'owner-a'));
  });
  vi.stubGlobal('fetch', fetch);
  const active = await open('PROMOTER', '/promoter/users'); await flushPromises();
  expect(active.wrapper.findAll('table')).toHaveLength(1);
  expect(active.wrapper.get('h2').text()).toBe('邀请用户');
  expect(active.wrapper.text()).toContain('直接邀请用户');
  expect(active.wrapper.text()).not.toMatch(/一级|二级|按级别/);
  await active.wrapper.get('nav[aria-label="推广用户分页"] button:last-child').trigger('click'); await flushPromises();
  expect(active.wrapper.text()).toContain('第二页邀请用户');
  expect(new URL(fetch.mock.calls.at(-1)![0], 'http://test').searchParams.get('page')).toBe('1');
});

it('filters only the current invitation page without changing the server total', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const path = new URL(url, 'http://test').pathname;
    return json(path.endsWith('/downlines') ? page([
      { nickname: '小蓝', maskedUserId: '****1111', maskedPhone: '138****1234', accountStatus: 'ENABLED' },
      { nickname: '小白', maskedUserId: '****2222', maskedPhone: '139****5678', accountStatus: 'DISABLED' }
    ], 32) : respond(path, 'owner-a'));
  }));
  const {wrapper} = await open('PROMOTER', '/promoter/users'); await flushPromises();
  await wrapper.get('input[aria-label="搜索本页用户"]').setValue('1234');
  expect(wrapper.findAll('tbody tr')).toHaveLength(1); expect(wrapper.get('tbody').text()).toContain('小蓝');
  expect(wrapper.text()).toContain('32');
  await wrapper.get('input[aria-label="搜索本页用户"]').setValue('');
  await wrapper.get('select[aria-label="本页用户状态"]').setValue('DISABLED');
  expect(wrapper.get('tbody').text()).toContain('小白'); expect(wrapper.get('tbody').text()).not.toContain('小蓝');
});

it('keeps historical consumption amounts in expandable details and uses server date filtering', async () => {
  const fetch = vi.fn(async (url: string) => json(respond(new URL(url,'http://test').pathname,'owner-a'))); vi.stubGlobal('fetch',fetch);
  const {wrapper} = await open('PROMOTER','/promoter/consumption'); await flushPromises();
  expect(wrapper.get('tbody details').text()).toContain('1000');
  expect(wrapper.get('tbody details').text()).toContain('876.544');
  await wrapper.get('input[name="from"]').setValue('2026-09-01');
  await wrapper.get('input[name="to"]').setValue('2026-09-18');
  await wrapper.get('form[aria-label="消费日期筛选"]').trigger('submit'); await flushPromises();
  const last = new URL(fetch.mock.calls.at(-1)![0],'http://test');
  expect(last.searchParams.get('from')).toBe(new Date('2026-09-01T00:00:00').toISOString());
  expect(last.searchParams.get('page')).toBe('0');
});


it('retains consumption type and exact commission inside overview details', async () => {
 vi.stubGlobal('fetch',vi.fn(async (url:string)=>json(respond(new URL(url,'http://test').pathname,'owner-a'))));
 const {wrapper}=await open('PROMOTER','/promoter/overview'); await flushPromises();
 const detail=wrapper.get('.consumption-records details');
 expect(detail.text()).toContain('图片'); expect(detail.text()).toContain('¥ 1.2345');
});

it('shows complete Shanghai-day cards independent of table pagination and reference commission comparisons', async () => {
  vi.useFakeTimers({toFake:['Date']}); vi.setSystemTime(new Date('2026-09-18T01:00:00Z'));
  const fetch=vi.fn(async (url:string)=>{
    const req=new URL(url,'http://test');
    if(req.pathname.endsWith('/consumptions')&&req.searchParams.get('size')==='100') {
      const from=req.searchParams.get('from');
      const row=(id:string,paid:string,commission:string)=>({allocationId:id,maskedUserId:'****1234',paidAmount4:paid,commissionAmount4:commission});
      if(from==='2026-09-17T16:00:00.000Z')return json(req.searchParams.get('page')==='0'
        ? {...page([row('order1','2.8800','0.1280')],2),page:0}
        : {...page([row('order1','3.6000','0.1280')],2),page:1});
      return json(page([row('old','1.0000',from==='2026-09-16T16:00:00.000Z'?'0.1280':'0.2560')]));
    }
    return json(respond(req.pathname,'owner-a'));
  });vi.stubGlobal('fetch',fetch);
  try {
    const {wrapper,router}=await open('PROMOTER','/promoter/consumption');await flushPromises();
    const cards=()=>wrapper.findAll('.promoter-summary-grid article').map(card=>card.text());
    expect(cards()).toEqual(['今日消费¥ 6.48','今日订单1','今日佣金¥ 0.2560']);
    const dailyCalls=()=>fetch.mock.calls.filter(([url])=>new URL(url,'http://test').searchParams.get('size')==='100');
    const count=dailyCalls().length;
    await wrapper.get('form[aria-label="消费日期筛选"]').trigger('submit');await flushPromises();
    expect(dailyCalls()).toHaveLength(count);expect(cards()[0]).toBe('今日消费¥ 6.48');
    await router.push('/promoter/settlements');await flushPromises();
    expect(cards()[0]).toContain('累计佣金');expect(cards()[1]).toContain('今日佣金¥ 0.2560');expect(cards()[1]).toContain('+100.0%');
    expect(cards()[2]).toContain('昨日佣金¥ 0.1280');expect(cards()[2]).toContain('-50.0%');
    expect(wrapper.text()).not.toContain('待结算');expect(wrapper.text()).not.toContain('可提现');
    expect(dailyCalls().every(([url])=>new URL(url,'http://test').searchParams.has('to'))).toBe(true);
  } finally {vi.useRealTimers();}
});


it('keeps daily failures unknown, retries incomplete pages, and does not invent zero-base growth', async () => {
 let broken=true;
 vi.stubGlobal('fetch',vi.fn(async (url:string)=>{
  const req=new URL(url,'http://test');
  if(req.pathname.endsWith('/consumptions')&&req.searchParams.get('size')==='100') return json(broken?page([],2):page([]));
  return json(respond(req.pathname,'owner-a'));
 }));
 const {wrapper}=await open('PROMOTER','/promoter/settlements');await flushPromises();
 expect(wrapper.get('[role=alert]').text()).toContain('日统计');
 expect(wrapper.findAll('.promoter-stat strong').slice(1).map(x=>x.text())).toEqual(['—','—']);
 broken=false;await wrapper.get('[data-testid=portal-retry]').trigger('click');await flushPromises();
 expect(wrapper.find('[role=alert]').exists()).toBe(false);
 expect(wrapper.findAll('.promoter-stat strong').slice(1).map(x=>x.text())).toEqual(['¥ 0.0000','¥ 0.0000']);
 expect(wrapper.findAll('.daily-change').map(x=>x.text())).toEqual(['—','—']);
});

it('ignores delayed daily totals after the promoter identity changes', async () => {
 let resolveOld!:(value:Response)=>void; let current='owner-a';
 vi.stubGlobal('fetch',vi.fn(async (url:string)=>{
  const req=new URL(url,'http://test');
  if(req.pathname.endsWith('/consumptions')&&req.searchParams.get('size')==='100') {
   if(current==='owner-a')return new Promise<Response>(resolve=>{resolveOld=resolve;});
   return json(page([{allocationId:'new-order',paidAmount4:'2.0000',commissionAmount4:'0.2000'}]));
  }
  return json(respond(req.pathname,current));
 }));
 const {wrapper,identity}=await open('PROMOTER','/promoter/consumption');await flushPromises();
 current='owner-b';identity.value={role:'PROMOTER',username:current,userId:current};await flushPromises();
 expect(wrapper.findAll('.promoter-stat strong').map(x=>x.text())).toEqual(['¥ 2','1','¥ 0.2000']);
 resolveOld(json(page([{allocationId:'old',paidAmount4:'999.0000',commissionAmount4:'99.0000'}])));await flushPromises();
 expect(wrapper.findAll('.promoter-stat strong').map(x=>x.text())).toEqual(['¥ 2','1','¥ 0.2000']);
});


it('rolls daily cards forward at Shanghai midnight without table interaction', async () => {
 vi.useFakeTimers({toFake:['Date','setTimeout','clearTimeout']});
 vi.setSystemTime(new Date('2026-09-18T15:59:59.950Z'));
 vi.stubGlobal('fetch',vi.fn(async (url:string)=>{
  const req=new URL(url,'http://test');
  if(req.pathname.endsWith('/consumptions')&&req.searchParams.get('size')==='100') {
   return json(page([{allocationId:'order',paidAmount4:req.searchParams.get('from')==='2026-09-18T16:00:00.000Z'?'2.0000':'1.0000',commissionAmount4:'0.1000'}]));
  }
  return json(respond(req.pathname,'owner-a'));
 }));
 const {wrapper}=await open('PROMOTER','/promoter/consumption');
 try {
  await flushPromises();expect(wrapper.findAll('.promoter-stat strong')[0].text()).toBe('¥ 1');
  await vi.advanceTimersByTimeAsync(150);await flushPromises();
  expect(wrapper.findAll('.promoter-stat strong')[0].text()).toBe('¥ 2');
 } finally {wrapper.unmount();vi.useRealTimers();}
});

it('matches invitation cards using all registration dates at Shanghai day boundaries', async () => {
 vi.useFakeTimers({toFake:['Date','setTimeout','clearTimeout']});vi.setSystemTime(new Date('2026-09-18T15:59:59.950Z'));
 const row=(registeredAt:string,accountStatus='ENABLED')=>({maskedUserId:'****1234',nickname:'用户',registeredAt,accountStatus,boundAt:'2026-09-18T01:00:00Z'});
 const first=[row('2026-09-17T16:00:00Z'),row('2026-09-18T15:59:59Z','DISABLED'),row('2026-09-17T15:59:59Z')];
 const second=[row('2026-09-16T16:00:00Z'),row('2026-09-16T15:59:59Z'),row('2026-09-18T16:00:00Z'),...Array.from({length:15},()=>row('2026-01-01T00:00:00Z'))];
 const fetch=vi.fn(async(url:string)=>{
  const req=new URL(url,'http://test');
  if(req.pathname.endsWith('/downlines'))return json(req.searchParams.get('size')==='100'
   ?page(req.searchParams.get('page')==='0'?first:second,21):page(first,21));
  return json(respond(req.pathname,'owner-a'));
 });vi.stubGlobal('fetch',fetch);
 const {wrapper}=await open('PROMOTER','/promoter/users');
 try {
  await flushPromises();const cards=()=>wrapper.findAll('.promoter-summary-grid article').map(x=>x.text());
  expect(cards()).toEqual(['已邀请用户21','今天注册2','昨天注册2']);
  const calls=()=>fetch.mock.calls.filter(([url])=>new URL(url,'http://test').searchParams.get('size')==='100').length;
  expect(calls()).toBe(2);
  await wrapper.get('input[aria-label="搜索本页用户"]').setValue('不存在');
  await wrapper.get('select[aria-label="本页用户状态"]').setValue('DISABLED');
  await wrapper.get('nav[aria-label="推广用户分页"]').findAll('button')[1].trigger('click');await flushPromises();
  expect(cards()).toEqual(['已邀请用户21','今天注册2','昨天注册2']);expect(calls()).toBe(2);
  await vi.advanceTimersByTimeAsync(150);await flushPromises();
  expect(cards()).toEqual(['已邀请用户21','今天注册1','昨天注册2']);
 } finally {wrapper.unmount();vi.useRealTimers();}
});

it('keeps registration counts unknown on missing dates or incomplete pages and retries', async () => {
 let mode='missing';vi.stubGlobal('fetch',vi.fn(async(url:string)=>{
  const req=new URL(url,'http://test');
  if(req.pathname.endsWith('/downlines')&&req.searchParams.get('size')==='100')return json(mode==='missing'?page([{registeredAt:null}]):mode==='partial'?page([],1):page([]));
  return json(respond(req.pathname,'owner-a'));
 }));
 const {wrapper}=await open('PROMOTER','/promoter/users');await flushPromises();
 const counts=()=>wrapper.findAll('.promoter-summary-grid strong').slice(1).map(x=>x.text());
 expect(counts()).toEqual(['—','—']);expect(wrapper.get('[role=alert]').text()).toContain('日统计');
 mode='partial';await wrapper.get('[data-testid=portal-retry]').trigger('click');await flushPromises();expect(counts()).toEqual(['—','—']);
 mode='empty';await wrapper.get('[data-testid=portal-retry]').trigger('click');await flushPromises();expect(counts()).toEqual(['0','0']);
});

it('matches the account reference with three bank fields and real account status', async () => {
 let enabled=true;
 const fetch=vi.fn(async(url:string)=>{
  const path=new URL(url,'http://test').pathname;
  return json(path==='/api/v1/users/me'?{id:'owner-a',nickname:'真实用户',enabled}:respond(path,'owner-a'));
 });vi.stubGlobal('fetch',fetch);
 const {wrapper,router}=await open('PROMOTER','/promoter/account');await flushPromises();
 expect(wrapper.findAll('h2').map(x=>x.text())).toEqual(['收款账户','账户信息']);
 expect(wrapper.findAll('.payout-fields label').map(x=>x.text())).toEqual(['持卡人姓名','银行卡号','开户银行']);
 expect(wrapper.get('[data-testid=save-payout-method]').text()).toBe('保存');
 expect(wrapper.get('.promoter-account-info').text()).toContain('账户状态正常');
 expect(wrapper.get('.promoter-account-info').text()).toContain('真实用户');
 expect(wrapper.find('.promotion-link').exists()).toBe(false);expect(wrapper.find('.record-card').exists()).toBe(false);
 expect(wrapper.find('.payout-method-grid').exists()).toBe(false);
 expect(wrapper.get('[data-testid=payout-bank-card]').element).toHaveProperty('value','');
 expect(wrapper.get('[data-testid=payout-bank-card]').attributes('placeholder')).toBe('****5678');
 expect(fetch.mock.calls.some(([url])=>url.includes('/withdrawals'))).toBe(false);
 enabled=false;await router.push('/promoter/users');await flushPromises();await router.push('/promoter/account');await flushPromises();
 expect(wrapper.get('.promoter-account-info').text()).toContain('账户状态已停用');
});

it('edits the selected bank on later payout pages without overwriting another method or using masked drafts', async () => {
 const writes:{id:string;body:Record<string,unknown>}[]=[];
 vi.stubGlobal('fetch',vi.fn(async(url:string,init?:RequestInit)=>{
  const req=new URL(url,'http://test');
  if(init?.method==='PUT') {const body=JSON.parse(String(init.body));writes.push({id:req.pathname.split('/').at(-1)!,body});return json({...payout,id:'later-bank',sourceVersion:6});}
  if(req.pathname.endsWith('/payout-accounts'))return json(page(req.searchParams.get('page')==='1'
   ?[{...payout,id:'later-bank',sourceVersion:5}]:[{...payout,payoutType:'WECHAT',id:'wechat'},payout],21));
  return json(respond(req.pathname,'owner-a'));
 }));
 const {wrapper}=await open('PROMOTER','/promoter/account');await flushPromises();
 expect(wrapper.findAll('.payout-existing option').map(x=>x.attributes('value'))).not.toContain('wechat');
 await wrapper.get('[data-testid=payout-account-name]').setValue('未保存旧草稿');
 await wrapper.get('nav[aria-label="收款账户分页"] button:last-child').trigger('click');await flushPromises();
 expect(wrapper.get('[data-testid=payout-account-name]').element).toHaveProperty('value','');
 await wrapper.get('[data-testid=save-payout-method]').trigger('click');await flushPromises();expect(writes).toHaveLength(0);
 await wrapper.get('[data-testid=payout-account-name]').setValue('新持卡人');
 await wrapper.get('[data-testid=payout-bank-card]').setValue('6222000012345678');
 await wrapper.get('[data-testid=payout-bank-name]').setValue('自填银行支行');
 await wrapper.get('[data-testid=save-payout-method]').trigger('click');await flushPromises();
 expect(writes).toHaveLength(1);expect(writes[0]).toMatchObject({id:'later-bank',body:{payoutType:'BANK_CARD',expectedVersion:5,bankName:'自填银行支行'}});
});
