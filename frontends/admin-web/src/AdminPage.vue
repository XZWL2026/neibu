<script setup lang="ts">
import { formatAmount } from '../../shared/formatAmount';

import { computed, inject, ref } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import { adminManualRecharges, adminOnlineRecharges, adminPayments } from "./fixtures";
import PortalAccounts from "./PortalAccounts.vue";
import adminBrand from "./assets/admin-brand.png";
import NativeVideoUsage from "./NativeVideoUsage.vue";
import OverviewFinanceSummary from "./OverviewFinanceSummary.vue";
import PromoterIcon from "./PromoterIcon.vue";
import { adminSessionKey, homePathForRole, canAccessAdminPath, primaryOnlyRoutes } from "./adminSession";
import "./promotion/referral-management.css";
import PromotionApplicationsPanel from "./promotion/PromotionApplicationsPanel.vue";
import PromotionAttributionsPanel from "./promotion/PromotionAttributionsPanel.vue";
import AgentIncomePanel from "./promotion/AgentIncomePanel.vue";
import PromotionBenefitSettingsPanel from "./promotion/PromotionBenefitSettingsPanel.vue";
import PromotionReconciliationPanel from "./promotion/PromotionReconciliationPanel.vue";
import PromotionBenefitAuditPanel from "./promotion/PromotionBenefitAuditPanel.vue";

const route = useRoute();
const router = useRouter();
const injectedSession = inject(adminSessionKey);
if (!injectedSession) throw new Error("AdminPage must be rendered inside the admin session provider");
const adminSession = injectedSession;
const loginUsername = ref("");
const loginPassword = ref("");
const passwordVisible = ref(false);
const loggingIn = ref(false);
const financeRevision = ref(0);
const overviewDays = ref(7);
const paymentSearch = ref("");
const paymentStatus = ref("全部状态");
const notice = ref("");
const titles = {
  "admin-login": "后台登录", "admin-overview": "运营概览",
  "admin-agents": "代理管理", "admin-agent-detail": "代理详情", "admin-payments": "支付订单",
  "admin-recharge-review": "充值审核", "admin-online-recharges": "线上充值", "admin-media": "消费明细", "admin-consumption": "消费明细", "admin-consumption-detail": "视频任务详情", "admin-media-detail": "视频任务详情", "admin-pricing": "模型定价", "admin-admins": "管理员",
  "admin-promotion-applications": "推荐关系管理", "admin-promotion-attributions": "推荐关系管理",
  "admin-promotion-withdrawals": "代理收益",
  "admin-promotion-withdrawal-report": "代理收益",
  "admin-promotion-benefit-settings": "推广规则",
  "admin-promotion-reconciliation": "推广收益对账",
  "admin-promotion-benefit-audit": "推广收益审计",
  "admin-not-found": "页面不存在"
};
const pageName = computed(() => String(route.name ?? "admin-not-found"));
const referralPage=computed(()=>["admin-promotion-applications","admin-promotion-attributions"].includes(pageName.value));
const incomePage=computed(()=>["admin-promotion-withdrawals","admin-promotion-withdrawal-report"].includes(pageName.value));
const title = computed(() => titles[pageName.value as keyof typeof titles] ?? "页面不存在");
const role = computed(() => adminSession.currentAdmin.value?.role);
const revealSensitive = computed(() => role.value === "ADMIN");
const forbidden = computed(() => adminSession.currentAdmin.value
  ? !canAccessAdminPath(route.path, adminSession.currentAdmin.value)
  : primaryOnlyRoutes.includes(route.path));
const visiblePayments = computed(() => {
  const needle = paymentSearch.value.trim().toLowerCase();
  return adminPayments.filter((item) => (!needle || [item.id, item.user].some((value) => value.toLowerCase().includes(needle))) && (paymentStatus.value === "全部状态" || item.status === paymentStatus.value));
});
function localAction(message: string) { notice.value = message; }
async function login() {
  if (loggingIn.value) return;
  loggingIn.value = true;
  const succeeded = await adminSession.authenticate(loginUsername.value, loginPassword.value);
  loggingIn.value = false;
  if (!succeeded) {
    loginPassword.value = "";
    localAction("登录失败，请检查账户、密码或服务状态");
    return;
  }
  loginPassword.value = "";
  localAction("登录成功");
  router.replace(adminSession.destination
    ? adminSession.destination(route.query.redirect)
    : homePathForRole[adminSession.currentAdmin.value!.role]);
}
</script>

