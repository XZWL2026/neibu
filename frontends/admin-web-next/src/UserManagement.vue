<script setup lang="ts">
import PromoterIcon from './PromoterIcon.vue';
import './user-create.css';
import {enterAdminPortal} from './adminPortalEntry';
import { formatAmount } from './shared/formatAmount';

import {computed,inject,nextTick,onBeforeUnmount,onMounted,ref,watch} from 'vue';
import {RouterLink,useRoute,useRouter} from 'vue-router';
import {adminSessionKey,canAccessAdminPath} from './adminSession';
import {ApiError,paymentAdminAccountPatchApiV1AdminPaymentsAccountsId,refreshSession,promotionListPromotersGetApiV1AdminPromotionsPromoters,userListGetApiV1AdminUsers,userDetailGetApiV1AdminUsersId,userCreatePostApiV1AdminUsers,accessAdminListGetApiV1AdminAccessKeys,accessAdminGetGetApiV1AdminAccessKeysId,accessAdminSecretGetApiV1AdminAccessKeysIdSecret,paymentAdminIdentitiesGetApiV1AdminPaymentsAccountIdentities,billingBalanceGetApiV1BillingWalletsKeyId,userStatusPatchApiV1AdminUsersIdStatus} from './api';
import UserAccount from './UserAccount.vue';
import AmountAdjustment from './AmountAdjustment.vue';
const props=defineProps<{embedded?:boolean}>();
const emit=defineEmits<{changed:[]}>();
type UserView=Awaited<ReturnType<typeof userDetailGetApiV1AdminUsersId>>['data'];
type User=UserView&{id:string};
function usableUser(value:UserView):User{if(!value.id)throw new Error('用户 ID 缺失');return {...value,id:value.id};}
type Key=Awaited<ReturnType<typeof accessAdminListGetApiV1AdminAccessKeys>>['data'][number];
type Row={user:User;key:Key|null;accountType:string|null;balance:string;keyLoaded:boolean;keyLoading:boolean;secretLoaded:boolean;secret:string|null};
type CreateBody=Parameters<typeof userCreatePostApiV1AdminUsers>[0];
const route=useRoute(),router=useRouter(),session=inject(adminSessionKey),dialog=ref<HTMLDialogElement|null>(null),newButton=ref<HTMLButtonElement|null>(null);
const apiOnly=computed(()=>route.path.startsWith('/admin/api-users'));
const listPath=computed(()=>apiOnly.value?'/admin/api-users':'/admin/users');
const entering=ref('');
const permitted=computed(()=>!session?.currentAdmin.value||canAccessAdminPath(route.path,session.currentAdmin.value));
const primary=computed(()=>session?.currentAdmin.value?.role==='ADMIN'&&session.currentAdmin.value.primary===true);
const rows=ref<Row[]>([]),total=ref(0),offset=ref(0),search=ref(''),detail=ref<User|null>(null),error=ref(''),notice=ref(''),busy=ref(false),listBusy=ref(false),nickname=ref(''),phone=ref(''),type=ref<'UNSET'|'PERSONAL'|'ENTERPRISE'|'API'>('PERSONAL'),createKey=ref(false),created=ref<User|null>(null),attempt=ref<CreateBody|null>(null),dialogOpen=ref(false);
const copyResult=ref<{keyId:string;ok:boolean}|null>(null);
const editing=ref<Row|null>(null),editDialog=ref<HTMLDialogElement|null>(null);
let editTrigger:HTMLElement|null=null,createTrigger:HTMLElement|null=null;
const source=ref<'NATURAL'|'REFERRAL'|'ADMIN'>('ADMIN'),creationRemark=ref(''),countryCode=ref('+86');
let accountRequestId='';
const accountCards=[{value:'UNSET',name:'普通用户',tone:'blue',description:'适用于日常使用平台功能的用户'},{value:'PERSONAL',name:'个人用户',tone:'green',description:'适用于个人开发者或独立使用者'},{value:'ENTERPRISE',name:'企业用户',tone:'purple',description:'适用于企业团队多成员协作使用'},{value:'API',name:'API 用户',tone:'orange',description:'适用于通过 API 进行系统集成'}] as const;
const createReady=ref(false),restoringCreate=ref(false),creationRestoreError=ref('');
let storageKey='',loadVersion=0;const names:Record<string,string>={PERSONAL:'个人',ENTERPRISE:'企业',API:'API',UNAVAILABLE:'读取失败'};
const isDetail=computed(()=>!!route.params.userRef),normalizedPhone=computed(()=>(()=>{const value=phone.value.replace(/[\s()-]/g,'');return value.startsWith('+')?value:props.embedded?countryCode.value+value:/^1\d{10}$/.test(value)?`+86${value}`:value;})());
const valid=computed(()=>(props.embedded||nickname.value.trim().length>0)&&nickname.value.trim().length<=64&&/^\+[1-9]\d{7,14}$/.test(normalizedPhone.value));
watch(type,value=>{if(value==='API')createKey.value=true;});
function remember(value:CreateBody|null){attempt.value=value;try{if(storageKey){if(value)sessionStorage.setItem(storageKey,JSON.stringify({body:value,type:type.value,createKey:createKey.value,accountRequestId}));else sessionStorage.removeItem(storageKey);}}catch{/* Same request stays in memory until resolved. */}}
type DirectoryUser=UserView&{directoryAccountType:string|null};
let directoryCache:{query:string;items:DirectoryUser[]}|null=null;
async function ordinaryUsers(version:number){
 const searchTerm=search.value.trim()||undefined;
 const query=JSON.stringify([searchTerm,apiOnly.value]);
 // ponytail: exact totals still scan matching directory pages; use a server-side type projection when this becomes costly.
 let selected=directoryCache?.query===query?directoryCache.items:null;
 if(!selected){
  selected=[];
  for(let read=0;;){
   const {data}=await userListGetApiV1AdminUsers({search:searchTerm,offset:read,limit:100});
   if(version!==loadVersion)return null;
   if(!data.items||data.total==null)throw new Error('用户列表缺失');
   const candidates=data.items.map(usableUser);
   const identities=candidates.length?(await paymentAdminIdentitiesGetApiV1AdminPaymentsAccountIdentities({userIds:candidates.map(user=>user.id)})).data:[];
   if(version!==loadVersion)return null;
   const types=new Map(identities.map(identity=>[identity.userId,identity.accountType]));
   if(identities.length!==candidates.length||types.size!==candidates.length||candidates.some(user=>!types.has(user.id)||![null,'PERSONAL','ENTERPRISE','API'].includes(types.get(user.id)!)))throw new Error('账户分类缺失');
   const classified=candidates.map(user=>({...user,directoryAccountType:types.get(user.id)!}));
   selected.push(...classified.filter(user=>apiOnly.value?user.directoryAccountType==='API':user.directoryAccountType!=='API'));
   read+=data.items.length;if(read>=data.total)break;
   if(!data.items.length)throw new Error('用户分页未完成');
  }
  directoryCache={query,items:selected};
 }
 offset.value=Math.min(offset.value,Math.max(0,Math.floor((selected.length-1)/10)*10));
 return {items:selected.slice(offset.value,offset.value+10),total:selected.length};
}
function canEnterApi(row:Row){return primary.value&&row.user.enabled&&row.accountType==='API';}
async function enterApi(row:Row){
 if(busy.value||listBusy.value||entering.value||!canEnterApi(row))return;
 const version=loadVersion;entering.value=row.user.id;error.value='';
 try{await enterAdminPortal(row.user.id,'api-user');}
 catch{if(version===loadVersion)error.value='进入API后台失败，请刷新后重试。';}
 finally{if(version===loadVersion)entering.value='';}
}

