<script setup lang="ts">
import { computed, defineAsyncComponent, inject } from 'vue';
import { useRoute, RouterLink } from 'vue-router';
import { adminSessionKey, canAccessAdminPath } from './adminSession';
import './promotion/referral-management.css';
const PortalAccounts = defineAsyncComponent(() => import('./PortalAccounts.vue'));
const PromotionApplicationsPanel = defineAsyncComponent(() => import('./promotion/PromotionApplicationsPanel.vue'));
const PromotionAttributionsPanel = defineAsyncComponent(() => import('./promotion/PromotionAttributionsPanel.vue'));
const AgentIncomePanel = defineAsyncComponent(() => import('./promotion/AgentIncomePanel.vue'));
const PromotionBenefitSettingsPanel = defineAsyncComponent(() => import('./promotion/PromotionBenefitSettingsPanel.vue'));
const PromotionReconciliationPanel = defineAsyncComponent(() => import('./promotion/PromotionReconciliationPanel.vue'));
const PromotionBenefitAuditPanel = defineAsyncComponent(() => import('./promotion/PromotionBenefitAuditPanel.vue'));
const route = useRoute(), session = inject(adminSessionKey);
const pageName = computed(() => String(route.name));
const referralPage = computed(() => ['admin-promotion-applications','admin-promotion-attributions'].includes(pageName.value));
const incomePage = computed(() => ['admin-promotion-withdrawals','admin-promotion-withdrawal-report'].includes(pageName.value));
const titles: Record<string,string> = {
  'admin-admins':'管理员', 'admin-promotion-applications':'推荐关系管理', 'admin-promotion-attributions':'推荐关系管理',
  'admin-promotion-withdrawals':'代理收益', 'admin-promotion-withdrawal-report':'代理收益',
  'admin-promotion-benefit-settings':'推广规则', 'admin-promotion-reconciliation':'推广收益对账', 'admin-promotion-benefit-audit':'推广收益审计',
};
const title = computed(() => titles[pageName.value] ?? '管理后台');
const forbidden = computed(() => !canAccessAdminPath(route.path, session?.currentAdmin.value ?? null));
</script>
<template><section v-if="forbidden" class="blocking-state"><h1>无权限</h1><p>此功能需要主管理员权限。</p><RouterLink to="/admin/overview">返回运营概览</RouterLink></section><section v-else class="page-panel" :class="{'referral-management-page':referralPage,'income-route':incomePage}" aria-labelledby="admin-page-title"><header class="page-heading"><div><h1 id="admin-page-title">{{title}}</h1><p class="page-description">{{referralPage?'管理代理申请审核与推荐关系调整。':incomePage?'查看代理收益、提现与结算明细。':pageName==='admin-admins'?'管理登录账号、密码和账户状态。':'配置、核对和审计平台推广收益。'}}</p></div></header><template v-if="referralPage"><PromotionApplicationsPanel/><PromotionAttributionsPanel/></template><AgentIncomePanel v-if="incomePage"/><PromotionBenefitSettingsPanel v-if="pageName==='admin-promotion-benefit-settings'"/><PromotionReconciliationPanel v-if="pageName==='admin-promotion-reconciliation'"/><PromotionBenefitAuditPanel v-if="pageName==='admin-promotion-benefit-audit'"/><PortalAccounts v-if="pageName==='admin-admins'"/></section></template>
