import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';

const adminPage = () => import('./AdminPage.vue');
const users = () => import('./UserDirectory.vue');
const agents = () => import('./AgentManagement.vue');
const apiUsers = () => import('./ApiManagement.vue');
const payments = () => import('./PaymentOrders.vue');
const configuration = () => import('./ConfigurationPage.vue');

/** Route names and parameters remain compatible with the existing admin deep links. */
export const adminRoutes: RouteRecordRaw[] = [
  { name: 'admin-root', path: '/', redirect: '/admin/overview' },
  { path: '/admin', redirect: '/admin/overview' },
  { name: 'portal-entry', path: '/admin/portal-entry', component: () => import('./PortalEntry.vue') },
  { name: 'admin-login', path: '/admin/login', component: () => import('./pages/AdminLogin.vue') },
  { name: 'admin-overview', path: '/admin/overview', component: () => import('./pages/AdminOverview.vue') },
  { name: 'admin-users', path: '/admin/users', component: users },
  { name: 'admin-user-status', path: '/admin/users/:userRef/status', redirect: to => `/admin/users/${encodeURIComponent(String(to.params.userRef))}` },
  { name: 'admin-user-detail', path: '/admin/users/:userRef', component: users },
  { name: 'admin-agents', path: '/admin/agents', component: agents },
  { name: 'admin-agent-detail', path: '/admin/agents/:agentRef', component: agents },
  { name: 'admin-api-users', path: '/admin/api-users', component: apiUsers },
  { name: 'admin-api-user-detail', path: '/admin/api-users/:userRef', component: apiUsers },
  { name: 'admin-keys', path: '/admin/keys', redirect: to => {
    const key = to.query.keyId ?? to.query.keyRef;
    if (typeof key === 'string' && key) return `/admin/keys/${encodeURIComponent(key)}`;
    return typeof to.query.userRef === 'string' && to.query.userRef ? `/admin/users/${encodeURIComponent(to.query.userRef)}` : '/admin/users';
  } },
  { name: 'admin-key-detail', path: '/admin/keys/:keyRef', component: () => import('./UserManagement.vue') },
  { name: 'admin-payments', path: '/admin/payments', component: payments },
  { name: 'admin-corporate-transfers', path: '/admin/recharges/corporate', component: () => import('./CorporateTransfers.vue') },
  { name: 'admin-recharge-review', path: '/admin/recharges/review', component: payments, props: { review: true } },
  { name: 'admin-online-recharges', path: '/admin/recharges/online', component: () => import('./RechargeManagement.vue'), props: { onlineOnly: true } },
  { name: 'admin-consumption', path: '/admin/consumption', component: () => import('./ConsumptionManagement.vue') },
  { name: 'admin-consumption-detail', path: '/admin/consumption/:recordRef', component: () => import('./ConsumptionManagement.vue') },
  { name: 'admin-provider-logs', path: '/admin/provider-logs', component: () => import('./ProviderLogs.vue') },
  { name: 'admin-media', path: '/admin/media', redirect: '/admin/consumption' },
  { name: 'admin-media-detail', path: '/admin/media/:taskRef', redirect: to => `/admin/consumption/${encodeURIComponent(String(to.params.taskRef))}` },
  { name: 'admin-promotion-applications', path: '/admin/promotions/applications', component: adminPage },
  { name: 'admin-promotion-attributions', path: '/admin/promotions/attributions', component: adminPage },
  { name: 'admin-promotion-withdrawals', path: '/admin/promotions/withdrawals', component: adminPage },
  { name: 'admin-promotion-withdrawal-report', path: '/admin/promotions/withdrawal-report', component: adminPage },
  { name: 'admin-promotion-benefit-settings', path: '/admin/promotions/benefit-settings', component: adminPage },
  { name: 'admin-promotion-reconciliation', path: '/admin/promotions/reconciliation', component: adminPage },
  { name: 'admin-promotion-benefit-audit', path: '/admin/promotions/benefit-audit', component: adminPage },
  { name: 'admin-pricing', path: '/admin/pricing', component: configuration },
  { name: 'admin-providers', path: '/admin/providers', component: configuration },
  { name: 'admin-admins', path: '/admin/admins', component: adminPage },
  { name: 'admin-not-found', path: '/:pathMatch(.*)*', component: () => import('./pages/NotFound.vue') },
];
export const adminRouter = createRouter({ history: createWebHistory(import.meta.env.BASE_URL), routes: adminRoutes });
