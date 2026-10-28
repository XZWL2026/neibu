<script setup lang="ts">
import { onUnmounted, ref, watch } from "vue";
import { validatePayoutAccountInput, type LocalPayoutAccountView, type PayoutAccountAdapter, type PayoutAccountFieldErrors, type PayoutAccountInput, type PayoutAccountType } from "../promotion/localPayoutAccountFixtures";

const props = defineProps<{ adapter: PayoutAccountAdapter }>();
const accounts = ref<LocalPayoutAccountView[]>(props.adapter.getSnapshot().map((account) => ({ ...account })));
const type = ref<PayoutAccountType>("BANK_CARD");
const holderName = ref("");
const cardNumber = ref("");
const bankName = ref("");
const realName = ref("");
const account = ref("");
const phone = ref("");
const qrCode = ref("");
const errors = ref<PayoutAccountFieldErrors>({});
const notice = ref("");
const actionError = ref("");
const busy = ref(false);
const editingId = ref<string | null>(null);
const confirmingDisableId = ref<string | null>(null);
let actionLocked = false;
let active = true;
onUnmounted(() => { active = false; });

const typeLabels: Record<PayoutAccountType, string> = { BANK_CARD: "银行卡", ALIPAY: "支付宝", WECHAT: "微信" };
const statusLabels = { ACTIVE: "启用中", DISABLED: "已停用" } as const;

function clearFields() {
  holderName.value = "";
  cardNumber.value = "";
  bankName.value = "";
  realName.value = "";
  account.value = "";
  phone.value = "";
  qrCode.value = "";
  errors.value = {};
  actionError.value = "";
}

watch(type, clearFields);

function input(): PayoutAccountInput {
  if (type.value === "BANK_CARD") {
    return { type: "BANK_CARD", holderName: holderName.value.trim(), cardNumber: cardNumber.value.trim(), bankName: bankName.value.trim() };
  }
  if (type.value === "ALIPAY") {
    return { type: "ALIPAY", realName: realName.value.trim(), account: account.value.trim(), qrCode: qrCode.value.trim() || undefined };
  }
  return { type: "WECHAT", realName: realName.value.trim(), phone: phone.value.trim(), qrCode: qrCode.value.trim() || undefined };
}

function errorFor(field: keyof PayoutAccountFieldErrors) {
  return errors.value[field] || "";
}

function replaceAccount(replacement: LocalPayoutAccountView) {
  const index = accounts.value.findIndex((item) => item.id === replacement.id);
  if (index < 0) accounts.value.unshift({ ...replacement });
  else accounts.value.splice(index, 1, { ...replacement });
}

async function saveAccount() {
  if (actionLocked) return;
  const payload = input();
  const validation = validatePayoutAccountInput(payload);
  errors.value = Object.fromEntries(Object.entries(validation).filter(([, message]) => Boolean(message)));
  if (Object.keys(errors.value).length) {
    notice.value = "";
    return;
  }
  actionLocked = true;
  busy.value = true;
  actionError.value = "";
  notice.value = "";
  try {
    const editing = editingId.value;
    const saved = editing
      ? await props.adapter.updateAccount(editing, payload)
      : await props.adapter.createAccount(payload);
    if (!active) return;
    replaceAccount(saved);
    notice.value = editing ? "收款账户已更新" : "收款账户已添加";
    editingId.value = null;
    clearFields();
  } catch {
    if (active) actionError.value = "保存失败，请检查后重试";
  } finally {
    actionLocked = false;
    if (active) busy.value = false;
  }
}

function editAccount(item: LocalPayoutAccountView) {
  if (actionLocked || item.status !== "ACTIVE") return;
  clearFields();
  type.value = item.type;
  editingId.value = item.id;
  confirmingDisableId.value = null;
  notice.value = "请重新填写完整收款信息，列表不会回显明文";
}

function cancelEdit() {
  editingId.value = null;
  clearFields();
}

