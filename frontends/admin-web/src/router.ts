import {agentSessionBase} from './portalSession';
import PortalEntry from "./PortalEntry.vue";
import ApiLogin from "./ApiLogin.vue";
import ConsumptionManagement from "./ConsumptionManagement.vue";
import ProviderLogs from "./ProviderLogs.vue";
import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import UserManagement from "./UserManagement.vue";
import CorporateTransfers from "./CorporateTransfers.vue";
import ApiManagement from "./ApiManagement.vue";
import RechargeManagement from "./RechargeManagement.vue";
import PaymentOrders from "./PaymentOrders.vue";
import ConfigurationPage from "./ConfigurationPage.vue";
import AdminPage from "./AdminPage.vue";
import AgentManagement from "./AgentManagement.vue";
import ApiPage from "./ApiPage.vue";
import PromoterPage from "./PromoterPage.vue";

const apiRoot = "/" + "api";

import UserDirectory from "./UserDirectory.vue";

export const adminRoutes: RouteRecordRaw[] = [
  { name: "api-root", path: "/api", redirect: "/api/dashboard" },
  { name: "api-login", path: "/api/login", component: ApiLogin },
  { name: "api-portal-entry", path: "/api/portal-entry", component: PortalEntry },
  { name: "admin-root", path: "/", redirect: "/admin/login" },
  { name: "portal-entry", path: "/admin/portal-entry", component: PortalEntry },
  { name: "admin-login", path: "/admin/login", component: AdminPage },
  { name: "admin-overview", path: "/admin/overview", component: AdminPage },
  { name: "admin-users", path: "/admin/users", component: UserDirectory },
  { name: "admin-user-status", path: "/admin/users/:userRef/status", redirect: to => `/admin/users/${encodeURIComponent(String(to.params.userRef))}` },
  { name: "admin-user-detail", path: "/admin/users/:userRef", component: UserDirectory },
  { name: "admin-agents", path: "/admin/agents", component: AgentManagement },
  { name: "admin-agent-detail", path: "/admin/agents/:agentRef", component: AgentManagement },
  { name: "admin-api-users", path: "/admin/api-users", component: ApiManagement },
  { name: "admin-api-user-detail", path: "/admin/api-users/:userRef", component: ApiManagement },
  { name: "admin-keys", path: "/admin/keys", redirect: to => {
    const keyRef = to.query.keyId ?? to.query.keyRef;
    if (typeof keyRef === "string" && keyRef) return `/admin/keys/${encodeURIComponent(keyRef)}`;
    return typeof to.query.userRef === "string" && to.query.userRef ? `/admin/users/${encodeURIComponent(to.query.userRef)}` : "/admin/users";
  } },
  { name: "admin-key-detail", path: "/admin/keys/:keyRef", component: UserManagement },
  { name: "admin-payments", path: "/admin/payments", component: PaymentOrders },
  { name: "admin-corporate-transfers", path: "/admin/recharges/corporate", component: CorporateTransfers },
  { name: "admin-recharge-review", path: "/admin/recharges/review", component: PaymentOrders, props: {review:true} },
  { name: "admin-online-recharges", path: "/admin/recharges/online", component: RechargeManagement, props: {onlineOnly:true} },
  { name: "admin-consumption", path: "/admin/consumption", component: ConsumptionManagement },
  { name: "admin-consumption-detail", path: "/admin/consumption/:recordRef", component: ConsumptionManagement },
  { name: "admin-provider-logs", path: "/admin/provider-logs", component: ProviderLogs },
  { name: "admin-media", path: "/admin/media", redirect: "/admin/consumption" },
  { name: "admin-media-detail", path: "/admin/media/:taskRef", redirect: (to) => `/admin/consumption/${to.params.taskRef}` },
  { name: "admin-promotion-applications", path: "/admin/promotions/applications", component: AdminPage },
  { name: "admin-promotion-attributions", path: "/admin/promotions/attributions", component: AdminPage },
  { name: "admin-promotion-withdrawals", path: "/admin/promotions/withdrawals", component: AdminPage },
  { name: "admin-promotion-withdrawal-report", path: "/admin/promotions/withdrawal-report", component: AdminPage },
  { name: "admin-promotion-benefit-settings", path: "/admin/promotions/benefit-settings", component: AdminPage },
  { name: "admin-promotion-reconciliation", path: "/admin/promotions/reconciliation", component: AdminPage },
  { name: "admin-promotion-benefit-audit", path: "/admin/promotions/benefit-audit", component: AdminPage },
  { name: "admin-pricing", path: "/admin/pricing", component: ConfigurationPage },
  { name: "admin-providers", path: "/admin/providers", component: ConfigurationPage },
  { name: "admin-admins", path: "/admin/admins", component: AdminPage },
  { name: "promoter-overview", path: "/promoter/overview", component: PromoterPage },
  { name: "promoter-users", path: "/promoter/users", component: PromoterPage },
  { name: "promoter-consumption", path: "/promoter/consumption", component: PromoterPage },
  { name: "promoter-settlements", path: "/promoter/settlements", component: PromoterPage },
  { name: "promoter-account", path: "/promoter/account", component: PromoterPage },
  { name: "api-dashboard", path: `${apiRoot}/dashboard`, component: ApiPage },
  { name: "api-keys", path: `${apiRoot}/keys`, component: ApiPage },
  { name: "api-consumption", path: `${apiRoot}/consumption`, component: ApiPage },
  { name: "api-billing", path: `${apiRoot}/billing`, component: ApiPage },
  { name: "api-transfer", path: `${apiRoot}/transfer`, component: ApiPage },
  { name: "api-docs", path: `${apiRoot}/docs`, component: ApiPage },
  { name: "api-account", path: `${apiRoot}/account`, component: ApiPage },
  { name: "admin-not-found", path: "/:pathMatch(.*)*", component: AdminPage }
];

export const adminRouter = createRouter({ history: createWebHistory(agentSessionBase() || import.meta.env.BASE_URL), routes: adminRoutes });
