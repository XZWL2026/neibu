<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import QRCode from 'qrcode';
import { ApiError, paymentOptionsGetApiV1PaymentsRechargeOptions as readOptions,
  paymentCreatePostApiV1PaymentsRecharges as createOrder, paymentGetGetApiV1PaymentsRechargesId as readOrder } from './api';
import { decimal4, money, points, units4, when } from './portalAmounts';
const props = defineProps<{ owner: string }>();
const emit = defineEmits<{ settled: [] }>();
type Options = Awaited<ReturnType<typeof readOptions>>['data'];
type Order = Awaited<ReturnType<typeof readOrder>>['data'];
type Method = 'ALIPAY' | 'WECHAT';
type Attempt = { requestId: string; amount4: string; accountType: 'API'; paymentMethod: Method; orderId?: string };
const options = ref<Options | null>(null), order = ref<Order | null>(null), attempt = ref<Attempt | null>(null);
const amount = ref(''), method = ref<Method>('ALIPAY'), busy = ref(false), error = ref(''), qr = ref('');
const methods: Record<Method, string> = { ALIPAY: '支付宝', WECHAT: '微信' };
let revision = 0, abort = new AbortController(), timer: ReturnType<typeof setTimeout> | undefined;
const rule = computed(() => options.value?.rules?.find(item => item.accountType === 'API'));
const compatible = computed(() => options.value && (options.value.accountType === null || options.value.accountType === 'API'));
const amountUnits = computed(() => amount.value.length <= 64 && /^\d+(\.\d{1,2})?$/.test(amount.value) ? units4(amount.value) : null);
const expected = computed(() => { const rate = units4(options.value?.pointsPerYuan4); return rate !== null && amountUnits.value !== null ? decimal4(amountUnits.value * rate / 10000n) : null; });
const valid = computed(() => { const minimum = units4(rule.value?.minimumAmount4); return minimum !== null && amountUnits.value !== null && amountUnits.value > 0n && amountUnits.value >= minimum; });
const active = (item: Order) => item.status === 'PENDING' || item.status === 'REFUND_PENDING' || item.status === 'PAID' && !item.fulfilled;
const status = computed(() => order.value?.status === 'PAID' ? order.value.fulfilled ? '充值成功，积分已到账' : '已付款，正在入账' : ({ PENDING: '等待支付', EXPIRED: '订单已过期', REFUND_PENDING: '退款处理中', REFUNDED: '现金已退款', REVIEW_PENDING: '等待人工审核' }[order.value?.status || ''] || order.value?.status));
const shownAmount = (value: string) => { const [whole, fraction = ''] = value.split('.'); return `${whole}.${fraction.padEnd(2, '0').slice(0, 2)}`; };
function remember(value: Attempt | null) {
  attempt.value = value;
  try { const key = `xzwl-api-recharge:${props.owner}`; if (value) sessionStorage.setItem(key, JSON.stringify(value)); else sessionStorage.removeItem(key); } catch { /* Keep the current attempt in memory when storage is unavailable. */ }
}
function restore(): Attempt | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(`xzwl-api-recharge:${props.owner}`) || 'null');
    if (value && typeof value.requestId === 'string' && value.requestId.length <= 128 && typeof value.amount4 === 'string' && value.amount4.length <= 64
      && /^\d+(\.\d{1,2})?$/.test(value.amount4) && value.accountType === 'API' && ['ALIPAY', 'WECHAT'].includes(value.paymentMethod)
      && (value.orderId === undefined || typeof value.orderId === 'string'))
      return { requestId: value.requestId, amount4: value.amount4, accountType: 'API', paymentMethod: value.paymentMethod, ...(value.orderId ? { orderId: value.orderId } : {}) };
  } catch { /* A malformed or unavailable saved attempt is ignored. */ }
  return null;
}
function checkout(value: string | null | undefined) {
  try {
    const url = new URL(value || '');
    const supported = url.protocol === 'https:' || url.protocol === 'weixin:' && url.hostname === 'wxpay' && ['/bizpayurl', '/bizpayurl/up'].includes(url.pathname) && !!url.search;
    return supported && !url.username && !url.password && !url.hash && value!.length <= 2048 ? url.href : null;
  } catch { return null; }
}
async function receive(value: Order, current: number) {
  if (current !== revision) return;
  if (value.userId !== props.owner || value.accountType !== 'API' || !value.orderId) throw new Error('充值订单归属不匹配');
  const url = value.status === 'PENDING' ? checkout(value.checkoutUrl) : null;
  if (value.status === 'PENDING' && value.checkoutUrl && !url) throw new Error('支付地址不可用，请刷新订单');
  const image = url ? `data:image/svg+xml;charset=utf-8,${encodeURIComponent(await QRCode.toString(url, { type: 'svg', width: 256, margin: 4, errorCorrectionLevel: 'M' }))}` : '';
  if (current !== revision) return;
  order.value = value; qr.value = image;
  if (!active(value)) {
    remember(null);
    if (value.status === 'PAID' && value.fulfilled) { emit('settled'); await loadOptions(current); }
  } else if (attempt.value) remember({ ...attempt.value, orderId: value.orderId });
}
async function loadOptions(current: number) {
  const result = (await readOptions({ signal: abort.signal })).data;
  if (current !== revision) return;
  if (result.userId !== props.owner) throw new Error('充值账户归属不匹配');
  options.value = result;
  if (!amount.value) amount.value = shownAmount(rule.value?.presetAmounts4?.[0] || '');
}
async function run(action: (current: number) => Promise<void>) {
  if (busy.value || !props.owner) return;
  const current = revision; busy.value = true; error.value = ''; clearTimeout(timer);
  try { await action(current); }
  catch (cause) { if (current === revision) error.value = cause instanceof ApiError ? cause.code === 'PAYMENT_CHANNEL_AMOUNT_LIMIT' ? '金额超过支付渠道单笔限额，请调整金额。' : '充值请求未完成，请刷新或重试本次订单。' : cause instanceof Error ? cause.message : '充值服务暂不可用'; }
  finally {
    if (current === revision) {
      busy.value = false;
      if (!error.value && order.value && active(order.value)) timer = setTimeout(() => void refreshOrder(), 3000);
    }
  }
}
async function refreshOrder(refreshRules = false) {
  await run(async current => {
    if (refreshRules || !options.value) await loadOptions(current);
    if (current !== revision || !compatible.value) return;
    const id = order.value?.orderId || attempt.value?.orderId;
    if (id) await receive((await readOrder(id, { signal: abort.signal })).data, current);
  });
}
async function submit() {
  if (!compatible.value || (!attempt.value && !valid.value)) return;
  await run(async current => {
    const next = attempt.value || { requestId: crypto.randomUUID(), amount4: amount.value, accountType: 'API' as const, paymentMethod: method.value };
    remember(next);
    const { orderId: ignored, ...body } = next; void ignored;
    try { await receive((await createOrder(body, { signal: abort.signal })).data, current); }
    catch (cause) {
      if (current === revision && cause instanceof ApiError && [400, 403, 409, 422].includes(cause.status)) remember(null);
      throw cause;
    }
  });
}
watch(() => props.owner, () => {
  revision++; abort.abort(); abort = new AbortController(); clearTimeout(timer);
  options.value = null; order.value = null; qr.value = ''; amount.value = ''; error.value = ''; busy.value = false; attempt.value = restore(); method.value = attempt.value?.paymentMethod || 'ALIPAY';
  if (attempt.value) amount.value = attempt.value.amount4;
  void run(async current => { await loadOptions(current); if (current === revision && compatible.value && attempt.value?.orderId) await receive((await readOrder(attempt.value.orderId, { signal: abort.signal })).data, current); });
}, { immediate: true });
onBeforeUnmount(() => { revision++; abort.abort(); clearTimeout(timer); });
</script>

