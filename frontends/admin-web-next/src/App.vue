<script setup lang="ts">
import { computed, defineAsyncComponent, h, nextTick, onMounted, onUnmounted, provide, ref, watch } from 'vue';
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router';
import { NConfigProvider, NMessageProvider, NDialogProvider, NButton, NDropdown, NDrawer, NDrawerContent, NTooltip, zhCN, dateZhCN, type GlobalThemeOverrides } from 'naive-ui';
import { Menu, PanelLeftClose, PanelLeftOpen, LogOut, KeyRound, RefreshCw, ChevronDown, ShieldCheck, Layers, ChevronRight, CalendarDays } from '@lucide/vue';
import { adminNavigation } from './navigation';
import { adminSessionKey, canAccessAdminPath, type AdminSession } from './adminSession';
import { createAdminAuth } from './adminAuth';
import { logout as logoutRemote } from './api';
import { adminRequestCache } from './data/requestCache';
import PromoterIcon from './PromoterIcon.vue';
import SidebarNavigation from './components/SidebarNavigation.vue';
import { createPageMotion } from './pageMotion';
import { adminWithdrawalAdapterKey, type AdminWithdrawalAdapter } from './promotion/localWithdrawalFixtures';
import { adminWithdrawalReportAdapterKey, type AdminWithdrawalReportAdapter } from './promotion/localWithdrawalReportFixtures';
import { adminBenefitSettingsAdapterKey, type AdminBenefitSettingsAdapter } from './promotion/localBenefitSettingsFixtures';
import { adminReconciliationAdapterKey, type AdminReconciliationAdapter } from './promotion/localReconciliationFixtures';
import { adminBenefitAuditAdapterKey, type AdminBenefitAuditAdapter } from './promotion/localBenefitAuditFixtures';
import { adminAttributionAdapterKey, createHttpPromotionAdminAdapters, type AdminAttributionAdapter } from './promotion/httpPromotionAdminAdapters';
import { createHttpBenefitSettingsAdapter } from './promotion/httpBenefitSettingsAdapter';

