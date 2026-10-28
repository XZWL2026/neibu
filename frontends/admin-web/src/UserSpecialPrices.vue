<script setup lang="ts">
import {computed,inject,onBeforeUnmount,onMounted,ref} from 'vue';
import {adminSessionKey} from './adminSession';
import ApiClientPrices from './ApiClientPrices.vue';
import {ApiError,refreshSession,userDirectoryGetApiV1AdminUsersDirectory,billingGetGetApiV1SettingsRuntime,
 billingGetGetApiV1AdminBillingUsersOwnerIdMediaPrices as getPrices,billingSavePutApiV1AdminBillingUsersOwnerIdMediaPrices as putPrices,
 billingAuditGetApiV1AdminBillingUsersOwnerIdMediaPricesAudit as getAudit,type RequestBillingDomainPricingMediaPrice as Price,type ResponseRoutingInterfacesHttpRoutingAdminControllerModelView as Model} from './api';
defineProps<{models?:Model[]}>();
const session=inject(adminSessionKey),primary=computed(()=>session?.currentAdmin.value?.role==='ADMIN'&&session.currentAdmin.value.primary===true);
type User=NonNullable<Awaited<ReturnType<typeof userDirectoryGetApiV1AdminUsersDirectory>>['data']['items']>[number];
type Snapshot=Awaited<ReturnType<typeof getPrices>>['data'];
type Pending=Parameters<typeof putPrices>[1];
const labels:Record<string,string>={PERSONAL:'个人',ENTERPRISE:'企业',API:'API'};
const users=ref<User[]>([]),total=ref(0),query=ref(''),filter=ref(''),searchBusy=ref(false),searchError=ref('');
const owner=ref(''),selectedUser=ref<User>(),snapshot=ref<Snapshot>(),prices=ref<Price[]>([]),rate=ref(''),editor=ref<InstanceType<typeof ApiClientPrices>>();
const busy=ref(false),saving=ref(false),ready=ref(false),conflict=ref(false),error=ref(''),pending=ref<Pending|null>(null);
const audit=ref<Awaited<ReturnType<typeof getAudit>>['data']>([]),auditError=ref('');
let alive=true,searchVersion=0,ownerVersion=0,auditVersion=0,storageKey='',adminId='';
let submittedSearch:{search?:string;accountType?:string}={};
const selectedOptions=computed(()=>selectedUser.value&&!users.value.some(u=>u.id===owner.value)?[selectedUser.value,...users.value]:users.value);
const blocked=computed(()=>saving.value||!!pending.value);
const dimension=(p:Price)=>[p.type,p.accountType,p.model,p.resolution].join('/');
function apply(value:Snapshot){snapshot.value=value;const enabled=new Set((value.defaults||[]).map(dimension));prices.value=(value.overrides||[]).filter(p=>enabled.has(dimension(p))).map(p=>({...p}));}
async function search(more=false){if(!primary.value)return;const request=++searchVersion;searchBusy.value=true;searchError.value='';const offset=more?users.value.length:0;const criteria=more?submittedSearch:{search:query.value.trim()||undefined,accountType:filter.value||undefined};
 try{const result=await userDirectoryGetApiV1AdminUsersDirectory({offset,limit:20,...criteria});if(!alive||request!==searchVersion)return;submittedSearch=criteria;users.value=more?[...users.value,...(result.data.items||[])]:result.data.items||[];total.value=result.data.total;}
 catch{if(alive&&request===searchVersion)searchError.value='用户读取失败，请重试。';}finally{if(alive&&request===searchVersion)searchBusy.value=false;}}
function remember(){try{if(pending.value)sessionStorage.setItem(storageKey,JSON.stringify(pending.value));else sessionStorage.removeItem(storageKey);}catch{/* Keep the exact request in memory if browser storage is unavailable. */}}
async function loadAudit(id=owner.value,version=ownerVersion){const request=++auditVersion;auditError.value='';try{const result=await getAudit(id);if(alive&&version===ownerVersion&&request===auditVersion)audit.value=result.data;}catch{if(alive&&version===ownerVersion&&request===auditVersion)auditError.value='价格记录读取失败，请重试。';}}
async function selectOwner(){if(!primary.value||blocked.value)return;const version=++ownerVersion,id=owner.value;selectedUser.value=selectedOptions.value.find(u=>u.id===id);snapshot.value=undefined;prices.value=[];audit.value=[];pending.value=null;error.value='';auditError.value='';ready.value=false;conflict.value=false;busy.value=!!id;if(!id)return;
 try{if(!adminId){const auth=await refreshSession();if(!auth.authenticated||!auth.user?.id)throw Error();adminId=auth.user.id;}if(!alive||version!==ownerVersion)return;
  storageKey=`xzwl-user-prices:${adminId}:${id}`;const saved=JSON.parse(sessionStorage.getItem(storageKey)||'null');
  if(saved&&(typeof saved.requestId!=='string'||!Number.isSafeInteger(saved.expectedVersion)||saved.expectedVersion<0||!Array.isArray(saved.prices)))throw Error();
  pending.value=saved;
  const [catalog,runtime]=await Promise.all([getPrices(id),billingGetGetApiV1SettingsRuntime()]);if(!alive||version!==ownerVersion)return;
  rate.value=runtime.data.rechargePointsPerYuan4||'';apply(catalog.data);if(saved)prices.value=saved.prices;ready.value=true;void loadAudit(id,version);
 }catch{if(alive&&version===ownerVersion)error.value='用户价格读取失败，请重试。';}finally{if(alive&&version===ownerVersion)busy.value=false;}}
