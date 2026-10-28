<script setup lang="ts">
import { formatAmount } from '../../../shared/formatAmount';
import { inject, onMounted, onUnmounted, ref } from "vue";
import {
  adminReconciliationAdapterKey,
  copyValidReconciliationDryRunPreview,
  copyValidReconciliationSnapshot,
  InvalidReconciliationDecimalError,
  type AdminReconciliationAdapter,
  type ReconciledBalances,
  type ReconciliationAnomalyType,
  type ReconciliationDryRunPreview,
  type ReconciliationSnapshot
} from "./localReconciliationFixtures";

const injectedAdapter = inject(adminReconciliationAdapterKey);
if (!injectedAdapter) throw new Error("PromotionReconciliationPanel requires a reconciliation adapter");
const adapter: AdminReconciliationAdapter = injectedAdapter;
const snapshot = ref<ReconciliationSnapshot | null>(null);
const preview = ref<ReconciliationDryRunPreview | null>(null);
const loading = ref(true);
const querying = ref(false);
const dryRunning = ref(false);
const type = ref<ReconciliationAnomalyType>("ALL");
const keyword = ref("");
const reason = ref("");
const fieldError = ref("");
const feedback = ref("");
let queryLocked = false;
let dryRunLocked = false;
let active = true;
onUnmounted(() => { active = false; });
const dimensionLabels: Record<string, string> = { frozen4: '冻结佣金', available4: '可用佣金', withdrawing4: '发放预留', withdrawn4: '已发放佣金', cash_debt4: '现金待追回', FROZEN: '冻结佣金', AVAILABLE: '可用佣金', WITHDRAWING: '发放预留', WITHDRAWN: '已发放佣金', CASH_DEBT: '现金待追回' };
const balances = ref<ReconciledBalances | null>(null), balancePreview = ref<ReconciledBalances | null>(null);
async function loadBalances(preview = false) {
  if (querying.value || !adapter.balances) return;
  if (preview && !reason.value.trim()) { fieldError.value = '必须填写管理员原因'; return; }
  querying.value = true; feedback.value = ''; fieldError.value = '';
  try {
    const result = preview ? await adapter.previewBalances!(reason.value.trim()) : await adapter.balances();
    if (!active) return;
    if (result.dryRun !== true || result.executable !== false) throw new Error('Invalid preview');
    if (preview) balancePreview.value = result; else balances.value = result;
  } catch { if (active) feedback.value = '对账加载失败，请重试'; }
  finally { if (active) { querying.value = false; loading.value = false; } }
}

const typeLabels = {
  PENDING_DEPENDENCY: "依赖待处理",
  CASH_DEBT: "现金欠账",
  POINT_DEBT: "积分欠账"
};
const statusLabels = { OPEN: "待处理", REVIEWING: "复核中" };

async function load() {
  if (adapter.balances) { await loadBalances(); return; }
  loading.value = true;
  feedback.value = "";
  try {
    snapshot.value = copyValidReconciliationSnapshot(await adapter.load!());
  } catch {
    snapshot.value = null;
  } finally {
    loading.value = false;
  }
}

onMounted(load);

async function submitFilters() {
  if (queryLocked) return;
  queryLocked = true;
  querying.value = true;
  feedback.value = "";
  try {
    snapshot.value = copyValidReconciliationSnapshot(await adapter.query!({ type: type.value, keyword: keyword.value }));
  } catch (error) {
    feedback.value = error instanceof InvalidReconciliationDecimalError
      ? "金额格式错误，已保留原快照"
      : "筛选失败，已保留原快照";
  } finally {
    queryLocked = false;
    querying.value = false;
  }
}

async function runDryRun(anomalyId: string) {
  const submittedReason = reason.value.trim();
  fieldError.value = submittedReason ? "" : "必须填写管理员原因";
  if (fieldError.value || dryRunLocked) return;
  dryRunLocked = true;
  dryRunning.value = true;
  feedback.value = "";
  try {
    preview.value = copyValidReconciliationDryRunPreview(await adapter.repairDryRun!(anomalyId, submittedReason));
  } catch {
    feedback.value = "预演失败，已保留原快照与上次预览";
  } finally {
    dryRunLocked = false;
    dryRunning.value = false;
  }
}
</script>

