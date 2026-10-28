<script setup lang="ts">
import TableLoadingRows from './components/TableLoadingRows.vue';
import LoadingValue from './components/LoadingValue.vue';
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import Icon from './PromoterIcon.vue';
import ApiRecharge from './ApiRecharge.vue';
import { paymentHistoryGetApiV1PaymentsRecharges as readRecharges, billingSummaryGetApiV1BillingPortalSummary as readSummary } from './api';
import { money, shown } from './portalAmounts';
import { monthDates, dateRange, localTime, csv } from './apiConsolePresentation';
const props=defineProps<{owner:string}>();
type Order=Awaited<ReturnType<typeof readRecharges>>['data'][number];
const orders=ref<Order[]>([]),monthAmount=ref<string|null>(null),loading=ref(false),error=ref(''),rechargeOpen=ref(false);
const dates=ref({from:'',to:''}),method=ref(''),status=ref(''),search=ref(''),page=ref(0),size=12;
const details=ref<Order|null>(null),dialog=ref<HTMLDialogElement|null>(null);
let revision=0,abort=new AbortController();
const paymentMethod=(row:Order)=>row.source==='CORPORATE'?'CORPORATE':row.source==='MANUAL'?'MANUAL':row.paymentMethod??'';
const methods:Record<string,string>={ALIPAY:'支付宝',WECHAT:'微信支付',MANUAL:'人工入账',CORPORATE:'公户转账'};
const orderStatus=(row:Order)=>row.status==='PAID'?(row.fulfilled?'CREDITED':'CREDIT_PENDING'):row.status??'';
const statuses:Record<string,string>={CREDITED:'充值成功',CREDIT_PENDING:'待入账',PENDING:'待支付',EXPIRED:'已过期',REVIEW_PENDING:'待审核',REJECTED:'已拒绝',REFUND_PENDING:'退款中',REFUNDED:'已退款'};
const filtered=computed(()=>orders.value.filter(row=>{
 const day=localTime(row.createdAt).slice(0,10);
 return (!dates.value.from||day>=dates.value.from)&&(!dates.value.to||day<=dates.value.to)&&(!method.value||paymentMethod(row)===method.value)&&(!status.value||orderStatus(row)===status.value)&&(!search.value.trim()||`${row.orderId??''} ${row.remark??''}`.toLowerCase().includes(search.value.trim().toLowerCase()));
}));
const rows=computed(()=>filtered.value.slice(page.value*size,(page.value+1)*size));
const creditedCount=computed(()=>orders.value.filter(row=>row.fulfilled&&row.status==='PAID').length);
async function load(){
 const current=++revision,owner=props.owner;abort.abort();abort=new AbortController();orders.value=[];monthAmount.value=null;error.value='';loading.value=false;
 if(!owner){error.value='无法读取真实账户：登录身份缺少业务用户，请重新登录';return;}loading.value=true;
 try{const options={signal:abort.signal},[history,summary]=await Promise.all([readRecharges(options),readSummary(dateRange(monthDates())!,options)]);
 if(history.data.some(row=>row.userId!==owner))throw Error('账单归属不匹配');if(current!==revision)return;orders.value=history.data;monthAmount.value=summary.data.totalRechargeAmount4;
 }catch(e){if(current===revision)error.value=`无法读取真实数据：${e instanceof Error?e.message:'服务暂不可用'}`;}finally{if(current===revision)loading.value=false;}
}
async function showDetails(row:Order){details.value=row;await nextTick();dialog.value?.showModal();}
function exportRecords(){
 const content=csv([['创建时间','订单号','充值金额（元）','支付方式','状态','到账时间','备注'],...filtered.value.map(row=>[localTime(row.createdAt),shown(row.orderId),row.amount4??'',methods[paymentMethod(row)]??'—',statuses[orderStatus(row)]??shown(row.status),'—',row.remark??''])]);
 const url=URL.createObjectURL(new Blob([content],{type:'text/csv;charset=utf-8'})),link=document.createElement('a');link.href=url;link.download='充值记录.csv';link.click();setTimeout(()=>URL.revokeObjectURL(url),0);
}
watch([()=>dates.value.from,()=>dates.value.to,method,status,search],()=>{page.value=0;});
watch(()=>props.owner,()=>{page.value=0;rechargeOpen.value=false;details.value=null;if(dialog.value?.open)dialog.value.close();void load();},{immediate:true});
onBeforeUnmount(()=>{revision++;abort.abort();});
</script>
<template>
 <section class="api-reference-page" aria-labelledby="api-billing-title">
  <header class="api-reference-heading"><div><p>财务中心 / <span>充值记录</span></p><h1 id="api-billing-title">充值记录</h1><p>查看所有充值记录，了解账户充值情况。</p></div><div class="api-page-actions"><button @click="rechargeOpen=!rechargeOpen"><Icon name="card"/>充值</button><RouterLink to="/api/transfer"><Icon name="bank"/>公户转账</RouterLink></div></header>
  <div v-if="error" role="alert">{{error}} <button data-testid="portal-retry" @click="load">重试</button></div>
  <div class="api-summary-grid api-billing-summary" :aria-busy="loading"><article class="api-summary-card amber"><span class="api-stat-icon"><Icon name="bolt"/></span><div><h2>本月充值</h2><strong><LoadingValue :loading="loading">{{money(monthAmount)}}</LoadingValue></strong></div></article><article class="api-summary-card purple"><span class="api-stat-icon"><Icon name="document"/></span><div><h2>充值次数</h2><strong><LoadingValue :loading="loading">{{error?'—':creditedCount}} <small>次</small></LoadingValue></strong></div><small>最近 {{loading?'—':orders.length}} 条记录中的成功充值</small></article></div>
  <ApiRecharge v-if="rechargeOpen" :key="owner" :owner="owner" @settled="load"/>
  <section class="api-record-panel api-billing-panel">
   <div class="api-billing-filters"><div class="api-date-range"><input type="date" aria-label="充值开始日期" v-model="dates.from" :max="dates.to||undefined"/><span>～</span><input type="date" aria-label="充值结束日期" v-model="dates.to" :min="dates.from||undefined"/></div><select v-model="method" aria-label="支付方式筛选"><option value="">全部支付方式</option><option v-for="(label,key) in methods" :key="key" :value="key">{{label}}</option></select><select v-model="status" aria-label="充值状态筛选"><option value="">全部状态</option><option v-for="(label,key) in statuses" :key="key" :value="key">{{label}}</option></select><label class="api-record-search"><Icon name="search"/><input v-model="search" aria-label="订单号或备注" placeholder="订单号 / 备注"/></label><button type="button" :disabled="loading||!!error||!filtered.length" @click="exportRecords"><Icon name="download"/>导出记录</button></div>
   <div class="api-record-scroll" tabindex="0" :aria-busy="loading"><table aria-label="充值记录"><thead><tr><th>创建时间</th><th>订单号</th><th class="api-numeric">充值金额（元）</th><th>支付方式</th><th>状态</th><th>到账时间</th><th>备注</th><th>操作</th></tr></thead><tbody><TableLoadingRows v-if="loading&&!rows.length" :columns="8" label="正在读取充值记录…"/><tr v-for="row in rows" :key="row.orderId??''"><td>{{localTime(row.createdAt)}}</td><td>{{shown(row.orderId)}}</td><td class="api-amount api-numeric">{{money(row.amount4)}}</td><td>{{methods[paymentMethod(row)]??'—'}}</td><td><span class="api-badge" :class="orderStatus(row)==='CREDITED'?'green':'blue'">{{statuses[orderStatus(row)]??shown(row.status)}}</span></td><td>—</td><td>{{shown(row.remark)}}</td><td><button class="api-text-button" @click="showDetails(row)">查看详情</button></td></tr><tr v-if="!loading&&!rows.length"><td colspan="8" class="api-empty">{{loading?'正在读取…':error?'—':'暂无充值记录'}}</td></tr></tbody></table></div>
   <nav class="api-pagination" aria-label="充值记录分页"><span>共 {{loading?'—':filtered.length}} 条记录 <small>（最近 100 条内）</small></span><button type="button" :disabled="page===0" @click="page--" aria-label="上一页"><Icon name="chevron-left"/></button><span class="api-current-page" aria-current="page">{{page+1}}</span><button type="button" :disabled="(page+1)*size>=filtered.length" @click="page++" aria-label="下一页"><Icon name="chevron-right"/></button></nav>
  </section>
  <dialog ref="dialog" class="api-details-dialog" aria-label="充值详情" @close="details=null"><template v-if="details"><header><h2>充值详情</h2><button @click="dialog?.close()">关闭</button></header><dl><dt>订单号</dt><dd>{{shown(details.orderId)}}</dd><dt>充值金额</dt><dd>{{money(details.amount4)}}</dd><dt>支付方式</dt><dd>{{methods[paymentMethod(details)]??'—'}}</dd><dt>状态</dt><dd>{{statuses[orderStatus(details)]??shown(details.status)}}</dd><dt>创建时间</dt><dd>{{localTime(details.createdAt)}}</dd><dt>到账时间</dt><dd>—</dd><dt>备注</dt><dd>{{shown(details.remark)}}</dd></dl></template></dialog>
 </section>
</template>