async function disableAccount(id: string) {
  if (actionLocked) return;
  actionLocked = true;
  busy.value = true;
  actionError.value = "";
  notice.value = "";
  try {
    const disabled = await props.adapter.disableAccount(id);
    if (!active) return;
    replaceAccount(disabled);
    confirmingDisableId.value = null;
    if (editingId.value === id) {
      editingId.value = null;
      clearFields();
    }
    notice.value = "收款账户已停用";
  } catch {
    if (active) actionError.value = "停用失败，请重试";
  } finally {
    actionLocked = false;
    if (active) busy.value = false;
  }
}
</script>

<template>
  <section class="partner-page payout-account-page" aria-labelledby="payout-account-title">
    <header class="page-heading"><div><p class="eyebrow">PAYOUT ACCOUNTS</p><h1 id="payout-account-title">收款账户</h1><p>管理线下佣金发放使用的银行卡、支付宝或微信账户。列表只保留脱敏展示信息。</p></div><span class="local-chip">本地 fixture</span></header>

    <form class="payout-account-form" novalidate @submit.prevent="saveAccount">
      <div class="payout-form-heading"><div><h2>{{ editingId ? "修改收款账户" : "新增收款账户" }}</h2><p>{{ editingId ? "为保护敏感信息，请重新填写该类型的完整信息。" : "切换类型会清空当前未提交字段。" }}</p></div><button v-if="editingId" type="button" class="secondary-button" :disabled="busy" @click="cancelEdit">取消修改</button></div>

      <label for="payout-account-type">收款类型</label>
      <select id="payout-account-type" v-model="type" aria-label="收款类型" :disabled="busy || Boolean(editingId)"><option value="BANK_CARD">银行卡</option><option value="ALIPAY">支付宝</option><option value="WECHAT">微信</option></select>

      <template v-if="type === 'BANK_CARD'">
        <label for="payout-holder-name">持卡人</label><input id="payout-holder-name" v-model="holderName" aria-label="持卡人" autocomplete="name" :aria-invalid="Boolean(errorFor('holderName'))" :aria-describedby="errorFor('holderName') ? 'payout-holder-name-error' : undefined"><p v-if="errorFor('holderName')" id="payout-holder-name-error" class="field-error" role="alert">{{ errorFor("holderName") }}</p>
        <label for="payout-card-number">银行卡号</label><input id="payout-card-number" v-model="cardNumber" aria-label="银行卡号" inputmode="numeric" autocomplete="cc-number" :aria-invalid="Boolean(errorFor('cardNumber'))" :aria-describedby="errorFor('cardNumber') ? 'payout-card-number-error' : undefined"><p v-if="errorFor('cardNumber')" id="payout-card-number-error" class="field-error" role="alert">{{ errorFor("cardNumber") }}</p>
        <label for="payout-bank-name">开户银行</label><input id="payout-bank-name" v-model="bankName" aria-label="开户银行" autocomplete="organization" :aria-invalid="Boolean(errorFor('bankName'))" :aria-describedby="errorFor('bankName') ? 'payout-bank-name-error' : undefined"><p v-if="errorFor('bankName')" id="payout-bank-name-error" class="field-error" role="alert">{{ errorFor("bankName") }}</p>
      </template>

      <template v-else-if="type === 'ALIPAY'">
        <label for="payout-alipay-name">支付宝实名</label><input id="payout-alipay-name" v-model="realName" aria-label="支付宝实名" autocomplete="name" :aria-invalid="Boolean(errorFor('realName'))" :aria-describedby="errorFor('realName') ? 'payout-alipay-name-error' : undefined"><p v-if="errorFor('realName')" id="payout-alipay-name-error" class="field-error" role="alert">{{ errorFor("realName") }}</p>
        <label for="payout-alipay-account">支付宝账号</label><input id="payout-alipay-account" v-model="account" aria-label="支付宝账号" autocomplete="username" :aria-invalid="Boolean(errorFor('account'))" :aria-describedby="errorFor('account') ? 'payout-alipay-account-error' : undefined"><p v-if="errorFor('account')" id="payout-alipay-account-error" class="field-error" role="alert">{{ errorFor("account") }}</p>
        <label for="payout-alipay-qr">支付宝收款码（可选）</label><input id="payout-alipay-qr" v-model="qrCode" aria-label="支付宝收款码" autocomplete="off" :aria-invalid="Boolean(errorFor('qrCode'))" :aria-describedby="errorFor('qrCode') ? 'payout-alipay-qr-error' : undefined"><p v-if="errorFor('qrCode')" id="payout-alipay-qr-error" class="field-error" role="alert">{{ errorFor("qrCode") }}</p>
      </template>

      <template v-else>
        <label for="payout-wechat-name">微信实名</label><input id="payout-wechat-name" v-model="realName" aria-label="微信实名" autocomplete="name" :aria-invalid="Boolean(errorFor('realName'))" :aria-describedby="errorFor('realName') ? 'payout-wechat-name-error' : undefined"><p v-if="errorFor('realName')" id="payout-wechat-name-error" class="field-error" role="alert">{{ errorFor("realName") }}</p>
        <label for="payout-wechat-phone">绑定手机号</label><input id="payout-wechat-phone" v-model="phone" aria-label="绑定手机号" inputmode="tel" autocomplete="tel" :aria-invalid="Boolean(errorFor('phone'))" :aria-describedby="errorFor('phone') ? 'payout-wechat-phone-error' : undefined"><p v-if="errorFor('phone')" id="payout-wechat-phone-error" class="field-error" role="alert">{{ errorFor("phone") }}</p>
        <label for="payout-wechat-qr">微信收款码（可选）</label><input id="payout-wechat-qr" v-model="qrCode" aria-label="微信收款码" autocomplete="off" :aria-invalid="Boolean(errorFor('qrCode'))" :aria-describedby="errorFor('qrCode') ? 'payout-wechat-qr-error' : undefined"><p v-if="errorFor('qrCode')" id="payout-wechat-qr-error" class="field-error" role="alert">{{ errorFor("qrCode") }}</p>
      </template>

      <button type="submit" class="primary-button" :disabled="busy">{{ busy ? "保存中…" : editingId ? "保存修改" : "保存收款账户" }}</button>
    </form>

    <p v-if="actionError" class="payout-feedback payout-error" role="alert">{{ actionError }}</p>
    <p v-if="notice" class="payout-feedback payout-notice" role="status" aria-live="polite">{{ notice }}</p>

    <div class="payout-account-table-wrap">
      <table><caption>收款账户列表</caption><thead><tr><th>类型</th><th>收款人</th><th>脱敏账号</th><th>收款码</th><th>状态</th><th>操作</th></tr></thead><tbody>
        <tr v-for="item in accounts" :key="item.id" class="payout-account-row"><td>{{ typeLabels[item.type] }}</td><td>{{ item.ownerName }}</td><td>{{ item.maskedAccount }}</td><td>{{ item.qrConfigured ? "已配置" : "未配置" }}</td><td>{{ statusLabels[item.status] }}</td><td><div v-if="item.status === 'ACTIVE'" class="payout-row-actions"><button type="button" class="secondary-button" :disabled="busy" :aria-label="`修改收款账户 ${item.id}`" @click="editAccount(item)">修改</button><button v-if="confirmingDisableId !== item.id" type="button" class="danger-button" :disabled="busy" :aria-label="`停用收款账户 ${item.id}`" @click="confirmingDisableId = item.id">停用</button><span v-else class="disable-confirm"><span>确认停用这个收款账户？</span><button type="button" class="danger-button" :disabled="busy" :aria-label="`确认停用收款账户 ${item.id}`" @click="disableAccount(item.id)">确认停用</button><button type="button" class="secondary-button" :disabled="busy" @click="confirmingDisableId = null">取消</button></span></div><span v-else>不可重复停用</span></td></tr>
        <tr v-if="!accounts.length"><td colspan="6">暂无收款账户</td></tr>
      </tbody></table>
    </div>
  </section>
</template>
