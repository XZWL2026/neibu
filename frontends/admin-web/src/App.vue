<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, provide, ref, watch } from "vue";
import { RouterLink, RouterView, useRoute, useRouter } from "vue-router";
import "./stage-zero-contract-marker";
import { adminNavigation, apiNavigation, promoterNavigation } from "./navigation";
import { adminSessionKey, homePathForRole, primaryOnlyRoutes, canAccessAdminPath, type AdminSession } from "./adminSession";
import PasswordChange from "./PasswordChange.vue";
import PromoterIcon from "./PromoterIcon.vue";
import "./promoter.css";
import "./overview.css";
import "./operations-polish.css";
import { createPageMotion } from './pageMotion';
import "./api-console.css";
import adminBrand from "./assets/admin-brand.png";
import { logout as logoutRemote } from "./api";
import { createAdminAuth } from "./adminAuth";
import { adminWithdrawalAdapterKey, type AdminWithdrawalAdapter } from "./promotion/localWithdrawalFixtures";
import { adminWithdrawalReportAdapterKey, type AdminWithdrawalReportAdapter } from "./promotion/localWithdrawalReportFixtures";
import { adminBenefitSettingsAdapterKey, createLocalAdminBenefitSettingsAdapter, type AdminBenefitSettingsAdapter } from "./promotion/localBenefitSettingsFixtures";
import { adminReconciliationAdapterKey, type AdminReconciliationAdapter } from "./promotion/localReconciliationFixtures";
import { adminBenefitAuditAdapterKey, type AdminBenefitAuditAdapter } from "./promotion/localBenefitAuditFixtures";

import { adminAttributionAdapterKey, createHttpPromotionAdminAdapters, type AdminAttributionAdapter } from "./promotion/httpPromotionAdminAdapters";
import { createHttpBenefitSettingsAdapter } from "./promotion/httpBenefitSettingsAdapter";

const props = defineProps<{ session?: AdminSession; adminAttributionAdapter?: AdminAttributionAdapter; adminWithdrawalAdapter?: AdminWithdrawalAdapter; adminWithdrawalReportAdapter?: AdminWithdrawalReportAdapter; adminBenefitSettingsAdapter?: AdminBenefitSettingsAdapter; adminReconciliationAdapter?: AdminReconciliationAdapter; adminBenefitAuditAdapter?: AdminBenefitAuditAdapter }>();

