<script setup lang="ts">
import TableLoadingRows from './components/TableLoadingRows.vue';
import TableUpdating from './components/TableUpdating.vue';
import LoadingValue from './components/LoadingValue.vue';
import {computed,inject,onBeforeUnmount,ref,watch} from 'vue';
import {adminSessionKey} from './adminSession';
import {billingDailyFinanceGetApiV1AdminBillingDailyFinance as query,type ResponseBillingApplicationLedgerDailyFinanceQueryReport as Report} from './api';

import {paymentAdminHistoryGetApiV1AdminPaymentsRecharges as readOrders} from './api';
import {decimal4,units4} from './portalAmounts';
function sumPoints(values:(string|null|undefined)[]){const parts=values.map(units4);return parts.every(n=>n!==null)?decimal4(parts.reduce<bigint>((sum,n)=>sum+n!,0n)):null;}
import {formatAmount} from '../../shared/formatAmount';
const props=defineProps<{range:{from?:string;to?:string};revision:number}>();
const session=inject(adminSessionKey);
const permitted=computed(()=>['ADMIN','NORMAL_ADMIN'].includes(session?.currentAdmin.value?.role||''));
const report=ref<Report|null>(null),busy=ref(false),error=ref(''),page=ref(0);
type Order=Awaited<ReturnType<typeof readOrders>>['data'][number];
const orders=ref<Order[]>([]),ordersBusy=ref(false),ordersError=ref('');
const dateFormat=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'});
function orderDay(value:string|null){const date=new Date(value||'');return Number.isFinite(date.getTime())?dateFormat.format(date):'—';}
const filteredOrders=computed(()=>orders.value.filter(order=>{
 const day=orderDay(order.createdAt);return (!props.range.from||day>=props.range.from)&&(!props.range.to||day<=props.range.to);
}));
const creditedTotal=computed(()=>sumPoints(filteredOrders.value.filter(order=>order.status==='PAID'&&order.fulfilled).map(order=>order.amount4)));
const rangeLabel=computed(()=>props.range.from&&props.range.to?`${props.range.from.replaceAll('-','/')} – ${props.range.to.replaceAll('-','/')}`:'全部日期');
const rangeDays=computed(()=>props.range.from&&props.range.to?(Date.parse(props.range.to)-Date.parse(props.range.from))/86400000+1:report.value?.totalDays??'—');
function headerMoney(value?:string|null){
 if(units4(value)===null)return '—';
 const [whole,fraction='']=value!.split('.');
 return `¥${whole!.replace(/\B(?=(\d{3})+(?!\d))/g,',')}.${fraction.padEnd(2,'0').slice(0,2)}`;
}
function method(order:Order){return order.source==='CORPORATE'?'对公转账':order.source==='MANUAL'?'人工入账':order.paymentMethod==='ALIPAY'?'支付宝':order.paymentMethod==='WECHAT'?'微信支付':'—';}
function status(order:Order){return order.status==='PAID'?(order.fulfilled?'已到账':'待入账'):({PENDING:'待支付',EXPIRED:'已过期',REVIEW_PENDING:'待审核',REJECTED:'已拒绝',REFUND_PENDING:'退款中',REFUNDED:'已退款'} as Record<string,string>)[order.status||'']||'—';}
let ordersVersion=0;
async function loadOrders(){
 const own=++ordersVersion;ordersError.value='';ordersBusy.value=false;
 if(!permitted.value)return;
 ordersBusy.value=true;
 try{const data=(await readOrders()).data;
  if(own!==ordersVersion)return;
  if(!Array.isArray(data)||data.some(order=>!order.orderId||units4(order.amount4)===null))throw Error('invalid orders');
  orders.value=data;
 }catch{if(own===ordersVersion){orders.value=[];ordersError.value='充值记录读取失败，请重试。';}}
 finally{if(own===ordersVersion)ordersBusy.value=false;}
}
let version=0;
async function load(next=0,clear=true){
 const own=++version;if(clear){page.value=0;}error.value='';busy.value=false;
 if(!permitted.value)return;
 busy.value=true;
 try{
  const data=(await query({...props.range,offset:next*30,limit:30})).data;
  if(own!==version)return;
  if(!Array.isArray(data.items)||data.items.length>30||!Number.isSafeInteger(data.totalDays)||data.totalDays!<0||data.offset!==next*30||data.limit!==30||
    units4(data.totalRechargeAmount4)===null||(data.totalConsumptionAmount4!==null&&units4(data.totalConsumptionAmount4)===null)||
    data.items.some(day=>!day.date||units4(day.rechargeAmount4)===null||(day.consumptionAmount4!==null&&units4(day.consumptionAmount4)===null)))throw Error('invalid report');
  report.value=data;page.value=next;
 }catch{if(own===version){report.value=null;error.value='账务统计读取失败，请重试。';}}
 finally{if(own===version)busy.value=false;}
}
watch(()=>[props.revision,props.range,session?.currentAdmin.value],(next,prior)=>{if(next[2]!==prior?.[2]){report.value=null;orders.value=[];}void load();void loadOrders();},{immediate:true});
onBeforeUnmount(()=>{version++;ordersVersion++;});
</script>

