<script setup lang="ts">
import TableLoadingRows from './components/TableLoadingRows.vue';
import {mapPool} from './data/mapPool';
import TableUpdating from './components/TableUpdating.vue';
import { formatAmount } from './shared/formatAmount';
import { units4, decimal4 } from './portalAmounts';

import {computed,inject,ref,watch,onBeforeUnmount} from 'vue';
import {RouterLink,useRoute} from 'vue-router';
import {adminSessionKey} from './adminSession';
import ConsumptionCharts from './ConsumptionCharts.vue';
const showAnalysis = ref(false);
import ConsumptionDetail from './ConsumptionDetail.vue';
import PromoterIcon from './PromoterIcon.vue';
import {
 billingListGetApiV1AdminBillingConsumptions as list,
 billingDetailGetApiV1AdminBillingConsumptionsId as getDetail,
 userListGetApiV1AdminUsers as findUsers,
 accessAdminSecretGetApiV1AdminAccessKeysIdSecret as readKey,
 accessAdminLookupPostApiV1AdminAccessKeysLookup as lookupKey,
 type BillingListGetApiV1AdminBillingConsumptionsParams as Query,
 type ResponseBillingApplicationLedgerAdminConsumptionQueryItem as ApiItem,
 type ResponseBillingApplicationLedgerConsumptionStoreRow as ApiRow,
 type ResponseBillingApplicationLedgerAdminConsumptionQueryDetail as Detail,
 type ResponseUserDomainIdentityUserIdentity as User
} from './api';
import './consumption.css';
import {consumptionAnalytics as analytics} from './data/consumptionAnalytics';
type Item=Omit<ApiItem,'ledger'>&{ledger:ApiRow&{id:string;taskId:string;keyId:string;createdAt:string;debitedPoints4:string;refundedPoints4:string;netPoints4:string};before?:string|null;after?:string|null;secret?:string|null;snapshot?:Detail['snapshot']};
const session=inject(adminSessionKey),route=useRoute();
const permitted=computed(()=>['ADMIN','NORMAL_ADMIN'].includes(session?.currentAdmin.value?.role||''));
const primary=computed(()=>session?.currentAdmin.value?.role==='ADMIN'&&session.currentAdmin.value.primary===true);
const copyResult=ref<{id:string;ok:boolean}|null>(null);
const recordId=computed(()=>String(route.params.recordRef||''));
const busy=ref(false),error=ref(''),rows=ref<Item[]>([]),total=ref(0),offset=ref(0),size=ref(10),detail=ref<Detail|null>(null);
const userSearch=ref(''),userId=ref(''),users=ref<User[]>([]),userError=ref(''),usersBusy=ref(false);
const pageSize=ref(10);
const model=ref(''),status=ref(''),from=ref(''),to=ref('');
const statuses:Record<string,string>={ROUTING:'准备中',CHARGE_PENDING:'扣费确认中',SUBMIT_PENDING:'提交中',RUNNING:'生成中',ARCHIVING:'保存结果中',REFUND_PENDING:'退款处理中',SUCCEEDED:'成功',PARTIAL_SUCCESS:'部分成功',FAILED:'失败',TIMED_OUT:'已超时',MISSING:'历史任务信息缺失'};
const statusClasses:Record<string,string>={SUCCEEDED:'status-success',FAILED:'status-danger',TIMED_OUT:'status-danger',PARTIAL_SUCCESS:'status-pending',RUNNING:'status-pending',ARK_RUNNING:'status-pending',MPS_RUNNING:'status-pending'};
const accounts:Record<string,string>={PERSONAL:'个人',ENTERPRISE:'企业',API:'API'};
let active:Query={to:new Date().toISOString()},version=0,userVersion=0;
const summary=ref<Awaited<ReturnType<typeof analytics>>['data']|null>(null),summaryError=ref(false),summaryBusy=ref(false),chartQuery=ref<Query>({...active}),checkedRows=ref<string[]>([]),exportBusy=ref(false);
const current=computed(()=>Math.floor(offset.value/pageSize.value)+1),pages=computed(()=>Math.max(1,Math.ceil(total.value/pageSize.value))),pageButtons=computed(()=>[...new Set([1,current.value-1,current.value,current.value+1,pages.value])].filter(n=>n>=1&&n<=pages.value).sort((a,b)=>a-b));
const previousId=computed(()=>rows.value[rows.value.findIndex(r=>r.ledger.id===recordId.value)-1]?.ledger.id),nextId=computed(()=>{const i=rows.value.findIndex(r=>r.ledger.id===recordId.value);return i>=0?rows.value[i+1]?.ledger.id:undefined;});
const allSelected=computed(()=>rows.value.length>0&&rows.value.every(r=>checkedRows.value.includes(r.ledger.id)));
const selected=computed(()=>detail.value?.item);
function debitBalances(record:Detail,debitedPoints4=record.item?.ledger?.debitedPoints4){
 const debit=record.entries?.find(entry=>entry.id===record.item?.ledger?.id&&entry.kind==='CONSUME');
 const before=units4(debit?.balanceBefore4),change=units4(debit?.points4),after=units4(debit?.balanceAfter4);
 return before!==null&&change!==null&&after!==null&&change<=0n&&before+change===after&&-change===units4(debitedPoints4)
  ? {before:debit!.balanceBefore4,after:debit!.balanceAfter4} : {before:null,after:null};
}
function yuan(value?:string|null){return units4(value)===null?'—':formatAmount(value)+' 元';}
function pointsYuan(value?:string|null){
 const points=units4(value),rate=units4(selected.value?.ledger?.rate4);
 return points===null||rate===null||rate<=0n?'—':yuan(decimal4(points*10000n/rate));
}
const detailBalances=computed(()=>detail.value?debitBalances(detail.value):{before:null,after:null});
function time(value?:string|null){return value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'未记录';}
function account(value?:string|null){return value?accounts[value]||value:'未记录';}
function checked(value:ApiItem):Item{
 const row=value.ledger;if(!row?.id||!row.taskId||!row.keyId||!row.createdAt||![row.debitedPoints4,row.refundedPoints4,row.netPoints4].every(v=>typeof v==='string'&&/^-?\d+\.\d{4}$/.test(v)))throw Error('invalid ledger');
 return value as Item;
}
async function load(clear=false){const own=++version;if(clear)rows.value=[];copyResult.value=null;detail.value=null;error.value='';if(!permitted.value)return;busy.value=true;
 try{if(recordId.value){const value=(await getDetail(recordId.value)).data;if(!value.item||!Array.isArray(value.entries))throw Error('invalid detail');checked(value.item);if(own===version)detail.value=value;}
 else {void loadSummary(own);const page=(await list({...active,offset:offset.value,limit:pageSize.value})).data;if(!Array.isArray(page.items)||typeof page.total!=='number')throw Error('invalid list');if(own!==version)return;const values=page.items.map(checked);
 rows.value=values;total.value=page.total;checkedRows.value=[];busy.value=false;
 const enriched=await mapPool(values,4,async item=>{
  if(own!==version)return item;
  const [record]=await Promise.allSettled([getDetail(item.ledger.id)]);
  let before:string|null=null,after:string|null=null,snapshot:Detail['snapshot']=null;
  if(record.status==='fulfilled'){
   const data=record.value.data,ledger=data.item?.ledger;
   if(ledger?.id===item.ledger.id&&ledger.keyId===item.ledger.keyId&&ledger.taskId===item.ledger.taskId&&ledger.ownerId===item.ledger.ownerId){
    ({before,after}=debitBalances(data,item.ledger.debitedPoints4));snapshot=data.snapshot;
   }
  }
  const enriched={...item,before,after,snapshot};
  if(own===version){const index=rows.value.findIndex(row=>row.ledger.id===item.ledger.id);if(index>=0)rows.value[index]=enriched;}
  return enriched;
 });
 if(own===version){rows.value=enriched;total.value=page.total;checkedRows.value=[];}}}
 catch{if(own===version)error.value=recordId.value?'消费详情读取失败，记录可能不存在或服务暂不可用。':'消费明细读取失败，请重试。';}
 finally{if(own===version)busy.value=false;}}