async function load(reuseDirectory:unknown=false){if(props.embedded){emit('changed');return;}if(!permitted.value)return;if(reuseDirectory!==true)directoryCache=null;const version=++loadVersion;copyResult.value=null;rows.value=[];entering.value='';listBusy.value=true;error.value='';try{
 if(route.params.keyRef){const result=await accessAdminGetGetApiV1AdminAccessKeysId(String(route.params.keyRef));if(!result.data.ownerId)throw Error('owner missing');if(version===loadVersion)await router.replace(`/admin/users/${encodeURIComponent(result.data.ownerId)}`);return;}
 if(isDetail.value){
  const id=String(route.params.userRef),agents=await promotionListPromotersGetApiV1AdminPromotionsPromoters({userId:id,page:0,size:1});
  if(version!==loadVersion)return;
  if(!agents.data.items)throw new Error('代理查询未完成');
  if(!apiOnly.value&&agents.data.items.some(agent=>agent.userId===id)){detail.value=null;await router.replace(`/admin/agents/${encodeURIComponent(id)}`);return;}
  const result=await userDetailGetApiV1AdminUsersId(id);if(version===loadVersion)detail.value=usableUser(result.data);return;
 }
 const result=await ordinaryUsers(version);
 if(version!==loadVersion||!result)return;
 if(!result.items)throw new Error('用户列表缺失');
 rows.value=result.items.map(value=>({user:usableUser(value),key:null,keyLoaded:false,keyLoading:true,secretLoaded:false,accountType:value.directoryAccountType,balance:'—',secret:null}));
 total.value=result.total;
 for(const row of rows.value)void hydrateRow(row,version);
 }catch{if(version===loadVersion)error.value='无法读取真实用户或账户，请重试。';}finally{if(version===loadVersion)listBusy.value=false;}}
