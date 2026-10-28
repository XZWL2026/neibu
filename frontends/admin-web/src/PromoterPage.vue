<script setup lang="ts">
import { computed, inject, onBeforeUnmount, ref, watch } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import { adminSessionKey } from './adminSession';
import {
  ApiError,
  promotionOverviewGetApiV1PromotionsPortalOverview as readOverview,
  promotionDownlinesGetApiV1PromotionsPortalDownlines as readDownlines,
  promotionConsumptionsGetApiV1PromotionsPortalConsumptions as readConsumptions,
  promotionRechargesGetApiV1PromotionsPortalRecharges as readRecharges,
  promotionWithdrawalsGetApiV1PromotionsPortalWithdrawals as readWithdrawals,
  promotionPaidMonthsGetApiV1PromotionsPortalPaidMonths as readPaidMonths,
  promotionPayoutsGetApiV1PromotionsPortalPayoutAccounts as readPayouts,
  promotionSavePayoutPutApiV1PromotionsPortalPayoutAccountsPayoutAccountId as savePayout,
  userSelfGetApiV1UsersMe as readSelf
} from './api';
import { decimal4, money, points, shown, units4, when } from './portalAmounts';
import PromoterIcon from './PromoterIcon.vue';
import PromotionLink from './promotion/PromotionLink.vue';
const route = useRoute();
const session = inject(adminSessionKey);
if (!session) throw new Error('PromoterPage requires a session provider');
const allowed = computed(() => session.currentAdmin.value?.role === 'PROMOTER');
const owner = computed(() => session.currentAdmin.value?.userId ?? '');
const pageName = computed(() => String(route.name ?? ''));
const scope = computed(() => `${allowed.value}:${owner.value}:${pageName.value}`);
const titles: Record<string, string> = { 'promoter-overview': '推广概览', 'promoter-users': '推广用户', 'promoter-consumption': '消费明细', 'promoter-settlements': '佣金', 'promoter-account': '账户设置' };
const title = computed(() => titles[pageName.value] ?? '推广后台');
type Overview = Awaited<ReturnType<typeof readOverview>>['data'];
type DownlinePage = Awaited<ReturnType<typeof readDownlines>>['data'];
type ConsumptionPage = Awaited<ReturnType<typeof readConsumptions>>['data'];
type RechargePage = Awaited<ReturnType<typeof readRecharges>>['data'];
type PaidMonthPage = Awaited<ReturnType<typeof readPaidMonths>>['data'];
type WithdrawalPage = Awaited<ReturnType<typeof readWithdrawals>>['data'];
type PayoutPage = Awaited<ReturnType<typeof readPayouts>>['data'];
type PayoutBody = Parameters<typeof savePayout>[1];
const overview = ref<Overview | null>(null), downlines = ref<DownlinePage | null>(null), consumption = ref<ConsumptionPage | null>(null), recharges = ref<RechargePage | null>(null), withdrawals = ref<WithdrawalPage | null>(null), payouts = ref<PayoutPage | null>(null);
const profile = ref<Awaited<ReturnType<typeof readSelf>>['data'] | null>(null);
const paidMonths = ref<PaidMonthPage | null>(null), paidMonthPage = ref(0);
const pendingMonth = ref('');
const pendingDisbursement = computed(() => {
  const c = overview.value?.commission;
  const available = units4(c?.available), reserved = units4(c?.withdrawing), debt = units4(c?.cashDebt);
  if (available === null || reserved === null || debt === null) return null;
  const pending = available + reserved - debt;
  return decimal4(pending > 0n ? pending : 0n);
});
function monthLabel(value: string | null | undefined) {
  const match = value?.match(/^(\d{4})-(0[1-9]|1[0-2])$/);
  return match ? `${match[1]}年${Number(match[2])}月` : '—';
}
const loading = ref(false), error = ref(''), notice = ref('');
const downlinePage = ref(0), consumptionPage = ref(0), rechargePage = ref(0), withdrawalPage = ref(0), payoutPage = ref(0), withdrawalMonth = ref('');
const pageSize = 20;
const search = ref(''), userStatus = ref(''), consumptionType = ref('');
const descriptions: Record<string,string> = {
 'promoter-overview':'一目了然，轻松管理你的推广收益。',
 'promoter-users':'邀请更多用户，一起创造更多可能。',
 'promoter-consumption':'查看用户的消费记录，掌握推广效果。',
 'promoter-settlements':'推广带来更多创作价值，佣金实时统计。',
 'promoter-account':'管理收款账户信息，保障佣金顺利发放。'
};
const eyebrows: Record<string,string> = {'promoter-overview':'PROMOTION OVERVIEW','promoter-users':'PROMOTION USERS','promoter-consumption':'CONSUMPTION DETAILS','promoter-settlements':'COMMISSION','promoter-account':'ACCOUNT SETTINGS'};
const matches = (item: {nickname: string | null; maskedPhone: string | null}) => `${item.nickname ?? ''} ${item.maskedPhone ?? ''}`.toLowerCase().includes(search.value.trim().toLowerCase());
const visibleUsers = computed(() => (downlines.value?.items ?? []).filter(item => matches(item) && (!userStatus.value || item.accountStatus === userStatus.value)));
const visibleConsumption = computed(() => (consumption.value?.items ?? []).filter(item => matches(item) && (!consumptionType.value || item.type === consumptionType.value)));
type DailySummary = { amount: string | null; commission: string | null; orders: number };
const daily = ref<DailySummary[]>([]), dailyError = ref('');
const registrations = ref<{ today: number; yesterday: number } | null>(null);
let dailyKey = '', dailyRevision = 0, dailyAbort: AbortController | undefined;
let dailyTimer: ReturnType<typeof setTimeout> | undefined;
function shanghaiDay() { return new Intl.DateTimeFormat('sv-SE', {timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date()); }
const commissionMoney = (value: string | null | undefined) => { const amount = units4(value); return amount === null ? '—' : `¥ ${decimal4(amount)}`; };
function dailyChange(index: number) {
 const current = units4(daily.value[index]?.commission), previous = units4(daily.value[index + 1]?.commission);
 if (current === null || previous === null || previous === 0n) return '—';
 const delta = current - previous, base = previous < 0n ? -previous : previous;
 const tenths = ((delta < 0n ? -delta : delta) * 1000n + base / 2n) / base;
 return `${delta > 0n ? '+' : delta < 0n ? '-' : ''}${tenths / 10n}.${tenths % 10n}%`;
}
async function readDay(start: number, signal: AbortSignal): Promise<DailySummary> {
 const from = new Date(start).toISOString(), to = new Date(start + 86400000).toISOString();
 let amount: bigint | null = 0n, commission: bigint | null = 0n, read = 0, total: number | undefined;
 const orders = new Set<string>();
 // ponytail: reads each day's pages once per portal view; move aggregation server-side if daily volume becomes costly.
 for (let page = 0;; page++) {
  const {data} = await readConsumptions({from,to,page,size:100},{signal});
  if (!Array.isArray(data.items) || !Number.isSafeInteger(data.total) || data.total < 0 || (total !== undefined && total !== data.total)) throw new Error('日统计分页不完整');
  total = data.total;
  for (const item of data.items) {
   if (!item.allocationId) throw new Error('日统计缺少订单标识');
   orders.add(item.allocationId);
   const paid = units4(item.paidAmount4), earned = units4(item.commissionAmount4);
   amount = amount === null || paid === null ? null : amount + paid;
   commission = commission === null || earned === null ? null : commission + earned;
  }
  read += data.items.length;
  if (read === total) break;
  if (!data.items.length || read > total) throw new Error('日统计分页不完整');
 }
 return {amount: amount === null ? null : decimal4(amount), commission: commission === null ? null : decimal4(commission), orders: orders.size};
}
async function readRegistrations(start: number, signal: AbortSignal) {
 let today = 0, yesterday = 0, read = 0, total: number | undefined;
 // ponytail: the existing API has no date filter; use a server aggregate when invitation volume becomes costly.
 for (let page = 0;; page++) {
  const {data} = await readDownlines({page,size:100},{signal});
  if (!Array.isArray(data.items) || !Number.isSafeInteger(data.total) || data.total < 0 || (total !== undefined && total !== data.total)) throw new Error('注册统计分页不完整');
  total = data.total;
  for (const user of data.items) {
   const time = user.registeredAt ? Date.parse(user.registeredAt) : NaN;
   if (!Number.isFinite(time)) throw new Error('缺少真实注册时间');
   if (time >= start && time < start + 86400000) today++;
   if (time >= start - 86400000 && time < start) yesterday++;
  }
  read += data.items.length;
  if (read === total) return {today,yesterday};
  if (!data.items.length || read > total) throw new Error('注册统计分页不完整');
 }
}
function loadDaily(name: string) {
 if (!['promoter-users','promoter-consumption','promoter-settlements'].includes(name)) return;
 const day = shanghaiDay(), key = `${scope.value}:${day}`;
 if (dailyKey === key) return;
 dailyKey = key; const current = ++dailyRevision;
 dailyAbort?.abort(); dailyAbort = new AbortController(); daily.value = []; registrations.value = null; dailyError.value = '';
 const start = new Date(`${day}T00:00:00+08:00`).getTime();
 clearTimeout(dailyTimer);
 dailyTimer = setTimeout(() => { if (!disposed && allowed.value) loadDaily(pageName.value); }, Math.max(1, start + 86400000 - Date.now() + 50));
 const request = name === 'promoter-users'
  ? readRegistrations(start, dailyAbort.signal).then(result => { if (current === dailyRevision) registrations.value = result; })
  : Promise.all((name === 'promoter-consumption' ? [0] : [0,1,2]).map(offset => readDay(start - offset * 86400000, dailyAbort!.signal)))
    .then(result => { if (current === dailyRevision) daily.value = result; });
 void request
  .catch(() => { if (current === dailyRevision) dailyError.value = '无法读取真实数据：日统计暂时不可用'; });
}
function retry() { dailyKey = ''; void load(); }
function recentRange(days: number) {
 const end = new Date(), start = new Date(end); start.setDate(start.getDate() - days + 1);
 recordDates.value = {from: localDate(start), to: localDate(end)}; queryRecords();
}
function localDate(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}
function recentDates() {
  const end = new Date(), start = new Date(end); start.setDate(start.getDate() - 29);
  return { from: localDate(start), to: localDate(end) };
}
const recordDates = ref(recentDates());
function dateRange(dates: { from: string; to: string }) {
  if (dates.from && dates.to && dates.from > dates.to) throw new Error('开始日期不能晚于结束日期');
  const from = dates.from ? new Date(`${dates.from}T00:00:00`) : null;
  const to = dates.to ? new Date(`${dates.to}T00:00:00`) : null;
  if ((from && !Number.isFinite(from.getTime())) || (to && !Number.isFinite(to.getTime()))) throw new Error('请选择有效日期');
  if (to) to.setDate(to.getDate() + 1);
  return { from: from?.toISOString(), to: to?.toISOString() };
}
const recordRange = ref(dateRange(recordDates.value));
let changingRecordQuery = false;
function queryRecords() {
  try {
    const range = dateRange(recordDates.value);
    changingRecordQuery = true;
    recordRange.value = range; consumptionPage.value = 0; rechargePage.value = 0;
    changingRecordQuery = false;
    void load();
  } catch (failure) { error.value = failure instanceof Error ? failure.message : '请选择有效日期'; }
}
function recordTime(value: string | null | undefined) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isFinite(date.getTime()) ? `${localDate(date)} ${date.toLocaleTimeString('zh-CN', { hour12: false })}` : '—';
}

