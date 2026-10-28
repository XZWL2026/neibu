// Session double for historical local-data page tests; configuration integration uses the real SDK separately.
import { ref } from 'vue';
import { vi } from 'vitest';
import type { AdminIdentity, AdminSession } from './adminSession';
import { loginIdentities } from './fixtures';
const current = vi.hoisted(() => ({ identity: null as AdminIdentity | null }));
vi.mock('./api', async (original) => {
  const actual = await original<typeof import('./api')>();
  return { ...actual, billingDailyFinanceGetApiV1AdminBillingDailyFinance: vi.fn(async (range) => ({ data: {...range,items:[],totalDays:0,totalRechargeAmount4:'0.0000',totalConsumptionAmount4:'0.0000'} })), mediaNativeVideoUsageGetApiV1AdminMediaNativeVideoUsage: vi.fn(async (range) => ({ data: {...range,items:[],total:{tasks:0,seconds:0}} })), logout: vi.fn(async () => ({})), refreshSession: vi.fn(async () => ({
    authenticated: current.identity !== null, primary: current.identity?.primary ?? false,
    roles: current.identity ? [current.identity.role.toLowerCase()] : [],
    user: current.identity ? { id: 'fixture', username: current.identity.username } : null, csrfToken: 'fixture-csrf'
  })), billingGetGetApiV1AdminBillingMediaPrices: vi.fn(async () => ({ data: { managed: true, version: 1, prices: [] }, status: 200, headers: new Headers() })) };
});
export function fixtureSession(): AdminSession {
  current.identity = null;
  const currentAdmin = ref<AdminIdentity | null>(null);
  return { currentAdmin, authenticate(username) {
    const found = loginIdentities.find(item => item.username === (username || 'primary.admin'));
    currentAdmin.value = found ? { username: found.username, role: found.role, primary: found.role === 'ADMIN' } : null;
    current.identity = currentAdmin.value;
    return !!found;
  } };
}
