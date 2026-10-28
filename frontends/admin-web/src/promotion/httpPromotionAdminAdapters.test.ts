import { mount, flushPromises } from '@vue/test-utils';
import { ref } from 'vue';
import { createRouter, createMemoryHistory } from 'vue-router';
import { afterEach, expect, it, vi } from 'vitest';
import App from '../App.vue';
import { adminRoutes } from '../router';

afterEach(() => vi.unstubAllGlobals());

it.each(['attributions', 'withdrawals', 'withdrawal-report', 'reconciliation', 'benefit-audit'])('never falls back to demo records when the real %s request fails', async page => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
  const router = createRouter({ history: createMemoryHistory(), routes: adminRoutes });
  await router.push(`/admin/promotions/${page}`); await router.isReady();
  const wrapper = mount(App, { props: { session: { authenticate: () => true, currentAdmin: ref({ role: 'ADMIN' as const, primary: true, username: 'primary', userId: 'real-admin' }) } }, global: { plugins: [router] } });
  await flushPromises();
  expect(wrapper.text()).not.toMatch(/WD-LOCAL|REC-LOCAL|AUD-LOCAL|Nova|星河企业|本地演示|本地 fixture|本地 typed adapter/);
  expect(wrapper.text()).toMatch(/失败|不可用/);
  wrapper.unmount();
});

import { createHttpPromotionAdminAdapters } from './httpPromotionAdminAdapters';
import { clearSession } from '../api';
const payoutRow = (overrides = {}) => ({ id: 'payout-real', promoterUserId: 'promoter-real', settlementMonth: '2026-08', amount4: '0.0100', status: 'WITHDRAWING', payoutAccountId: 'account-real', payoutType: 'BANK_CARD', maskedAccountName: '张***', maskedAccountReference: '***1234', rejectionReason: null, transferReference: null, paidAt: null, requestedAt: '2026-09-15T00:00:00Z', updatedAt: '2026-09-15T00:00:00Z', ...overrides });
const json = (value: unknown) => new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json' } });

it('uses generated Gateway requests and preserves the full monthly creation command after a lost response', async () => {
  clearSession(); const bodies: Record<string, unknown>[] = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith('/auth/session')) return json({ authenticated: true, csrfToken: 'session-csrf' });
    expect(url).toContain('/api/v1/admin/promotions/payouts'); expect(init.credentials).toBe('include');
    expect(new Headers(init.headers).get('X-CSRF-TOKEN')).toBe('session-csrf');
    bodies.push(JSON.parse(String(init.body)));
    if (bodies.length === 1) throw new Error('response lost');
    return json(payoutRow());
  }));
  const api = createHttpPromotionAdminAdapters();
  await expect(api.withdrawals.create!('promoter-real', '2026-08', '0.01', 'account-real')).rejects.toThrow('response lost');
  const result = await api.withdrawals.create!('promoter-real', '2026-08', '0.01', 'account-real');
  expect(bodies[0]).toEqual(bodies[1]); expect(bodies[0]).toMatchObject({ amount4: '0.01', settlementMonth: '2026-08', promoterUserId: 'promoter-real', payoutAccountId: 'account-real' });
  expect(result.payoutAccountSnapshot).toBe('银行卡 · 张*** · ***1234');
  await api.withdrawals.create!('promoter-real', '2026-08', '0.02', 'new-account', 'payout-real');
  expect(bodies[2].withdrawalId).toBe('payout-real'); expect(bodies[2].commandId).not.toBe(bodies[0].commandId);
});
it('retains proof and command identity across a completion retry', async () => {
  clearSession(); const bodies: unknown[] = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith('/auth/session')) return json({ authenticated: true, csrfToken: 'session-csrf' });
    bodies.push(JSON.parse(String(init.body))); if (bodies.length === 1) throw new Error('response lost');
    return json(payoutRow({ status: 'PAID' }));
  }));
  const api = createHttpPromotionAdminAdapters(), proof = { transferReference: 'BANK-1', paidAt: '2026-09-15T01:00:00Z' };
  await expect(api.withdrawals.markPaid('payout-real', 'operation', proof)).rejects.toThrow('response lost');
  await api.withdrawals.markPaid('payout-real', 'operation', proof);
  expect(bodies[0]).toEqual(bodies[1]); expect(bodies[1]).toMatchObject(proof);
});
it('takes report totals from the whole filtered server result and serializes page and month', async () => {
  const fetch = vi.fn().mockResolvedValue(json({ items: [payoutRow()], page: 1, size: 20, total: 45, totalAmount4: '999.1234', statusTotals: [{ status: 'WITHDRAWING', count: 45, amount4: '999.1234' }] }));
  vi.stubGlobal('fetch', fetch);
  const result = await createHttpPromotionAdminAdapters().reports.query({ status: 'ALL', promoterKeyword: 'promoter-real', startDate: '', endDate: '', month: '2026-08', page: 1 });
  expect(result.summary.total).toEqual({ amount4: '999.1234', count: 45 }); expect(result.entries).toHaveLength(1);
  const query = new URL(fetch.mock.calls[0][0], 'http://test').searchParams;
  expect(query.get('month')).toBe('2026-08'); expect(query.get('page')).toBe('1'); expect(query.get('promoterUserId')).toBe('promoter-real');
});
it('rejects in-flight data after the account revision changes', async () => {
  let finish!: (value: Response) => void;
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => { finish = resolve; })));
  const api = createHttpPromotionAdminAdapters(); const response = api.withdrawals.query!({ status: '', promoterUserId: '', month: '', page: 0 });
  api.reset(); finish(json({ items: [payoutRow()], page: 0, size: 20, total: 1, totalAmount4: '0.0100', statusTotals: [] }));
  await expect(response).rejects.toThrow('Session changed');
});