const route = useRoute();
const router = useRouter();
const mainElement = ref<HTMLElement | null>(null);
const pageContent = ref<HTMLElement | null>(null);
const pageMotion = createPageMotion();
onUnmounted(pageMotion.cancel);
const menuOpen = ref(false);
const accountTools = ref<HTMLElement | null>(null);
const accountMenuButton = ref<HTMLButtonElement | null>(null);
function dismissAccountMenu(event: PointerEvent) {
  if (menuOpen.value && event.target instanceof Node && !accountTools.value?.contains(event.target)) menuOpen.value = false;
}
function leaveAccountTools(event: FocusEvent) {
  if (event.relatedTarget instanceof Node && !accountTools.value?.contains(event.relatedTarget)) menuOpen.value = false;
}
function escapeAccountMenu() {
  if (!menuOpen.value) return;
  menuOpen.value = false;
  accountMenuButton.value?.focus();
}
onMounted(() => document.addEventListener('pointerdown', dismissAccountMenu));
onUnmounted(() => document.removeEventListener('pointerdown', dismissAccountMenu));
const passwordOpen = ref(false);
async function passwordChanged() {
  passwordOpen.value = false;
  await session.verify?.();
  await router.replace("/admin/login");
}
const session = props.session ?? createAdminAuth(router);
const currentAdmin = session.currentAdmin;
const phase = session.phase;
const isLogin = computed(() => ['admin-login', 'portal-entry', 'api-login', 'api-portal-entry'].includes(String(route.name)));
const forbidden = computed(() => !!phase && !canAccessAdminPath(route.path, currentAdmin.value));
onMounted(() => { void session.verify?.(); });
onUnmounted(() => { session.dispose?.(); liveBenefitSettings.reset(); livePromotion.reset(); });
const visibleNavigation = computed(() => {
  if (phase && (phase.value !== "ready" || !currentAdmin.value)) return [];
  if (currentAdmin.value?.role === "PROMOTER") return promoterNavigation;
  if (currentAdmin.value?.role === "API_USER") return apiNavigation;
  return currentAdmin.value?.role === "ADMIN" && currentAdmin.value.primary ? adminNavigation : adminNavigation.filter((item) => !primaryOnlyRoutes.includes(item.to));
});
provide(adminSessionKey, session);
const livePromotion = createHttpPromotionAdminAdapters();
provide(adminAttributionAdapterKey, props.adminAttributionAdapter ?? livePromotion.attribution);
provide(adminWithdrawalAdapterKey, props.adminWithdrawalAdapter ?? livePromotion.withdrawals);
provide(adminWithdrawalReportAdapterKey, props.adminWithdrawalReportAdapter ?? livePromotion.reports);
const liveBenefitSettings = createHttpBenefitSettingsAdapter();
provide(adminBenefitSettingsAdapterKey, props.adminBenefitSettingsAdapter ?? liveBenefitSettings);
watch(() => `${currentAdmin.value?.role}:${currentAdmin.value?.userId}:${currentAdmin.value?.username}`, () => { liveBenefitSettings.reset(); livePromotion.reset(); }, { flush: "sync" });
provide(adminReconciliationAdapterKey, props.adminReconciliationAdapter ?? livePromotion.reconciliation);
provide(adminBenefitAuditAdapterKey, props.adminBenefitAuditAdapter ?? livePromotion.audit);
async function logout() {
  if (session.logout) { menuOpen.value = false; await session.logout(); return; }
  try { await logoutRemote(); } catch { return; }
  currentAdmin.value = null;
  passwordOpen.value = false;
  menuOpen.value = false;
  router.push("/admin/login");
}
const overviewTheme=computed(()=>['admin-provider-logs','admin-admins','admin-providers','admin-pricing','admin-overview','admin-users','admin-user-detail','admin-agents','admin-agent-detail','admin-api-users','admin-api-user-detail','admin-consumption','admin-consumption-detail','admin-payments','admin-recharge-review','admin-promotion-applications','admin-promotion-attributions','admin-promotion-withdrawals','admin-promotion-withdrawal-report','admin-promotion-benefit-settings'].includes(String(route.name))&&['ADMIN','NORMAL_ADMIN'].includes(currentAdmin.value?.role||''));
const overviewIcons=['home','users','user','globe','database','document','card','document','users','money','settings','document','settings','user'];
const apiDocsTheme = computed(() => currentAdmin.value?.role === 'API_USER' && route.name === 'api-docs');
const apiConsoleTheme = computed(() => currentAdmin.value?.role === 'API_USER' && !apiDocsTheme.value);
const referenceTheme = computed(() => promoterTheme.value || apiConsoleTheme.value);
const apiIcons = ['home', 'card', 'bank', 'document', 'settings'];
const promoterTheme = computed(() => currentAdmin.value?.role === "PROMOTER");
const promoterIcons = ["home", "user", "document", "money", "settings"];
const homePath = computed(() => currentAdmin.value ? homePathForRole[currentAdmin.value.role] : "/admin/overview");
const navigationLabel = computed(() => currentAdmin.value?.role === "PROMOTER" ? "推广后台导航" : currentAdmin.value?.role === "API_USER" ? "API 控制台导航" : "管理端导航");
const sidebarNote = computed(() => currentAdmin.value?.role === "PROMOTER" ? "只展示当前推广关系中的脱敏用户数据。" : currentAdmin.value?.role === "API_USER" ? "只展示当前 API 账号的数据。" : "充值身份以服务端确认结果为准，主管理员配置保持权限隔离。");
function isCurrent(target: string) { return route.path === target || route.path.startsWith(target + '/') || (target === "/admin/promotions/withdrawals" && route.path === "/admin/promotions/withdrawal-report"); }
const currentNavigation = computed(() => visibleNavigation.value.find(item => isCurrent(item.to))?.to ?? '');
function changeManagementPage(event: Event) {
  const destination = (event.target as HTMLSelectElement).value;
  if (visibleNavigation.value.some(item => item.to === destination)) void router.push(destination);
}
watch(() => currentAdmin.value?.username, () => { passwordOpen.value = false; });
let navigationRevision = 0;
watch(() => route.fullPath, async (_to, from) => {
  const revision = ++navigationRevision;
  pageMotion.cancel();
  if (!phase && route.path === "/admin/login") currentAdmin.value = null;
  passwordOpen.value = false;
  menuOpen.value = false;
  const previousPath = from?.split(/[?#]/)[0];
  const previousIndex = visibleNavigation.value.findIndex(item => previousPath === item.to || previousPath?.startsWith(item.to + '/') || (item.to === '/admin/promotions/withdrawals' && previousPath === '/admin/promotions/withdrawal-report'));
  const nextIndex = visibleNavigation.value.findIndex(item => item.to === currentNavigation.value);
  // A detail drawer, filter or query belongs to the existing workspace: keep its focus and scroll.
  if (overviewTheme.value && previousIndex >= 0 && previousIndex === nextIndex) return;
  await nextTick();
  if (revision !== navigationRevision) return;
  if (referenceTheme.value || apiDocsTheme.value || overviewTheme.value) {
    mainElement.value?.focus({ preventScroll: true });
    if (!apiDocsTheme.value || !route.hash) window.scrollTo(0, 0);
    else document.getElementById(route.hash.slice(1))?.scrollIntoView?.({ block: 'start' });
  } else mainElement.value?.focus();
  if (overviewTheme.value && previousIndex >= 0 && nextIndex >= 0 && !forbidden.value && (!phase || phase.value === 'ready')) {
    pageMotion.play(pageContent.value, nextIndex - previousIndex);
  }
}, { immediate: true });
watch(() => `${currentAdmin.value?.role}:${currentAdmin.value?.userId}:${currentAdmin.value?.username}:${phase?.value}`, () => {
  navigationRevision++;
  pageMotion.cancel();
}, { flush: 'sync' });
watch(() => phase?.value, value => {
  if (value !== 'anonymous') return;
  menuOpen.value = false;
  if (props.adminBenefitSettingsAdapter) Object.assign(props.adminBenefitSettingsAdapter, createLocalAdminBenefitSettingsAdapter());
  liveBenefitSettings.reset(); livePromotion.reset();
}, { flush: 'sync' });
</script>

<template>
  <main v-if="isLogin" id="main-content" ref="mainElement" class="admin-auth-layout" tabindex="-1"><RouterView /></main>
  <div v-else class="admin-frame" :class="{ 'overview-theme': overviewTheme, 'promoter-theme': promoterTheme, 'api-docs-theme': apiDocsTheme, 'api-console-theme': apiConsoleTheme }">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <aside v-if="!apiDocsTheme" class="admin-sidebar">
      <RouterLink :to="homePath" custom v-slot="{ href, navigate }"><a class="brand-block" :href="href" aria-label="XZWLab 首页" @click="navigate"><img class="brand-logo" :src="adminBrand" alt="" /><small v-if="overviewTheme">AI 让创意，更快成片</small><small v-if="referenceTheme">MAKE ANYONE A FILMMAKER.</small></a></RouterLink>
      <label v-if="overviewTheme" class="management-mobile-nav"><span class="sr-only">管理页面</span><select aria-label="管理页面" :value="currentNavigation" @change="changeManagementPage"><option v-if="!currentNavigation" value="" disabled>选择管理页面</option><option v-for="item in visibleNavigation" :key="item.to" :value="item.to">{{ item.label }}</option></select></label>
      <nav class="primary-nav" :aria-label="navigationLabel">
        <RouterLink v-for="(item, index) in visibleNavigation" :key="item.to" :to="item.to" :aria-current="isCurrent(item.to) ? 'page' : undefined"><PromoterIcon v-if="overviewTheme" :name="overviewIcons[adminNavigation.findIndex(nav=>nav.to===item.to)] || 'document'"/><PromoterIcon v-else-if="referenceTheme" :name="(apiConsoleTheme ? apiIcons : promoterIcons)[index] || 'home'"/><span v-else class="nav-icon" aria-hidden="true">{{ item.icon }}</span><span>{{ item.label }}</span></RouterLink>
      </nav>
      <div v-if="overviewTheme" class="overview-copyright">© {{new Date().getFullYear()}} YOMIO</div><div v-else-if="referenceTheme" class="promoter-brand-card"><img :src="adminBrand" alt="YOMIO"/><span>让创作更简单</span><small>MAKE ANYONE A FILMMAKER.</small></div><div v-else class="sidebar-note"><strong>权限边界</strong><span>{{ sidebarNote }}</span></div>
    </aside>
    <div class="admin-workspace">
      <header class="workspace-header"><RouterLink v-if="apiDocsTheme" class="brand-block" to="/api/dashboard" aria-label="YOMIO API 控制台"><img class="brand-logo" :src="adminBrand" alt="" /></RouterLink><div v-else-if="promoterTheme" class="promoter-welcome">欢迎回来，{{ currentAdmin?.username }}</div><div v-else-if="!overviewTheme && !apiConsoleTheme"><p>BUSINESS OPERATIONS</p><strong>运营管理台</strong></div><div ref="accountTools" class="header-tools" @focusout="leaveAccountTools" @keydown.esc.stop="escapeAccountMenu"><RouterLink v-if="currentAdmin?.role === 'API_USER'" class="api-recharge-link" to="/api/billing">充值</RouterLink><span v-if="referenceTheme" class="promoter-language"><PromoterIcon name="globe"/>简体中文</span><span v-else-if="!apiDocsTheme && !overviewTheme" class="offline-chip">{{ ["admin-pricing", "admin-providers"].includes(String(route.name)) ? "实时配置" : "运营视图" }}</span><span v-if="overviewTheme" class="overview-admin-name"><strong>{{currentAdmin?.username}}</strong><span class="sr-only">{{currentAdmin?.role}}</span><small>{{currentAdmin?.primary?'超级管理员':'管理员'}}</small></span><span v-if="!referenceTheme && !overviewTheme" class="admin-role">{{ currentAdmin?.role ?? (phase === 'ready' ? '无管理权限' : phase === 'checking' ? '验证中' : '未登录') }}</span><button ref="accountMenuButton" type="button" aria-controls="account-menu" :aria-label="currentAdmin?.role === 'API_USER' ? '打开账户菜单' : '打开管理员菜单'" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen"><template v-if="referenceTheme"><span class="promoter-avatar"><PromoterIcon name="user"/></span><span class="account-name" :title="currentAdmin?.username">{{ currentAdmin?.username }}</span><span aria-hidden="true">⌄</span></template><template v-else>{{overviewTheme?currentAdmin?.username?.slice(0,1).toUpperCase():'管'}}</template></button><div v-if="menuOpen" id="account-menu" class="admin-menu"><strong>{{ currentAdmin?.username ?? (phase === 'ready' ? '当前账号无管理权限' : '尚未登录') }}</strong><button type="button" @click="passwordOpen = true; menuOpen = false">修改密码</button><button type="button" data-testid="admin-logout" @click="logout">退出当前设备</button></div></div></header>
      <main id="main-content" ref="mainElement" class="main-content" tabindex="-1"><PasswordChange v-if="passwordOpen && currentAdmin && (!phase || phase === 'ready' || phase === 'checking')" v-show="!phase || phase === 'ready'" @changed="passwordChanged" @cancel="passwordOpen = false" /><p v-if="!isLogin && phase === 'checking'" role="status">正在验证登录状态…</p>
        <section v-else-if="!isLogin && phase === 'error'" role="alert"><h1>无法验证登录状态</h1><p>请检查网络或服务状态后重试。</p><button data-testid="retry-session" @click="session.verify?.()">重试</button></section>
        <section v-else-if="!isLogin && phase === 'ready' && forbidden"><h1>无权限</h1><p>当前账号没有访问此页面的权限。</p><RouterLink v-if="currentAdmin" :to="homePath">返回首页</RouterLink></section>
        <div v-if="isLogin || !phase || ((phase === 'ready' || phase === 'checking') && currentAdmin && !forbidden)"
          v-show="isLogin || !phase || phase === 'ready'" ref="pageContent" data-testid="protected-content"><RouterView :key="`${currentAdmin?.role}:${currentAdmin?.userId}:${currentAdmin?.username}`" /></div>
      </main>
    </div>
  </div>
</template>

<style scoped>
.api-recharge-link { display: inline-flex; align-items: center; justify-content: center; height: 44px; padding: 0 20px; border-radius: 999px; background: #49dce5; color: #05262b; text-decoration: none; font-weight: 750; white-space: nowrap; }
.api-recharge-link:hover { background: #7be9ed; }
.header-tools { min-width: 0; max-width: 100%; }
.header-tools > button { flex-shrink: 0; }
.account-name { max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.admin-menu { max-width: calc(100vw - 32px); }
.admin-menu strong { overflow-wrap: anywhere; }
@media (max-width: 760px) { .account-name { max-width: 120px; } }
</style>
