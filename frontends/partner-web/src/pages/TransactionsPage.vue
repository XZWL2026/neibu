<script setup lang="ts">
import { formatAmount } from '../../../shared/formatAmount';

import { computed, ref } from "vue";
import { type PromotionFinanceAdapter, type PromotionFinanceLevel } from "../promotion/localFinanceFixtures";

const props = defineProps<{ adapter: PromotionFinanceAdapter }>();
const snapshot = props.adapter.getSnapshot();
const level = ref<"ALL" | PromotionFinanceLevel>("ALL");
const status = ref<"ALL" | "SUCCESS">("ALL");
const transactions = computed(() => snapshot.transactions.filter((item) =>
  (level.value === "ALL" || item.level === level.value)
  && (status.value === "ALL" || item.status === status.value)));
</script>

<template>
  <section class="partner-page" aria-labelledby="transaction-title">
    <header class="page-heading"><div><p class="eyebrow">SUCCESSFUL CONSUMPTION</p><h1 id="transaction-title">消费明细</h1><p>只展示最终成功且未退款的任务；处理中与失败任务不进入收益明细。</p></div><span class="local-chip">本地 fixture</span></header>
    <section class="finance-grid ledger-summary-grid" aria-label="消费合计">
      <article class="finance-card" data-testid="successful-points-total"><span>服务端成功消费积分</span><strong>{{ formatAmount(snapshot.transactionTotals.successfulPoints4) }}</strong><small>不随筛选重算</small></article>
      <article class="finance-card" data-testid="transaction-cash-total"><span>对应现金佣金</span><strong>¥{{ formatAmount(snapshot.transactionTotals.cashCommission4) }}</strong><small>不随筛选重算</small></article>
    </section>
    <div class="ledger-filter-bar">
      <label>层级<select v-model="level" aria-label="筛选消费层级"><option value="ALL">全部层级</option><option value="LEVEL_1">LEVEL_1</option><option value="LEVEL_2">LEVEL_2</option></select></label>
      <label>状态<select v-model="status" aria-label="筛选消费状态"><option value="ALL">全部状态</option><option value="SUCCESS">最终成功</option></select></label>
    </div>
    <div class="referral-table-wrap ledger-table-wrap"><table><caption>最终成功消费记录</caption><thead><tr><th>用户</th><th>层级</th><th>任务 / 结算号</th><th>成功消费积分</th><th>现金佣金</th><th>资金来源</th><th>状态</th><th>时间</th></tr></thead><tbody>
      <tr v-for="item in transactions" :key="item.id" class="transaction-row" :data-testid="item.fundingType === 'REFERRAL_REWARD' ? 'transaction-reward-row' : undefined"><td>{{ item.maskedUser }}</td><td><span class="source-badge">{{ item.level }}</span></td><td>{{ item.taskSettlementRef }}</td><td>{{ formatAmount(item.successfulPoints4) }}</td><td>¥{{ formatAmount(item.cashCommission4) }}</td><td>{{ item.fundingType }}</td><td>最终成功</td><td>{{ item.occurredAt }}</td></tr>
      <tr v-if="!transactions.length"><td colspan="8">当前筛选没有消费明细</td></tr>
    </tbody></table></div>
  </section>
</template>
