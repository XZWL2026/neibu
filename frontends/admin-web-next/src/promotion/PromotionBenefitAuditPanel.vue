<script setup lang="ts">
import { formatAmount } from '../shared/formatAmount';
import { inject, onMounted, onUnmounted, ref } from "vue";
import {
  adminBenefitAuditAdapterKey,
  benefitAuditActionLabels,
  benefitAuditReasonLabels,
  copyValidBenefitAuditSnapshot,
  InvalidBenefitAuditDecimalError,
  type AdminBenefitAuditAdapter,
  type AuditRecords,
  type BenefitAuditActionFilter,
  type BenefitAuditSnapshot,
  type BenefitAuditUnit
} from "./localBenefitAuditFixtures";

const injectedAdapter = inject(adminBenefitAuditAdapterKey);
if (!injectedAdapter) throw new Error("PromotionBenefitAuditPanel requires a benefit audit adapter");
const adapter: AdminBenefitAuditAdapter = injectedAdapter;
const snapshot = ref<BenefitAuditSnapshot | null>(null);
const loading = ref(true);
const querying = ref(false);
const unit = ref<BenefitAuditUnit>("ALL");
const action = ref<BenefitAuditActionFilter>("ALL");
const userKeyword = ref("");
const referenceKeyword = ref("");
const startDate = ref("");
const endDate = ref("");
const fieldError = ref("");
const feedback = ref("");
let queryLocked = false;
let active = true;
onUnmounted(() => { active = false; });
const records = ref<AuditRecords | null>(null);
const actionLabels: Record<string, string> = { COMMISSION_FROZEN: '佣金冻结', COMMISSION_RELEASED: '佣金释放', CONSUMPTION_RELEASE_REVERSED: '消费佣金冲回', RECHARGE_REFUND_CLAWBACK: '充值退款追偿', COMMISSION_RATE_CORRECTION: '佣金比例更正', WITHDRAWAL_REQUESTED: '发放预留', WITHDRAWAL_REJECTED: '发放拒绝', WITHDRAWAL_COMPENSATED: '发放退回', WITHDRAWAL_PAID: '线下已发放', GRANT: '奖励发放', REVERSE: '奖励冲回' };
const postingLabels: Record<string, string> = { POSTED: '已入账', DELIVERED: '已入账', PENDING: '待入账' };
async function loadRecords(page = 0) {
  if (!adapter.records || querying.value) return;
  querying.value = true; feedback.value = '';
  try { const result = await adapter.records({ unit: unit.value, userId: userKeyword.value.trim(), reference: referenceKeyword.value.trim(), page }); if (active) records.value = result; }
  catch { if (active) feedback.value = '收益审计加载失败，请重试'; }
  finally { if (active) { querying.value = false; loading.value = false; } }
}

const unitLabels = { CASH: "现金", POINT: "邀请积分" } as const;
const tierLabels = { DIRECT: "直接推广", INDIRECT: "间接推广" } as const;

async function load() {
  if (adapter.records) { await loadRecords(); return; }
  loading.value = true;
  feedback.value = "";
  try {
    snapshot.value = copyValidBenefitAuditSnapshot(await adapter.load!());
  } catch {
    snapshot.value = null;
  } finally {
    loading.value = false;
  }
}

onMounted(load);

async function submitFilters() {
  fieldError.value = startDate.value && endDate.value && startDate.value > endDate.value ? "开始日期不能晚于结束日期" : "";
  if (fieldError.value || queryLocked) return;
  queryLocked = true;
  querying.value = true;
  feedback.value = "";
  try {
    snapshot.value = copyValidBenefitAuditSnapshot(await adapter.query!({
      unit: unit.value,
      action: action.value,
      userKeyword: userKeyword.value,
      referenceKeyword: referenceKeyword.value,
      startDate: startDate.value,
      endDate: endDate.value
    }));
  } catch (error) {
    feedback.value = error instanceof InvalidBenefitAuditDecimalError
      ? "审计数值格式错误，已保留上次快照"
      : "查询失败，已保留上次快照";
  } finally {
    queryLocked = false;
    querying.value = false;
  }
}
</script>

