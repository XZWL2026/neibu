<script setup lang="ts">
import LoadingValue from './components/LoadingValue.vue';
import TableLoadingRows from './components/TableLoadingRows.vue';
import TableUpdating from './components/TableUpdating.vue';
import { computed, inject, nextTick, onUnmounted, reactive, ref, watch } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { adminSessionKey } from './adminSession';
import {enterAdminPortal} from './adminPortalEntry';
import AgentDetail from './AgentDetail.vue';
import PromoterIcon from './PromoterIcon.vue';
import './user-management.css';
import './agent-management.css';
import {money,units4} from './portalAmounts';
import AdminSearchSelect from './components/AdminSearchSelect.vue';
import { ApiError, promotionSearchGetApiV1AdminPromotionsAgentDirectory as readDirectory,
  promotionCreateAdminPromoterPostApiV1AdminPromotionsPromoters,
  userListGetApiV1AdminUsers, userDetailGetApiV1AdminUsersId,
  iamListGetApiV1AdminAdmins, iamCreatePostApiV1AdminAdmins } from './api';

type Directory=Awaited<ReturnType<typeof readDirectory>>['data'];
type Agent=NonNullable<Directory['items']>[number];
type User = Awaited<ReturnType<typeof userDetailGetApiV1AdminUsersId>>['data'];
type Credential = NonNullable<Awaited<ReturnType<typeof iamListGetApiV1AdminAdmins>>['data']>[number];
type Provision = Parameters<typeof promotionCreateAdminPromoterPostApiV1AdminPromotionsPromoters>[0];
type Login = Parameters<typeof iamCreatePostApiV1AdminAdmins>[0];
const session=inject(adminSessionKey), route=useRoute(),router=useRouter();
const permitted=computed(()=>['ADMIN','NORMAL_ADMIN'].includes(session?.currentAdmin.value?.role??''));
const primary=computed(()=>session?.currentAdmin.value?.role==='ADMIN'&&session.currentAdmin.value.primary===true);
const detailId=computed(()=>String(route.params.agentRef??''));
const rows=ref<Agent[]>([]), credentials=ref<Credential[]>([]), credentialsLoaded=ref(false);
const summary=ref<Directory['summary']>(null),size=ref(10);
const filters=reactive({search:'',source:'',enabled:'',from:'',to:''});
let applied:NonNullable<Parameters<typeof readDirectory>[0]>={};
const detailComponent=ref<InstanceType<typeof AgentDetail>>();
const drawer=ref<HTMLDialogElement>(),drawerTrigger=ref<HTMLElement>();
const total=ref(0), page=ref(0), loading=ref(false), error=ref(''), entering=ref('');
const dialog=ref<HTMLDialogElement>(), addButton=ref<HTMLButtonElement>();
const dialogOpen=ref(false), busy=ref(false), formError=ref(''), selected=ref(''), username=ref('');
const users=ref<User[]>([]), userTotal=ref(0), userOffset=ref(0), userQuery=ref(''), userLoading=ref(false), userError=ref('');
const provision=ref<Provision>(), provisioned=ref(false), loginAttempt=ref<Login>();
let epoch=0, listVersion=0, userVersion=0, opener:HTMLElement|undefined;
const options=computed(()=>users.value.filter(u=>u.id&&u.enabled).map(u=>({value:u.id!,label:u.nickname??'未命名用户',detail:u.phone??''})));
const hasMore=computed(()=>userOffset.value<userTotal.value);
const valid=computed(()=>!!selected.value&&/^[A-Za-z0-9一-鿿._@-]{1,64}$/.test(username.value.trim()));
const credentialFor=(id?:string|null)=>credentials.value.find(c=>c.role==='PROMOTER'&&c.boundUserId===id);