<template>
  <section v-if="pageName === 'admin-login'" class="admin-login-page" aria-labelledby="admin-page-title">
    <div class="admin-login-scene" aria-hidden="true"><div class="login-planet login-planet-left"></div><div class="login-orbit"></div><div class="login-planet login-planet-right"></div><div class="login-moon login-moon-left"></div><div class="login-moon login-moon-right"></div><div class="login-horizon"></div></div>
    <div class="login-corner login-corner-top" aria-hidden="true">IDEAS<br>INTO<br>A BRIGHTER<br>TOMORROW</div>
    <div class="login-corner login-corner-right" aria-hidden="true">管理<br>创作<br>增长</div>
    <div class="login-corner login-corner-bottom" aria-hidden="true">YOMIO<br>AI CREATIVE PLATFORM</div>
    <div class="login-corner login-corner-end" aria-hidden="true">AI<br>CREATIVITY<br>FOR A BETTER<br>WORLD</div>
    <div class="admin-login-stage">
    <form class="admin-login-card" @submit.prevent="login">
      <header class="admin-login-brand">
        <img class="admin-login-logo" :src="adminBrand" alt="YOMIO">
        <h1 id="admin-page-title">{{ title }}</h1>
        <p>欢迎进入 YOMIO 管理后台</p>
      </header>
      <p v-if="route.query.logout === 'failed'" class="admin-login-feedback" role="alert">退出请求未完成，当前页面已锁定，请重试退出或重新登录。</p>
      <label class="admin-login-field"><span class="sr-only">用户名</span><svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="7" r="3.5"/><path d="M4.5 21v-3a7.5 7.5 0 0 1 15 0v3"/></svg><input v-model="loginUsername" autocomplete="username" placeholder="账号" required :disabled="loggingIn"></label>
      <label class="admin-login-field"><span class="sr-only">密码</span><svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 4v3"/></svg><input v-model="loginPassword" :type="passwordVisible ? 'text' : 'password'" autocomplete="current-password" placeholder="密码" required :disabled="loggingIn"><button class="admin-login-reveal" type="button" :aria-label="passwordVisible ? '隐藏密码' : '显示密码'" :aria-pressed="passwordVisible" @click="passwordVisible = !passwordVisible"><svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/><path v-if="!passwordVisible" d="m3 3 18 18"/></svg></button></label>
      <button class="admin-login-submit" :disabled="loggingIn" type="submit" aria-label="登录管理后台"><span>{{ loggingIn ? "正在登录…" : "登录" }}</span><span v-if="!loggingIn" aria-hidden="true">→</span></button>
      <p v-if="notice" class="admin-login-feedback" role="status">{{ notice }}</p>
      <footer class="admin-login-footer"><span>用 AI 创造更大的可能</span></footer>
    </form>
    <div class="admin-login-pedestal" aria-hidden="true"></div>
    </div>
  </section>

  <section v-else-if="forbidden" class="page-panel" aria-labelledby="admin-page-title">
    <header class="page-heading"><div><p class="page-eyebrow">PERMISSION REQUIRED</p><h1 id="admin-page-title">无权限</h1><p class="page-description">当前 NORMAL_ADMIN 不能访问主管理员专属页面。</p></div></header>
    <div class="empty-state"><strong>需要主管理员权限</strong><p>模型定价、管理员管理和推广管理仅对 primary ADMIN 开放。</p><RouterLink to="/admin/overview">返回运营概览</RouterLink></div>
  </section>

  <section v-else class="page-panel" :class="{'referral-management-page':referralPage,'income-route':incomePage}" aria-labelledby="admin-page-title">
    <header class="page-heading">
      <div><p v-if="referralPage" class="page-eyebrow">⌂　运营管理　›　推荐关系管理</p><h1 id="admin-page-title">{{ title }}</h1><p v-if="pageName === 'admin-overview'" class="page-description">实时掌握平台核心运营数据</p><p v-else-if="pageName === 'admin-admins'" class="page-description">管理真实登录账号、密码和状态。</p><p v-else-if="referralPage" class="page-description">管理代理申请审核与推荐关系调整，保障推荐体系的健康运行。</p><p v-else-if="pageName !== 'admin-overview' && pageName !== 'admin-promotion-applications' && !pageName.startsWith('admin-promotion-')" class="page-description">本地 fixture 运营视图，所有操作均不会发起网络请求。{{ revealSensitive ? "当前为主管理员，敏感信息明文展示。" : "当前角色仅显示脱敏信息。" }}</p></div>
      <label v-if="pageName === 'admin-overview'" class="overview-period"><PromoterIcon name="calendar"/><select v-model.number="overviewDays" aria-label="运营概览时间范围" @change="financeRevision++"><option :value="7">近7天</option><option :value="30">近30天</option><option :value="1">今天</option><option :value="0">全部时间</option><option v-if="overviewDays===-1" :value="-1" disabled>自定义</option></select></label>
    </header>

    <template v-if="pageName === 'admin-overview'">
      <OverviewFinanceSummary :revision="financeRevision" />
      <NativeVideoUsage :days="overviewDays" @refresh="financeRevision++" @custom="overviewDays=-1" />
    </template>

    <template v-if="pageName === 'admin-payments'">
      <div class="table-toolbar"><input v-model="paymentSearch" aria-label="搜索支付订单" placeholder="订单号或用户"><select v-model="paymentStatus" aria-label="筛选支付状态"><option>全部状态</option><option>已支付</option><option>待支付</option><option>退款处理中</option></select></div>
      <div class="table-wrap"><table><caption class="sr-only">支付订单列表</caption><thead><tr><th>订单号</th><th>用户</th><th>金额</th><th>充值类型</th><th>状态</th><th>确认通道</th><th>创建时间</th></tr></thead><tbody><tr v-for="item in visiblePayments" :key="item.id"><td>{{ item.id }}</td><td>{{ item.user }}</td><td>{{ formatAmount(item.amount) }}</td><td>{{ item.kind }}</td><td><span class="status">{{ item.status }}</span></td><td>{{ item.channel }}</td><td>{{ item.created }}</td></tr></tbody></table></div>
    </template>

    <template v-if="pageName === 'admin-recharge-review'"><div class="table-wrap"><table><caption class="sr-only">手动充值审核</caption><thead><tr><th>申请单</th><th>用户</th><th>金额</th><th>类型</th><th>状态</th><th>提交时间</th><th>审核人</th><th>备注</th></tr></thead><tbody><tr v-for="item in adminManualRecharges" :key="item.id"><td>{{ item.id }}</td><td>{{ item.user }}</td><td>{{ formatAmount(item.amount) }}</td><td>{{ item.type }}</td><td><span class="status">{{ item.status }}</span></td><td>{{ item.submitted }}</td><td>{{ item.reviewer }}</td><td>{{ item.remark || "—" }}</td></tr></tbody></table></div></template>

    <template v-if="pageName === 'admin-online-recharges'"><div class="table-wrap"><table><caption class="sr-only">线上充值订单</caption><thead><tr><th>订单号</th><th>用户</th><th>金额</th><th>支付方式</th><th>订单状态</th><th>支付时间</th><th>退款状态</th></tr></thead><tbody><tr v-for="item in adminOnlineRecharges" :key="item.id"><td>{{ item.id }}</td><td>{{ item.user }}</td><td>{{ formatAmount(item.amount) }}</td><td>{{ item.channel }}</td><td><span class="status">{{ item.status }}</span></td><td>{{ item.paidAt }}</td><td>{{ item.refund }}</td></tr></tbody></table></div></template>



    <PromotionApplicationsPanel v-if="referralPage" />
    <PromotionAttributionsPanel v-if="referralPage" />
    <AgentIncomePanel v-if="incomePage" />
    <PromotionBenefitSettingsPanel v-if="pageName === 'admin-promotion-benefit-settings'" />
    <PromotionReconciliationPanel v-if="pageName === 'admin-promotion-reconciliation'" />
    <PromotionBenefitAuditPanel v-if="pageName === 'admin-promotion-benefit-audit'" />

    <PortalAccounts v-if="pageName === 'admin-admins'" />

    <template v-if="pageName === 'admin-not-found'">
      <div class="empty-state"><strong>404</strong><p>这个本地管理页面不存在。</p><RouterLink to="/admin/overview">返回运营概览</RouterLink></div>
    </template>
    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
  </section>
</template>