const props = defineProps<{ session?: AdminSession; adminAttributionAdapter?: AdminAttributionAdapter; adminWithdrawalAdapter?: AdminWithdrawalAdapter; adminWithdrawalReportAdapter?: AdminWithdrawalReportAdapter; adminBenefitSettingsAdapter?: AdminBenefitSettingsAdapter; adminReconciliationAdapter?: AdminReconciliationAdapter; adminBenefitAuditAdapter?: AdminBenefitAuditAdapter }>();
const route = useRoute(), router = useRouter();
const session = props.session ?? createAdminAuth(router);
const currentAdmin = session.currentAdmin, phase = session.phase;
provide(adminSessionKey, session);
const promotion = createHttpPromotionAdminAdapters(), benefit = createHttpBenefitSettingsAdapter();
provide(adminAttributionAdapterKey, props.adminAttributionAdapter ?? promotion.attribution);
provide(adminWithdrawalAdapterKey, props.adminWithdrawalAdapter ?? promotion.withdrawals);
provide(adminWithdrawalReportAdapterKey, props.adminWithdrawalReportAdapter ?? promotion.reports);
provide(adminBenefitSettingsAdapterKey, props.adminBenefitSettingsAdapter ?? benefit);
provide(adminReconciliationAdapterKey, props.adminReconciliationAdapter ?? promotion.reconciliation);
provide(adminBenefitAuditAdapterKey, props.adminBenefitAuditAdapter ?? promotion.audit);
watch(() => JSON.stringify(currentAdmin.value), () => { adminRequestCache.setScope(currentAdmin.value ? JSON.stringify([currentAdmin.value.userId,currentAdmin.value.username,currentAdmin.value.role,currentAdmin.value.primary]) : 'anonymous'); promotion.reset(); benefit.reset(); }, { immediate:true, flush: 'sync' });
onMounted(() => { void session.verify?.(); });
const motion = createPageMotion();
onUnmounted(() => { motion.cancel(); session.dispose?.(); promotion.reset(); benefit.reset(); });
const isLogin = computed(() => ['admin-login', 'portal-entry'].includes(String(route.name)));
const forbidden = computed(() => !canAccessAdminPath(route.path, currentAdmin.value));
const navigation = computed(() => (!phase || phase.value === 'ready') && currentAdmin.value ? adminNavigation.filter(item => canAccessAdminPath(item.to, currentAdmin.value)) : []);
const activeNavigation = computed(() => navigation.value.find(item => route.path === item.to || route.path.startsWith(item.to + '/') || (item.to === '/admin/promotions/withdrawals' && route.path === '/admin/promotions/withdrawal-report')));
const title = computed(() => activeNavigation.value?.label ?? '管理后台');
const dateLabel = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: 'long', day: 'numeric', weekday: 'short' }).format(new Date());
const collapsed = ref(false), mobileOpen = ref(false), passwordOpen = ref(false), revision = ref(0);
const main = ref<HTMLElement>();
const content = ref<HTMLElement>();
watch(phase ?? ref('ready'), value => { if (value !== 'ready') motion.cancel(); });
const PasswordChange = defineAsyncComponent(() => import('./PasswordChange.vue'));
watch(() => route.path, async (to, from) => {
  mobileOpen.value = false; passwordOpen.value = false;
  const workspace = (path: string) => adminNavigation.find(item => path === item.to || path.startsWith(item.to + '/'))?.to;
  if (from && workspace(to) === workspace(from)) return;
  await nextTick(); main.value?.focus({ preventScroll: true }); window.scrollTo?.(0, 0);
  const index = (path: string) => adminNavigation.findIndex(item => workspace(path) === item.to);
  if (!phase || phase.value === 'ready') motion.play(content.value ?? null, Math.sign(index(to) - index(from)));
});
watch(() => currentAdmin.value?.username, () => { passwordOpen.value = false; });
async function logout() {
  if (session.logout) return session.logout();
  try { await logoutRemote(); } catch { return; }
  currentAdmin.value = null; adminRequestCache.clear(); await router.replace('/admin/login');
}
async function passwordChanged() { passwordOpen.value = false; await session.verify?.(); await router.replace('/admin/login'); }
function refresh() { adminRequestCache.invalidate(); revision.value++; }
const accountOptions = [
  { label: '修改密码', key: 'password', icon: () => h(KeyRound, { size: 16 }) },
  { type: 'divider', key: 'divider' },
  { label: '退出当前设备', key: 'logout', icon: () => h(LogOut, { size: 16 }) },
];
function selectAccount(key: string) { if (key === 'password') passwordOpen.value = true; else if (key === 'logout') void logout(); }
const theme: GlobalThemeOverrides = {
  common: {
    primaryColor: '#4f46e5', primaryColorHover: '#4338ca', primaryColorPressed: '#3730a3',
    primaryColorSuppl: '#4f46e5', infoColor: '#4f46e5', successColor: '#15805e', warningColor: '#956014', errorColor: '#c43d52',
    bodyColor: '#f6f7fa', cardColor: '#ffffff', textColorBase: '#202d43', borderColor: '#e3e7ee',
    borderRadius: '7px', fontFamily: '"Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif', fontSize: '14px',
  },
  Button: { fontWeight: '500', heightMedium: '38px' },
  DataTable: { thColor: '#f8f9fc', thTextColor: '#596579', thFontWeight: '500', tdColorHover: '#f8f8fe', borderColor: '#edf0f4', fontSizeSmall: '13px' },
  Input: { heightMedium: '38px', borderHover: '1px solid #a5a0ef', placeholderColor: '#657187' },
  Select: { peers: { InternalSelection: { heightMedium: '38px', placeholderColor: '#657187' } } },
  Drawer: { titleFontSize: '18px' },
};
</script>

