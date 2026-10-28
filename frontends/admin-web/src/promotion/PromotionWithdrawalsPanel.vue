<script setup lang="ts">
import { formatAmount } from '../../../shared/formatAmount';

import { inject, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import AdminSearchSelect from "../components/AdminSearchSelect.vue";
import { adminWithdrawalAdapterKey, type AdminWithdrawalAdapter, type AdminWithdrawalView, type PayoutAccountDetails } from "./localWithdrawalFixtures";

const injectedAdapter = inject(adminWithdrawalAdapterKey);
if (!injectedAdapter) throw new Error("PromotionWithdrawalsPanel requires a withdrawal adapter");
const adapter: AdminWithdrawalAdapter = injectedAdapter;

const withdrawals = ref<AdminWithdrawalView[]>((adapter.getSnapshot?.() ?? []).map((item) => ({ ...item })));
const rejectionReasons = ref<Record<string, string>>({});
const rejectionErrors = ref<Record<string, string>>({});
const confirmingPaidId = ref<string | null>(null);
const feedback = ref("");
const feedbackFailed = ref(false);
const busy = ref(false);
let actionLocked = false;
let active = true;
const loading = ref(false);
const listPage = ref(0), listTotal = ref(0), listSize = ref(20), filterStatus = ref(''), filterOwner = ref(''), filterMonth = ref('');
const existingId = ref<string | undefined>();
const owner = ref(''), month = ref(''), amount = ref(''), accountId = ref('');
const owners = ref<{ value: string; label: string }[]>([]), accounts = ref<{ value: string; label: string }[]>([]);
const ownerTotal = ref(0), accountTotal = ref(0), ownerPage = ref(0), accountPage = ref(0), ownerQuery = ref('');
const ownerLoading = ref(false), accountLoading = ref(false);
const details = ref<PayoutAccountDetails | null>(null), detailsId = ref('');
const transferReference = ref(''), paidAt = ref('');
let ownerRevision = 0, accountRevision = 0;
async function searchOwners(query = '', more = false) {
  if (!adapter.promoters) return;
  const revision = ++ownerRevision; ownerLoading.value = true;
  if (!more) { ownerPage.value = 0; ownerQuery.value = query; }
  try { const result = await adapter.promoters(ownerQuery.value, ownerPage.value); if (active && revision === ownerRevision) { owners.value = more ? [...owners.value, ...result.items] : result.items; ownerTotal.value = result.total; ownerPage.value++; } }
  catch { if (active) { feedback.value = '推广人加载失败，请重试'; feedbackFailed.value = true; } }
  finally { if (active && revision === ownerRevision) ownerLoading.value = false; }
}
async function loadAccounts(more = false) {
  const selected = owner.value, revision = ++accountRevision;
  if (!adapter.accounts || !selected) return;
  accountLoading.value = true; if (!more) accountPage.value = 0;
  try { const result = await adapter.accounts(selected, accountPage.value); if (active && revision === accountRevision) { accounts.value = more ? [...accounts.value, ...result.items] : result.items; accountTotal.value = result.total; accountPage.value++; } }
  catch { if (active && revision === accountRevision) { feedback.value = '收款账户加载失败，请重试'; feedbackFailed.value = true; } }
  finally { if (active && revision === accountRevision) accountLoading.value = false; }
}
watch(owner, () => { accountRevision++; accounts.value = []; accountId.value = ''; accountTotal.value = 0; if (owner.value) void loadAccounts(); });
watch(confirmingPaidId, () => { transferReference.value = ''; paidAt.value = ''; });
async function reveal(id: string) {
  if (!adapter.account || busy.value) return;
  busy.value = true; details.value = null; detailsId.value = id;
  try { const result = await adapter.account(id); if (active && detailsId.value === id) details.value = result; }
  catch { if (active) { feedback.value = '收款快照加载失败，请重试'; feedbackFailed.value = true; } }
  finally { if (active) busy.value = false; }
}
async function reopen(item: AdminWithdrawalView) {
  if (!item.settlementMonth || !item.payoutAccountId || busy.value) return;
  existingId.value = item.id; owner.value = item.promoter; month.value = item.settlementMonth;
  const [whole, fraction = ''] = item.amount.split('.'); amount.value = `${whole}.${(fraction + '00').slice(0, 2)}`;
  if (!owners.value.some(row => row.value === item.promoter)) owners.value.unshift({ value: item.promoter, label: item.promoter });
  await nextTick(); accountId.value = item.payoutAccountId;
}
async function create() {
  if (!adapter.create || busy.value) return;
  if (!owner.value || !/^\d{4}-\d{2}$/.test(month.value) || !/^(?:0|[1-9]\d*)(?:\.\d{1,2})?$/.test(amount.value) || Number(amount.value) <= 0 || !accountId.value) {
    feedback.value = '请选择推广人、月份和收款账户，并填写正数两位以内金额'; feedbackFailed.value = true; return;
  }
  busy.value = true; feedback.value = '';
  try { await adapter.create(owner.value, month.value, amount.value, accountId.value, existingId.value); if (active) { amount.value = ''; existingId.value = undefined; await load(0); } }
  catch { if (active) { feedback.value = '创建失败，请重试'; feedbackFailed.value = true; } }
  finally { if (active) busy.value = false; }
}
onUnmounted(() => { active = false; });
async function load(page = 0) {
  if (!adapter.load && !adapter.query) return;
  loading.value = true; feedback.value = '';
  details.value = null; detailsId.value = '';
  try {
    if (adapter.query) { const result = await adapter.query({ status: filterStatus.value, promoterUserId: filterOwner.value.trim(), month: filterMonth.value, page }); if (active) { withdrawals.value = result.items; listPage.value = result.page; listSize.value = result.size; listTotal.value = result.total; } }
    else { const result = await adapter.load!(); if (active) withdrawals.value = [...result]; }
  }
  catch { if (active) { feedback.value = '付款单加载失败，请重试'; feedbackFailed.value = true; } }
  finally { if (active) loading.value = false; }
}
onMounted(() => { void load(); if (adapter.create) void searchOwners(); });

const statusLabels = {
  WITHDRAWING: "审核中",
  PENDING_PAYOUT: "待发放",
  PAID: "已发放",
  REJECTED: "已拒绝"
} as const;

function replaceWithdrawal(updated: AdminWithdrawalView) {
  const index = withdrawals.value.findIndex((item) => item.id === updated.id);
  if (index >= 0) withdrawals.value.splice(index, 1, { ...updated });
}

function operationKey(action: "approve" | "reject" | "mark-paid", id: string) {
  return `withdrawal-operation-${action}-${id}`;
}

async function run(action: () => Promise<AdminWithdrawalView>, success: string) {
  if (actionLocked) return;
  actionLocked = true;
  busy.value = true;
  feedback.value = "";
  feedbackFailed.value = false;
  try {
    const result = await action();
    if (!active) return;
    replaceWithdrawal(result);
    feedback.value = success;
  } catch {
    if (!active) return;
    feedback.value = "操作失败，请重试";
    feedbackFailed.value = true;
  } finally {
    actionLocked = false;
    busy.value = false;
  }
}

function approve(id: string) {
  return run(() => adapter.approve(id, operationKey("approve", id)), adapter.create ? "发放单已通过，等待线下发放" : "提现申请已通过，等待线下发放");
}

async function reject(id: string) {
  const reason = rejectionReasons.value[id]?.trim() ?? "";
  if (!reason) {
    rejectionErrors.value[id] = "必须填写拒绝原因";
    feedback.value = "";
    return;
  }
  delete rejectionErrors.value[id];
  await run(() => adapter.reject(id, reason, operationKey("reject", id)), adapter.create ? "发放单已拒绝" : "提现申请已拒绝");
  const rejected = withdrawals.value.find((item) => item.id === id)?.status === "REJECTED";
  if (rejected) delete rejectionReasons.value[id];
}

async function markPaid(id: string) {
  if (adapter.create && (!transferReference.value.trim() || !paidAt.value || !Number.isFinite(new Date(paidAt.value).getTime()))) { feedback.value = '请填写转账参考号和实际付款时间'; feedbackFailed.value = true; return; }
  await run(() => adapter.markPaid(id, operationKey("mark-paid", id), adapter.create ? { transferReference: transferReference.value.trim(), paidAt: new Date(paidAt.value).toISOString() } : undefined), adapter.create ? "发放单已标记为已发放" : "提现申请已标记为已发放");
  if (withdrawals.value.find((item) => item.id === id)?.status === "PAID") confirmingPaidId.value = null;
}
</script>

<template>
<form v-if="adapter.create" class="admin-card promotion-attribution-form payout-create-form" @submit.prevent="create">
    <h2 class="attribution-form-heading">{{ existingId ? "重新提交月度发放单" : "创建月度发放单" }}</h2>
    <div class="attribution-field"><label for="payout-owner">推广人</label><AdminSearchSelect id="payout-owner" v-model="owner" label="推广人" :options="owners" remote :loading="ownerLoading" :has-more="ownerPage * 20 < ownerTotal" :disabled="busy || !!existingId" @query="searchOwners($event)" @load-more="searchOwners('', true)" /></div>
    <label class="attribution-field">归属月份<input v-model="month" aria-label="归属月份" type="month" :disabled="busy || !!existingId" required></label>
    <label class="attribution-field">发放金额<input v-model="amount" aria-label="发放金额" inputmode="decimal" :disabled="busy" required></label>
    <label class="attribution-field">收款账户<select v-model="accountId" aria-label="收款账户" :disabled="busy || accountLoading" required><option value="">请选择</option><option v-for="item in accounts" :key="item.value" :value="item.value">{{ item.label }}</option></select></label>
    <div class="attribution-form-footer">
    <div class="payout-create-secondary">
    <button v-if="owner" type="button" :disabled="accountLoading || busy" @click="loadAccounts()">刷新收款账户</button><button v-if="accountPage * 20 < accountTotal" type="button" :disabled="accountLoading || busy" @click="loadAccounts(true)">更多收款账户</button>
    <button v-if="existingId" type="button" :disabled="busy" @click="existingId = undefined; amount = ''">取消重新提交</button>
    </div>
    <button class="primary-action" type="submit" :disabled="busy || accountLoading">{{ existingId ? "重新提交发放单" : "创建发放单" }}</button>
    </div>
  </form>
  <form v-if="adapter.query" class="withdrawal-report-filters" @submit.prevent="load(0)">
    <label>状态<select v-model="filterStatus" aria-label="发放状态"><option value="">全部</option><option v-for="(label, value) in statusLabels" :key="value" :value="value">{{ label }}</option></select></label><label>推广人编号<input v-model="filterOwner" aria-label="推广人编号"></label><label>归属月份<input v-model="filterMonth" aria-label="筛选归属月份" type="month"></label><button type="submit" :disabled="loading || busy">查询</button>
  </form>
  <div class="table-wrap withdrawal-review-table">
    <table>
      <caption class="sr-only">提现审核列表</caption>
      <thead><tr><th>推广人</th><th>金额</th><th>状态</th><th>收款账户</th><th>申请时间</th><th>拒绝原因</th><th>操作</th></tr></thead>
      <tbody>
        <tr v-for="item in withdrawals" :key="item.id" :data-testid="`row-${item.id}`">
          <td><strong>{{ item.promoter }}</strong><small>{{ item.id }}</small></td>
          <td class="withdrawal-amount">{{ formatAmount(item.amount) }}</td>
          <td><span class="status" :data-testid="`status-${item.id}`">{{ statusLabels[item.status] }}</span></td>
          <td>{{ item.payoutAccountSnapshot }}<button v-if="adapter.account" type="button" class="table-action" :disabled="busy" @click="reveal(item.id)">查看收款快照</button></td>
          <td>{{ item.requestedAt }}<small v-if="item.settlementMonth">{{ item.settlementMonth }}</small></td>
          <td>{{ item.rejectionReason ?? "—" }}</td>
          <td>
            <div v-if="item.status === 'WITHDRAWING'" class="withdrawal-review-actions">
              <button class="table-action" type="button" :disabled="busy" :data-testid="`approve-${item.id}`" @click="approve(item.id)">通过</button>
              <div class="withdrawal-rejection-field">
                <label :for="`rejection-${item.id}`">拒绝原因</label>
                <input :id="`rejection-${item.id}`" v-model="rejectionReasons[item.id]" :aria-label="`${item.id} 拒绝原因`" :aria-invalid="Boolean(rejectionErrors[item.id])" :aria-describedby="rejectionErrors[item.id] ? `rejection-error-${item.id}` : undefined" :disabled="busy">
                <small v-if="rejectionErrors[item.id]" :id="`rejection-error-${item.id}`" :data-testid="`rejection-error-${item.id}`" role="alert">{{ rejectionErrors[item.id] }}</small>
              </div>
              <button class="table-action danger-action" type="button" :disabled="busy" :data-testid="`reject-${item.id}`" @click="reject(item.id)">拒绝</button>
            </div>
            <div v-else-if="item.status === 'PENDING_PAYOUT'" class="withdrawal-review-actions withdrawal-paid-actions">
              <button v-if="confirmingPaidId !== item.id" class="table-action" type="button" :disabled="busy" :data-testid="`mark-paid-${item.id}`" @click="confirmingPaidId = item.id">标记已发放</button>
              <template v-else>
                <label v-if="adapter.create">转账参考号<input v-model="transferReference" aria-label="转账参考号" :disabled="busy" maxlength="256"></label>
                <label v-if="adapter.create">实际付款时间<input v-model="paidAt" aria-label="实际付款时间" type="datetime-local" :disabled="busy"></label>
                <span :data-testid="`paid-confirmation-${item.id}`">请在线下款已实际完成后再操作。</span>
                <button class="table-action" type="button" :disabled="busy" :data-testid="`confirm-paid-${item.id}`" @click="markPaid(item.id)">确认已发放</button>
                <button class="table-action danger-action" type="button" :disabled="busy" @click="confirmingPaidId = null">取消</button>
              </template>
            </div>
            <button v-else-if="adapter.create && item.status === 'REJECTED' && item.settlementMonth" type="button" class="table-action" :disabled="busy" @click="reopen(item)">重新提交</button>
            <span v-else>已处理<small v-if="item.transferReference">{{ item.transferReference }} · {{ item.paidAt }}</small></span>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
  <div v-if="adapter.query" class="account-actions"><button type="button" :disabled="!listPage || loading || busy" @click="load(listPage - 1)">上一页</button><span>共 {{ listTotal }} 笔</span><button type="button" :disabled="(listPage + 1) * listSize >= listTotal || loading || busy" @click="load(listPage + 1)">下一页</button></div>
  <section v-if="details" aria-label="收款快照"><h2>收款快照</h2><dl><div><dt>收款人</dt><dd>{{ details.accountName }}</dd></div><div><dt>账号</dt><dd>{{ details.accountReference }}</dd></div><div v-if="details.bankName"><dt>银行</dt><dd>{{ details.bankName }}</dd></div><div v-if="details.qrCodeReference"><dt>收款码</dt><dd>{{ details.qrCodeReference }}</dd></div><div><dt>快照时间</dt><dd>{{ details.snapshotAt }}</dd></div></dl><button type="button" @click="details = null; detailsId = ''">关闭收款快照</button></section>
  <p v-if="feedback" class="notice" :class="{ 'withdrawal-feedback-error': feedbackFailed }" role="status" aria-live="polite">{{ feedback }}</p>
</template>

<style scoped>
.payout-create-form h2 { margin: 0; }
.payout-create-form .attribution-field > input,
.payout-create-form .attribution-field > select { width: 100%; min-width: 0; min-height: 44px; box-sizing: border-box; }
.payout-create-secondary { display: flex; flex-wrap: wrap; gap: 10px; }
</style>