function editSearch(){userVersion++;usersBusy.value=false;userId.value='';users.value=[];userError.value='';}
async function searchUsers(){const own=++userVersion;usersBusy.value=true;userError.value='';users.value=[];
 const search=userSearch.value.trim(),isKey=search.startsWith('sk-');
 try{
  if(isKey){
   if(!primary.value){userError.value='仅主管理员可按平台 Key 查询';return false;}
   if(!/^sk-(?:[a-z0-9]{32}|_[0-9a-f]{64})$/.test(search)){userError.value='请输入完整的平台 Key';return false;}
   const key=(await lookupKey({secret:search})).data;if(own!==userVersion)return false;
   if(!key.ownerId)throw Error('invalid key owner');
   // Access enforces one primary Key per owner, so the existing owner filter covers this Key's ledger.
   userId.value=key.ownerId;userSearch.value=shortKey(search);return true;
  }
  const page=(await findUsers({search,offset:0,limit:50})).data;if(!Array.isArray(page.items))throw Error('invalid users');if(own!==userVersion)return false;
  const matches=page.items.filter(user=>user.id);if(matches.length===1){userId.value=matches[0]!.id!;userSearch.value=[matches[0]!.nickname,matches[0]!.phone].filter(Boolean).join(' · ');return true;}
  users.value=matches;if(!matches.length)userError.value='未找到匹配用户';return false;
 }catch(failure){if(own===userVersion)userError.value=isKey&&failure&&typeof failure==='object'&&'status' in failure&&failure.status===404?'未找到匹配的平台 Key':'用户检索失败，请重试。';return false;}finally{if(own===userVersion)usersBusy.value=false;}}
