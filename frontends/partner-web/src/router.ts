import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import NotFoundPage from "./pages/NotFoundPage.vue";
import CommissionEntriesPage from "./pages/CommissionEntriesPage.vue";
import PayoutAccountsPage from "./pages/PayoutAccountsPage.vue";
import PromotionOverviewPage from "./pages/PromotionOverviewPage.vue";
import PromotionReferralsPage from "./pages/PromotionReferralsPage.vue";
import TransactionsPage from "./pages/TransactionsPage.vue";
import WithdrawalsPage from "./pages/WithdrawalsPage.vue";

export const partnerRoutes: RouteRecordRaw[] = [
  { name: "partner-root", path: "/", redirect: "/promotion/overview" },
  { name: "promotion-overview", path: "/promotion/overview", component: PromotionOverviewPage },
  { name: "promotion-referrals", path: "/promotion/referrals", component: PromotionReferralsPage },
  { name: "promotion-commission-entries", path: "/promotion/commission-entries", component: CommissionEntriesPage },
  { name: "promotion-transactions", path: "/promotion/transactions", component: TransactionsPage },
  { name: "promotion-payout-accounts", path: "/promotion/payout-accounts", component: PayoutAccountsPage },
  { name: "promotion-withdrawals", path: "/promotion/withdrawals", component: WithdrawalsPage },
  { name: "partner-not-found", path: "/:pathMatch(.*)*", component: NotFoundPage }
];

export const partnerRouter = createRouter({ history: createWebHistory(import.meta.env.BASE_URL), routes: partnerRoutes });
