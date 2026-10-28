<script setup lang="ts">
import TableLoadingRows from './components/TableLoadingRows.vue';
import LoadingValue from './components/LoadingValue.vue';
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import Icon from './PromoterIcon.vue';
import ApiRecharge from './ApiRecharge.vue';
import ApiModelUsage from './ApiModelUsage.vue';
import { accessListGetApiV1AccessKeys as listKeys, accessSecretGetApiV1AccessKeysIdSecret as readSecret, billingBalanceGetApiV1BillingWalletsKeyId as readBalance, billingSummaryGetApiV1BillingPortalSummary as readSummary, billingConsumptionsGetApiV1BillingPortalConsumptions as listConsumption } from './api';
import { money, points, shown, units4 } from './portalAmounts';
import { dayKey, monthDates, dateRange, localTime, sumPoints } from './apiConsolePresentation';
const props=defineProps<{owner:string}>();
type Summary=Awaited<ReturnType<typeof readSummary>>['data'];
type Row=NonNullable<Awaited<ReturnType<typeof listConsumption>>['data']['items']>[number];
type Key=Awaited<ReturnType<typeof listKeys>>['data'][number];
const summary=ref<Summary|null>(null),today=ref<Summary|null>(null),yesterday=ref<Summary|null>(null),balance=ref<string|null>(null),keys=ref<Key[]>([]);
const records=ref<Row[]>([]),total=ref(0),offset=ref(0),usageDates=ref(monthDates()),usageLoading=ref(false),usageError=ref('');
const dailyDates=ref(monthDates()),dailyOffset=ref(0),dailyRows=ref<{date:string;amount:string|null}[]>([]),dailyTotal=ref<string|null>(null),dailyLoading=ref(false),dailyError=ref('');
const loading=ref(false),error=ref(''),rechargeOpen=ref(false),secrets=ref<Record<string,string>>({}),keyBusy=ref(''),keyNotice=ref('');
const details=ref<Row|null>(null),dialog=ref<HTMLDialogElement|null>(null);
const size=10;
let baseOwner='';
let generation=0,baseAbort=new AbortController(),usageAbort=new AbortController(),dailyAbort=new AbortController(),usageRevision=0,dailyRevision=0;
const dailyCount=computed(()=>{const range=dateRange(dailyDates.value);return range?Math.round((Date.parse(range.to)-Date.parse(range.from))/86400000):0;});
const metrics=computed(()=>[
 {label:'总充值',value:money(summary.value?.totalRechargeAmount4),icon:'database',tone:'green'},
 {label:'剩余额度',value:points(balance.value),icon:'card',tone:'blue'},
 {label:'今日消费',value:money(today.value?.chargedAmount4),icon:'bolt',tone:'amber'},
 {label:'昨日消费',value:money(yesterday.value?.chargedAmount4),icon:'calendar',tone:'pink'}
]);
async function loadBase(){
 const revision=++generation,owner=props.owner;baseAbort.abort();baseAbort=new AbortController();
 if(baseOwner!==owner){summary.value=null;today.value=null;yesterday.value=null;balance.value=null;keys.value=[];baseOwner=owner;}secrets.value={};keyNotice.value='';keyBusy.value='';error.value='';loading.value=false;
 if(!owner){error.value='无法读取真实账户：登录身份缺少业务用户，请重新登录';return;}loading.value=true;
 try{
 const options={signal:baseAbort.signal},now=new Date(),start=new Date(now);start.setHours(0,0,0,0);const previous=new Date(start);previous.setDate(previous.getDate()-1);
 const [all,t,y,k]=await Promise.all([readSummary(undefined,options),readSummary({from:start.toISOString(),to:now.toISOString()},options),readSummary({from:previous.toISOString(),to:start.toISOString()},options),listKeys(options)]);
 if(k.data.some(key=>key.ownerId!==owner))throw Error('密钥归属不匹配');
 if(revision!==generation)return;
 const wallets=await Promise.all(k.data.map(async key=>{if(!key.keyId)return null;const w=(await readBalance(key.keyId,options)).data;if(w.walletId!==key.keyId)throw Error('钱包归属不匹配');return w.availablePoints4;}));
 if(revision!==generation)return;
 summary.value=all.data;today.value=t.data;yesterday.value=y.data;keys.value=k.data;balance.value=wallets.length?sumPoints(wallets):null;
 }catch(e){if(revision===generation)error.value=`无法读取真实数据：${e instanceof Error?e.message:'服务暂不可用'}`;}finally{if(revision===generation)loading.value=false;}
}
async function loadUsage(){
 const rev=++usageRevision,owner=props.owner;usageAbort.abort();usageAbort=new AbortController();records.value=[];total.value=0;usageError.value='';usageLoading.value=false;
 const range=dateRange(usageDates.value);if(!owner)return;if(!range){usageError.value='请选择有效日期范围';return;}usageLoading.value=true;
 try{const result=(await listConsumption({...range,offset:offset.value,limit:size},{signal:usageAbort.signal})).data;if(result.items?.some(row=>row.ownerId!==owner))throw Error('消费归属不匹配');if(rev===usageRevision){records.value=result.items??[];total.value=result.total;}}
 catch(e){if(rev===usageRevision)usageError.value=e instanceof Error?e.message:'调用记录暂不可用';}finally{if(rev===usageRevision)usageLoading.value=false;}
}
async function loadDays(){
 const rev=++dailyRevision;dailyAbort.abort();dailyAbort=new AbortController();dailyRows.value=[];dailyTotal.value=null;dailyError.value='';dailyLoading.value=false;
 const range=dateRange(dailyDates.value);if(!props.owner)return;if(!range){dailyError.value='请选择有效日期范围';return;}dailyLoading.value=true;
 const end=new Date(`${dailyDates.value.to}T00:00:00`);
 const days=Array.from({length:Math.min(size,Math.max(0,dailyCount.value-dailyOffset.value))},(_,i)=>{const day=new Date(end);day.setDate(day.getDate()-dailyOffset.value-i);return dayKey(day);});
 try{const options={signal:dailyAbort.signal};const [overall,rows]=await Promise.all([readSummary(range,options),Promise.all(days.map(async date=>({date,amount:(await readSummary(dateRange({from:date,to:date})!,options)).data.chargedAmount4})))]);
 if(rev===dailyRevision){dailyRows.value=rows;dailyTotal.value=overall.data.chargedAmount4;}}
 catch(e){if(rev===dailyRevision)dailyError.value=e instanceof Error?e.message:'每日消费暂不可用';}finally{if(rev===dailyRevision)dailyLoading.value=false;}
}
async function accessKey(key:Key,copy=false){
 if(!key.keyId||keyBusy.value)return;
 if(!copy&&secrets.value[key.keyId]){delete secrets.value[key.keyId];return;}
 const revision=generation,owner=props.owner,id=key.keyId;keyBusy.value=id;keyNotice.value='';
 try{const secret=secrets.value[id]||(await readSecret(id,{signal:baseAbort.signal})).data.secret;
 if(revision!==generation||owner!==props.owner)return;if(!secret)throw Error('密钥读取失败');
 if(copy){await navigator.clipboard.writeText(secret);if(revision===generation)keyNotice.value='密钥已复制';}else secrets.value[id]=secret;
 }catch(e){if(revision===generation)keyNotice.value=e instanceof Error?e.message:'密钥操作未完成';}finally{if(revision===generation)keyBusy.value='';}
}
async function showDetails(row:Row){details.value=row;await nextTick();dialog.value?.showModal();}
watch(()=>props.owner,()=>{rechargeOpen.value=false;details.value=null;if(dialog.value?.open)dialog.value.close();void loadBase();},{immediate:true});
watch([()=>props.owner,()=>usageDates.value.from,()=>usageDates.value.to],()=>{offset.value=0;},{flush:'sync'});
watch([()=>props.owner,()=>usageDates.value.from,()=>usageDates.value.to,offset],()=>void loadUsage(),{immediate:true});
watch([()=>props.owner,()=>dailyDates.value.from,()=>dailyDates.value.to],()=>{dailyOffset.value=0;},{flush:'sync'});
watch([()=>props.owner,()=>dailyDates.value.from,()=>dailyDates.value.to,dailyOffset],()=>void loadDays(),{immediate:true});
onBeforeUnmount(()=>{generation++;usageRevision++;dailyRevision++;baseAbort.abort();usageAbort.abort();dailyAbort.abort();secrets.value={};});
</script>
<template>
 <section class="api-reference-page" aria-labelledby="api-dashboard-title">
  <header class="api-reference-heading"><div><p>API CONSOLE</p><h1 id="api-dashboard-title">API 仪表盘</h1><p>一站式管理 API 使用情况，实时掌握消费与额度。</p></div><div class="api-page-actions"><button @click="rechargeOpen=!rechargeOpen"><Icon name="card"/>充值</button><RouterLink to="/api/transfer"><Icon name="bank"/>公户转账</RouterLink></div></header>
  <p v-if="error" role="alert">{{error}} <button data-testid="portal-retry" @click="loadBase">重试</button></p>
  <div class="api-summary-grid" :aria-busy="loading"><article v-for="(metric,index) in metrics" :key="metric.label" class="api-summary-card" :class="metric.tone"><span class="api-stat-icon"><Icon :name="metric.icon"/></span><h2>{{metric.label}}</h2><strong :data-testid="index===1?'api-balance':undefined"><LoadingValue :loading="loading&&!summary">{{metric.value}}</LoadingValue></strong><div v-if="index===0" class="api-card-actions"><button @click="rechargeOpen=!rechargeOpen">充值 →</button><RouterLink to="/api/transfer">公户转账 →</RouterLink></div></article></div>
  <ApiRecharge v-if="rechargeOpen" :key="owner" :owner="owner" @settled="loadBase"/>
  <div class="api-ledger-grid">
   <section class="api-record-panel"><header><h2>调用记录</h2><div class="api-date-range"><input type="date" aria-label="调用开始日期" v-model="usageDates.from" :max="usageDates.to"/><span>～</span><input type="date" aria-label="调用结束日期" v-model="usageDates.to" :min="usageDates.from"/></div></header>
    <p v-if="usageError" role="alert">{{usageError}} <button @click="loadUsage">重试</button></p>
    <div class="api-record-scroll" tabindex="0" :aria-busy="usageLoading"><table aria-label="调用记录"><thead><tr><th>时间</th><th>模型</th><th>规格</th><th>类型</th><th class="api-numeric">金额（元）</th><th>状态</th><th>操作</th></tr></thead><tbody><TableLoadingRows v-if="usageLoading&&!records.length" :columns="7" label="正在读取调用记录…"/><tr v-for="item in records" :key="item.id??''"><td>{{localTime(item.createdAt)}}</td><td><span class="api-model-tag" :title="item.model || undefined">{{shown(item.model)}}</span></td><td>{{shown(item.resolution)}}</td><td><span class="api-badge red">消费</span></td><td class="api-debit api-numeric">{{money(item.amount4)}}</td><td><span class="api-badge" :class="(units4(item.refundedPoints4)??0n)>0n?'blue':'green'">{{(units4(item.refundedPoints4)??0n)>0n?'有退款':'已记账'}}</span></td><td><button class="api-text-button" @click="showDetails(item)">查看详情</button></td></tr><tr v-if="!usageLoading&&!records.length"><td colspan="7" class="api-empty">{{usageLoading?'正在读取…':usageError?'—':'暂无调用记录'}}</td></tr></tbody></table></div>
    <nav class="api-pagination" aria-label="调用记录分页"><span>共 {{usageLoading?'—':total}} 条记录</span><button type="button" :disabled="usageLoading||offset===0" @click="offset-=size" aria-label="上一页"><Icon name="chevron-left"/></button><span class="api-current-page" aria-current="page">{{offset/size+1}}</span><button type="button" :disabled="usageLoading||offset+size>=total" @click="offset+=size" aria-label="下一页"><Icon name="chevron-right"/></button></nav>
   </section>
   <section class="api-record-panel"><header><h2>每日消费查询列表</h2><div class="api-range-total"><span>区间总消费（元）</span><strong><LoadingValue :loading="dailyLoading">{{money(dailyTotal)}}</LoadingValue></strong></div><div class="api-date-range"><input type="date" aria-label="每日消费开始日期" v-model="dailyDates.from" :max="dailyDates.to"/><span>～</span><input type="date" aria-label="每日消费结束日期" v-model="dailyDates.to" :min="dailyDates.from"/></div></header>
    <p v-if="dailyError" role="alert">{{dailyError}} <button @click="loadDays">重试</button></p>

    <div class="api-record-scroll" tabindex="0" :aria-busy="dailyLoading"><table aria-label="每日消费查询列表"><thead><tr><th>日期</th><th class="api-numeric">消费（元）</th></tr></thead><tbody><TableLoadingRows v-if="dailyLoading&&!dailyRows.length" :columns="2" label="正在读取每日消费…"/><tr v-for="day in dailyRows" :key="day.date"><td>{{day.date}}</td><td class="api-debit api-numeric">{{money(day.amount)}}</td></tr><tr v-if="!dailyLoading&&!dailyError&&!dailyRows.length"><td colspan="2" class="api-empty">{{dailyLoading?'正在读取…':'暂无消费记录'}}</td></tr></tbody></table></div>
    <nav class="api-pagination" aria-label="每日消费分页"><span>共 {{dailyCount}} 天</span><button type="button" :disabled="dailyLoading||dailyOffset===0" @click="dailyOffset-=size" aria-label="上一页"><Icon name="chevron-left"/></button><span class="api-current-page" aria-current="page">{{dailyOffset/size+1}}</span><button type="button" :disabled="dailyLoading||dailyOffset+size>=dailyCount" @click="dailyOffset+=size" aria-label="下一页"><Icon name="chevron-right"/></button></nav>
   </section>
  </div>
  <ApiModelUsage :owner="owner"/>
  <section class="api-record-panel api-key-panel"><h2>API 密钥</h2><div v-if="loading&&!keys.length" class="api-key-row" role="status" aria-label="正在读取密钥"><LoadingValue :loading="true"/></div><div v-for="key in keys" :key="key.keyId??''" class="api-key-row"><div><code class="api-key-secret">{{secrets[key.keyId??'']||'••••••••••••••••••••••••••••••••••••••••'}}</code><button :aria-label="secrets[key.keyId??'']?'隐藏 API 密钥':'显示 API 密钥'" :disabled="!!keyBusy" @click="accessKey(key)"><Icon name="eye"/></button></div><button type="button" :disabled="!!keyBusy" @click="accessKey(key,true)"><Icon name="copy"/>复制</button></div><p v-if="!loading&&!error&&!keys.length">暂无密钥</p><span v-if="keyNotice" role="status">{{keyNotice}}</span></section>
  <dialog ref="dialog" class="api-details-dialog" aria-label="调用详情" @close="details=null"><template v-if="details"><header><h2>调用详情</h2><button @click="dialog?.close()">关闭</button></header><dl><dt>任务 ID</dt><dd>{{shown(details.taskId)}}</dd><dt>模型 / 类型</dt><dd>{{shown(details.model)}} / {{shown(details.type)}}</dd><dt>规格</dt><dd>{{shown(details.resolution)}}</dd><dt>消费金额</dt><dd>{{money(details.amount4)}}</dd><dt>扣除积分</dt><dd>{{points(details.debitedPoints4)}}</dd><dt>退回积分</dt><dd>{{points(details.refundedPoints4)}}</dd><dt>净消耗积分</dt><dd>{{points(details.netPoints4)}}</dd><dt>时间</dt><dd>{{localTime(details.createdAt)}}</dd></dl></template></dialog>
 </section>
</template>