function chooseUser(user:User){userId.value=user.id!;userSearch.value=[user.nickname,user.phone].filter(Boolean).join(' · ');users.value=[];void apply();}
function openDate(event:Event){const input=event.target as HTMLInputElement;try{input.showPicker?.();}catch{/* Native keyboard entry remains available when the picker is unsupported. */}}
function shortKey(secret:string){return (secret.startsWith('sk-_')?'sk-_':'sk-')+'****'+secret.slice(-4);}
async function apply(){if(usersBusy.value)return;const until=to.value?new Date(to.value+'T23:59:59.999+08:00'):new Date();const since=from.value?new Date(from.value+'T00:00:00.000+08:00'):null;if(!Number.isFinite(until.getTime())||since&&(!Number.isFinite(since.getTime())||since>until)){error.value='请选择有效时间范围。';return;}if(userSearch.value.trim()&&!userId.value&&!await searchUsers())return;active={to:until.toISOString(),from:since?.toISOString(),userId:userId.value||undefined,model:model.value.trim()||undefined,status:status.value||undefined};chartQuery.value={...active};pageSize.value=size.value;offset.value=0;void load();}
function page(delta:number){offset.value=Math.max(0,offset.value+delta*pageSize.value);void load();}
async function copyKey(item:Item){
 if(!primary.value)return;const own=version;
 try{const result=await readKey(item.ledger.keyId,{cache:'no-store'});if(own!==version||!primary.value)return;if(!result.data.secret)throw Error('missing key');await navigator.clipboard.writeText(result.data.secret);if(own===version)copyResult.value={id:item.ledger.id,ok:true};}
 catch{if(own===version)copyResult.value={id:item.ledger.id,ok:false};}
}
const cash=(value?:string|null)=>{if(units4(value)===null)return '—';const [w,f='']=value!.split('.');return `${w!.replace(/\B(?=(\d{3})+(?!\d))/g,',')}.${f.padEnd(2,'0').slice(0,2)}`;};
function balanceYuan(value:string|null|undefined,item:Item){const amount=units4(value),rate=units4(item.ledger.rate4);return amount===null||rate===null||rate<=0n?null:decimal4(amount*10000n/rate);}
function boundTask(item:Item){const t=item.snapshot?.task;return t&&t.id===item.ledger.taskId&&t.type===item.ledger.type&&t.model===item.ledger.model?t:null;}
function quantity(item:Item){const t=boundTask(item);return !t||t.count<=0||t.type==='VIDEO'&&t.durationSeconds<=0?null:t.type==='VIDEO'?t.durationSeconds*t.count:t.type==='IMAGE'?t.count:null;}
function unitPrice(item:Item){const n=units4(item.ledger.amount4),q=quantity(item);return n===null||!q?null:decimal4(n/BigInt(q));}
async function loadSummary(own:number){summaryBusy.value=true;summaryError.value=false;summary.value=null;try{const result=await analytics({...active});if(own===version)summary.value=result.data;}catch{if(own===version){summary.value=null;summaryError.value=true;}}finally{if(own===version)summaryBusy.value=false;}}
function reset(){userVersion++;usersBusy.value=false;userSearch.value='';userId.value='';users.value=[];userError.value='';model.value=status.value=from.value=to.value='';size.value=10;void apply();}
function resizePage(){pageSize.value=size.value;offset.value=0;void load();}
function jump(n:number){offset.value=(n-1)*pageSize.value;void load();}
function selectAll(){checkedRows.value=allSelected.value?[]:rows.value.map(row=>row.ledger.id);}
function chartFilter(value:{model?:string;from:string;to:string}){model.value=value.model||'';from.value=value.from;to.value=value.to;void apply();}
async function exportCsv(){if(exportBusy.value||busy.value)return;const own=version,query={...active};exportBusy.value=true;error.value='';try{let records=rows.value.filter(r=>checkedRows.value.includes(r.ledger.id));if(!checkedRows.value.length){records=[];for(let start=0;;start+=100){const result=(await list({...query,offset:start,limit:100})).data;if(own!==version)return;if(!Array.isArray(result.items)||!Number.isSafeInteger(result.total)||result.total<0||!result.items.length&&start<result.total)throw Error();records.push(...result.items.map(checked));if(start+result.items.length>=result.total||!result.items.length)break;}records=await mapPool(records,4,async item=>{if(own!==version)return item;try{const data=(await getDetail(item.ledger.id)).data;if(data.item?.ledger?.id!==item.ledger.id||data.item.ledger.keyId!==item.ledger.keyId||data.item.ledger.taskId!==item.ledger.taskId||data.item.ledger.ownerId!==item.ledger.ownerId)return item;return {...item,...debitBalances(data,item.ledger.debitedPoints4),snapshot:data.snapshot};}catch{return item;}});}
 if(own!==version)return;const cell=(value:unknown)=>{let text=String(value??'');if(/^[\s]*[=+\-@]/.test(text)||/^[\t\r]/.test(text))text="'"+text;return '"'+text.replaceAll('"','""')+'"';};
 const lines=[['用户','时间','类型','模型','分辨率','生成量','单价（元）','消费前额度（元）','消费金额（元）','消费后额度（元）','状态','公开任务ID'],...records.map(r=>[r.user?.nickname,r.ledger.createdAt,r.ledger.type,r.modelName||r.ledger.model,boundTask(r)?.resolution,quantity(r),unitPrice(r),balanceYuan(r.before,r),r.ledger.amount4,balanceYuan(r.after,r),statuses[r.taskStatus||'MISSING']||r.taskStatus,r.ledger.taskId])];
 const url=URL.createObjectURL(new Blob(['\ufeff'+lines.map(line=>line.map(cell).join(',')).join('\r\n')],{type:'text/csv;charset=utf-8;'}));const link=document.createElement('a');link.href=url;link.download='消费明细.csv';link.click();URL.revokeObjectURL(url);
 }catch{if(own===version)error.value='导出失败，请重试。';}finally{exportBusy.value=false;}}
