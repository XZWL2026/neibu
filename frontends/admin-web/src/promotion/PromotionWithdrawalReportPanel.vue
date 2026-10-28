<script setup lang="ts">
import { formatAmount } from '../../../shared/formatAmount';

import { inject, onMounted, onUnmounted, ref } from "vue";
import {
  adminWithdrawalReportAdapterKey,
  copyValidWithdrawalReportSnapshot,
  InvalidWithdrawalReportDecimalError,
  type AdminWithdrawalReportAdapter,
  type WithdrawalReportSnapshot,
  type WithdrawalReportStatus
} from "./localWithdrawalReportFixtures";

const injectedAdapter = inject(adminWithdrawalReportAdapterKey);
if (!injectedAdapter) throw new Error("PromotionWithdrawalReportPanel requires a report adapter");
const adapter: AdminWithdrawalReportAdapter = injectedAdapter;

const snapshot = ref<WithdrawalReportSnapshot | null>(adapter.getSnapshot ? copyValidWithdrawalReportSnapshot(adapter.getSnapshot()) : null);
const status = ref<WithdrawalReportStatus>("ALL");
const promoterKeyword = ref("");
const startDate = ref("");
const endDate = ref("");
const month = ref("");
const dateError = ref("");
const feedback = ref("");
const busy = ref(false);
let queryLocked = false;
let active = true;
onUnmounted(() => { active = false; });
onMounted(async () => {
  if (!adapter.load) return;
  busy.value = true;
  try { const result = await adapter.load(); if (active) snapshot.value = copyValidWithdrawalReportSnapshot(result); }
  catch { if (active) feedback.value = '报表加载失败，请重试'; }
  finally { if (active) busy.value = false; }
});

const statusLabels = {
  WITHDRAWING: "审核中",
  PENDING_PAYOUT: "待发放",
  PAID: "已发放",
  REJECTED: "已拒绝"
} as const;

async function submitReport(page = 0) {
  if (queryLocked) return;
  dateError.value = "";
  feedback.value = "";
  if (startDate.value && endDate.value && startDate.value > endDate.value) {
    dateError.value = "开始日期不能晚于结束日期";
    return;
  }
  queryLocked = true;
  busy.value = true;
  try {
    const result = await adapter.query({
      status: status.value,
      promoterKeyword: promoterKeyword.value,
      startDate: startDate.value,
      endDate: endDate.value,
      ...(adapter.live ? { month: month.value, page } : {})
    });
    if (active) snapshot.value = copyValidWithdrawalReportSnapshot(result);
  } catch (error) {
    if (!active) return;
    feedback.value = error instanceof InvalidWithdrawalReportDecimalError
      ? "报表金额格式错误，已保留上次报表"
      : "查询失败，已保留上次报表";
  } finally {
    queryLocked = false;
    busy.value = false;
  }
}
</script>

<template>


  <form class="withdrawal-report-filters" @submit.prevent="submitReport()">
    <label for="withdrawal-report-status">提现状态
      <select id="withdrawal-report-status" v-model="status" aria-label="提现状态">
        <option value="ALL">全部状态</option>
        <option value="WITHDRAWING">审核中</option>
        <option value="PENDING_PAYOUT">待发放</option>
        <option value="PAID">已发放</option>
        <option value="REJECTED">已拒绝</option>
      </select>
    </label>
    <label for="withdrawal-report-keyword">{{ adapter.live ? "推广人编号" : "推广人关键词" }}
      <input id="withdrawal-report-keyword" v-model="promoterKeyword" :aria-label="adapter.live ? '推广人编号' : '推广人关键词'" :placeholder="adapter.live ? '推广人编号' : '推广人名称或编号'">
    </label>
    <label v-if="!adapter.live" for="withdrawal-report-start">开始日期
      <input id="withdrawal-report-start" v-model="startDate" aria-label="开始日期" type="date">
    </label>
    <label v-if="!adapter.live" for="withdrawal-report-end">结束日期
      <input id="withdrawal-report-end" v-model="endDate" aria-label="结束日期" type="date">
    </label>
    <label v-if="adapter.live">归属月份<input v-model="month" aria-label="报表归属月份" type="month"></label>
    <button class="primary-action" type="submit" :disabled="busy">{{ busy ? "查询中…" : "查询报表" }}</button>
    <p v-if="dateError" class="field-error" role="alert">{{ dateError }}</p>
  </form>

  <template v-if="snapshot"><dl class="withdrawal-report-summary">
    <div data-testid="report-total"><dt>总申请</dt><dd>¥ {{ formatAmount(snapshot.summary.total.amount4) }}</dd><small>{{ snapshot.summary.total.count }} 笔</small></div>
    <div data-testid="report-withdrawing"><dt>审核中</dt><dd>¥ {{ formatAmount(snapshot.summary.withdrawing.amount4) }}</dd><small>{{ snapshot.summary.withdrawing.count }} 笔</small></div>
    <div data-testid="report-pending-payout"><dt>待发放</dt><dd>¥ {{ formatAmount(snapshot.summary.pendingPayout.amount4) }}</dd><small>{{ snapshot.summary.pendingPayout.count }} 笔</small></div>
    <div data-testid="report-paid"><dt>已发放</dt><dd>¥ {{ formatAmount(snapshot.summary.paid.amount4) }}</dd><small>{{ snapshot.summary.paid.count }} 笔</small></div>
    <div data-testid="report-rejected"><dt>已拒绝</dt><dd>¥ {{ formatAmount(snapshot.summary.rejected.amount4) }}</dd><small>{{ snapshot.summary.rejected.count }} 笔</small></div>
  </dl>

  <div class="table-wrap withdrawal-report-table">
    <table>
      <caption>提现报表明细</caption>
      <thead><tr><th>申请编号</th><th>推广人</th><th>金额</th><th>状态</th><th>申请日期</th><th v-if="adapter.live">归属月份</th><th v-if="adapter.live">转账记录</th></tr></thead>
      <tbody>
        <tr v-for="entry in snapshot.entries" :key="entry.id" :data-testid="`row-${entry.id}`">
          <td>{{ entry.id }}</td><td>{{ entry.promoter }}</td><td>¥ {{ formatAmount(entry.amount4) }}</td><td><span class="status">{{ statusLabels[entry.status] }}</span></td><td>{{ entry.requestedDate }}</td><td v-if="adapter.live">{{ entry.settlementMonth ?? "—" }}</td><td v-if="adapter.live">{{ entry.transferReference ?? "—" }}<small>{{ entry.paidAt ?? "—" }}</small></td>
        </tr>
        <tr v-if="!snapshot.entries.length"><td :colspan="adapter.live ? 7 : 5">当前筛选没有提现明细</td></tr>
      </tbody>
    </table>
  </div>
    <div v-if="adapter.live" class="account-actions"><button type="button" :disabled="!snapshot.page || busy" @click="submitReport((snapshot.page ?? 0) - 1)">上一页</button><span>共 {{ snapshot.total }} 笔</span><button type="button" :disabled="((snapshot.page ?? 0) + 1) * (snapshot.size ?? 20) >= (snapshot.total ?? 0) || busy" @click="submitReport((snapshot.page ?? 0) + 1)">下一页</button></div>
  </template>
  <p v-if="feedback" class="notice withdrawal-feedback-error" role="status" aria-live="polite">{{ feedback }}</p>
</template>