<template>
  <NConfigProvider :theme-overrides="theme" :locale="zhCN" :date-locale="dateZhCN">
    <NMessageProvider><NDialogProvider>
      <main v-if="isLogin" id="main-content" class="admin-auth-layout"><RouterView /></main>
      <div v-else class="admin-frame overview-theme" :class="{ 'sidebar-collapsed': collapsed }">
        <a class="skip-link" href="#main-content">跳到主要内容</a>
        <aside class="admin-sidebar">
          <RouterLink class="brand-block" to="/admin/overview" aria-label="YOMIO 管理后台首页"><span class="brand-symbol"><Layers :size="22" :stroke-width="1.7" aria-hidden="true" /></span><span v-if="!collapsed" class="brand-lockup"><span class="brand-word">YOMIO<span class="brand-dot">.</span></span><span class="brand-subtitle">管理工作台</span></span></RouterLink>
          <SidebarNavigation :items="navigation" :collapsed="collapsed" />
          <div class="sidebar-footer"><ShieldCheck :size="18" aria-hidden="true"/><span v-if="!collapsed"><strong>{{ currentAdmin?.primary ? '主管理员工作空间' : '管理员工作空间' }}</strong><small>YOMIO 管理后台</small></span></div>
        </aside>
        <div class="admin-workspace">
          <header class="workspace-header">
            <div class="header-location">
              <NButton quaternary class="desktop-toggle" :aria-label="collapsed ? '展开侧栏' : '收起侧栏'" @click="collapsed = !collapsed"><template #icon><PanelLeftOpen v-if="collapsed" :size="19" /><PanelLeftClose v-else :size="19" /></template></NButton>
              <NButton quaternary class="mobile-toggle" aria-label="打开导航" @click="mobileOpen = true"><template #icon><Menu :size="20" /></template></NButton>
              <RouterLink to="/admin/overview">工作台</RouterLink><ChevronRight class="breadcrumb-divider" :size="14" aria-hidden="true"/><template v-if="activeNavigation?.group && activeNavigation.group !== '工作台'"><span class="breadcrumb-group">{{ activeNavigation.group }}</span><ChevronRight class="breadcrumb-divider breadcrumb-group" :size="14" aria-hidden="true"/></template><strong>{{ title }}</strong>
            </div>
            <div class="header-tools">
              <span class="header-date"><CalendarDays :size="15" aria-hidden="true"/>{{ dateLabel }}</span>
              <NTooltip><template #trigger><NButton quaternary aria-label="刷新当前页面数据" @click="refresh"><template #icon><RefreshCw :size="18" /></template></NButton></template>清除查询缓存并刷新当前页面</NTooltip>
              <span class="header-separator" />
              <NDropdown trigger="click" :to="false" :options="accountOptions" :node-props="option=>({'data-testid':option.key==='logout'?'admin-logout':option.key==='password'?'admin-change-password':undefined})" @select="selectAccount"><NButton quaternary class="account-trigger" aria-label="打开管理员菜单"><span class="account-avatar">{{ currentAdmin?.username?.slice(0, 1).toUpperCase() || '管' }}</span><span class="account-text"><strong>{{ currentAdmin?.username || (phase === 'ready' ? '无管理权限' : '未登录') }}</strong><small>{{ currentAdmin?.primary ? '超级管理员' : '管理员' }}</small></span><ChevronDown :size="14" /></NButton></NDropdown>
            </div>
          </header>
          <main id="main-content" ref="main" class="main-content" tabindex="-1">
            <PasswordChange v-if="passwordOpen && currentAdmin && (!phase || phase === 'ready' || phase === 'checking')" v-show="!phase || phase === 'ready'" @changed="passwordChanged" @cancel="passwordOpen = false" />
            <section v-if="phase === 'checking'" class="blocking-state" role="status"><span class="loading-spinner" />正在验证登录状态…</section>
            <section v-else-if="phase === 'error'" class="blocking-state" role="alert"><h1>无法验证登录状态</h1><p>请检查网络或服务状态后重试。</p><NButton type="primary" data-testid="retry-session" @click="session.verify?.()">重试</NButton></section>
            <section v-else-if="forbidden" class="blocking-state"><PromoterIcon name="shield" /><h1>无权限</h1><p>当前账号没有访问此页面的权限。</p><RouterLink to="/admin/overview">返回首页</RouterLink></section>
            <div v-if="!forbidden && (!phase || phase === 'ready' || phase === 'checking')" v-show="!phase || phase === 'ready'" ref="content" data-testid="protected-content"><RouterView v-slot="{ Component }"><component :is="Component" :key="`${currentAdmin?.role}:${currentAdmin?.userId}:${currentAdmin?.username}:${revision}`" /></RouterView></div>
          </main>
          <footer class="workspace-footer">YOMIO 管理后台<span>数据以服务端确认为准</span></footer>
        </div>
        <NDrawer v-model:show="mobileOpen" :width="280" placement="left"><NDrawerContent title="YOMIO · 管理后台" closable><SidebarNavigation :items="navigation" /></NDrawerContent></NDrawer>
      </div>
    </NDialogProvider></NMessageProvider>
  </NConfigProvider>
</template>