watch(()=>{const identity=session?.currentAdmin.value;return JSON.stringify([route.fullPath,identity?.role,identity?.primary,identity?.userId,identity?.username]);},()=>{userVersion++;usersBusy.value=false;users.value=[];userError.value='';checkedRows.value=[];if(!recordId.value&&typeof route.query.userId==='string'&&route.query.userId){userId.value=route.query.userId;userSearch.value=route.query.userId;model.value=status.value=from.value=to.value='';active={userId:userId.value,to:new Date().toISOString()};chartQuery.value={...active};offset.value=0;}void load();},{immediate:true,flush:'sync'});
onBeforeUnmount(()=>{version++;userVersion++;});
</script>
<template><section class="consumption-page" aria-label="消费明细"><div v-if="!permitted" class="empty-state"><h1>无权限</h1><p>仅管理员可查看消费明细。</p></div><template v-else>
<div class="consumption-breadcrumb"><PromoterIcon name="home"/>运营管理 <span>›</span><RouterLink to="/admin/consumption">消费明细</RouterLink><template v-if="recordId"><span>›</span>记录详情</template></div>
<template v-if="!recordId"><header class="consumption-heading"><div><h1>消费明细</h1><p>查看所有生成视频模型与生图模型的消费记录，支持多维度筛选与导出。</p></div></header><div class="analysis-disclosure"><button type="button" :aria-expanded="showAnalysis" aria-controls="consumption-analysis" @click="showAnalysis=!showAnalysis"><PromoterIcon name="chart"/>{{showAnalysis?'收起消费分析':'展开消费分析'}}</button><span>按当前查询查看趋势与模型分布</span></div><div v-if="showAnalysis" id="consumption-analysis"><ConsumptionCharts :query="chartQuery" :summary="summary" :summary-busy="summaryBusy" :summary-error="summaryError" @retry-summary="loadSummary(version)" @filter="chartFilter"/></div>
<section class="consumption-list-panel"><div class="consumption-list-toolbar"><h2>消费明细 <small>共 {{total.toLocaleString()}} 条记录</small></h2><span class="consumption-list-total"><PromoterIcon name="calendar"/>总金额 <strong>{{summary?.amount4==null?'—':'¥ '+cash(summary.amount4)}}</strong></span><form class="consumption-filters" @submit.prevent="apply"><div class="consumption-user-search"><label><PromoterIcon name="user"/><span class="consumption-user-label">用户</span><input v-model="userSearch" aria-label="搜索消费用户" :placeholder="primary?'用户 / 手机号 / 平台 Key':'用户 / 手机号'" autocomplete="off" spellcheck="false" @input="editSearch"/></label><ul v-if="users.length" class="consumption-user-results" aria-label="用户检索结果"><li v-for="user in users" :key="user.id||''"><button type="button" @click="chooseUser(user)">{{user.nickname}} · {{user.phone}}</button></li></ul><small v-if="userError" role="alert">{{userError}}</small></div><div class="consumption-date-range"><input v-model="from" type="date" aria-label="开始时间" @click="openDate"/><span>→</span><input v-model="to" type="date" aria-label="截止时间" @click="openDate"/></div><input v-model="model" aria-label="模型标识" list="consumption-models" placeholder="全部模型"/><datalist id="consumption-models"><option v-for="entry in summary?.models||[]" :key="entry.type+'/'+entry.model" :value="entry.model||''">{{entry.modelName}}</option></datalist><select v-model="status" aria-label="任务状态"><option value="">全部状态</option><option v-for="(name,key) in statuses" :key="key" :value="key">{{name}}</option></select><div class="consumption-filter-actions"><button type="button" :disabled="busy||usersBusy" @click="reset">重置</button><button class="primary-action" type="submit" :disabled="busy||usersBusy">{{busy||usersBusy?'查询中…':'查询'}}</button><button type="button" :disabled="busy||exportBusy||!total" @click="exportCsv"><PromoterIcon name="download"/>{{exportBusy?'导出中…':checkedRows.length?'导出所选':'导出数据'}}</button></div></form></div>
<p v-if="summaryError" role="alert">消费汇总读取失败或超时，请重试或缩小查询时间范围。<button type="button" @click="loadSummary(version)">重试</button></p><div v-if="error" role="alert" class="empty-state"><p>{{error}}</p><button type="button" aria-label="重试读取" @click="load()">重试</button></div>
<div class="table-wrap consumption-table data-table-region" :aria-busy="busy" tabindex="0" role="region" aria-label="消费明细表格，可横向滚动"><TableUpdating v-if="busy&&rows.length"/><table><caption class="sr-only">消费明细</caption><thead><tr><th><input type="checkbox" aria-label="选择本页所有消费" :checked="allSelected" :disabled="busy" @change="selectAll"/></th><th>用户</th><th>时间</th><th>类型</th><th>模型</th><th>分辨率</th><th>生成量</th><th>单价</th><th class="money">消费前额度（元）</th><th class="money">消费金额（元）</th><th class="money">消费后额度（元）</th><th>状态</th><th>操作</th></tr></thead><tbody><TableLoadingRows v-if="busy&&!rows.length" :columns="13" label="正在读取消费明细…"/><tr v-for="item in rows" :key="item.ledger.id"><td><input v-model="checkedRows" type="checkbox" :value="item.ledger.id" :aria-label="'选择消费 '+item.ledger.id" :disabled="busy"/></td><td><strong>{{item.user?.nickname||'历史用户信息缺失'}}</strong><small>{{item.user?.phone||item.ledger.ownerId||'归属未记录'}}</small></td><td>{{time(item.ledger.createdAt)}}</td><td><span class="consumption-type" :class="item.ledger.type==='IMAGE'?'image':'video'">{{item.ledger.type==='IMAGE'?'图片':item.ledger.type==='VIDEO'?'视频':'—'}}</span></td><td :title="item.ledger.model||undefined">{{item.modelName||item.ledger.model||'—'}}</td><td>{{boundTask(item)?.resolution||'—'}}</td><td>{{quantity(item)==null?'—':quantity(item)+(item.ledger.type==='VIDEO'?'秒':'张')}}</td><td :title="unitPrice(item)||undefined">{{unitPrice(item)==null?'—':'¥ '+formatAmount(unitPrice(item))+(item.ledger.type==='VIDEO'?'/秒':'/张')}}</td><td data-column="before" class="money" :title="balanceYuan(item.before,item)||undefined">{{cash(balanceYuan(item.before,item))}}</td><td data-column="amount" class="money" :title="item.ledger.amount4||undefined">{{cash(item.ledger.amount4)}}</td><td data-column="after" class="money" :title="balanceYuan(item.after,item)||undefined">{{cash(balanceYuan(item.after,item))}}</td><td><span class="status" :class="statusClasses[item.taskStatus||'']">● {{statuses[item.taskStatus||'MISSING']||item.taskStatus}}</span></td><td><div class="consumption-row-actions"><RouterLink :to="'/admin/consumption/'+encodeURIComponent(item.ledger.id)">查看</RouterLink><details><summary :aria-label="'消费 '+item.ledger.id+' 的更多操作'"><PromoterIcon name="more"/></summary><div><span>{{account(item.currentAccountType)}}</span><code>{{item.ledger.taskId}}</code><span v-if="primary" class="consumption-key"><code class="consumption-platform-key">Key · {{item.ledger.keyId.slice(-8)}}</code><button type="button" aria-label="复制本条消费的平台 Key" @click="copyKey(item)">{{copyResult?.id===item.ledger.id?(copyResult.ok?'已复制':'复制失败，重试'):'复制'}}</button></span></div></details></div></td></tr></tbody></table><p v-if="busy&&!rows.length" role="status" class="consumption-empty">正在读取真实消费记录…</p><p v-else-if="!rows.length&&!error" class="consumption-empty">暂无消费记录</p></div><nav class="consumption-pagination" aria-label="消费分页"><span>共 {{total.toLocaleString()}} 条记录<small v-if="checkedRows.length"> · 已选择 {{checkedRows.length}} 项</small></span><div><button type="button" aria-label="上一页" :disabled="busy||offset===0" @click="page(-1)"><PromoterIcon name="chevron-left"/></button><button v-for="n in pageButtons" :key="n" type="button" :aria-current="current===n?'page':undefined" :disabled="busy" @click="jump(n)">{{n}}</button><button type="button" aria-label="下一页" :disabled="busy||offset+pageSize>=total" @click="page(1)"><PromoterIcon name="chevron-right"/></button></div><select v-model.number="size" aria-label="每页条数" @change="resizePage"><option :value="10">10 条/页</option><option :value="20">20 条/页</option><option :value="50">50 条/页</option><option :value="100">100 条/页</option></select></nav></section></template>
<template v-else><div v-if="error" role="alert" class="empty-state"><h1>消费详情</h1><p>{{error}}</p><button type="button" aria-label="重试读取" @click="load()">重试</button></div><p v-else-if="busy&&!detail" role="status">正在读取真实消费记录…</p><ConsumptionDetail v-if="detail&&selected?.ledger" :key="recordId" :detail="detail" :record-id="recordId" :balances="detailBalances" :previous-id="previousId" :next-id="nextId" :busy="busy" @refresh="load()"/></template>
</template></section></template>
