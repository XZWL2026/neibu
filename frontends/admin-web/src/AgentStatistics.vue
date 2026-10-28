<script setup lang="ts">
import {computed,onUnmounted,ref,watch} from 'vue';
import {
 promotionDownlinesGetApiV1AdminPromotionsAgentDirectoryUserIdDownlines as readDownlines,
 promotionAdminPromoterRechargesGetApiV1AdminPromotionsPromotersUserIdRecharges as readRecharges,
 promotionAdminPromoterConsumptionsGetApiV1AdminPromotionsPromotersUserIdConsumptions as readConsumptions,
 promotionAuditGetApiV1AdminPromotionsBenefitAudit as readEarnings,
 promotionPayoutsGetApiV1AdminPromotionsPayouts as readWithdrawals,
 promotionOperationsGetApiV1AdminPromotionsAgentDirectoryUserIdOperations as readOperations,
} from './api';
import {money,points,shown} from './portalAmounts';
import {csv,localTime as when} from './apiConsolePresentation';
const props=defineProps<{userId:string;administrator?:boolean;primary?:boolean}>();
const tabs=[{id:'users',label:'下级用户'},{id:'recharges',label:'充值记录'},{id:'earnings',label:'收益记录'},
 {id:'withdrawals',label:'提现记录'},{id:'operations',label:'操作日志'},{id:'consumptions',label:'消费记录'}] as const;