async function hydrateRow(row:Row,version:number){
 try{
  const result=await accessAdminListGetApiV1AdminAccessKeys({ownerId:row.user.id});
  if(version!==loadVersion)return;
  row.key=result.data.find(item=>item.ownerId===row.user.id)||null;row.keyLoaded=true;
 }catch{if(version!==loadVersion)return;}
 row.keyLoading=false;
 if(!row.key?.keyId)return;
 const keyId=row.key.keyId;
 void billingBalanceGetApiV1BillingWalletsKeyId(keyId).then(result=>{if(version===loadVersion)row.balance=result.data.availablePoints4||'读取失败';})
  .catch(()=>{if(version===loadVersion)row.balance='读取失败';});
 if(primary.value)void accessAdminSecretGetApiV1AdminAccessKeysIdSecret(keyId).then(result=>{if(version===loadVersion)row.secret=result.data.secret||null;})
  .catch(()=>{}).finally(()=>{if(version===loadVersion)row.secretLoaded=true;});
}

async function open(){if(!createReady.value)return;dialogOpen.value=true;await nextTick();dialog.value?.showModal();}
function close(){if(busy.value)return;dialog.value?.close();dialogOpen.value=false;(createTrigger||newButton.value)?.focus();}
function openNew(event?:Event){createTrigger=(event?.currentTarget as HTMLElement)||null;if(!createReady.value||(apiOnly.value&&!primary.value))return;if(!attempt.value&&!created.value){nickname.value='';phone.value='';type.value=apiOnly.value&&primary.value?'API':'PERSONAL';createKey.value=!!props.embedded;source.value='ADMIN';creationRemark.value='';countryCode.value='+86';accountRequestId='';}void open();}
async function create(){
 if((apiOnly.value&&!primary.value)||!createReady.value||busy.value||(!props.embedded&&created.value)||(!attempt.value&&!valid.value))return;
 busy.value=true;error.value='';notice.value='';const retry=!!attempt.value,started=loadVersion;
 if(props.embedded&&!accountRequestId)accountRequestId=crypto.randomUUID();
 const body=attempt.value||{requestId:crypto.randomUUID(),nickname:props.embedded?'用户'+normalizedPhone.value.slice(-4):nickname.value.trim(),phone:normalizedPhone.value,...(props.embedded?{source:source.value,remark:creationRemark.value||null}:{})};
 remember(body);
 try{
  const result=await userCreatePostApiV1AdminUsers(body);if(started!==loadVersion)return;created.value=usableUser(result.data);
  if(props.embedded){
   if(type.value!=='UNSET')await paymentAdminAccountPatchApiV1AdminPaymentsAccountsId(created.value.id,{requestId:accountRequestId,accountType:type.value,createKey:true});
   if(started!==loadVersion)return;
   remember(null);created.value=null;busy.value=false;close();void load();
  }else{remember(null);notice.value='用户已保存。请在下方保存账户；勾选的主 Key 将由服务端生成。';void load();}
 }catch(cause){
  if(started!==loadVersion)return;
  if(props.embedded&&created.value){error.value='用户已保存，账户设置尚未完成。请重试原请求完成设置。';}
  else{if(cause instanceof ApiError&&([400,422].includes(cause.status)||(!retry&&[403,409].includes(cause.status))))remember(null);error.value=cause instanceof ApiError&&[400,422].includes(cause.status)?'用户创建未受理，请修改昵称或手机号后提交；原输入已保留。':cause instanceof ApiError&&cause.status===403?'当前管理员没有创建用户权限。':cause instanceof ApiError&&cause.status===409?'手机号已存在或请求冲突，请核对用户列表。输入已保留。':'创建响应尚未确认。请重试原请求，避免重复创建。';}
 }finally{busy.value=false;}
}
async function toggle(user:User){if(busy.value)return;busy.value=true;error.value='';try{await userStatusPatchApiV1AdminUsersIdStatus(user.id,{enabled:!user.enabled});notice.value=user.enabled?'用户已停用，已有登录将失效。':'用户已启用。';await load();}catch{error.value='用户状态更新失败，请重试。';}finally{busy.value=false;}}
function shortKey(secret:string){return secret.slice(0,secret.startsWith('sk-_')?4:3)+'****'+secret.slice(-4);}
async function copyKey(row:Row){
 if(!primary.value||!row.secret||!row.key?.keyId)return;
 const version=loadVersion,keyId=row.key.keyId;
 try{await navigator.clipboard.writeText(row.secret);if(version===loadVersion)copyResult.value={keyId,ok:true};}
 catch{if(version===loadVersion)copyResult.value={keyId,ok:false};}
}
async function edit(row:Row,event:Event){if(busy.value||listBusy.value||row.keyLoading)return;editTrigger=event.currentTarget as HTMLElement;editing.value=row;await nextTick();editDialog.value?.showModal();}
function closeEdit(){editDialog.value?.close();editTrigger?.focus();}
function another(){created.value=null;nickname.value='';phone.value='';type.value=apiOnly.value&&primary.value?'API':'PERSONAL';createKey.value=false;notice.value='';}
watch(()=>route.fullPath,()=>{closeEdit();editing.value=null;detail.value=null;if(!props.embedded)void load();});
async function initializeCreation(){
 if(createReady.value||restoringCreate.value)return;
 restoringCreate.value=true;creationRestoreError.value='';
 try{const auth=await refreshSession();if(!auth.authenticated||!auth.user?.id)throw new Error('登录未确认');storageKey=`xzwl-create-user:${auth.user.id}`;
 const saved=JSON.parse(sessionStorage.getItem(storageKey)||'null');
 if(saved){if(typeof saved.body?.requestId!=='string'||typeof saved.body.phone!=='string'||typeof saved.body.nickname!=='string')throw new Error('创建草稿无法恢复');attempt.value=saved.body;phone.value=saved.body.phone;nickname.value=saved.body.nickname;type.value=saved.type==='UNSET'?'UNSET':['ENTERPRISE','API'].includes(saved.type)&&primary.value?saved.type:'PERSONAL';createKey.value=saved.createKey===true;source.value=saved.body.source||'ADMIN';creationRemark.value=saved.body.remark||'';accountRequestId=typeof saved.accountRequestId==='string'?saved.accountRequestId:'';}
 createReady.value=true;
 }catch{creationRestoreError.value='无法读取此前的创建请求，请重试后再新增。旧草稿不会被覆盖。';}finally{restoringCreate.value=false;}
}
watch(()=>{const identity=session?.currentAdmin.value;return identity?`${identity.role}:${identity.primary}:${identity.userId}:${identity.username}`:'';},()=>{loadVersion++;rows.value=[];void load();},{flush:'sync'});
onBeforeUnmount(()=>{loadVersion++;directoryCache=null;rows.value=[];});
onMounted(()=>{if(!props.embedded)void load();void initializeCreation();});
async function openEditor(id:string,event:Event){
 const version=++loadVersion;const trigger=event.currentTarget as HTMLElement;error.value='';
 try{const [user,keys]=await Promise.all([userDetailGetApiV1AdminUsersId(id),accessAdminListGetApiV1AdminAccessKeys({ownerId:id})]);if(version!==loadVersion)return;
 await edit({user:usableUser(user.data),key:keys.data.find(key=>key.ownerId===id)||null,accountType:null,balance:'—',keyLoaded:true,keyLoading:false,secretLoaded:false,secret:null},event);editTrigger=trigger;
 }catch{if(version===loadVersion)error.value='无法读取用户，请重试。';}
}
defineExpose({openNew,openEditor,createReady});
</script>
<template>
 <section v-if="permitted" class="page-panel user-management" :class="{'user-editor-host':embedded}"><template v-if="!embedded"><header class="page-heading"><div><h1>{{isDetail?(apiOnly?'API用户详情':'用户详情'):(apiOnly?'API 管理':'用户管理')}}</h1><p class="page-description">管理真实用户、本人主 Key 与账户额度。</p></div><RouterLink v-if="isDetail" :to="listPath" class="secondary-action back-link">{{apiOnly?'返回API列表':'返回用户列表'}}</RouterLink><button v-else-if="!apiOnly||primary" ref="newButton" class="primary-action" :disabled="!createReady||busy" @click="openNew">{{attempt?'继续未确认的创建':created?'继续新用户设置':'新增用户'}}</button></header>
 <p v-if="error&&!dialogOpen" role="alert">{{error}}</p><p v-if="notice&&!dialogOpen" role="status">{{notice}}</p>
 <article v-if="isDetail" class="admin-card"><template v-if="detail"><header><div><h2>{{detail.nickname}}</h2><p>{{detail.phone}} · {{detail.enabled?'用户已启用':'用户已停用'}}</p></div><button class="secondary-action" :disabled="busy" @click="toggle(detail)">{{detail.enabled?'停用用户':'启用用户'}}</button></header><UserAccount :key="detail.id" :user-id="detail.id" @changed="load"/></template><p v-else>{{listBusy?'正在读取用户…':'用户详情不可用'}}</p><button v-if="error" class="secondary-action" @click="load">重试</button></article>
 <template v-else><form class="user-search" @submit.prevent="offset=0;load()"><label>搜索用户<input v-model="search" aria-label="搜索用户" placeholder="昵称或手机号"/></label><button class="secondary-action" :disabled="listBusy">搜索</button><button type="button" class="secondary-action" :disabled="listBusy" @click="load">刷新</button></form>
 <article class="admin-card"><p v-if="listBusy" role="status">正在读取真实用户…</p><p v-else-if="!rows.length">暂无用户</p><div v-if="rows.length" class="table-wrap"><table><thead><tr><th>用户</th><th>手机号</th><th>账户类型</th><th>本人主 Key</th><th>可用积分</th><th>用户状态</th><th>操作</th></tr></thead><tbody><tr v-for="row in rows" :key="row.user.id"><td><RouterLink :to="`${listPath}/${encodeURIComponent(row.user.id)}`">{{row.user.nickname}}</RouterLink></td><td>{{row.user.phone?.replace(/^\+86/,'')}}</td><td>{{names[row.accountType||'']||'尚未设置'}}</td><td><template v-if="row.key"><template v-if="primary"><span class="primary-key-cell"><code class="primary-key-secret">{{row.secret?shortKey(row.secret):row.secretLoaded?'Key 明文读取失败':'—'}}</code><button v-if="row.secret" type="button" class="secondary-action copy-key" :aria-label="`复制${row.user.nickname}的主 Key`" @click="copyKey(row)">{{copyResult?.keyId===row.key.keyId?(copyResult.ok?'已复制':'复制失败，重试'):'复制'}}</button></span></template><span v-else>—</span></template><span v-else>{{row.keyLoading?'—':row.keyLoaded?'未生成 Key':'Key 读取失败'}}</span></td><td>{{ formatAmount(row.balance) }}</td><td>{{row.user.enabled?'已启用':'已停用'}}</td><td><div class="user-row-actions"><template v-if="apiOnly&&primary"><button type="button" class="table-action" :data-testid="`enter-api-${row.user.id}`" :disabled="busy||listBusy||!!entering||!canEnterApi(row)" @click="enterApi(row)">{{entering===row.user.id?'进入中…':'进入后台'}}</button></template><button type="button" class="secondary-action" :aria-label="`编辑用户 ${row.user.nickname}`" :disabled="busy||listBusy||row.keyLoading" @click="edit(row,$event)">编辑用户</button><button class="secondary-action" :aria-label="`${row.user.enabled?'停用':'启用'}用户 ${row.user.nickname}`" :disabled="busy||listBusy" @click="toggle(row.user)">{{row.user.enabled?'停用用户':'启用用户'}}</button></div></td></tr></tbody></table></div><div class="pagination"><button class="secondary-action" :disabled="listBusy||offset===0" @click="offset-=10;load(true)">上一页</button><span>共 {{total}} 位用户</span><button class="secondary-action" :disabled="listBusy||offset+10>=total" @click="offset+=10;load(true)">下一页</button></div></article></template>
 </template>
 <p v-if="creationRestoreError" role="alert">{{creationRestoreError}} <button class="secondary-action" :disabled="restoringCreate" @click="initializeCreation">重试读取创建草稿</button></p>
 <p v-if="embedded&&error&&!dialogOpen" role="alert">{{error}}</p>
 <dialog ref="dialog" class="new-user-dialog" :class="{'user-create-dialog':embedded}" :aria-describedby="embedded?'user-create-description':undefined" aria-labelledby="new-user-title" @cancel.prevent="close"><header><div><h2 id="new-user-title">新增用户</h2><p v-if="embedded" id="user-create-description" class="user-create-description">创建新用户，设置账户类型与相关信息。</p></div><button type="button" class="secondary-action" aria-label="关闭新增用户" :disabled="busy" @click="close"><PromoterIcon v-if="embedded" name="close"/><template v-else>关闭</template></button></header><p v-if="error" role="alert">{{error}}</p><p v-if="notice" role="status">{{notice}}</p>
 <form v-if="embedded" id="new-user-create-form" class="new-user-form user-create-form" @submit.prevent="create">
  <fieldset class="user-create-fields" :disabled="busy||!!attempt">
   <div class="user-create-field"><label for="new-user-phone">手机号 <em>*</em></label><div class="user-create-phone"><select v-model="countryCode" aria-label="手机区号"><option value="+86">+86</option></select><input id="new-user-phone" v-model="phone" aria-label="手机号" aria-describedby="new-user-phone-hint" type="tel" autocomplete="tel-national" placeholder="请输入手机号" required autofocus/></div><p id="new-user-phone-hint" class="user-create-hint">支持国内手机号或带区号的国际号码。</p></div>
   <fieldset class="user-create-accounts"><legend>账户类型</legend><div class="user-create-choices"><label v-for="card in accountCards.filter(card=>primary||['UNSET','PERSONAL'].includes(card.value))" :key="card.value" class="user-create-choice" :class="{selected:type===card.value}"><input v-model="type" type="radio" name="new-account-type" :value="card.value" :aria-label="card.name" :aria-describedby="'new-account-'+card.value"/><span><strong>{{card.name}}</strong><span :id="'new-account-'+card.value" class="user-create-choice-description">{{card.description}}</span></span></label></div></fieldset>
   <div class="user-create-other"><div class="user-create-field"><label for="new-user-source">注册来源 <em>*</em></label><select id="new-user-source" v-model="source" aria-label="注册来源" required><option value="ADMIN">后台创建</option><option value="NATURAL">自然注册</option><option value="REFERRAL">代理</option></select></div><div class="user-create-field"><label for="new-user-remark">备注 <span class="user-create-optional">选填</span></label><textarea id="new-user-remark" v-model="creationRemark" aria-label="用户备注" maxlength="200" rows="2" placeholder="添加用户备注" aria-describedby="new-user-remark-count"/><small id="new-user-remark-count" class="user-create-count">{{creationRemark.length}} / 200</small></div></div>
  </fieldset>
  <p class="user-create-notice"><PromoterIcon name="key"/>{{type==='UNSET'?'普通用户暂不生成 Key，可在账户设置中开通。':'创建用户后，系统将自动生成 Key，可在用户列表中查看。'}}</p>
 </form>
 <form v-else id="new-user-create-form" class="new-user-form" @submit.prevent="create"><label>昵称<input v-model="nickname" aria-label="昵称" maxlength="64" required autofocus :disabled="busy||!!attempt||!!created"/></label><label>手机号<input v-model="phone" aria-label="手机号" type="tel" placeholder="13800000000 或 +8613800000000" required :disabled="busy||!!attempt||!!created"/></label>
 <label v-if="!created">账户类型<select v-model="type" aria-label="新用户账户类型" :disabled="busy||!!attempt"><option value="PERSONAL">个人</option><option v-if="primary" value="ENTERPRISE">企业</option><option v-if="primary" value="API">API</option></select></label><label v-if="!created" class="checkbox-label"><input v-model="createKey" type="checkbox" :disabled="busy||!!attempt||type==='API'"/>保存用户后生成本人主 Key</label><p v-if="!created">先保存用户，再单独保存账户并由服务端安全生成 Key；额度操作不会重复创建用户。</p><p v-if="created">已保存用户：{{created.id}}</p></form>
 <UserAccount v-if="created&&!embedded" :key="created.id" :user-id="created.id" :initial-type="type==='UNSET'?'PERSONAL':type" :initial-create-key="createKey||type==='API'" :show-ledger="false" @changed="load"/><AmountAdjustment v-else-if="!embedded"/>
 <footer class="dialog-footer"><button v-if="created&&!embedded" type="button" class="secondary-action" @click="another">新增另一位用户</button><button type="button" :class="created?'primary-action':'secondary-action'" :disabled="busy" @click="close">{{created&&!embedded?'完成':'取消'}}</button><button v-if="!created||embedded" type="submit" form="new-user-create-form" class="primary-action" :disabled="!createReady||busy||(!attempt&&!valid)">{{busy?'正在创建…':attempt?'重试原用户创建':'创建用户'}}</button></footer>
 </dialog>
 <dialog v-if="editing" ref="editDialog" class="new-user-dialog" aria-labelledby="edit-user-title" @cancel.prevent="closeEdit">
 <header><div><h2 id="edit-user-title">编辑用户</h2></div><button type="button" class="secondary-action" aria-label="关闭编辑用户" @click="closeEdit">关闭</button></header>
 <div><h3>{{editing.user.nickname}}</h3><p>{{editing.user.phone}} · {{editing.user.enabled?'用户已启用':'用户已停用'}}</p></div>
 <UserAccount :key="editing.user.id" :user-id="editing.user.id" :initial-create-key="editing.keyLoaded&&!editing.key" :show-ledger="false" @changed="load"/>
 <footer class="dialog-footer"><button type="button" class="primary-action" @click="closeEdit">完成</button></footer>
 </dialog>
 </section>
 <section v-else><h1>无权限</h1><p>当前账号没有访问此页面的权限。</p></section>