<template>
  <section class="api-recharge admin-card" aria-label="API 账户充值" :aria-busy="busy">
    <header><h2>API 账户充值</h2><button type="button" :disabled="busy" @click="refreshOrder(true)">刷新账户与订单</button></header>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="busy && !options" role="status">正在读取充值规则…</p>
    <p v-if="options && !compatible" role="status">当前账户不能切换为 API。</p>
    <template v-if="compatible">
      <div v-if="order" class="api-order" aria-live="polite">
        <strong>{{ status }}</strong><p>订单：{{ order.orderId }}</p>
        <p>支付金额：{{ money(order.amount4) }} · 预计到账：{{ points(order.expectedPoints4) }}</p>
        <figure v-if="qr" class="recharge-qr"><img :src="qr" width="256" height="256" alt="收钱吧支付二维码"/><figcaption>{{ methods[order.paymentMethod as Method] || '支付' }}扫码付款</figcaption></figure>
        <p v-if="order.status === 'PENDING'">有效期至 {{ when(order.expiresAt) }}</p>
      </div>
      <form v-if="!order || !active(order) || order.status === 'PENDING' && !qr" @submit.prevent="submit">
        <div class="payment-methods" role="group" aria-label="支付方式"><button v-for="(name, value) in methods" :key="value" type="button" :data-method="value" :aria-pressed="method === value" :disabled="busy || !!attempt" @click="method = value">{{ name }}</button></div>
        <div class="recharge-grid"><button v-for="value in rule?.presetAmounts4" :key="value" type="button" :aria-pressed="amountUnits === units4(value)" :disabled="busy || !!attempt" @click="amount = shownAmount(value)">{{ money(value) }}</button></div>
        <label>自定义充值金额（元）<input :value="amount" @input="amount = ($event.target as HTMLInputElement).value" type="number" step="0.01" :min="rule?.minimumAmount4 || undefined" required :disabled="busy || !!attempt" /></label>
        <p>本次最低 {{ money(rule?.minimumAmount4) }} · 1 元 = {{ points(options?.pointsPerYuan4) }}</p>
        <p aria-live="polite">支付金额：{{ money(amount) }} · 预计到账：{{ points(expected) }}</p>
        <button type="submit" class="recharge-submit" :disabled="busy || (!attempt && !valid)">{{ busy ? '处理中…' : attempt ? '重试本次订单' : '创建支付订单' }}</button>
      </form>
    </template>
  </section>