<template>
 <div v-if="permitted" class="daily-finance" :aria-busy="busy||ordersBusy">
  <div class="finance-grid">
   <article class="finance-card recharge" aria-labelledby="finance-recharge-title">
    <header><h3 id="finance-recharge-title"><span class="finance-icon"><svg viewBox="0 0 32 32" aria-hidden="true"><path d="M6 9 24 4q2 0 2 2v6H6Z" fill="#008aff" stroke="#d2e9ff"/><rect x="4" y="10" width="24" height="19" rx="3" fill="#006eff" stroke="#87c9ff"/><path d="M6 13h20" stroke="#c4e6ff"/><path d="M24 17h5v8h-5a4 4 0 0 1 0-8Z" fill="#008bff" stroke="#b6ddff"/><circle cx="24" cy="21" r="1.4" fill="white"/></svg></span>充值记录</h3><div class="finance-meta" title="最近100条订单内的所选日期记录，金额仅汇总已到账订单" aria-label="最近100条订单内充值汇总"><span>{{rangeLabel}}</span><span>{{ordersBusy||ordersError?'—':filteredOrders.length}} 条记录</span><span>总充值 <strong data-testid="finance-recharge-total" :title="ordersBusy||ordersError?undefined:'已到账金额：'+creditedTotal+' 元'"><LoadingValue :loading="ordersBusy&&!orders.length">{{ordersError?'—':headerMoney(creditedTotal)}}</LoadingValue></strong></span></div></header>
    <p v-if="ordersError" class="finance-error" role="alert">{{ordersError}}</p>
    <div class="finance-table data-update-region" tabindex="0" :aria-busy="ordersBusy" aria-label="充值记录"><TableUpdating v-if="ordersBusy&&filteredOrders.length"/><table><caption class="sr-only">充值记录</caption><thead><tr><th scope="col">日期</th><th scope="col">订单号</th><th scope="col">充值金额（元）</th><th scope="col">支付方式</th><th scope="col">状态</th></tr></thead><tbody><TableLoadingRows v-if="ordersBusy&&!filteredOrders.length" :columns="5" label="正在读取充值记录…"/>
     <tr v-for="order in filteredOrders" :key="order.orderId!"><td>{{orderDay(order.createdAt)}}</td><td class="finance-order" :title="order.orderId!">{{order.orderId}}</td><td>{{formatAmount(order.amount4)}}</td><td>{{method(order)}}</td><td><span class="finance-status" :class="{'credited':order.status==='PAID'&&order.fulfilled}">{{status(order)}}</span></td></tr>
     <tr v-if="!ordersBusy&&!filteredOrders.length"><td colspan="5" class="finance-empty">{{ordersBusy?'正在读取充值记录…':ordersError?'记录暂不可用':'该日期范围暂无充值记录'}}</td></tr>
    </tbody></table></div>
   </article>
   <article class="finance-card consumption" aria-labelledby="finance-consumption-title">
    <header><h3 id="finance-consumption-title"><span class="finance-icon"><svg viewBox="0 0 32 32" aria-hidden="true"><path d="M15 3a13 13 0 1 0 14 14H15Z" fill="#0077ff" stroke="#c5e5ff" stroke-width="1.3"/><path d="M18 2v12h12A13 13 0 0 0 18 2Z" fill="#0057ee" stroke="#c5e5ff" stroke-width="1.3"/></svg></span>每日消耗记录</h3><div class="finance-meta"><span>{{rangeLabel}}</span><span>{{rangeDays}} 天</span><span>总消耗 <strong data-testid="finance-consumption-total" :title="report?.totalConsumptionAmount4==null?undefined:'净消耗：'+report.totalConsumptionAmount4+' 元'"><LoadingValue :loading="busy&&!report">{{report?headerMoney(report.totalConsumptionAmount4):'—'}}</LoadingValue></strong></span></div></header>
    <p v-if="error" class="finance-error" role="alert">{{error}}</p>
    <div class="finance-table data-update-region" :aria-busy="busy" tabindex="0" aria-label="每日净消耗金额"><TableUpdating v-if="busy&&report?.items?.length"/><table><caption class="sr-only">每日净消耗金额</caption><thead><tr><th scope="col">日期</th><th scope="col">净消耗（元）</th></tr></thead><tbody><TableLoadingRows v-if="busy&&!report?.items?.length" :columns="2" label="正在读取账务记录…"/><tr v-for="day in report?.items||[]" :key="day.date!"><th scope="row">{{day.date}}</th><td>{{formatAmount(day.consumptionAmount4)}}</td></tr><tr v-if="!busy&&!report?.items?.length"><td colspan="2" class="finance-empty">{{busy?'正在读取账务记录…':error?'统计暂不可用':'该日期范围暂无账务记录'}}</td></tr></tbody></table></div>
    <footer v-if="report&&report.totalDays!>30" class="finance-pagination"><span>共 {{ report.totalDays }} 天</span><button aria-label="账务统计上一页" :disabled="page===0" :aria-disabled="busy" @click="!busy&&load(page-1,false)">上一页</button><span>{{ page+1 }} / {{ Math.ceil(report.totalDays!/30) }}</span><button aria-label="账务统计下一页" :disabled="(page+1)*30>=report.totalDays!" :aria-disabled="busy" @click="!busy&&load(page+1,false)">下一页</button></footer>
   </article>
  </div>
 </div>