</template>
<style scoped>
.primary-key-cell{display:flex;align-items:center;gap:10px}.copy-key{flex-shrink:0}.primary-key-secret{white-space:nowrap;user-select:all}.user-row-actions{display:flex;align-items:center;flex-wrap:nowrap;gap:12px}.user-row-actions>button,.user-row-actions>a{display:inline-flex;align-items:center;justify-content:center;flex:none;min-height:36px;padding:0 4px;border:0;border-radius:6px;background:transparent;color:#78dce1;font:inherit;font-size:14px;font-weight:500;line-height:20px;white-space:nowrap;text-decoration:none}.user-row-actions>a{order:1}.user-row-actions>button:enabled:hover,.user-row-actions>a:hover{background:#203234;color:#c0f7f6}.user-management td:last-child{width:1%;max-width:none;white-space:nowrap}.user-management>.admin-card{min-width:0}.user-management [role=alert]{color:#ffb7a2}.user-management [role=status]{color:#a6e4d9}.user-search{display:flex;align-items:end;gap:12px}.user-search label{flex:1}.user-management label{display:grid;gap:8px;font-size:14px}.user-management input,.user-management select{width:100%;min-width:0;min-height:44px;padding:10px 12px;border:1px solid #3a4646;border-radius:8px;color:#eef3f3;background:#0d1111}.user-management small{display:block;color:#a4b1b1;margin-top:6px}.user-management table{min-width:900px}.user-management td{max-width:230px;overflow-wrap:anywhere}.pagination{display:flex;align-items:center;justify-content:flex-end;gap:12px;margin-top:20px}.back-link{display:inline-flex;align-items:center;text-decoration:none}.new-user-dialog{width:min(620px,calc(100vw - 32px));max-height:calc(100dvh - 40px);padding:28px;border:1px solid #3a4646;border-radius:20px;background:#141818;color:#eef3f3;overflow:auto;overscroll-behavior:contain}.new-user-dialog::backdrop{background:rgb(0 0 0 / .72)}.new-user-dialog[open]{display:grid;gap:24px}.new-user-dialog>header{display:flex;align-items:center;justify-content:space-between;gap:16px}.dialog-footer{display:flex;justify-content:flex-end;gap:12px;padding-top:16px;border-top:1px solid #2b3232}.new-user-dialog h2{margin:0}.new-user-form{display:grid;gap:18px}.new-user-form p{margin:0;color:#a9b7b7;font-size:13px;line-height:1.6}.new-user-form .checkbox-label{display:flex;align-items:center}.checkbox-label input{width:20px;min-height:20px;height:20px}.user-management button:disabled{opacity:.45;cursor:not-allowed}@media(max-width:600px){.new-user-dialog{padding:18px;width:calc(100vw - 20px);max-height:calc(100dvh - 20px);border-radius:14px}.user-search{flex-wrap:wrap}.user-search label{flex-basis:100%}.user-management .page-heading{align-items:start}.pagination{justify-content:space-between;gap:6px}.user-management .page-heading h1{font-size:28px}}
</style>