const withdrawalRange = computed(() => {
  if (!withdrawalMonth.value) return {};
  const start = new Date(`${withdrawalMonth.value}-01T00:00:00`), end = new Date(start);
  end.setMonth(end.getMonth() + 1);
  return { from: start.toISOString(), to: end.toISOString() };
});
const banks = ['中国工商银行','中国农业银行','中国银行','中国建设银行','交通银行','中国邮政储蓄银行','招商银行','中信银行','中国光大银行','浦发银行','兴业银行','平安银行','中国民生银行','华夏银行','广发银行'];
const selectedAccountId = ref('');
const bankAccounts = computed(() => payouts.value?.items?.filter(account => account.payoutType === 'BANK_CARD') ?? []);
const selectedAccount = computed(() => bankAccounts.value.find(account => account.id === selectedAccountId.value));
const accountStatus = computed(() => {
 if (profile.value?.enabled === false || overview.value?.promoter?.status === 'DISABLED') return '已停用';
 return profile.value?.enabled === true && overview.value?.promoter?.status === 'ENABLED' ? '正常' : '—';
});
const draft = ref({ name: '', reference: '', bankName: '' });
const pendingSave = ref<{ id: string; body: PayoutBody } | null>(null);
const saving = ref(false);
const busy = saving;
let revision = 0, identityGeneration = 0, disposed = false, abort: AbortController | undefined;
watch(scope, () => {
  identityGeneration++; dailyRevision++; dailyAbort?.abort(); clearTimeout(dailyTimer); dailyKey = ''; daily.value = []; registrations.value = null; dailyError.value = '';
  search.value = ''; userStatus.value = ''; consumptionType.value = '';
  recordDates.value = recentDates(); recordRange.value = dateRange(recordDates.value);
  downlinePage.value = 0; consumptionPage.value = 0; rechargePage.value = 0; withdrawalPage.value = 0; payoutPage.value = 0; paidMonthPage.value = 0;
  withdrawalMonth.value = ''; notice.value = ''; pendingSave.value = null;
  selectedAccountId.value = ''; draft.value = { name: '', reference: '', bankName: '' }; saving.value = false;
}, { flush: 'sync' });
watch(withdrawalMonth, () => {
  if (withdrawalPage.value !== 0) withdrawalPage.value = 0;
  else void load();
}, { flush: 'sync' });
watch([scope, downlinePage, consumptionPage, rechargePage, withdrawalPage, payoutPage, paidMonthPage], () => { if (!changingRecordQuery) void load(); }, { immediate: true, flush: 'sync' });
onBeforeUnmount(() => { disposed = true; identityGeneration++; revision++; abort?.abort(); dailyRevision++; dailyAbort?.abort(); clearTimeout(dailyTimer); });
async function load() {
  if (disposed) return;
  const current = ++revision, userId = owner.value, name = pageName.value;
  abort?.abort(); abort = new AbortController(); const options = { signal: abort.signal };
  overview.value = null; downlines.value = null; consumption.value = null; recharges.value = null; withdrawals.value = null; payouts.value = null; profile.value = null; paidMonths.value = null; error.value = ''; loading.value = false;
  pendingMonth.value = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', timeZone: 'Asia/Shanghai' }).format(new Date());
  if (!allowed.value || !titles[name]) return;
  if (!userId) { error.value = '无法读取真实账户：登录身份缺少业务用户，请重新登录'; return; }
  loading.value = true; loadDaily(name);
  try {
    if (name === 'promoter-users') {
      const data = (await readDownlines({ page: downlinePage.value, size: pageSize }, options)).data;
      if (current === revision) downlines.value = data;
    } else if (name === 'promoter-consumption') {
      const data = (await readConsumptions({ page: consumptionPage.value, size: pageSize, ...recordRange.value }, options)).data;
      if (current === revision) consumption.value = data;
    } else {
      const data = (await readOverview(options)).data;
      if (data.promoter?.userId !== userId || data.commission?.promoterUserId !== userId) throw new Error('推广账户归属不匹配');
      if (current !== revision) return;
      overview.value = data;
      if (name === 'promoter-overview') {
        const [used, paid, monthly] = await Promise.all([readConsumptions({ page: consumptionPage.value, size: pageSize, ...recordRange.value }, options), readRecharges({ page: rechargePage.value, size: pageSize, ...recordRange.value }, options), readPaidMonths({ page: paidMonthPage.value, size: pageSize }, options)]);
        if (current !== revision) return;
        consumption.value = used.data; recharges.value = paid.data; paidMonths.value = monthly.data;
      } else {
        const [accounts, applied, identity] = await Promise.all([readPayouts({ page: payoutPage.value, size: pageSize }, options), name === 'promoter-account' ? Promise.resolve(null) : readWithdrawals({ page: withdrawalPage.value, size: pageSize, ...withdrawalRange.value }, options), readSelf(options)]);
        if (identity.data.id !== userId || applied?.data.items?.some(item => item.promoterUserId !== userId)) throw new Error('账户或提现记录归属不匹配');
        if (current !== revision) return;
        payouts.value = accounts.data; withdrawals.value = applied?.data ?? null; profile.value = identity.data;
        const first = accounts.data.items?.find(account => account.payoutType === 'BANK_CARD');
        if (!selectedAccountId.value && first?.id) selectedAccountId.value = first.id;
      }
    }
  } catch (failure) { if (current === revision) error.value = `无法读取真实数据：${failure instanceof Error ? failure.message : '服务暂时不可用'}`; }
  finally { if (current === revision) loading.value = false; }
}
function chooseAccount() { draft.value = { name: '', reference: '', bankName: '' }; }
async function savePayoutMethod() {
  if (!allowed.value || !owner.value || busy.value || loading.value || error.value || !payouts.value) return;
  if (!pendingSave.value) {
    const name = draft.value.name.trim(), reference = draft.value.reference.trim(), bankName = draft.value.bankName.trim();
    if (!name || !bankName || !/^\d{1,32}$/.test(reference)) { notice.value = '请补充完整的收款信息'; return; }
    pendingSave.value = { id: selectedAccount.value?.id ?? crypto.randomUUID(), body: { commandId: crypto.randomUUID(), expectedVersion: selectedAccount.value?.sourceVersion ?? 0, payoutType: 'BANK_CARD', payoutAccountName: name, payoutAccountReference: reference, bankName, qrCodeReference: null } };
  }
  const operation = pendingSave.value, currentScope = scope.value, generation = identityGeneration; saving.value = true; notice.value = '';
  try {
    const saved = (await savePayout(operation.id, operation.body)).data;
    if (disposed || generation !== identityGeneration || currentScope !== scope.value) return;
    if (saved.id !== operation.id) throw new Error('收款账户响应不匹配');
    pendingSave.value = null; draft.value = { name: '', reference: '', bankName: '' };
    selectedAccountId.value = saved.id;
    notice.value = '收款方式已保存'; await load();
  } catch (failure) {
    if (disposed || generation !== identityGeneration || currentScope !== scope.value) return;
    const backendError = failure instanceof ApiError && typeof failure.body === 'object' && failure.body !== null && 'error' in failure.body ? failure.body.error : null;
    if (failure instanceof ApiError && ((failure.status === 400 && typeof backendError === 'string') || (failure.status === 409 && backendError === 'payout account version conflict'))) {
      pendingSave.value = null;
      notice.value = `收款方式未保存：${failure.message}。请核对并修改后重新提交。`;
      if (failure.status === 409) await load();
    } else notice.value = `收款方式尚未确认保存：${failure instanceof Error ? failure.message : '请求失败'}。请重试原申请。`;
  }
  finally { if (!disposed && generation === identityGeneration && currentScope === scope.value) saving.value = false; }
}
const recordTitle = '发放记录';
const disbursementStatus = (status: string) => ({ WITHDRAWING: '待审核', PENDING_PAYOUT: '待发放', PAID: '已发放', REJECTED: '已拒绝' }[status] ?? status);
const typeName = (type: string | null) => type === 'IMAGE' ? '图片' : type === 'VIDEO' ? '视频' : shown(type);
</script>