<template>
  <template v-if="adapter.balances">
    <button type="button" class="secondary-action" :disabled="querying" @click="loadBalances()">刷新</button>
    <p v-if="loading">推广收益对账加载中…</p>
    <template v-if="balances">
      <dl class="reconciliation-summary"><div><dt>账实差异</dt><dd>{{ balances.differences.length }}</dd></div><div><dt>待到账奖励</dt><dd>{{ balances.pendingRewardDeliveries }}</dd></div><div><dt>待追回账户</dt><dd>{{ balances.debts.length }}</dd></div></dl>
      <div class="table-wrap"><table><caption>账实差异</caption><thead><tr><th>用户</th><th>账户维度</th><th>账本值</th><th>账户值</th><th>差额</th></tr></thead><tbody><tr v-for="item in balances.differences" :key="item.userId + item.dimension"><td>{{ item.userId }}</td><td>{{ dimensionLabels[item.dimension] ?? item.dimension }}</td><td>{{ item.expected4 }}</td><td>{{ item.actual4 }}</td><td>{{ item.difference4 }}</td></tr><tr v-if="!balances.differences.length"><td colspan="5">暂无账实差异</td></tr></tbody></table></div>
      <div class="table-wrap"><table><caption>待追回余额</caption><thead><tr><th>用户</th><th>单位</th><th>待追回</th></tr></thead><tbody><tr v-for="item in balances.debts" :key="item.userId + item.unit"><td>{{ item.userId }}</td><td>{{ item.unit === 'CASH' ? '现金' : '邀请积分' }}</td><td>{{ item.amount4 }}</td></tr><tr v-if="!balances.debts.length"><td colspan="3">暂无待追回余额</td></tr></tbody></table></div>
      <form @submit.prevent="loadBalances(true)"><label>管理员原因<input v-model="reason" aria-label="管理员原因" maxlength="2000"></label><button type="submit" class="secondary-action" :disabled="querying">修复预演</button><p v-if="fieldError" role="alert">{{ fieldError }}</p></form>
      <div v-if="balancePreview" data-testid="reconciliation-dry-run-preview"><h3>修复预演</h3><p>{{ balancePreview.generatedAt }}</p><dl><div v-for="item in balancePreview.differences" :key="item.userId + item.dimension"><dt>{{ item.userId }} · {{ dimensionLabels[item.dimension] ?? item.dimension }}</dt><dd>{{ item.actual4 }} → {{ item.expected4 }}</dd></div></dl><p v-if="!balancePreview.differences.length">暂无账实差异</p></div>
    </template>
  </template>
  <template v-else>
  <p v-if="loading" data-testid="reconciliation-loading">推广收益对账加载中…</p>
  <div v-else-if="!snapshot" class="reconciliation-load-error" data-testid="reconciliation-load-error">
    <p>对账快照加载失败</p>
    <button class="secondary-action" data-testid="reconciliation-retry" type="button" @click="load">重试</button>
  </div>
  <template v-else>
    <form class="reconciliation-filters" data-testid="reconciliation-filter-form" @submit.prevent="submitFilters">
      <label>异常类型
        <select v-model="type" aria-label="异常类型">
          <option value="ALL">全部异常</option>
          <option value="PENDING_DEPENDENCY">依赖待处理</option>
          <option value="CASH_DEBT">现金欠账</option>
          <option value="POINT_DEBT">积分欠账</option>
        </select>
      </label>
      <label>用户 / 订单 / 资金批次关键词
        <input v-model="keyword" aria-label="对账关键词" placeholder="脱敏用户、订单或资金批次标识">
      </label>
      <button class="primary-action" type="submit" :disabled="querying">{{ querying ? "筛选中…" : "筛选" }}</button>
    </form>

    <dl class="reconciliation-summary">
      <div data-testid="reconciliation-total"><dt>异常总数</dt><dd>{{ snapshot.summary.total }}</dd></div>
      <div data-testid="reconciliation-pending-dependency"><dt>依赖待处理</dt><dd>{{ snapshot.summary.pendingDependency }}</dd></div>
      <div data-testid="reconciliation-cash-debt"><dt>现金欠账</dt><dd>{{ snapshot.summary.cashDebt }}</dd></div>
      <div data-testid="reconciliation-point-debt"><dt>积分欠账</dt><dd>{{ snapshot.summary.pointDebt }}</dd></div>
    </dl>

    <label class="reconciliation-reason">管理员原因
      <input v-model="reason" aria-label="管理员原因" autocomplete="off" placeholder="dry-run 必填，不会执行修复">
    </label>
    <p v-if="fieldError" class="field-error" role="alert">{{ fieldError }}</p>

    <div class="table-wrap reconciliation-table">
      <table>
        <caption>推广收益对账差异</caption>
        <thead><tr><th>类型 / 状态</th><th>脱敏主体</th><th>标识</th><th>期望值</th><th>实际值</th><th>差额</th><th>检测时间</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="entry in snapshot.entries" :key="entry.id" :data-testid="`reconciliation-row-${entry.id}`">
            <td><strong>{{ typeLabels[entry.type] }}</strong><small>{{ statusLabels[entry.status] }}</small></td>
            <td>{{ entry.maskedSubject }}</td>
            <td><strong>来源 {{ entry.sourceId }}</strong><small>订单 {{ entry.orderId ?? "—" }} · 批次 {{ entry.lotId ?? "—" }} · 分配 {{ entry.allocationId ?? "—" }}</small></td>
            <td>{{ entry.expected4 }}</td><td>{{ entry.actual4 }}</td><td>{{ formatAmount(entry.difference4) }}</td><td>{{ entry.detectedAt }}</td>
            <td><button class="table-action" type="button" :data-testid="`reconciliation-dry-run-${entry.id}`" :disabled="dryRunning" @click="runDryRun(entry.id)">{{ dryRunning ? "预演中…" : "修复 dry-run" }}</button></td>
          </tr>
          <tr v-if="!snapshot.entries.length"><td colspan="8" data-testid="reconciliation-empty">当前筛选没有异常</td></tr>
        </tbody>
      </table>
    </div>

    <aside v-if="preview" class="reconciliation-preview" data-testid="reconciliation-dry-run-preview">
      <header><strong>repair dry-run · 不可执行</strong><span>{{ preview.generatedAt }}</span></header>
      <p>异常 {{ preview.anomalyId }}；此预览不会修复或写账。</p>
      <dl><div v-for="change in preview.changes" :key="change.label"><dt>{{ change.label }}</dt><dd>{{ formatAmount(change.before4) }} → {{ formatAmount(change.after4) }} · 差额 {{ formatAmount(change.difference4) }}</dd></div></dl>
    </aside>
  </template>
  </template>
  <p v-if="feedback" class="notice withdrawal-feedback-error" role="status" aria-live="polite">{{ feedback }}</p>
</template>