it('shows promoter phone and nickname, searches all pages, and keeps the user ID as the selected value', async () => {
  const fetch = vi.fn(async (url: string) => {
    const path = new URL(url, 'http://test');
    if (path.pathname === '/api/v1/admin/promotions/promoters') {
      const page = Number(path.searchParams.get('page'));
      expect(path.searchParams.get('userId')).toBeNull();
      return json({ items: page === 0 ? [{ userId: 'first', status: 'ENABLED' }, { userId: 'disabled-promoter', status: 'DISABLED' }] : [{ userId: 'target', status: 'ENABLED' }, { userId: 'disabled-user', status: 'ENABLED' }], total: 4, page, size: 20 });
    }
    if (path.pathname === '/api/v1/admin/users/first') return json({ id: 'first', phone: '+8613800000001', nickname: '小王', enabled: true });
    if (path.pathname === '/api/v1/admin/users/target') return json({ id: 'target', phone: '+8618811627177', nickname: '星河', enabled: true });
    if (path.pathname === '/api/v1/admin/users/disabled-user') return json({ id: 'disabled-user', phone: '+8613800000002', nickname: '停用用户', enabled: false });
    throw new Error('Unexpected request: ' + path.pathname);
  });
  vi.stubGlobal('fetch', fetch);
  const api = createHttpPromotionAdminAdapters();
  expect(await api.withdrawals.promoters!('', 0)).toEqual({ total: 2, items: [
    { value: 'first', label: '+8613800000001 · 小王' }, { value: 'target', label: '+8618811627177 · 星河' }
  ] });
  for (const query of ['18811627177', '星河']) expect(await api.withdrawals.promoters!(query, 0)).toEqual({ total: 1, items: [{ value: 'target', label: '+8618811627177 · 星河' }] });
});

it('loads the full adjustment page through the SDK and resolves display names without changing IDs',async()=>{
 const fetch=vi.fn(async(url:string)=>{const path=new URL(url,'http://test');if(path.pathname==='/api/v1/admin/promotions/operations/referral-adjustments'){expect(path.searchParams.get('page')).toBe('2');expect(path.searchParams.get('size')).toBe('10');return json({total:31,items:[{commandId:'change',userId:'client',previousInviterId:null,inviterId:'agent',operatorId:'operator',reason:'业务调整',occurredAt:'2026-09-20T00:00:00Z'}]});}if(path.pathname==='/api/v1/admin/users/operator')throw new Error('unavailable');return json({nickname:path.pathname.endsWith('/client')?'客户甲':'星辰工作室'});});
 vi.stubGlobal('fetch',fetch);const page=await createHttpPromotionAdminAdapters().attribution.records(2,10);expect(page.total).toBe(31);expect(page.items[0]).toMatchObject({id:'change',userId:'client',user:'客户甲',previousInviter:'无',inviter:'星辰工作室',operator:'operator'});
});