type Tab=typeof tabs[number]['id'];
const financeAllowed=computed(()=>props.administrator===true&&props.primary===true);
const visibleTabs=computed(()=>tabs.filter(item=>financeAllowed.value||!['earnings','withdrawals'].includes(item.id)));
const tab=ref<Tab>('users'),page=ref(0),loading=ref(false),error=ref(''),exporting=ref(false);
const search=ref(''),accountType=ref(''),applied=ref({search:'',accountType:''});
const users=ref<Awaited<ReturnType<typeof readDownlines>>['data']|null>(null);
const recharges=ref<Awaited<ReturnType<typeof readRecharges>>['data']|null>(null);
const consumptions=ref<Awaited<ReturnType<typeof readConsumptions>>['data']|null>(null);
const earnings=ref<Awaited<ReturnType<typeof readEarnings>>['data']|null>(null);
const withdrawals=ref<Awaited<ReturnType<typeof readWithdrawals>>['data']|null>(null);
const operations=ref<Awaited<ReturnType<typeof readOperations>>['data']|null>(null);
const active=computed(()=>({users:users.value,recharges:recharges.value,consumptions:consumptions.value,earnings:earnings.value,withdrawals:withdrawals.value,operations:operations.value})[tab.value]);
let version=0,controller:AbortController|undefined,exportController:AbortController|undefined;
async function load(targetPage=page.value,filters=applied.value){
 const request=++version;controller?.abort();exportController?.abort();controller=new AbortController();
 loading.value=true;exporting.value=false;error.value='';
 try{
  const params={page:targetPage,size:20},options={signal:controller.signal};
  if(tab.value==='users'){const result=await readDownlines(props.userId,{...params,search:filters.search,accountType:filters.accountType||undefined},options);if(request===version){users.value=result.data;applied.value={...filters};}}
  else if(tab.value==='recharges'){const result=await readRecharges(props.userId,params,options);if(request===version)recharges.value=result.data;}
  else if(tab.value==='consumptions'){const result=await readConsumptions(props.userId,params,options);if(request===version)consumptions.value=result.data;}
  else if(tab.value==='operations'){const result=await readOperations(props.userId,params,options);if(request===version)operations.value=result.data;}
  else if(financeAllowed.value&&tab.value==='earnings'){const result=await readEarnings({...params,unit:'CASH',userId:props.userId},options);if(request===version)earnings.value=result.data;}
  else if(financeAllowed.value&&tab.value==='withdrawals'){const result=await readWithdrawals({...params,promoterUserId:props.userId},options);if(request===version)withdrawals.value=result.data;}
  if(request===version)page.value=targetPage;
 }catch{if(request===version)error.value='明细读取失败，请重试。';}
 finally{if(request===version)loading.value=false;}
}
function filter(){void load(0,{search:search.value.trim(),accountType:accountType.value});}
watch(()=>[props.userId,props.administrator,props.primary],()=>{tab.value='users';search.value='';accountType.value='';applied.value={search:'',accountType:''};});
watch(()=>[props.userId,props.administrator,props.primary,tab.value],()=>{page.value=0;users.value=null;recharges.value=null;consumptions.value=null;earnings.value=null;withdrawals.value=null;operations.value=null;void load();},{immediate:true});
onUnmounted(()=>{version++;controller?.abort();exportController?.abort();});
const account=(value?:string|null)=>({UNSET:'普通',PERSONAL:'个人',ENTERPRISE:'企业',API:'API'}[value??'']??'—');
const action=(value?:string|null)=>({ADMIN_PROMOTER_CREATE:'新增代理',PROMOTER_STATUS:'变更代理状态',APPLICATION_APPROVED:'申请通过',APPLICATION_REJECTED:'申请驳回',BENEFIT_RATE:'调整佣金比例',COMMISSION_FROZEN:'计提佣金',COMMISSION_RELEASED:'结算佣金',CONSUMPTION_RELEASE_REVERSED:'撤销结算',RECHARGE_REFUND_CLAWBACK:'退款扣回',COMMISSION_RATE_CORRECTION:'佣金比例修正',WITHDRAWAL_REQUESTED:'申请提现',WITHDRAWAL_APPROVED:'提现通过',WITHDRAWAL_REJECTED:'提现驳回',WITHDRAWAL_PAID:'提现到账'}[value??'']??shown(value));
const status=(value?:string|null)=>({REQUESTED:'待审核',APPROVED:'待打款',REJECTED:'已驳回',PAID:'已到账',COMPLETED:'已到账'}[value??'']??shown(value));
async function exportUsers(){
 if(exporting.value||loading.value||!users.value)return;
 const request=version,owner=props.userId,filters={...applied.value};exportController=new AbortController();exporting.value=true;error.value='';
 try{
  const rows:NonNullable<NonNullable<typeof users.value>['items']>=[];
  for(let targetPage=0;;targetPage++){
   const result=await readDownlines(owner,{page:targetPage,size:100,search:filters.search,accountType:filters.accountType||undefined},{signal:exportController.signal});
   if(request!==version)return;
   rows.push(...result.data.items??[]);
   if((targetPage+1)*100>=result.data.total)break;
   if(!result.data.items?.length)throw new Error('incomplete export');
  }
  const content=csv([['用户ID','昵称','手机号','账户类型','状态','绑定时间','已付费','累计充值（元）'],...rows.map(row=>[row.userId??'',row.nickname??'',row.phone??'',account(row.accountType),row.enabled?'启用':'停用',when(row.boundAt),row.paid?'是':'否',row.totalRechargeAmount4??''])].map(row=>row.map(value=>/^\s+[=+\-@]/.test(value)?"'"+value:value)));
  const url=URL.createObjectURL(new Blob([content],{type:'text/csv;charset=utf-8'})),link=document.createElement('a');link.href=url;link.download=`代理下级-${owner}.csv`;link.click();setTimeout(()=>URL.revokeObjectURL(url),0);
 }catch{if(request===version)error.value='导出失败，请重试。';}
 finally{if(request===version)exporting.value=false;}
}
</script>
<template>
 <article class="agent-statistics">
  <header><nav class="agent-record-tabs" aria-label="代理详情明细"><button v-for="item in visibleTabs" :key="item.id" type="button" :aria-pressed="tab===item.id" @click="tab=item.id">{{item.label}}</button></nav></header>
  <form class="agent-record-filters" @submit.prevent="filter">
   <template v-if="tab==='users'">
    <label>账户类型<select v-model="accountType"><option value="">全部类型</option><option value="UNSET">普通</option><option value="PERSONAL">个人</option><option value="ENTERPRISE">企业</option><option value="API">API</option></select></label>
    <label class="agent-record-search">搜索用户<input v-model="search" maxlength="128" placeholder="昵称、手机号或ID" /></label>
    <button class="secondary-action" type="submit" :disabled="loading">搜索</button>
    <button class="secondary-action" type="button" :disabled="loading||exporting||!users" @click="exportUsers">{{exporting?'导出中…':'导出'}}</button>
   </template>
   <button v-else type="button" class="secondary-action" :disabled="loading" @click="load()">{{loading?'刷新中…':'刷新明细'}}</button>
  </form>
  <p v-if="error" role="alert">{{error}}</p>
  <div class="table-wrap" :aria-busy="loading"><table>
   <caption class="sr-only">{{tabs.find(item=>item.id===tab)?.label}}</caption>
   <thead v-if="tab==='users'"><tr><th>用户</th><th>账户类型</th><th>状态</th><th>绑定时间</th><th>累计充值</th></tr></thead>
   <thead v-else-if="tab==='recharges'"><tr><th>用户</th><th>充值时间</th><th>订单号</th><th>充值金额</th><th>退款金额</th><th>计提佣金</th></tr></thead>
   <thead v-else-if="tab==='consumptions'"><tr><th>用户</th><th>消费时间</th><th>类型</th><th>扣除前积分</th><th>扣除积分</th><th>扣除后积分</th><th>消费金额</th><th>释放佣金</th></tr></thead>
   <thead v-else-if="tab==='earnings'"><tr><th>时间</th><th>类型</th><th>金额</th><th>订单号</th></tr></thead>
   <thead v-else-if="tab==='withdrawals'"><tr><th>申请时间</th><th>提现金额</th><th>状态</th><th>收款账户</th><th>到账时间</th></tr></thead>
   <thead v-else><tr><th>时间</th><th>操作</th><th>操作人</th></tr></thead>
   <tbody v-if="tab==='users'"><tr v-for="(row,index) in users?.items??[]" :key="row.userId??index"><td>{{shown(row.nickname)}}<br/><small>{{shown(row.phone)}}</small></td><td><span class="pill" :class="row.accountType?.toLowerCase()">{{account(row.accountType)}}</span></td><td>{{row.enabled?'启用':'停用'}}</td><td>{{when(row.boundAt)}}</td><td>{{money(row.totalRechargeAmount4)}}</td></tr></tbody>
   <tbody v-else-if="tab==='recharges'"><tr v-for="(row,index) in recharges?.items??[]" :key="row.orderId??index"><td>{{shown(row.nickname)}}<br/><small>{{shown(row.maskedPhone)}}</small></td><td>{{when(row.occurredAt)}}</td><td>{{shown(row.orderId)}}</td><td>{{money(row.paidAmount4)}}</td><td>{{money(row.refundedPaidAmount4)}}</td><td>{{money(row.commissionAmount4)}}</td></tr></tbody>
   <tbody v-else-if="tab==='consumptions'"><tr v-for="(row,index) in consumptions?.items??[]" :key="index"><td>{{shown(row.nickname)}}<br/><small>{{shown(row.maskedPhone)}}</small></td><td>{{when(row.occurredAt)}}</td><td>{{row.type==='IMAGE'?'图片':row.type==='VIDEO'?'视频':shown(row.type)}}</td><td>{{points(row.balanceBeforePoints4)}}</td><td>{{points(row.points4)}}</td><td>{{points(row.balanceAfterPoints4)}}</td><td>{{money(row.paidAmount4)}}</td><td>{{money(row.commissionAmount4)}}</td></tr></tbody>
   <tbody v-else-if="tab==='earnings'"><tr v-for="(row,index) in earnings?.items??[]" :key="row.id??index"><td>{{when(row.occurredAt)}}</td><td>{{action(row.action)}}</td><td>{{money(row.amount4)}}</td><td>{{shown(row.orderId)}}</td></tr></tbody>
   <tbody v-else-if="tab==='withdrawals'"><tr v-for="(row,index) in withdrawals?.items??[]" :key="row.id??index"><td>{{when(row.requestedAt)}}</td><td>{{money(row.amount4)}}</td><td>{{status(row.status)}}</td><td>{{shown(row.maskedAccountReference)}}</td><td>{{when(row.paidAt)}}</td></tr></tbody>
   <tbody v-else><tr v-for="(row,index) in operations?.items??[]" :key="row.id??index"><td>{{when(row.occurredAt)}}</td><td>{{action(row.type)}}</td><td>{{shown(row.operatorId)}}</td></tr></tbody>
   <tbody v-if="!active?.items?.length"><tr><td colspan="8" class="empty">{{loading?'正在读取…':error?'明细暂不可用':'暂无记录'}}</td></tr></tbody>
  </table></div>
  <footer><span>{{active?`共 ${active.total??0} 条`:'—'}}</span><div><button class="secondary-action" type="button" :disabled="loading||page===0" @click="load(page-1)">上一页</button><span>第 {{page+1}} 页</span><button class="secondary-action" type="button" :disabled="loading||!active||(page+1)*20>=(active.total??0)" @click="load(page+1)">下一页</button></div></footer>
 </article>
</template>