</template>

<style scoped>
.api-recharge { max-width: 1080px; margin-bottom: 24px; display: grid; gap: 20px; }
.api-recharge header { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; }
.api-recharge h2, .api-recharge p { margin: 0; }
.api-recharge form { display: grid; gap: 18px; }
.api-recharge button { padding: 10px 16px; border: 1px solid #455252; border-radius: 10px; background: #202929; color: #e6f3f3; cursor: pointer; }
.api-recharge button:disabled { opacity: .55; cursor: not-allowed; }
.payment-methods { display: flex; gap: 8px; }
.payment-methods button { border-radius: 999px; }
.recharge-grid { display: grid; grid-template-columns: repeat(auto-fit,minmax(130px,1fr)); gap: 12px; }
.recharge-grid button { min-height: 78px; font-size: 21px; }
.api-recharge button[aria-pressed=true] { border-color: #31d7be; box-shadow: inset 0 0 0 1px #31d7be; }
.api-recharge label { display: grid; gap: 8px; max-width: 360px; }
.api-recharge input { width: 100%; min-width: 0; }
.api-recharge .recharge-submit { justify-self: start; min-height: 46px; padding: 0 20px; border: 0; border-radius: 13px; color: #061011; background: linear-gradient(105deg,#31d7be,#49dce5); font-weight: 850; }
.api-order { padding: 20px; border: 1px solid #3d464c; border-radius: 16px; display: grid; gap: 12px; overflow-wrap: anywhere; }
.recharge-qr { margin: 8px 0; display: grid; gap: 10px; }
.recharge-qr img { max-width: 100%; height: auto; }
[role=alert] { color: #ffbdab; }
</style>
