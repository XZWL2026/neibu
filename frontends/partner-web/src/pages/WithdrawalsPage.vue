<script setup lang="ts">
import { randomUUID } from '../../../shared/randomUUID';
import { formatAmount } from '../../../shared/formatAmount';

import { computed, onUnmounted, ref } from "vue";
import type { LocalWithdrawalBalanceView, LocalWithdrawalItemView, LocalWithdrawalView, WithdrawalAdapter, WithdrawalRequest } from "../promotion/localWithdrawalFixtures";

const props = defineProps<{ view: LocalWithdrawalView; adapter: WithdrawalAdapter }>();
const snapshot = props.adapter.getSnapshot();
const balance = ref<LocalWithdrawalBalanceView>({ ...snapshot.balance });
const withdrawals = ref<LocalWithdrawalItemView[]>(snapshot.items.map((item) => ({ ...item })));
const amount = ref(snapshot.unresolvedRequest?.amount2 ?? "");
const error = ref("");
const notice = ref("");
const submitting = ref(false);
let submitLocked = false;
const unresolvedRequest = ref<WithdrawalRequest | null>(snapshot.unresolvedRequest ? { ...snapshot.unresolvedRequest } : null);
let active = true;
onUnmounted(() => { active = false; });

const statusLabels = { WITHDRAWING: "审核中", PENDING_PAYOUT: "待发放", REJECTED: "已拒绝", PAID: "已发放" } as const;
const hasCashDebt = computed(() => !/^0(?:\.0*)?$/.test(balance.value.cashDebt4));

function scaled4(value: string) {
  const [whole, fraction = ""] = value.split(".");
  return BigInt(whole) * 10_000n + BigInt((fraction + "0000").slice(0, 4));
}

function normalizeAmount2(value: string) {
  const [whole, fraction = ""] = value.split(".");
  return `${whole}.${(fraction + "00").slice(0, 2)}`;
}

function formatCash2(value: string) {
  return `¥${formatAmount(value)}`;
}

function validateAmount(value: string) {
  if (!/^(0|[1-9]\d*)(?:\.\d{1,2})?$/.test(value)) return "提现金额最多保留两位小数";
  if (scaled4(value) < scaled4(balance.value.minimumAmount2)) return `最低提现金额为 ${formatCash2(balance.value.minimumAmount2)}`;
  if (scaled4(value) > scaled4(balance.value.available4)) return "提现金额不能超过可提现佣金";
  return "";
}

async function submitWithdrawal() {
  if (submitLocked || (hasCashDebt.value && !unresolvedRequest.value)) return;
  let request = unresolvedRequest.value;
  if (!request) {
    const validationError = validateAmount(amount.value.trim());
    if (validationError) {
      error.value = validationError;
      notice.value = "";
      return;
    }
    request = { amount2: normalizeAmount2(amount.value.trim()), idempotencyKey: randomUUID() };
    unresolvedRequest.value = request;
    props.adapter.setUnresolvedRequest(request);
  }
  submitLocked = true;
  submitting.value = true;
  error.value = "";
  notice.value = "";
  try {
    const result = await props.adapter.submitWithdrawal(request);
    props.adapter.setUnresolvedRequest(null);
    if (!active) return;
    balance.value = { ...result.balance };
    withdrawals.value.unshift({ ...result.withdrawal });
    unresolvedRequest.value = null;
    amount.value = "";
    notice.value = "提现申请已提交，可提现与提现中金额已刷新";
  } catch {
    if (active) error.value = "提现申请提交失败，请重试";
  } finally {
    submitLocked = false;
    if (active) submitting.value = false;
  }
}
</script>

<template>
  <section class="partner-page withdrawal-page" aria-labelledby="withdrawal-title">
    <header class="page-heading"><div><p class="eyebrow">COMMISSION WITHDRAWAL</p><h1 id="withdrawal-title">佣金提现</h1><p>提现申请提交后立即占用可提现佣金；审核通过后进入待发放，由管理员在线下发放完成后确认状态。</p></div><span class="local-chip">本地 fixture</span></header>

    <section class="finance-grid" aria-label="佣金余额">
      <article class="finance-card"><span>冻结佣金</span><strong>¥{{ formatAmount(balance.frozen4) }}</strong></article>
      <article class="finance-card" data-testid="available-balance"><span>可提现佣金</span><strong>¥{{ formatAmount(balance.available4) }}</strong></article>
      <article class="finance-card" data-testid="withdrawing-balance"><span>提现中</span><strong>¥{{ formatAmount(balance.withdrawing4) }}</strong><small>含审核中与待发放</small></article>
      <article class="finance-card"><span>累计已提现</span><strong>¥{{ formatAmount(balance.cumulativePaid4) }}</strong></article>
    </section>

    <p v-if="hasCashDebt" class="cash-debt-alert" role="alert">存在现金待追偿，暂时无法提现（¥{{ formatAmount(balance.cashDebt4) }}）</p>

    <form class="withdrawal-form" novalidate @submit.prevent="submitWithdrawal">
      <div><h2>申请提现</h2><p>最低提现金额 ¥{{ formatAmount(balance.minimumAmount2) }}，金额最多保留两位小数。</p></div>
      <label for="withdrawal-amount">提现金额</label>
      <input id="withdrawal-amount" v-model="amount" aria-label="提现金额" inputmode="decimal" autocomplete="off" placeholder="100" :disabled="submitting || Boolean(unresolvedRequest)" :aria-invalid="Boolean(error)" :aria-describedby="error ? 'withdrawal-error' : undefined">
      <p v-if="error" id="withdrawal-error" class="field-error" role="alert">{{ error }}</p>
      <button type="submit" :disabled="submitting || (hasCashDebt && !unresolvedRequest)">{{ submitting ? "提交中…" : "提交提现申请" }}</button>
    </form>
    <p v-if="notice" class="withdrawal-notice" role="status" aria-live="polite">{{ notice }}</p>

    <div class="withdrawal-table-wrap">
      <table><caption>提现记录</caption><thead><tr><th>提现单号</th><th>金额</th><th>状态</th><th>拒绝原因</th><th>申请时间</th></tr></thead><tbody>
        <tr v-for="item in withdrawals" :key="item.id"><td>{{ item.id }}</td><td>¥{{ formatAmount(item.amount2) }}</td><td><span class="withdrawal-status">{{ statusLabels[item.status] }}</span></td><td>{{ item.status === "REJECTED" ? item.rejectionReason : "—" }}</td><td>{{ item.createdAt }}</td></tr>
        <tr v-if="!withdrawals.length"><td colspan="5">暂无提现记录</td></tr>
      </tbody></table>
    </div>
  </section>
</template>