async function reload(){if(!primary.value||saving.value)return;if(pending.value){const version=ownerVersion;try{const [result,runtime]=await Promise.all([getPrices(owner.value),billingGetGetApiV1SettingsRuntime()]);if(alive&&version===ownerVersion){snapshot.value=result.data;rate.value=runtime.data.rechargePointsPerYuan4||'';ready.value=true;error.value='';void loadAudit();}}catch{if(alive&&version===ownerVersion)error.value='用户价格读取失败，请重试。';}}else await selectOwner();}
async function save(){if(!primary.value||!ready.value||saving.value||!snapshot.value||conflict.value||(!pending.value&&!editor.value?.valid))return;
 const version=ownerVersion,id=owner.value,wasRetry=!!pending.value;saving.value=true;error.value='';
 if(!pending.value){pending.value={requestId:crypto.randomUUID(),expectedVersion:snapshot.value.version,prices:prices.value.map(p=>({...p}))};remember();}
 try{const result=await putPrices(id,pending.value);if(!alive||version!==ownerVersion)return;pending.value=null;remember();apply(result.data);void loadAudit(id,version);return true;}
 catch(cause){if(alive&&version===ownerVersion){if(cause instanceof ApiError&&([400,422].includes(cause.status)||!wasRetry&&[403,409].includes(cause.status))){pending.value=null;remember();conflict.value=cause.status===409;error.value=conflict.value?'价格已被修改，请重新读取后核对。':'价格未保存，请检查输入与权限。';}else error.value='价格保存结果尚未确认，请重试原修改。';}}
 finally{if(alive&&version===ownerVersion)saving.value=false;}}
async function saveSelected(){if(await save())editor.value?.advance();}
onMounted(()=>{void search();});onBeforeUnmount(()=>{alive=false;ownerVersion++;searchVersion++;});
</script>
<template>
<section v-if="primary" class="user-special-prices">
 <form class="pricing-user-search" @submit.prevent="search()"><select v-model="filter" aria-label="用户账户类型" :disabled="searchBusy"><option value="">全部用户类型</option><option v-for="(label,type) in labels" :key="type" :value="type">{{label}}</option></select><input v-model="query" aria-label="搜索定价用户" placeholder="搜索用户昵称、手机号…"/><button type="submit" :disabled="searchBusy">搜索用户</button></form>
 <p v-if="searchError" role="alert">{{searchError}}</p>
 <div class="pricing-user-picker"><label for="pricing-user">选择用户</label><select id="pricing-user" v-model="owner" aria-label="选择定价用户" :disabled="blocked" @change="selectOwner"><option value="">请选择用户</option><option v-for="user in selectedOptions" :key="user.id||undefined" :value="user.id||''" :disabled="!labels[user.accountType||'']">{{user.nickname||user.phone||user.id}} · {{labels[user.accountType||'']||'未设置'}} · {{user.phone||user.id}}</option></select><button v-if="users.length<total" type="button" :disabled="searchBusy" @click="search(true)">加载更多用户</button></div>
 <p v-if="busy" role="status">正在读取用户价格…</p><p v-if="error" role="alert">{{error}} <button v-if="!pending||!ready" type="button" @click="reload">重新读取价格</button></p>
 <section v-if="snapshot" class="pricing-special-editor"><header class="pricing-special-heading"><h2>{{selectedUser?.nickname||selectedUser?.phone||owner}} <small>{{labels[selectedUser?.accountType||'']}}</small></h2><button type="button" class="primary-action" aria-label="保存用户价格" :disabled="!ready||saving||conflict||(!pending&&!editor?.valid)" @click="save">{{saving?'保存中…':pending?'重试原修改':'保存用户价格'}}</button></header>
 <ApiClientPrices ref="editor" model-layout :models="models" :saved-prices="snapshot.overrides||[]" @save="saveSelected" v-model="prices" :prices="snapshot.defaults||[]" :account-type="selectedUser?.accountType||''" :points-per-yuan4="rate" :disabled="blocked||!ready||conflict"/>
 </section>
 <section v-if="snapshot" class="pricing-section"><header class="pricing-special-heading"><h2>价格修改记录</h2></header><p v-if="auditError" role="alert">{{auditError}} <button type="button" @click="loadAudit()">重试</button></p><div class="pricing-table-wrap"><table v-if="audit.length"><thead><tr><th>时间</th><th>操作人</th><th>修改内容</th></tr></thead><tbody><tr v-for="row in audit" :key="row.requestId||undefined"><td>{{row.changedAt?new Date(row.changedAt).toLocaleString('zh-CN',{hour12:false}):'—'}}</td><td>{{row.operatorId}}</td><td><template v-if="row.prices?.length"><div v-for="p in row.prices" :key="dimension(p)">{{p.model}} / {{p.resolution}}：{{p.yuanPerUnit}} 元/{{p.unit==='SECOND'?'秒':'张'}}</div></template><template v-else>恢复默认价格</template></td></tr></tbody></table><p v-else-if="!auditError" class="pricing-empty">暂无价格修改记录</p></div></section>
</section>
</template>