<template>
  <template v-if="adapter.records">
    <form class="benefit-audit-filters" @submit.prevent="loadRecords()">
      <label>单位<select v-model="unit" aria-label="审计单位"><option value="ALL">全部</option><option value="CASH">现金</option><option value="POINT">邀请积分</option></select></label>
      <label>用户编号<input v-model="userKeyword" aria-label="用户编号"></label>
      <label>来源编号<input v-model="referenceKeyword" aria-label="来源编号"></label>
      <button type="submit" class="primary-action" :disabled="querying">查询</button>
    </form>
    <p v-if="loading">推广收益审计加载中…</p>
    <template v-if="records">
      <div class="table-wrap benefit-audit-table"><table><caption>推广收益明细</caption><thead><tr><th>单位</th><th>受益用户</th><th>动作</th><th>金额 / 积分</th><th>状态</th><th>来源 / 订单</th><th>时间</th></tr></thead><tbody>
        <tr v-for="entry in records.items" :key="entry.id" :data-testid="`benefit-audit-row-${entry.id}`"><td>{{ entry.unit === 'CASH' ? '现金' : '邀请积分' }}</td><td>{{ entry.beneficiaryUserId }}</td><td>{{ actionLabels[entry.action] ?? entry.action }}</td><td>{{ formatAmount(entry.amount4) }}</td><td>{{ postingLabels[entry.status ?? ''] ?? entry.status ?? '—' }}</td><td>{{ entry.sourceEventId ?? '—' }}<small>{{ entry.orderId ?? '—' }} · {{ entry.fundingLotId ?? '—' }} · {{ entry.allocationId ?? '—' }} · {{ entry.withdrawalId ?? '—' }}</small></td><td>{{ entry.occurredAt }}</td></tr>
        <tr v-if="!records.items.length"><td colspan="7">当前筛选没有收益明细</td></tr>
      </tbody></table></div>
      <div class="account-actions"><button type="button" :disabled="!records.page || querying" @click="loadRecords(records.page - 1)">上一页</button><span>共 {{ records.total }} 条</span><button type="button" :disabled="(records.page + 1) * records.size >= records.total || querying" @click="loadRecords(records.page + 1)">下一页</button></div>
    </template>
  </template>
  <template v-else>
  <p v-if="loading" data-testid="benefit-audit-loading">推广收益审计加载中…</p>
  <div v-else-if="!snapshot" class="benefit-audit-load-error" data-testid="benefit-audit-load-error" role="alert">
    <p>推广收益审计加载失败</p>
    <button class="secondary-action" data-testid="benefit-audit-retry" type="button" @click="load">重试</button>
  </div>
  <template v-else>
    <form class="benefit-audit-filters" data-testid="benefit-audit-filter-form" @submit.prevent="submitFilters">
      <label>单位
        <select v-model="unit" aria-label="审计单位"><option value="ALL">全部（分别统计）</option><option value="CASH">现金</option><option value="POINT">邀请积分</option></select>
      </label>
      <label>动作
        <select v-model="action" aria-label="审计动作"><option value="ALL">全部动作</option><option v-for="(label, value) in benefitAuditActionLabels" :key="value" :value="value">{{ label }}</option></select>
      </label>
      <label>脱敏用户关键词<input v-model="userKeyword" aria-label="脱敏用户关键词" placeholder="例如 N***a 或 U***388"></label>
      <label>订单 / 批次关键词<input v-model="referenceKeyword" aria-label="订单批次关键词" placeholder="订单或 lot 标识"></label>
      <label>开始日期<input v-model="startDate" aria-label="审计开始日期" type="date"></label>
      <label>结束日期<input v-model="endDate" aria-label="审计结束日期" type="date"></label>
      <button class="primary-action" type="submit" :disabled="querying">{{ querying ? "筛选中…" : "筛选" }}</button>
      <p v-if="fieldError" class="field-error" role="alert">{{ fieldError }}</p>
    </form>

    <dl class="benefit-audit-summary">
      <div data-testid="benefit-audit-cash-count"><dt>现金审计明细</dt><dd>{{ snapshot.summary.cashCount }} 条</dd></div>
      <div data-testid="benefit-audit-point-count"><dt>邀请积分审计明细</dt><dd>{{ snapshot.summary.pointCount }} 条</dd></div>
      <div data-testid="benefit-audit-cash-debt-count"><dt>现金 debt 记录</dt><dd>{{ snapshot.summary.cashDebtCount }} 条</dd></div>
      <div data-testid="benefit-audit-point-debt-count"><dt>point debt 记录</dt><dd>{{ snapshot.summary.pointDebtCount }} 条</dd></div>
    </dl>

    <div class="table-wrap benefit-audit-table">
      <table>
        <caption>推广收益不可变审计明细（现金与邀请积分不混合求和）</caption>
        <thead><tr><th>动作 / 单位</th><th>脱敏受益人</th><th>来源标识</th><th>before</th><th>after</th><th>delta</th><th>现金层级</th><th>原因</th><th>时间</th></tr></thead>
        <tbody>
          <tr v-for="entry in snapshot.entries" :key="entry.id" :data-testid="`benefit-audit-row-${entry.id}`">
            <td><strong>{{ benefitAuditActionLabels[entry.action] }}</strong><small>{{ entry.action }} · {{ unitLabels[entry.unit] }}</small></td>
            <td>{{ entry.maskedBeneficiary }}</td>
            <td><strong>sourceEvent {{ entry.sourceEventId }}</strong><small>order {{ entry.orderId ?? "—" }} · lot {{ entry.lotId ?? "—" }}</small><small>allocation {{ entry.allocationId ?? "—" }} · withdrawal {{ entry.withdrawalId ?? "—" }}</small></td>
            <td class="audit-decimal">{{ unitLabels[entry.unit] }} · {{ formatAmount(entry.before4) }}</td>
            <td class="audit-decimal">{{ unitLabels[entry.unit] }} · {{ formatAmount(entry.after4) }}</td>
            <td class="audit-decimal">{{ unitLabels[entry.unit] }} · {{ formatAmount(entry.delta4) }}</td>
            <td><template v-if="entry.cashTier">{{ tierLabels[entry.cashTier] }}<small>{{ entry.cashTier }}</small></template><template v-else>—</template></td>
            <td><strong>{{ benefitAuditReasonLabels[entry.reason] }}</strong><small>{{ entry.reason }}</small></td>
            <td>{{ entry.occurredAt }}</td>
          </tr>
          <tr v-if="!snapshot.entries.length"><td colspan="9" data-testid="benefit-audit-empty">当前筛选没有审计明细</td></tr>
        </tbody>
      </table>
    </div>
  </template>
  </template>
  <p v-if="feedback" class="notice withdrawal-feedback-error" role="status" aria-live="polite">{{ feedback }}</p>
</template>
