<script setup lang="ts">
import { formatAmount } from '../../../shared/formatAmount';

import { computed, ref } from "vue";
import { type CommissionAction, type PromotionFinanceAdapter, type PromotionFinanceLevel } from "../promotion/localFinanceFixtures";

const props = defineProps<{ adapter: PromotionFinanceAdapter }>();
const snapshot = props.adapter.getSnapshot();
const level = ref<"ALL" | PromotionFinanceLevel>("ALL");
const action = ref<"ALL" | CommissionAction>("ALL");
const entries = computed(() => snapshot.commissionEntries.filter((item) =>
  (level.value === "ALL" || item.level === level.value)
  && (action.value === "ALL" || item.action === action.value)));
const actionLabels: Record<CommissionAction, string> = {
  FROZEN: "冻结",
  RELEASED: "释放",
  REVERSED: "冲回",
  WITHDRAWAL_HELD: "提现占用",
  WITHDRAWAL_REJECTED: "拒绝退回",
  PAID: "已发放",
  CASH_DEBT: "现金待追偿",
  DEBT_OFFSET: "抵扣"
};
</script>

<template>
  <section class="partner-page" aria-labelledby="commission-entry-title">
    <header class="page-heading"><div><p class="eyebrow">COMMISSION ENTRIES</p><h1 id="commission-entry-title">佣金明细</h1><p>展示服务端确认的佣金动作与余额快照；筛选不会重算财务合计。</p></div><span class="local-chip">本地 fixture</span></header>
    <section class="finance-grid ledger-summary-grid" aria-label="佣金合计"><article class="finance-card" data-testid="commission-total"><span>服务端佣金合计</span><strong>¥{{ formatAmount(snapshot.commissionTotal4) }}</strong><small>不随筛选重算</small></article></section>
    <div class="ledger-filter-bar">
      <label>层级<select v-model="level" aria-label="筛选佣金层级"><option value="ALL">全部层级</option><option value="LEVEL_1">LEVEL_1</option><option value="LEVEL_2">LEVEL_2</option></select></label>
      <label>动作<select v-model="action" aria-label="筛选佣金动作"><option value="ALL">全部动作</option><option v-for="(label, value) in actionLabels" :key="value" :value="value">{{ label }}</option></select></label>
    </div>
    <div class="referral-table-wrap ledger-table-wrap"><table><caption>佣金变动记录</caption><thead><tr><th>时间</th><th>动作</th><th>金额</th><th>变更前</th><th>变更后</th><th>订单 / 任务</th><th>层级</th></tr></thead><tbody>
      <tr v-for="item in entries" :key="item.id" class="commission-entry-row"><td>{{ item.occurredAt }}</td><td>{{ actionLabels[item.action] }}</td><td>¥{{ formatAmount(item.amount4) }}</td><td>¥{{ formatAmount(item.balanceBefore4) }}</td><td>¥{{ formatAmount(item.balanceAfter4) }}</td><td>{{ item.reference }}</td><td><span class="source-badge">{{ item.level }}</span></td></tr>
      <tr v-if="!entries.length"><td colspan="7">当前筛选没有佣金明细</td></tr>
    </tbody></table></div>
  </section>
</template>
