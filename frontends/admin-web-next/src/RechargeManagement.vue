<script setup lang="ts">
import RecordPagination from './components/RecordPagination.vue';
import { useRecordPagination } from './shared/useRecordPagination';
import { formatAmount } from './shared/formatAmount';

import {computed,nextTick,onMounted,ref} from 'vue';
import {refreshSession,paymentAdminOptionsGetApiV1AdminPaymentsRechargeOptions,paymentAdminHistoryGetApiV1AdminPaymentsRecharges,paymentConfirmManualPostApiV1AdminPaymentsRechargesIdConfirm,paymentRejectManualPostApiV1AdminPaymentsRechargesIdReject,userDetailGetApiV1AdminUsersId,accessAdminListGetApiV1AdminAccessKeys} from './api';
import AmountAdjustment from './AmountAdjustment.vue';
type Options=Awaited<ReturnType<typeof paymentAdminOptionsGetApiV1AdminPaymentsRechargeOptions>>['data'];
type Order=Awaited<ReturnType<typeof paymentAdminHistoryGetApiV1AdminPaymentsRecharges>>['data'][number];
const props=withDefaults(defineProps<{onlineOnly?:boolean}>(),{onlineOnly:false});
const target=ref(''),options=ref<Options|null>(null),keyId=ref<string>(),orders=ref<Order[]>([]),notice=ref(''),error=ref(''),busy=ref(false),pending=ref(false),confirmation=ref<Order|null>(null);
const detail=ref<Order|null>(null),detailDialog=ref<HTMLDialogElement>(),nicknames=ref<Record<string,string>>({});
async function openDetail(item:Order){detail.value=item;await nextTick();detailDialog.value?.showModal();}
function closeDetail(){detailDialog.value?.close();detail.value=null;}
let targetStorage='';
const names:Record<string,string>={PERSONAL:'个人',ENTERPRISE:'企业',API:'API'};
const visible=computed(()=>orders.value.filter(item=>item.source!=='CORPORATE'&&(!props.onlineOnly||item.source!=='MANUAL')));
async function run(action:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';try{await action();}catch{error.value='操作失败，未确认充值完成。请稍后重试。';}finally{busy.value=false;}}
async function history(){
 orders.value=(await paymentAdminHistoryGetApiV1AdminPaymentsRecharges()).data;
 if(detail.value)detail.value=orders.value.find(item=>item.orderId===detail.value?.orderId)||null;
 const ids=[...new Set(orders.value.map(item=>item.userId).filter((id):id is string=>!!id))];
 const users=await Promise.allSettled(ids.map(id=>userDetailGetApiV1AdminUsersId(id)));
 nicknames.value=Object.fromEntries(users.flatMap((result,index)=>result.status==='fulfilled'&&result.value.data.id===ids[index]?[[ids[index],result.value.data.nickname||'—']]:[]));
}
async function loadTarget(){if(pending.value)return;await run(async()=>{options.value=null;keyId.value=undefined;confirmation.value=null;const [account,keys]=await Promise.all([paymentAdminOptionsGetApiV1AdminPaymentsRechargeOptions({userId:target.value.trim()}),accessAdminListGetApiV1AdminAccessKeys({ownerId:target.value.trim()})]);options.value=account.data;try{if(targetStorage)sessionStorage.setItem(targetStorage,target.value.trim());}catch{}keyId.value=keys.data.find(item=>item.ownerId===account.data.userId)?.keyId||undefined;});}
async function refreshTargetOptions(){const userId=options.value?.userId;if(!userId)return;const result=await paymentAdminOptionsGetApiV1AdminPaymentsRechargeOptions({userId});if(options.value?.userId===userId)options.value=result.data;}
async function confirm(){const id=confirmation.value?.orderId;if(!id)return;await run(async()=>{await paymentConfirmManualPostApiV1AdminPaymentsRechargesIdConfirm(id);confirmation.value=null;notice.value='审核已确认，后台正在关联身份、主 Key 与积分入账，请刷新核对到账状态。';await Promise.all([history(),refreshTargetOptions()]);});}
async function reject(item:Order){if(!item.orderId)return;await run(async()=>{await paymentRejectManualPostApiV1AdminPaymentsRechargesIdReject(item.orderId!);if(confirmation.value?.orderId===item.orderId)confirmation.value=null;await history();});}
const date=(value?:string|null)=>value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'—';
const status=(item:Order)=>item.status==='PAID'?(item.fulfilled?'已到账':'已确认，入账中'):({REJECTED:'已拒绝',REVIEW_PENDING:'待人工审核',PENDING:'待支付',REFUND_PENDING:'退款处理中',REFUNDED:'已退款',EXPIRED:'已过期'}[item.status||'']||item.status);
onMounted(()=>void (async()=>{await run(async()=>{const auth=await refreshSession();targetStorage=auth.authenticated&&auth.user?.id?`xzwl-amount-target:${auth.user.id}`:'';try{target.value=sessionStorage.getItem(targetStorage)||'';}catch{}await history();});if(target.value)await loadTarget();})());
const { page: recordPage, pageRecords, pageSize: recordPageSize } = useRecordPagination(visible);
</script>
<template>
 <section class="recharge-management"><header class="page-heading"><div><h1>{{onlineOnly?'线上充值':'人工充值与审核'}}</h1><p>所有金额单位为人民币元；积分按订单兑换快照入账。</p></div></header>
 <p v-if="error" role="alert">{{error}}</p><p v-if="notice" role="status">{{notice}}</p>
 <article v-if="!onlineOnly" class="admin-card"><h2>为本人账户办理额度调整</h2><p>使用已保存用户的真实 ID；无 Key 时请先到用户详情保存账户并生成 Key。</p>
 <div class="target-form"><label>目标用户 ID<input aria-label="目标用户 ID" v-model="target" :disabled="busy||pending" @input="options=null;keyId=undefined"/></label><button data-testid="load-target" class="secondary-action" :disabled="busy||pending||!target.trim()" @click="loadTarget">读取目标账户</button></div>
 <AmountAdjustment v-if="options" :key="options.userId||undefined" :user-id="options.userId||undefined" :key-id="keyId" :options="options" @changed="run(history)" @pending="pending=$event"/>
 </article>
 <article v-if="confirmation" class="admin-card" role="region" aria-label="人工到账确认"><h2>确认人工到账</h2><p>用户 {{confirmation.userId}} · {{names[confirmation.accountType||'']}} · ¥ {{ formatAmount(confirmation.amount4) }} · {{ formatAmount(confirmation.expectedPoints4) }} 积分</p><p>备注：{{confirmation.remark}}</p><p>仅在已核实真实收款凭证后确认。确认将锁定首次身份并入账。</p><button class="primary-action" :disabled="busy" @click="confirm">确认已收到人民币款项</button><button class="secondary-action" :disabled="busy" @click="confirmation=null">取消</button></article>
 <article class="admin-card"><header><h2>真实充值记录</h2><button class="secondary-action" :disabled="busy" @click="run(history)">刷新记录</button></header><p>最近100条，人工申请与线上支付按来源区分。</p><p v-if="!visible.length">暂无充值记录</p><div v-else class="table-wrap"><table><thead><tr><th>订单/用户</th><th>用户昵称</th><th>身份</th><th>人民币（元）</th><th>积分快照</th><th>来源</th><th>状态</th><th>申请/确认人</th><th>审核</th></tr></thead><tbody><template v-for="item in pageRecords" :key="item.orderId||undefined"><tr><td>{{item.orderId}}<small>{{item.userId}}</small></td><td>{{nicknames[item.userId||'']||'—'}}</td><td>{{names[item.accountType||'']}}</td><td>{{ formatAmount(item.amount4) }}</td><td>{{ formatAmount(item.expectedPoints4) }}</td><td>{{item.source==='MANUAL'?'后台人工确认':'收钱吧真实支付'}}</td><td>{{status(item)}}</td><td>{{item.submittedBy||'—'}} / {{item.confirmedBy||item.rejectedBy||'—'}}</td><td><div class="review-actions">
 <button type="button" class="review-detail" aria-haspopup="dialog" @click="openDetail(item)"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>详情</button>
 <template v-if="item.source==='MANUAL'&&item.status==='REVIEW_PENDING'">
 <button type="button" class="review-approve" :disabled="busy" @click="confirmation=item"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12 4 4L19 6"/></svg>通过</button>
 <button type="button" class="review-reject" :disabled="busy" @click="reject(item)"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg>拒绝</button>
 </template></div></td></tr>
</template></tbody></table></div><RecordPagination v-model:page="recordPage" :total="visible.length" :page-size="recordPageSize" label="充值记录分页" :disabled="busy" /></article>
 <dialog v-if="detail" ref="detailDialog" class="review-details" aria-label="充值详情" @cancel.prevent="closeDetail" @click="event=>{if(event.target===detailDialog)closeDetail();}"><div class="review-dialog-body"><header><h2>充值详情</h2><button type="button" class="secondary-action" aria-label="关闭充值详情" autofocus @click="closeDetail">关闭</button></header><dl>
 <div><dt>订单号</dt><dd>{{detail.orderId}}</dd></div><div><dt>用户昵称</dt><dd>{{nicknames[detail.userId||'']||'—'}}</dd></div><div><dt>用户 ID</dt><dd>{{detail.userId}}</dd></div>
 <div><dt>人民币金额（元）</dt><dd>{{formatAmount(detail.amount4)}}</dd></div><div><dt>预计到账积分</dt><dd>{{formatAmount(detail.expectedPoints4)}}</dd></div><div><dt>兑换比例（积分/元）</dt><dd>{{detail.rateSnapshot4||'—'}}</dd></div>
 <div><dt>账户身份</dt><dd>{{names[detail.accountType||'']||'—'}}</dd></div><div><dt>来源</dt><dd>{{detail.source==='MANUAL'?'后台人工确认':'收钱吧真实支付'}}</dd></div><div><dt>状态</dt><dd>{{status(detail)}}</dd></div>
 <div><dt>申请时间</dt><dd>{{date(detail.createdAt)}}</dd></div><div><dt>申请人</dt><dd>{{detail.submittedBy||'—'}}</dd></div><div><dt>确认人</dt><dd>{{detail.confirmedBy||'—'}}</dd></div>
 <div v-if="detail.rejectedBy"><dt>拒绝审核人</dt><dd>{{detail.rejectedBy}}</dd></div><div v-if="detail.rejectedAt"><dt>拒绝时间</dt><dd>{{date(detail.rejectedAt)}}</dd></div>
 <div class="review-remark"><dt>备注 / 到账凭证说明</dt><dd>{{detail.remark||'—'}}</dd></div>
 </dl></div></dialog>
  </section>
</template>
<style scoped>.recharge-management{display:grid;gap:22px}.recharge-management form{display:grid;gap:18px;margin-top:18px}.recharge-management table{min-width:900px}.recharge-management td{max-width:240px;white-space:normal;overflow-wrap:anywhere}.recharge-management td:nth-child(n+3):nth-child(-n+7){white-space:nowrap}.recharge-management td:first-child{min-width:180px}.recharge-management td:nth-child(2){min-width:90px}.recharge-management [role=alert]{color:#ffb7a2}.recharge-management small{display:block}</style>

<style scoped>.target-form{display:flex;align-items:end;gap:12px;margin:18px 0}.target-form label{display:grid;gap:8px;flex:1}.target-form input{min-height:44px;width:100%;padding:10px;border:1px solid #3a4646;border-radius:8px;color:#eef3f3;background:#0d1111}@media(max-width:600px){.target-form{flex-wrap:wrap}.target-form label{flex-basis:100%}}</style>

<style scoped>
.review-actions{display:grid;grid-template-columns:auto auto;gap:4px 12px;align-items:center;width:max-content}
.review-actions button{display:inline-flex;align-items:center;justify-content:center;gap:7px;min-height:38px;padding:7px 12px;border:1px solid transparent;border-radius:9px;font:inherit;font-weight:600;white-space:nowrap;cursor:pointer}
.review-actions svg{width:18px;height:18px;fill:none;stroke:currentColor;stroke-width:2.4;stroke-linecap:round;stroke-linejoin:round}
.review-actions .review-detail{grid-row:1 / 3;background:#252d2d;border-color:#384545;color:#c2d0d0}
.review-actions .review-approve{grid-column:2;color:#6ee7a0;background:transparent}.review-actions .review-reject{grid-column:2;color:#ff8585;background:transparent}
.review-actions button:hover:not(:disabled){background:#ffffff10;border-color:currentColor}.review-actions button:focus-visible{outline:2px solid currentColor;outline-offset:2px}.review-actions button:disabled{opacity:.45;cursor:not-allowed}
.review-details{box-sizing:border-box;width:min(860px,calc(100vw - 32px));max-height:calc(100dvh - 40px);padding:0;border:1px solid #374242;border-radius:18px;background:#141818;color:#eef3f3;overflow:auto;overscroll-behavior:contain;color-scheme:dark}.review-details::backdrop{background:rgb(0 0 0 / .7)}.review-dialog-body{padding:28px}.review-dialog-body>header{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:26px}.review-details h2{margin:0;font-size:24px}.review-details dl{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:20px 28px;margin:0}.review-details dt{color:#9baaaa;font-size:12px;margin-bottom:7px}.review-details dd{margin:0;white-space:pre-wrap;overflow-wrap:anywhere;color:#eef6f4}.review-remark{grid-column:1 / -1}@media(max-width:760px){.review-details dl{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:480px){.review-dialog-body{padding:20px}.review-details dl{grid-template-columns:1fr}}
</style>