function canEnter(row:Agent) {
 const credential=credentialFor(row.userId);
 return primary.value&&credentialsLoaded.value&&!!credential?.id&&credential.enabled===true&&row.enabled===true;
}
async function enterAgent(row:Agent) {
 if(busy.value||entering.value||!canEnter(row))return;
 const id=credentialFor(row.userId)!.id!,run=epoch;
 entering.value=id;error.value='';
 try {await enterAdminPortal(id);}
 catch {if(run===epoch)error.value='进入代理后台失败，请刷新后重试。';}
 finally {if(run===epoch)entering.value='';}
}
async function load(targetPage=page.value) {
 if(!permitted.value)return;
 const run=epoch, version=++listVersion;loading.value=true;error.value='';
 try {
  const [result,accounts]=await Promise.all([readDirectory({...applied,page:targetPage,size:size.value}),primary.value?iamListGetApiV1AdminAdmins().then(r=>r.data).catch(()=>null):Promise.resolve(null)]);
  if(run!==epoch||version!==listVersion)return;
  if(!Array.isArray(result.data.items)||typeof result.data.total!=='number'||!result.data.summary)throw new Error('Invalid list');
  page.value=targetPage;rows.value=result.data.items;total.value=result.data.total;summary.value=result.data.summary;credentials.value=accounts??[];credentialsLoaded.value=accounts!==null;
 } catch {if(run===epoch&&version===listVersion)error.value='代理列表读取失败，请重试。';}
 finally {if(run===epoch&&version===listVersion)loading.value=false;}
}
async function search(){
 const from=filters.from?new Date(`${filters.from}T00:00:00+08:00`).toISOString():undefined;
 const to=filters.to?new Date(new Date(`${filters.to}T00:00:00+08:00`).getTime()+86400000).toISOString():undefined;
 if(from&&to&&from>=to){error.value='结束日期不能早于开始日期。';return;}
 applied={search:filters.search.trim()||undefined,source:filters.source||undefined,enabled:filters.enabled===''?undefined:filters.enabled==='true',from,to};await load(0);
}
function reset(){Object.assign(filters,{search:'',source:'',enabled:'',from:'',to:''});void search();}
async function view(row:Agent,event:Event){drawerTrigger.value=event.currentTarget as HTMLElement;await router.push(`/admin/agents/${encodeURIComponent(row.userId||'')}`);}
async function closeDetail(){if(detailComponent.value?.busy)return;drawer.value?.close();await router.push('/admin/agents');await nextTick();drawerTrigger.value?.focus();}
const date=(v?:string|null)=>v?new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date(v)).replaceAll('/','-'):'—';
const metricCards=[{key:'totalAgents',previous:'totalAgents',label:'代理总数',tone:'blue'},{key:'todayNewAgents',previous:'newAgents',label:'今日新增代理',tone:'green'},{key:'todayReferredUsers',previous:'referredUsers',label:'今日拉新用户',tone:'blue'},{key:'paidDownlineUsers',previous:'paidDownlineUsers',label:'付费用户',tone:'orange'},{key:'pendingCommissionAmount4',previous:'pendingCommissionAmount4',label:'待结算收益（元）',tone:'gold'}] as const;
const metricChanges=computed(()=>metricCards.map(metric=>{
 const unknown={text:'—',direction:'unknown'};
 if(loading.value)return unknown;
 if(metric.key!=='pendingCommissionAmount4'){
  const current=summary.value?.[metric.key],before=summary.value?.yesterday?.[metric.previous];
  if(current==null||before==null)return unknown;
  const delta=current-before;
  return {text:`${delta<0?'↓':'↑'} ${Math.abs(delta).toLocaleString()}`,direction:delta<0?'down':'up'};
 }
 const current=units4(summary.value?.pendingCommissionAmount4),before=units4(summary.value?.yesterday?.pendingCommissionAmount4);
 if(current===null||before===null||before===0n&&current!==0n)return unknown;
 const delta=current-before;
 if(delta===0n)return {text:'↑ 0%',direction:'up'};
 const base=before<0n?-before:before,change=delta<0n?-delta:delta;
 const hundredths=(change*10000n+base/2n)/base;
 const percent=`${hundredths/100n}.${String(hundredths%100n).padStart(2,'0')}`.replace(/\.?0+$/,'');
 return {text:`${delta<0n?'↓':'↑'} ${percent}%`,direction:delta<0n?'down':'up'};
}));
const pageCount=computed(()=>Math.max(1,Math.ceil(total.value/size.value)));
const pageButtons=computed(()=>Array.from(new Set([1,page.value,page.value+1,page.value+2,pageCount.value])).filter(n=>n>0&&n<=pageCount.value).sort((a,b)=>a-b));
async function searchUsers(query:string,more=false) {
 if(!primary.value||!dialogOpen.value)return;
 const run=epoch,version=++userVersion,offset=more?userOffset.value:0;
 userLoading.value=true;userError.value='';if(!more)userQuery.value=query;
 try {
  const result=await userListGetApiV1AdminUsers({search:query,offset,limit:20});
  if(run!==epoch||version!==userVersion||!dialogOpen.value)return;
  const incoming=result.data.items??[];
  users.value=more?[...users.value,...incoming.filter(u=>!users.value.some(old=>old.id===u.id))]:incoming;
  userOffset.value=offset+incoming.length;userTotal.value=result.data.total??0;
 }catch{if(run===epoch&&version===userVersion)userError.value='用户查询失败，请重新查询。';}
 finally{if(run===epoch&&version===userVersion)userLoading.value=false;}
}
function resetDraft(){selected.value='';username.value='';formError.value='';userError.value='';users.value=[];userTotal.value=0;userOffset.value=0;provision.value=undefined;provisioned.value=false;loginAttempt.value=undefined;}
async function openCreate(event:Event,row?:Agent) {
 if(!primary.value||busy.value)return;
 opener=event.currentTarget instanceof HTMLElement?event.currentTarget:addButton.value;
 resetDraft();dialogOpen.value=true;
 if(row?.userId){selected.value=row.userId;provisioned.value=true;users.value=[{id:row.userId,nickname:row.nickname,phone:row.phone,enabled:true,authVersion:0,avatarFileId:null}];}
 await nextTick();dialog.value?.showModal();
 if(!row)await searchUsers('');
}
function close(force=false){
 if(busy.value&&!force)return;
 const refresh=!force&&(provisioned.value||!!provision.value);
 userVersion++;dialog.value?.close();dialogOpen.value=false;resetDraft();
 if(opener?.isConnected)opener.focus();
 if(refresh)void load();
}
function creationError(cause:unknown){
 if(cause instanceof ApiError){
  if(cause.code==='USERNAME_EXISTS')return '用户名已存在，请更换用户名。';
  if(cause.code==='INVALID_ADMIN_INPUT')return '请检查登录用户名格式。';
  if(cause.code==='INVALID_PORTAL_BINDING')return '用户或代理已停用，请检查其状态。';
  if(cause.status===404)return '未找到该真实用户，请重新选择。';
  if(cause.status===409)return '代理状态或账号发生冲突，请检查后重试。';
  if(cause.status===403)return '当前账号没有创建代理的权限。';
 }
 return '请求未完成，请重试。';
}
async function save(){
 if(!primary.value||busy.value||!valid.value)return;
 const run=epoch;busy.value=true;formError.value='';
 try{
  const current=(await iamListGetApiV1AdminAdmins()).data??[];
  if(run!==epoch)return;
  const bound=current.find(c=>c.role==='PROMOTER'&&c.boundUserId===selected.value);
  if(bound){
   if(loginAttempt.value&&bound.username===loginAttempt.value.username&&bound.enabled){close(true);page.value=0;await load();return;}
   formError.value=`该用户已有登录账号 ${bound.username}，请使用已有账号管理密码。`;return;
  }
  const name=loginAttempt.value?.username??username.value.trim();
  if(current.some(c=>c.username===name)){loginAttempt.value=undefined;formError.value='用户名已存在，请更换用户名。';return;}
  if(!provisioned.value){
   provision.value??={commandId:crypto.randomUUID(),userId:selected.value};
   const result=await promotionCreateAdminPromoterPostApiV1AdminPromotionsPromoters(provision.value);
   if(run!==epoch)return;
   if(result.data.userId!==selected.value||result.data.status!=='ENABLED')throw new Error('Invalid agent response');
   provisioned.value=true;
  }
  loginAttempt.value??={username:name,password:crypto.randomUUID(),role:'PROMOTER',boundUserId:selected.value};
  const result=await iamCreatePostApiV1AdminAdmins(loginAttempt.value);
  if(run!==epoch)return;
  if(result.data.boundUserId!==selected.value||result.data.role!=='PROMOTER'||result.data.username!==name||!result.data.enabled)throw new Error('Invalid credential response');
  close(true);page.value=0;await load();
 }catch(cause){
  if(run!==epoch)return;
  if(cause instanceof ApiError&&cause.status>=400&&cause.status<500){
   // A binding conflict or uncertain outcome must be reconciled with the actual credential list on retry.
   if(cause.code!=='PORTAL_ACCOUNT_EXISTS')loginAttempt.value=undefined;
   if(!provisioned.value)provision.value=undefined;
  }
  formError.value=(provisioned.value?'代理已开通，登录账号尚未确认：':'')+creationError(cause);
 }finally{if(run===epoch)busy.value=false;}
}
watch(()=>{const a=session?.currentAdmin.value;return `${a?.role}:${a?.primary}:${a?.userId}:${a?.username}`;},()=>{
 epoch++;listVersion++;userVersion++;entering.value='';busy.value=false;loading.value=false;userLoading.value=false;close(true);
 drawer.value?.close();summary.value=null;rows.value=[];credentials.value=[];credentialsLoaded.value=false;total.value=0;page.value=0;error.value='';void load();
},{immediate:true});
watch(detailId,async id=>{await nextTick();if(id&&permitted.value&&!drawer.value?.open)drawer.value?.showModal();else if(!id)drawer.value?.close();},{immediate:true});
onUnmounted(()=>{drawer.value?.close();epoch++;userVersion++;dialog.value?.close();resetDraft();});
</script>
<template>
 <section v-if="permitted" class="page-panel user-directory agent-management" aria-labelledby="agent-page-title">
  <header class="directory-heading"><div class="directory-breadcrumb"><PromoterIcon name="bank"/>运营管理 <span>›</span>代理管理</div><h1 id="agent-page-title">代理管理</h1><p>管理代理合作伙伴，查看推广数据与收益情况。</p><button v-if="primary" ref="addButton" type="button" class="primary-action new-user" data-testid="add-agent" @click="openCreate($event)"><span aria-hidden="true">＋</span>新增代理</button></header>
  <section class="directory-stats user-stat-cards agent-stat-cards" aria-label="代理统计">
   <article v-for="(metric,index) in metricCards" :key="metric.key">
    <span class="stat-icon" :class="metric.tone"><svg viewBox="0 0 64 64" fill="currentColor" aria-hidden="true">
     <g v-if="metric.key==='totalAgents'" stroke="#fff" stroke-opacity=".55" stroke-width="1.5"><circle cx="26" cy="20" r="12"/><path d="M5 51c0-12 9-19 21-19s21 7 21 19v4H9a4 4 0 0 1-4-4Z"/><path d="M42 12a10 10 0 0 1 0 20 16 16 0 0 0-2-20Zm4 23c9 1 14 8 14 17a3 3 0 0 1-3 3h-7v-4c0-6-2-12-4-16Z"/></g>
     <g v-else-if="metric.key==='todayNewAgents'"><circle cx="25" cy="19" r="12" stroke="#fff" stroke-opacity=".55" stroke-width="1.5"/><path d="M4 55v-5c0-11 9-18 21-18s21 7 21 18v5Z" stroke="#fff" stroke-opacity=".55" stroke-width="1.5"/><path d="M52 28v18m-9-9h18" fill="none" stroke="currentColor" stroke-width="6"/></g>
     <g v-else-if="metric.key==='todayReferredUsers'" fill="none" stroke="currentColor" stroke-width="5"><circle cx="21" cy="44" r="13"/><circle cx="44" cy="20" r="13"/><path d="m28 36 8-9"/></g>
     <g v-else-if="metric.key==='paidDownlineUsers'"><path d="M6 9h9l9 34h28l9-26H18" stroke="currentColor" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/><circle cx="28" cy="54" r="5"/><circle cx="50" cy="54" r="5"/><path d="M21 20h35" stroke="#fff" stroke-opacity=".5" stroke-width="2"/></g>
     <g v-else><circle cx="32" cy="32" r="28" stroke="#fff" stroke-opacity=".65" stroke-width="2"/><path d="m21 17 11 15 11-15M21 32h22M21 39h22M32 32v17" fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/></g>
    </svg></span>
    <div class="stat-body"><h2>{{metric.label}}</h2><strong class="agent-metric-value" :title="metric.key==='pendingCommissionAmount4'?summary?.pendingCommissionAmount4??'':undefined"><LoadingValue :loading="loading&&!summary">{{metric.key==='pendingCommissionAmount4'?money(summary?.pendingCommissionAmount4).replace('¥ ','').replace(/^(-?\d+)/,part=>part.replace(/\B(?=(\d{3})+(?!\d))/g,',')):summary?.[metric.key]?.toLocaleString()??'—'}}</LoadingValue></strong>
     <div class="stat-change" :class="metricChanges[index].direction"><span>{{metricChanges[index].text}}</span><small>较昨日</small></div>
    </div>
   </article>
  </section>
  <form class="directory-filters directory-panel" aria-label="代理筛选" @submit.prevent="search"><div class="filter-fields agent-filter-fields"><label class="directory-search"><span class="sr-only">搜索代理</span><PromoterIcon name="search"/><input v-model="filters.search" aria-label="搜索代理" placeholder="搜索代理名称 / 手机号 / 代理ID..." maxlength="128"/></label><label>代理来源<select v-model="filters.source" aria-label="代理来源"><option value="">全部</option><option value="ADMIN">管理员添加</option><option value="APPLICATION">推广申请</option><option value="UNKNOWN">未知</option></select></label><label>代理状态<select v-model="filters.enabled" aria-label="代理状态"><option value="">全部</option><option value="true">正常</option><option value="false">已停用</option></select></label><fieldset class="directory-dates"><legend>加入时间</legend><input v-model="filters.from" type="date" aria-label="开始日期"/><span>→</span><input v-model="filters.to" type="date" aria-label="结束日期"/></fieldset><div class="agent-filter-actions"><button class="primary-action" type="submit" :disabled="loading">搜索</button><button type="button" @click="reset">重置</button><button type="button" aria-label="刷新代理列表" data-testid="refresh-agents" :disabled="loading" @click="load()"><PromoterIcon name="refresh"/></button></div></div></form>
  <p v-if="error" role="alert">{{error}}</p>
  <article class="directory-panel agent-list-card" aria-label="代理列表"><div class="directory-table agent-directory-table data-table-region" :aria-busy="loading"><TableUpdating v-if="loading&&rows.length"/><table><caption class="sr-only">代理列表</caption><thead><tr><th>代理名称</th><th>手机号</th><th class="balance-centered">推广用户</th><th class="balance-centered">付费用户</th><th class="balance-centered">个人</th><th class="balance-centered">企业</th><th class="balance-centered">API</th><th class="balance-centered">累计充值（元）</th><th class="balance-centered">累计收益（元）</th><th class="balance-centered">待结算（元）</th><th>状态</th><th>注册时间</th><th>操作</th></tr></thead><tbody><TableLoadingRows v-if="loading&&!rows.length" :columns="13" label="正在读取代理…"/><tr v-for="row in rows" :key="row.userId||''"><td><button class="user-name agent-name" @click="view(row,$event)">{{row.nickname||'—'}}</button></td><td>{{row.phone?.replace(/^\+86/,'')||'—'}}</td><td class="numeric balance-centered">{{row.invitedCount}}</td><td class="numeric balance-centered">{{row.paidCount}}</td><td class="numeric balance-centered">{{row.personalPaidCount??'—'}}</td><td class="numeric balance-centered">{{row.enterprisePaidCount??'—'}}</td><td class="numeric balance-centered">{{row.apiPaidCount??'—'}}</td><td class="numeric balance-centered" :title="row.totalRechargeAmount4||''">{{money(row.totalRechargeAmount4).replace('¥ ','')}}</td><td class="numeric balance-centered">{{money(row.totalCommissionAmount4).replace('¥ ','')}}</td><td class="numeric balance-centered">{{money(row.pendingCommissionAmount4).replace('¥ ','')}}</td><td><span class="pill" :class="row.enabled?'green':'red'">● {{row.enabled?'正常':'已停用'}}</span></td><td>{{date(row.registeredAt)}}</td><td><div class="row-actions agent-row-actions"><button class="view-user" :data-testid="`agent-detail-${row.userId}`" :aria-label="`查看代理 ${row.nickname}`" @click="view(row,$event)">查看</button><details v-if="primary"><summary :aria-label="`${row.nickname}更多操作`">•••</summary><div class="row-menu agent-list-menu"><button type="button" :data-testid="`enter-agent-${row.userId}`" :disabled="busy||!!entering||!canEnter(row)" @click="enterAgent(row)">{{entering===credentialFor(row.userId)?.id?'进入中…':'进入代理后台'}}</button><button v-if="credentialsLoaded&&!credentialFor(row.userId)&&row.enabled" type="button" :disabled="busy" @click="openCreate($event,row)">设置登录账号</button><RouterLink v-else-if="credentialFor(row.userId)" to="/admin/admins">管理账号</RouterLink></div></details></div></td></tr><tr v-if="!loading&&!rows.length"><td colspan="13" class="agent-empty">{{loading?'正在读取代理…':error?'列表暂不可用':'暂无代理'}}</td></tr></tbody></table></div>
  <footer class="directory-pagination"><span>共 {{total}} 条代理</span><div class="page-controls"><span>第 {{page+1}} 页</span><button :disabled="loading||page===0" aria-label="上一页" @click="load(page-1)">‹</button><button v-for="n in pageButtons" :key="n" :aria-current="page+1===n?'page':undefined" :disabled="loading" @click="load(n-1)">{{n}}</button><button :disabled="loading||page+1>=pageCount" aria-label="下一页" @click="load(page+1)">›</button><select v-model.number="size" aria-label="每页代理数" @change="load(0)"><option :value="10">10 条/页</option><option :value="20">20 条/页</option><option :value="50">50 条/页</option></select></div></footer></article>
  <dialog ref="drawer" class="agent-detail-drawer" aria-labelledby="agent-detail-title" @cancel.prevent="closeDetail"><header class="agent-drawer-heading"><h2 id="agent-detail-title">代理详情</h2><button aria-label="关闭代理详情" :disabled="detailComponent?.busy" @click="closeDetail">×</button></header><AgentDetail ref="detailComponent" v-if="detailId" :key="detailId" :user-id="detailId" :credential="credentialFor(detailId)" :credentials-loaded="credentialsLoaded" :entering="!!entering" @enter="enterAgent" @create-login="openCreate($event.event,$event.agent)" @changed="load()"/></dialog>
  <dialog ref="dialog" class="agent-dialog" aria-labelledby="agent-dialog-title" @cancel.prevent="close()">
   <header><h2 id="agent-dialog-title">{{provisioned?'设置代理登录账号':'新增代理'}}</h2><button type="button" class="secondary-action" :disabled="busy" aria-label="关闭新增代理" @click="close()">关闭</button></header>
   <form aria-label="新增代理" @submit.prevent="save">
    <div class="agent-user-field"><label for="agent-owner">选择用户</label><AdminSearchSelect id="agent-owner" label="业务用户" v-model="selected" :options="options" remote :loading="userLoading" :has-more="hasMore" :disabled="busy||!!provision||provisioned" @query="searchUsers($event)" @load-more="searchUsers(userQuery,true)"/><p v-if="userError" role="alert">{{userError}}</p></div>
    <label>登录用户名<input v-model="username" aria-label="登录用户名" autocomplete="off" maxlength="64" pattern="[A-Za-z0-9一-鿿._@\-]{1,64}" required :disabled="busy||!!loginAttempt"/></label>
    <p v-if="formError" role="alert">{{formError}}</p>
    <footer><button type="button" class="secondary-action" :disabled="busy" @click="close()">取消</button><button type="submit" class="primary-action" :disabled="busy||!valid">{{busy?'正在保存…':provisioned?'创建登录账号':'创建代理'}}</button></footer>
   </form>
  </dialog>
 </section>
 <section v-else class="page-panel"><h1>无权限</h1><p>当前账号没有访问此页面的权限。</p></section>
</template>