</template>

<style scoped>
.daily-finance{min-width:0}.finance-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}.finance-card{min-width:0;padding:16px 18px;border:1px solid var(--line);border-radius:14px;background:var(--panel);box-shadow:0 12px 28px #17448c10}.finance-card header{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:14px}h3{display:flex;align-items:center;gap:12px;margin:0;font-size:22px;white-space:nowrap}.finance-icon{display:grid;place-items:center;flex-shrink:0;width:44px;height:44px;border-radius:10px;background:linear-gradient(135deg,#e5f5ff,#d7e6ff);color:#0072ff}.finance-icon svg{width:30px;height:30px;filter:drop-shadow(0 2px 2px #0070ff35)}.finance-meta{display:flex;align-items:center;flex-wrap:wrap;justify-content:flex-end;gap:5px 9px;padding:12px;border:1px solid #eaf0fb;border-radius:8px;color:#344d83;font-size:11px}.finance-meta>span{white-space:nowrap}.finance-meta>span+span:before{content:"·";margin-right:9px;color:#344d83}.finance-meta strong{color:#006bff;font-size:14px;font-variant-numeric:tabular-nums}
.finance-table{height:260px;overflow:auto;scrollbar-width:thin;scrollbar-color:#bccbe0 #f2f6fc;border:1px solid #edf2fb;border-radius:9px}.finance-table:focus-visible{outline:2px solid var(--cyan);outline-offset:2px}.finance-table table{width:100%;min-width:0;border-collapse:collapse}.finance-table th,.finance-table td{padding:10px 12px;border-bottom:1px solid #edf2fb;font-size:12px;font-variant-numeric:tabular-nums;white-space:nowrap;color:#20376e}.finance-table thead th{position:sticky;top:0;background:#f3f7fd;font-weight:500;z-index:1}.finance-table th{text-align:left}.finance-table tbody th{background:transparent;font-weight:400}.consumption td,.consumption th:last-child{text-align:right}.finance-order{max-width:125px;overflow:hidden;text-overflow:ellipsis}.finance-status{display:inline-flex;padding:3px 9px;border-radius:6px;color:#6c7093;background:#f0f3fa;white-space:nowrap}.finance-status.credited{color:#008554;background:#e1faed}.finance-table td.finance-empty{text-align:center;height:180px;color:var(--muted)}.finance-error{color:#b52c37;font-size:13px}.finance-pagination{display:flex;align-items:center;justify-content:flex-end;gap:12px;margin-top:12px;font-size:12px;color:var(--muted)}.finance-pagination button{padding:6px 10px}.finance-pagination button[aria-disabled="true"]{opacity:.5;cursor:wait}
@media(max-width:1350px){.finance-card header{flex-wrap:wrap}.finance-meta{justify-content:flex-start}.finance-card{padding:14px}.finance-table th,.finance-table td{padding:10px 8px}.finance-order{max-width:90px}}
@media(max-width:1050px){.finance-grid{grid-template-columns:1fr}.finance-card header{flex-wrap:nowrap}.finance-order{max-width:180px}}
@media(max-width:560px){.finance-card header{flex-wrap:wrap}h3{font-size:20px}.finance-table table{min-width:480px}.consumption table{min-width:0}}
</style>