<template>
  <section v-if="!allowed" class="page-panel"><header class="page-heading"><div><h1>无权限</h1><p>当前账号不能访问推广后台页面。</p></div></header><div class="empty-state"><strong>需要推广人员权限</strong><RouterLink to="/admin/login">返回登录</RouterLink></div></section>
  <section v-else class="page-panel promoter-page" aria-labelledby="promoter-page-title" :aria-busy="loading">
    <header class="page-heading"><div><p class="page-eyebrow">{{ eyebrows[pageName] }}</p><h1 id="promoter-page-title">{{ title }}</h1><p class="page-description">{{ descriptions[pageName] }}</p></div></header>
    <p v-if="loading" role="status">正在读取真实数据…</p>
    <div v-if="error || dailyError" role="alert" class="notice">{{ error || dailyError }} <button data-testid="portal-retry" type="button" :disabled="busy" @click="retry">重试</button></div>
    <template v-if="pageName === 'promoter-overview'">
      <div class="promoter-summary-grid">
        <article class="promoter-stat blue"><span class="stat-icon"><PromoterIcon name="database"/></span><div><span>总充值</span><strong>{{ money(overview?.paidAmount4) }}</strong><small>归因资金</small></div><RouterLink to="/promoter/users" aria-label="查看推广用户" class="stat-arrow">›</RouterLink></article>
        <article class="promoter-stat orange"><span class="stat-icon"><PromoterIcon name="cart"/></span><div><span>总消耗</span><strong>{{ money(overview?.settledPaidAmount4) }}</strong><small>归因资金</small></div><RouterLink to="/promoter/consumption" aria-label="查看消费明细" class="stat-arrow">›</RouterLink></article>
        <article class="promoter-stat purple"><span class="stat-icon"><PromoterIcon name="gift"/></span><div><span>总佣金</span><strong>{{ money(overview?.totalCommissionAmount4) }}</strong><small>提现佣金 {{ money(overview?.commission?.withdrawn) }}</small></div><RouterLink to="/promoter/settlements" aria-label="查看佣金" class="stat-arrow">›</RouterLink></article>
      </div>
      <div class="promoter-funds">
        <article class="admin-card green"><span class="stat-icon"><PromoterIcon name="lock"/></span><span>冻结金额</span><strong>{{ money(overview?.commission?.frozen) }}</strong><RouterLink to="/promoter/settlements" aria-label="查看冻结金额" class="stat-arrow">›</RouterLink></article>
        <article class="admin-card blue"><span class="stat-icon"><PromoterIcon name="refresh"/></span><span>释放金额</span><strong>{{ money(overview?.releasedCommissionAmount4) }}</strong><RouterLink to="/promoter/settlements" aria-label="查看释放金额" class="stat-arrow">›</RouterLink></article>
      </div>
      <div class="detail-grid promoter-context-grid"><article class="admin-card invitation-count"><span class="stat-icon blue"><PromoterIcon name="users"/></span><span>邀请人数</span><strong>{{ overview?.downlineCount ?? '—' }}</strong></article><article class="admin-card invitation-link"><PromotionLink :code="overview?.promoter?.invitationCode" /></article></div>
      <form class="admin-card record-date-filter" aria-label="记录日期筛选" @submit.prevent="queryRecords"><span class="date-filter-title"><PromoterIcon name="calendar"/>时间筛选</span>
        <label>开始时间<input v-model="recordDates.from" name="from" type="date" :max="recordDates.to || undefined"></label>
        <label>结束时间<input v-model="recordDates.to" name="to" type="date" :min="recordDates.from || undefined"></label>
        <button class="primary-action" type="submit" :disabled="loading">查询</button><div class="range-presets"><button v-for="days in [7,30,90]" :key="days" type="button" :disabled="loading" @click="recentRange(days)">{{ days }}天</button></div>
      </form>
      <div class="financial-records-grid">
        <article class="admin-card financial-record-card recharge-records" aria-labelledby="recharge-records-title">
          <header><h2 id="recharge-records-title"><PromoterIcon name="database"/>充值记录</h2><div class="record-total"><span>总充值（元）</span><strong>{{ money(recharges?.totalAmount4) }}</strong></div></header>
          <ul class="financial-record-list" aria-label="充值记录">
            <li v-for="item in recharges?.items ?? []" :key="item.orderId ?? ''">
              <div class="record-line"><time>{{ recordTime(item.occurredAt) }}</time><strong class="record-amount">{{ money(item.paidAmount4) }}</strong></div>
              <details class="record-details"><summary>查看详情</summary><span>{{ shown(item.nickname) }} · {{ shown(item.maskedPhone) }}</span><span>冻结 {{ money(item.commissionAmount4) }}</span><small>{{ shown(item.orderId) }}</small></details>
            </li>
          </ul>
          <p v-if="!loading && !error && !recharges?.items?.length" class="records-empty">暂无充值记录</p>
          <nav class="record-pagination" aria-label="充值记录分页"><button :disabled="loading || rechargePage === 0" @click="rechargePage--">上一页</button><span>第 {{ rechargePage + 1 }} 页 · {{ recharges?.total ?? 0 }} 条</span><button :disabled="loading || (rechargePage + 1) * pageSize >= (recharges?.total ?? 0)" @click="rechargePage++">下一页</button></nav>
        </article>
        <article class="admin-card financial-record-card consumption-records" aria-labelledby="consumption-records-title">
          <header><h2 id="consumption-records-title"><PromoterIcon name="cart"/>消费记录</h2><div class="record-total"><span>总消费（元）</span><strong>{{ money(consumption?.totalAmount4) }}</strong></div></header>
          <ul class="financial-record-list" aria-label="消费记录">
            <li v-for="item in consumption?.items ?? []" :key="item.allocationId ?? ''">
              <div class="record-line"><time>{{ recordTime(item.occurredAt) }}</time><strong class="record-amount">{{ money(item.paidAmount4) }}</strong></div>
              <details class="record-details"><summary>查看详情</summary><span>{{ shown(item.nickname) }} · {{ shown(item.maskedPhone) }}</span><span>{{ typeName(item.type) }} · 释放 {{ money(item.commissionAmount4) }}</span><small>{{ shown(item.allocationId) }}</small></details>
            </li>
          </ul>
          <p v-if="!loading && !error && !consumption?.items?.length" class="records-empty">暂无消费记录</p>
          <nav class="record-pagination" aria-label="首页消费分页"><button :disabled="loading || consumptionPage === 0" @click="consumptionPage--">上一页</button><span>第 {{ consumptionPage + 1 }} 页 · {{ consumption?.total ?? 0 }} 条</span><button :disabled="loading || (consumptionPage + 1) * pageSize >= (consumption?.total ?? 0)" @click="consumptionPage++">下一页</button></nav>
        </article>
        <article class="admin-card financial-record-card withdrawal-records" aria-labelledby="withdrawal-records-title">
          <header><h2 id="withdrawal-records-title"><PromoterIcon name="gift"/>发放记录</h2><div class="record-total"><span>已发放总额（元）</span><strong>{{ money(paidMonths?.totalAmount4) }}</strong></div></header>
          <ul class="financial-record-list" aria-label="发放记录">
            <li v-if="!loading && !error && overview" class="disbursement-pending-row">
              <div class="record-line"><time>{{ pendingMonth }}</time><strong class="record-amount">{{ money(pendingDisbursement) }}</strong></div>
              <button type="button" disabled class="disbursement-status disbursement-pending">待发放</button>
            </li>
            <li v-for="item in paidMonths?.items ?? []" :key="item.month ?? ''">
              <div class="record-line"><time :datetime="item.month ?? undefined">{{ monthLabel(item.month) }}</time><strong class="record-amount">{{ money(item.amount4) }}</strong></div>
              <button type="button" disabled class="disbursement-status disbursement-paid">已发放</button>
            </li>
          </ul>
          <nav class="record-pagination" aria-label="发放记录分页"><button :disabled="loading || paidMonthPage === 0" @click="paidMonthPage--">上一页</button><span>第 {{ paidMonthPage + 1 }} 页 · {{ paidMonths?.total ?? 0 }} 个月</span><button :disabled="loading || (paidMonthPage + 1) * pageSize >= (paidMonths?.total ?? 0)" @click="paidMonthPage++">下一页</button></nav>
        </article>
      </div>
    </template>
    <template v-else-if="pageName === 'promoter-users'">
      <div class="promoter-summary-grid">
        <article class="promoter-stat blue"><span class="stat-icon"><PromoterIcon name="user"/></span><div><span>已邀请用户</span><strong>{{ downlines?.total ?? '—' }}</strong></div></article>
        <article class="promoter-stat green"><span class="stat-icon"><PromoterIcon name="user-plus"/></span><div><span>今天注册</span><strong>{{ registrations?.today ?? '—' }}</strong></div></article>
        <article class="promoter-stat purple"><span class="stat-icon"><PromoterIcon name="user-plus"/></span><div><span>昨天注册</span><strong>{{ registrations?.yesterday ?? '—' }}</strong></div></article>
      </div>
      <article class="admin-card promoter-table-card">
        <header class="sr-only"><h2>邀请用户</h2></header>
        <div class="promoter-filters"><label class="search-box"><PromoterIcon name="search"/><input v-model="search" aria-label="搜索本页用户" placeholder="搜索本页用户昵称或手机号"/></label><select v-model="userStatus" aria-label="本页用户状态"><option value="">本页全部状态</option><option value="ENABLED">已启用</option><option value="DISABLED">已停用</option></select></div>
        <div class="table-wrap"><table><caption class="sr-only">邀请用户</caption><thead><tr><th>用户</th><th>手机号</th><th>注册时间</th><th>状态</th><th>操作</th></tr></thead><tbody>
          <tr v-for="(user,index) in visibleUsers" :key="`${user.maskedUserId}-${index}`"><td><div class="user-cell"><span class="promoter-avatar"><PromoterIcon name="user"/></span><div><strong>{{ shown(user.nickname) }}</strong><small>{{ shown(user.maskedUserId) }}</small></div></div></td><td>{{ shown(user.maskedPhone) }}</td><td>{{ recordTime(user.registeredAt) }}</td><td><span class="user-status" :class="{enabled:user.accountStatus === 'ENABLED'}">{{ user.accountStatus === 'ENABLED' ? '已启用' : user.accountStatus === 'DISABLED' ? '已停用' : '—' }}</span></td><td><details class="row-details"><summary>查看详情 <span aria-hidden="true">›</span></summary><dl><dt>用户类型</dt><dd>{{ shown(user.accountType) }}</dd><dt>充值金额</dt><dd>{{ money(user.totalRechargeAmount4) }}</dd></dl></details></td></tr>
          <tr v-if="!loading && !error && !visibleUsers.length"><td colspan="5" class="table-empty">本页暂无邀请用户</td></tr>
        </tbody></table></div>
        <nav class="table-toolbar" aria-label="推广用户分页"><span class="pagination-total">共 {{ downlines?.total ?? '—' }} 条记录</span><button :disabled="loading || downlinePage === 0" @click="downlinePage--">上一页</button><span class="page-number">{{ downlinePage + 1 }}</span><button :disabled="loading || (downlinePage + 1) * pageSize >= (downlines?.total ?? 0)" @click="downlinePage++">下一页</button></nav>
      </article>
    </template>
    <template v-else-if="pageName === 'promoter-consumption'">
      <div class="promoter-summary-grid">
        <article class="promoter-stat green"><span class="stat-icon"><PromoterIcon name="card"/></span><div><span>今日消费</span><strong>{{ money(daily[0]?.amount) }}</strong></div></article>
        <article class="promoter-stat blue"><span class="stat-icon"><PromoterIcon name="document"/></span><div><span>今日订单</span><strong>{{ daily[0]?.orders ?? '—' }}</strong></div></article>
        <article class="promoter-stat purple"><span class="stat-icon"><PromoterIcon name="database"/></span><div><span>今日佣金</span><strong>{{ commissionMoney(daily[0]?.commission) }}</strong></div></article>
      </div>
      <article class="admin-card promoter-table-card">
        <form class="promoter-filters" aria-label="消费日期筛选" @submit.prevent="queryRecords"><label class="search-box"><PromoterIcon name="search"/><input v-model="search" aria-label="搜索本页消费用户" placeholder="搜索本页用户昵称或手机号"/></label><select v-model="consumptionType" aria-label="本页消费类型"><option value="">本页全部消费类型</option><option value="IMAGE">图片</option><option value="VIDEO">视频</option></select><div class="date-inputs"><input v-model="recordDates.from" name="from" type="date" aria-label="开始日期" :max="recordDates.to || undefined"/><span>—</span><input v-model="recordDates.to" name="to" type="date" aria-label="结束日期" :min="recordDates.from || undefined"/></div><button type="submit" :disabled="loading">查询</button></form>
        <div class="table-wrap"><table><caption class="sr-only">推广用户消费明细</caption><thead><tr><th>用户</th><th>消费类型</th><th>消费金额</th><th>佣金</th><th>积分扣减</th><th>时间</th><th>操作</th></tr></thead><tbody>
          <tr v-for="item in visibleConsumption" :key="item.allocationId ?? ''"><td><div class="user-cell"><span class="promoter-avatar"><PromoterIcon name="user"/></span><div><strong>{{ shown(item.nickname) }}</strong><small>{{ shown(item.maskedUserId) }}</small></div></div></td><td><span class="type-badge" :class="{purple:item.type === 'IMAGE'}">{{ typeName(item.type) }}</span></td><td>{{ money(item.paidAmount4) }}</td><td>{{ money(item.commissionAmount4) }}</td><td>{{ points(item.points4) }}</td><td>{{ recordTime(item.occurredAt) }}</td><td><details class="row-details"><summary>查看详情 <span aria-hidden="true">›</span></summary><dl><dt>记录</dt><dd>{{ shown(item.allocationId) }}</dd><dt>手机号</dt><dd>{{ shown(item.maskedPhone) }}</dd><dt>变动类型</dt><dd>消费 / 佣金释放</dd><dt>变动前余额（人民币）</dt><dd data-testid="balance-before">{{ money(item.balanceBeforeAmount4) }}</dd><dt>变动后余额（人民币）</dt><dd data-testid="balance-after">{{ money(item.balanceAfterAmount4) }}</dd><dt>任务前积分余额</dt><dd>{{ points(item.balanceBeforePoints4) }}</dd><dt>任务后积分余额</dt><dd>{{ points(item.balanceAfterPoints4) }}</dd></dl></details></td></tr>
          <tr v-if="!loading && !error && !visibleConsumption.length"><td colspan="7" class="table-empty">暂无消费记录</td></tr>
        </tbody></table></div>
        <nav class="table-toolbar" aria-label="消费记录分页"><span class="pagination-total">共 {{ consumption?.total ?? '—' }} 条记录</span><button :disabled="loading || consumptionPage === 0" @click="consumptionPage--">上一页</button><span class="page-number">{{ consumptionPage + 1 }}</span><button :disabled="loading || (consumptionPage + 1) * pageSize >= (consumption?.total ?? 0)" @click="consumptionPage++">下一页</button></nav>
      </article>
    </template>
    <template v-else-if="pageName === 'promoter-settlements'">
      <div class="metrics-grid promoter-summary-grid">
        <article class="promoter-stat green"><span class="stat-icon"><PromoterIcon name="database"/></span><div><span>累计佣金</span><strong>{{ money(overview?.totalCommissionAmount4) }}</strong><small>自推广以来获得的总佣金</small></div></article>
        <article class="promoter-stat blue"><span class="stat-icon"><PromoterIcon name="chart"/></span><div><span>今日佣金</span><strong>{{ commissionMoney(daily[0]?.commission) }}</strong><small>较昨日 <span class="daily-change" :class="{positive:dailyChange(0).startsWith('+'),negative:dailyChange(0).startsWith('-')}">{{ dailyChange(0) }} {{ dailyChange(0).startsWith('+') ? '▲' : dailyChange(0).startsWith('-') ? '▼' : '' }}</span></small></div></article>
        <article class="promoter-stat purple"><span class="stat-icon"><PromoterIcon name="chart"/></span><div><span>昨日佣金</span><strong>{{ commissionMoney(daily[1]?.commission) }}</strong><small>较前日 <span class="daily-change" :class="{positive:dailyChange(1).startsWith('+'),negative:dailyChange(1).startsWith('-')}">{{ dailyChange(1) }} {{ dailyChange(1).startsWith('+') ? '▲' : dailyChange(1).startsWith('-') ? '▼' : '' }}</span></small></div></article>
      </div>
    </template>
    <template v-if="pageName === 'promoter-account'">
      <article class="admin-card payout-card">
        <h2>收款账户</h2>
        <form @submit.prevent="savePayoutMethod"><fieldset :disabled="loading || busy || !!pendingSave || !!error"><legend>收款账户</legend>
          <div class="payout-fields">
            <label>持卡人姓名<input data-testid="payout-account-name" :placeholder="selectedAccount?.maskedPayoutAccountName || '请输入持卡人姓名'" v-model="draft.name" maxlength="100" autocomplete="off"></label>
            <label>银行卡号<input data-testid="payout-bank-card" :placeholder="selectedAccount?.maskedPayoutAccountReference || '请输入银行卡号'" v-model="draft.reference" inputmode="numeric" maxlength="32" autocomplete="off"></label>
            <label>开户银行<input data-testid="payout-bank-name" list="promoter-banks" :placeholder="selectedAccount?.bankName || '请选择开户银行'" v-model="draft.bankName" maxlength="100" autocomplete="off"></label>
          </div>
          <datalist id="promoter-banks"><option v-for="bank in banks" :key="bank" :value="bank"/></datalist>
          <button class="primary-action" data-testid="save-payout-method" type="button" :disabled="!payouts" @click="savePayoutMethod">{{ saving ? '保存中…' : '保存' }}</button>
        </fieldset></form>
        <button v-if="pendingSave && !saving" data-testid="payout-retry" type="button" :disabled="loading || busy || !!error" @click="savePayoutMethod">重试原收款申请</button>
        <details v-if="(payouts?.total ?? 0) > 1" class="payout-existing"><summary>已有收款账户</summary>
          <label>银行卡<select v-model="selectedAccountId" :disabled="loading || busy || !!pendingSave" @change="chooseAccount"><option value="">新建收款账户</option><option v-for="account in bankAccounts" :key="account.id ?? ''" :value="account.id">{{ account.maskedPayoutAccountReference }} · {{ account.status === 'ACTIVE' ? '已启用' : '已停用' }}</option></select></label>
          <nav v-if="(payouts?.total ?? 0) > pageSize" class="table-toolbar" aria-label="收款账户分页"><button :disabled="loading || busy || !!pendingSave || payoutPage === 0" @click="selectedAccountId = ''; chooseAccount(); payoutPage--">上一页收款账户</button><span>第 {{ payoutPage + 1 }} 页 · {{ payouts?.total ?? 0 }} 个</span><button :disabled="loading || busy || !!pendingSave || (payoutPage + 1) * pageSize >= (payouts?.total ?? 0)" @click="selectedAccountId = ''; chooseAccount(); payoutPage++">下一页收款账户</button></nav>
        </details>
      </article>
      <p v-if="notice" class="notice" role="status">{{ notice }}</p>
      <section class="admin-card promoter-account-info"><h2>账户信息</h2><div class="detail-grid">
        <article class="admin-card"><span>用户</span><strong>{{ shown(profile?.nickname) }}</strong></article>
        <article class="admin-card"><span>登录身份</span><strong>{{ session.currentAdmin.value?.username }}</strong><small>PROMOTER</small></article>
        <article class="admin-card"><span>账户状态</span><strong class="account-status" :class="{enabled:accountStatus === '正常',disabled:accountStatus === '已停用'}">{{ accountStatus }}</strong></article>
      </div></section>
    </template>
    <article v-if="pageName === 'promoter-settlements'" class="admin-card record-card"><header><div><h2>{{ recordTitle }}</h2><p>按实际审核、打款结果展示；按本地月份筛选全部记录。</p></div><label class="month-filter">筛选月份<input data-testid="settlement-withdrawal-month" v-model="withdrawalMonth" type="month"></label></header><div class="table-wrap"><table><caption class="sr-only">{{ recordTitle }}</caption><thead><tr><th>申请时间</th><th>发放金额</th><th>状态</th><th>记录号</th><th>收款账户</th></tr></thead><tbody><tr v-for="item in withdrawals?.items ?? []" :key="item.id ?? ''"><td>{{ when(item.requestedAt) }}</td><td>{{ money(item.amount) }}</td><td><button type="button" disabled class="disbursement-status" :class="{ 'disbursement-paid': item.status === 'PAID', 'disbursement-pending': item.status === 'PENDING_PAYOUT' }">{{ disbursementStatus(item.status) }}</button><small v-if="item.rejectionReason">{{ item.rejectionReason }}</small></td><td>{{ shown(item.id) }}</td><td>{{ shown(item.maskedPayoutAccountNo) }}</td></tr><tr v-if="!loading && !error && !withdrawals?.items?.length"><td colspan="5" class="table-empty payout-empty"><span class="empty-record-icon"><PromoterIcon name="document"/></span><strong>{{ withdrawalMonth ? `该月份暂无${recordTitle}` : `暂无${recordTitle}` }}</strong></td></tr></tbody></table></div><nav class="table-toolbar" :aria-label="`${recordTitle}分页`"><button :disabled="loading || busy || withdrawalPage === 0" @click="withdrawalPage--">上一页</button><span>第 {{ withdrawalPage + 1 }} 页 · {{ withdrawals?.total ?? 0 }} 条</span><button :disabled="loading || busy || (withdrawalPage + 1) * pageSize >= (withdrawals?.total ?? 0)" @click="withdrawalPage++">下一页</button></nav></article>
  </section>
</template>
