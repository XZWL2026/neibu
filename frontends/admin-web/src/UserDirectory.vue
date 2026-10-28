<script setup lang="ts">
import LoadingValue from './components/LoadingValue.vue';
import TableLoadingRows from './components/TableLoadingRows.vue';
import TableUpdating from './components/TableUpdating.vue';
import {computed,inject,nextTick,onBeforeUnmount,onMounted,reactive,ref,watch} from 'vue';
import {useRoute,useRouter} from 'vue-router';
import {adminSessionKey,canAccessAdminPath} from './adminSession';
import {userDirectoryGetApiV1AdminUsersDirectory,userStatusPatchApiV1AdminUsersIdStatus,accessAdminSecretGetApiV1AdminAccessKeysIdSecret,type UserDirectoryGetApiV1AdminUsersDirectoryParams} from './api';
import {formatAmount} from '../../shared/formatAmount';
import PromoterIcon from './PromoterIcon.vue';
import UserManagement from './UserManagement.vue';
import UserDetail from './UserDetail.vue';
import './user-management.css';
const route=useRoute(),router=useRouter(),session=inject(adminSessionKey);
const permitted=computed(()=>!session?.currentAdmin.value||canAccessAdminPath('/admin/users',session.currentAdmin.value));
const primary=computed(()=>session?.currentAdmin.value?.role==='ADMIN'&&session.currentAdmin.value.primary===true);
type Page=Awaited<ReturnType<typeof userDirectoryGetApiV1AdminUsersDirectory>>['data'];
type Item=NonNullable<Page['items']>[number];
const page=ref<Page|null>(null),busy=ref(false),error=ref(''),exporting=ref(false),acting=ref(false),offset=ref(0),limit=ref(20),selected=ref<string[]>([]);
const editor=ref<InstanceType<typeof UserManagement>|null>(null),drawer=ref<HTMLDialogElement|null>(null),detailView=ref<InstanceType<typeof UserDetail>|null>(null);
const filters=reactive({search:'',source:'',accountType:'',enabled:'',keyStatus:'',from:'',to:''});
let applied:UserDirectoryGetApiV1AdminUsersDirectoryParams={},version=0,exportVersion=0,lifecycle=0,trigger:HTMLElement|null=null;
const names:Record<string,string>={UNSET:'普通用户',PERSONAL:'个人用户',ENTERPRISE:'企业用户',API:'API 用户',NATURAL:'自然注册',REFERRAL:'代理',ADMIN:'后台创建',NONE:'未生成',ENABLED:'已生成',DISABLED:'已停用'};
const stats=[{field:'totalUsers',label:'总用户',tone:'blue',hint:'符合来源、类型、状态和搜索条件的总用户'}, {field:'newUsers',label:'新增用户',tone:'green',hint:'注册时间在所选区间内的用户'}, {field:'activeUsers',label:'活跃用户',tone:'blue',hint:'所选区间内有消费扣款记录的用户'}, {field:'unpaidUsers',label:'未付费用户',tone:'orange',hint:'尚无充值到账记录的用户'}, {field:'paidUsers',label:'付费用户',tone:'gold',hint:'已有充值到账记录的用户'}] as const;
const previous=ref<Page['summary']|null>(null);
const changes=computed(()=>Object.fromEntries(stats.map(({field})=>{
 const current=page.value?.summary?.[field],before=previous.value?.[field];
 return [field,busy.value||current==null||before==null?null:current-before];
})) as Record<typeof stats[number]['field'],number|null>);
async function loadPrevious(request:number){
 const day=86400000;
 const to=applied.to?new Date(new Date(applied.to).getTime()-day).toISOString():new Date(`${new Date().toLocaleDateString('sv-SE',{timeZone:'Asia/Shanghai'})}T00:00:00+08:00`).toISOString();
 const from=applied.from?new Date(new Date(applied.from).getTime()-day).toISOString():undefined;
 try{const result=await userDirectoryGetApiV1AdminUsersDirectory({...applied,from,to,offset:0,limit:1});if(request===version)previous.value=result.data.summary??null;}catch{if(request===version)previous.value=null;}
}
const columns=reactive({phone:true,registeredAt:true,source:true,accountType:true,key:true,enabled:true,amount:true,points:true});
const columnNames={phone:'手机号',registeredAt:'注册时间',source:'来源',accountType:'账户类型',key:'Key',enabled:'用户状态',amount:'可用金额（元）',points:'可用积分'};
const items=computed(()=>page.value?.items||[]),pages=computed(()=>Math.max(1,Math.ceil((page.value?.total||0)/limit.value))),current=computed(()=>Math.floor(offset.value/limit.value)+1);
const pageButtons=computed(()=>Array.from(new Set([1,current.value-1,current.value,current.value+1,pages.value])).filter(n=>n>0&&n<=pages.value).sort((a,b)=>a-b));
const allSelected=computed(()=>items.value.length>0&&items.value.every(item=>item.id&&selected.value.includes(item.id)));
const detailAllowed=ref(true);
const detailId=computed(()=>typeof route.params.userRef==='string'?route.params.userRef:'');
const date=(value:string|null|undefined)=>value?new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date(value)).replaceAll('/','-'):'—';
const selectedPeriod=ref(0),copied=ref('');
function period(days:number){selectedPeriod.value=days;const today=new Date().toLocaleDateString('sv-SE',{timeZone:'Asia/Shanghai'});const start=new Date(`${today}T00:00:00+08:00`);start.setUTCDate(start.getUTCDate()-days+1);filters.from=start.toLocaleDateString('sv-SE',{timeZone:'Asia/Shanghai'});filters.to=today;void search();}
function query():UserDirectoryGetApiV1AdminUsersDirectoryParams {
 const from=filters.from?new Date(`${filters.from}T00:00:00+08:00`).toISOString():undefined;
 const to=filters.to?new Date(new Date(`${filters.to}T00:00:00+08:00`).getTime()+86400000).toISOString():undefined;
 if(from&&to&&from>=to)throw new Error('结束日期不能早于开始日期。');
 if(/^sk[-_]/i.test(filters.search.trim())&&!filters.search.includes('*'))throw new Error('请使用 Key ID 或脱敏 Key 查询。');
 return {search:filters.search.trim()||undefined,source:filters.source||undefined,accountType:filters.accountType||undefined,enabled:filters.enabled===''?undefined:filters.enabled==='true',keyStatus:filters.keyStatus||undefined,from,to};
}
async function load(){if(!permitted.value)return;const request=++version;exportVersion++;busy.value=true;error.value='';selected.value=[];previous.value=null;try{const pending=userDirectoryGetApiV1AdminUsersDirectory({...applied,offset:offset.value,limit:limit.value});void loadPrevious(request);const result=await pending;if(request!==version)return;if(!result.data.items||!result.data.summary)throw Error();page.value=result.data;}catch{if(request===version){page.value=null;error.value='无法读取用户列表，请重试。';}}finally{if(request===version)busy.value=false;}}
async function search(){try{applied=query();offset.value=0;await load();}catch(cause){error.value=(cause as Error).message;}}
function reset(){Object.assign(filters,{search:'',source:'',accountType:'',enabled:'',keyStatus:'',from:'',to:''});selectedPeriod.value=0;void search();}
function go(n:number){offset.value=(n-1)*limit.value;void load();}
function selectAll(){selected.value=allSelected.value?[]:items.value.flatMap(item=>item.id?[item.id]:[]);}
async function view(item:Item,event:Event){if(!item.id)return;trigger=event.currentTarget as HTMLElement;await router.push(`/admin/users/${encodeURIComponent(item.id)}`);}
async function closeDetail(){drawer.value?.close();await router.push('/admin/users');await nextTick();trigger?.focus();}
async function edit(id:string,event:Event){await editor.value?.openEditor(id,event);}
async function changed(){await load();await detailView.value?.reload();}
async function copy(value:string|null|undefined,id:string){if(!value)return;try{await navigator.clipboard.writeText(value);copied.value=id;}catch{error.value='复制失败，请重试。';}}
async function copyKey(item:Item){if(!primary.value||!item.keyId)return;const request=version;try{const result=await accessAdminSecretGetApiV1AdminAccessKeysIdSecret(item.keyId);if(request===version&&primary.value)await copy(result.data.secret,item.keyId);}catch{if(request===version)error.value='无法复制 Key，请重试。';}}
async function status(ids:string[],enabled:boolean){if(acting.value)return;const owner=lifecycle;acting.value=true;error.value='';try{for(const id of ids){if(owner!==lifecycle)return;await userStatusPatchApiV1AdminUsersIdStatus(id,{enabled});}if(owner===lifecycle)await changed();}catch{if(owner===lifecycle)error.value='部分用户状态未更新，请刷新核对后重试。';}finally{if(owner===lifecycle)acting.value=false;}}
async function exportRows(){const request=++exportVersion;exporting.value=true;error.value='';const source:Item[]=[];try{for(let read=0;;){const result=await userDirectoryGetApiV1AdminUsersDirectory({...applied,offset:read,limit:100});if(request!==exportVersion)return;const rows=result.data.items;if(!rows||result.data.total==null||(!rows.length&&read<result.data.total))throw Error();source.push(...rows);read+=rows.length;if(read>=result.data.total)break;}
 const cell=(value:unknown)=>{const raw=String(value??'');return '"'+(/^[=+@\-\t\r]/.test(raw)?"'"+raw:raw).replaceAll('"','""')+'"';};
 const csv=[['昵称','手机号','注册时间','来源','账户类型','Key状态','用户状态','可用金额（元）','可用积分'],...source.map(item=>[item.nickname,item.phone,date(item.registeredAt),names[item.source||''],names[item.accountType||''],names[item.keyStatus||''],item.enabled?'正常':'停用',item.availableAmount4,item.availablePoints4])].map(row=>row.map(cell).join(',')).join('\r\n');
 const url=URL.createObjectURL(new Blob(['\ufeff'+csv],{type:'text/csv;charset=utf-8'}));const link=document.createElement('a');link.href=url;link.download='用户管理.csv';link.click();URL.revokeObjectURL(url);
 }catch{if(request===exportVersion)error.value='导出失败，请重试。';}finally{exporting.value=false;}}
watch(detailId,async id=>{detailAllowed.value=true;await nextTick();if(id&&!drawer.value?.open)drawer.value?.showModal();else if(!id)drawer.value?.close();},{immediate:true});
watch(()=>session?.currentAdmin.value,()=>{lifecycle++;acting.value=false;version++;exportVersion++;page.value=null;detailAllowed.value=false;drawer.value?.close();if(session?.currentAdmin.value)void load();},{flush:'sync'});
onMounted(()=>load());onBeforeUnmount(()=>{lifecycle++;version++;exportVersion++;});
</script>
<template>
<section v-if="permitted" class="page-panel user-directory">
 <header class="directory-heading"><div class="directory-breadcrumb"><PromoterIcon name="bank"/>运营管理 <span>›</span> 用户管理</div><h1>用户管理</h1><p>管理平台用户，查看账户信息、Key 与资金情况，掌握用户增长与活跃情况。</p><button class="primary-action new-user" :disabled="!editor?.createReady" @click="editor?.openNew($event)"><span aria-hidden="true">＋</span>新增用户</button></header>
 <section class="directory-stats user-stat-cards" aria-label="用户统计">
  <article v-for="stat in stats" :key="stat.field" :data-stat="stat.field" :title="stat.hint">
   <span class="stat-icon" :class="stat.tone"><svg viewBox="0 0 64 64" aria-hidden="true" fill="currentColor">
    <g v-if="stat.field==='totalUsers'" stroke="#fff" stroke-opacity=".55" stroke-width="1.5"><circle cx="26" cy="20" r="12"/><path d="M5 51c0-12 9-19 21-19s21 7 21 19v4H9a4 4 0 0 1-4-4Z"/><path d="M42 12a10 10 0 0 1 0 20 16 16 0 0 0-2-20Zm4 23c9 1 14 8 14 17a3 3 0 0 1-3 3h-7v-4c0-6-2-12-4-16Z"/></g>
    <g v-else-if="stat.field==='newUsers'" stroke="#fff" stroke-opacity=".4" stroke-width="1.5"><rect x="8" y="28" width="12" height="29" rx="6"/><rect x="26" y="9" width="12" height="48" rx="6"/><rect x="44" y="28" width="12" height="29" rx="6"/></g>
    <path v-else-if="stat.field==='activeUsers'" d="M5 32h10l4-5 6 11 7-29 7 47 6-29 5 10 4-5h5" fill="none" stroke="currentColor" stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round"/>
    <g v-else-if="stat.field==='unpaidUsers'" stroke="#fff" stroke-opacity=".55" stroke-width="1.5"><circle cx="32" cy="18" r="12"/><path d="M10 56v-5c0-12 9-19 22-19s22 7 22 19v5Z"/></g>
    <g v-else><path d="m9 22 13 10 10-22 10 22 13-10-5 27H14Z"/><rect x="14" y="53" width="36" height="4" rx="2"/><circle cx="8" cy="20" r="4"/><circle cx="32" cy="9" r="4"/><circle cx="56" cy="20" r="4"/></g>
   </svg></span>
   <div class="stat-body"><h2>{{stat.label}}</h2><strong><LoadingValue :loading="busy&&!page">{{page?.summary?.[stat.field]?.toLocaleString()??'—'}}</LoadingValue></strong>
    <div class="stat-change" :class="changes[stat.field]===null?'unknown':changes[stat.field]!<0?'down':'up'"><span>{{changes[stat.field]===null?'—':`${changes[stat.field]!<0?'↓':'↑'} ${Math.abs(changes[stat.field]!).toLocaleString()}`}}</span><small>较昨日</small></div>
   </div>
  </article>
 </section>
 <form class="directory-filters directory-panel" @submit.prevent="search"><div class="filter-fields"><label class="directory-search"><span class="sr-only">搜索用户</span><PromoterIcon name="search"/><input v-model="filters.search" aria-label="搜索用户" placeholder="昵称 / 手机号 / 用户 ID / 脱敏 Key" maxlength="128"/></label><label>来源<select v-model="filters.source" aria-label="来源筛选"><option value="">全部</option><option value="NATURAL">自然注册</option><option value="REFERRAL">代理</option><option value="ADMIN">后台创建</option></select></label><label>账户类型<select v-model="filters.accountType" aria-label="账户类型筛选"><option value="">全部</option><option value="UNSET">普通用户</option><option value="PERSONAL">个人用户</option><option value="ENTERPRISE">企业用户</option><option value="API">API 用户</option></select></label><label>用户状态<select v-model="filters.enabled" aria-label="用户状态筛选"><option value="">全部</option><option value="true">正常</option><option value="false">停用</option></select></label><label>Key 状态<select v-model="filters.keyStatus" aria-label="Key 状态筛选"><option value="">全部</option><option value="NONE">未生成</option><option value="ENABLED">已生成</option><option value="DISABLED">已停用</option></select></label><fieldset class="directory-dates"><legend>注册时间</legend><input v-model="filters.from" type="date" aria-label="开始日期" @input="selectedPeriod=0"/><span>→</span><input v-model="filters.to" type="date" aria-label="结束日期" @input="selectedPeriod=0"/></fieldset></div><div class="filter-bottom"><div class="period-buttons"><button v-for="days in [7,30,90]" :key="days" type="button" :aria-pressed="selectedPeriod===days" @click="period(days)">{{days===90?'近3个月':`近${days}天`}}</button><button type="button" :aria-pressed="selectedPeriod===0&&!!(filters.from||filters.to)" @click="selectedPeriod=0;($el.querySelector('input[type=date]') as HTMLInputElement)?.focus()">自定义</button></div><div><button class="primary-action" type="submit">搜索</button><button type="button" @click="reset">重置</button><button type="button" aria-label="刷新用户列表" :disabled="busy" @click="load"><PromoterIcon name="refresh"/></button></div></div></form>
 <p v-if="error" role="alert">{{error}}</p>
 <section class="directory-panel directory-list" aria-label="用户列表"><header><h2><PromoterIcon name="users"/>用户列表 <small>共 {{page?.total?.toLocaleString()??'—'}} 个用户</small></h2><div class="list-tools"><button type="button" :disabled="exporting||busy||!page" @click="exportRows"><PromoterIcon name="download"/>{{exporting?'导出中…':'导出数据'}}</button><details><summary><PromoterIcon name="settings"/>列设置</summary><div class="column-settings"><label v-for="(label,key) in columnNames" :key="key"><input v-model="columns[key]" type="checkbox"/>{{label}}</label></div></details></div></header>
 <div class="directory-table data-table-region" role="region" tabindex="0" aria-label="用户列表，可横向滚动" :aria-busy="busy"><TableUpdating v-if="busy&&items.length"/><table><thead><tr><th><input type="checkbox" aria-label="选择本页全部用户" :checked="allSelected" :disabled="busy" @change="selectAll"/></th><th>用户</th><th v-if="columns.phone">手机号</th><th v-if="columns.registeredAt">注册时间 ↓</th><th v-if="columns.source">来源</th><th v-if="columns.accountType">账户类型</th><th v-if="columns.key">Key</th><th v-if="columns.enabled">用户状态</th><th v-if="columns.amount" class="balance-centered">可用金额（元）</th><th v-if="columns.points" class="balance-centered">可用积分</th><th>操作</th></tr></thead><tbody><TableLoadingRows v-if="busy&&!items.length" :columns="3+Object.values(columns).filter(Boolean).length" label="正在读取用户…"/><tr v-for="item in items" :key="item.id||undefined"><td><input v-if="item.id" v-model="selected" type="checkbox" :value="item.id" :aria-label="`选择用户 ${item.nickname}`" :disabled="busy"/></td><td><button type="button" class="user-name" @click="view(item,$event)"><span class="user-avatar">{{item.nickname?.slice(0,1)||'用'}}</span>{{item.nickname||'—'}}</button></td><td v-if="columns.phone"><span class="copy-cell">{{item.phone?.replace(/^\+86/,'')}}<button class="icon-button" :aria-label="`复制${item.nickname}的手机号`" @click="copy(item.phone,item.id||'')"><span v-if="copied===item.id">✓</span><PromoterIcon v-else name="copy"/></button></span></td><td v-if="columns.registeredAt">{{date(item.registeredAt)}}</td><td v-if="columns.source"><span class="pill" :class="item.source==='REFERRAL'?'red':'green'">{{names[item.source||'']||'—'}}</span></td><td v-if="columns.accountType"><span class="pill" :class="item.accountType==='API'?'api-type':item.accountType==='ENTERPRISE'?'purple':'blue'">{{names[item.accountType||'UNSET']}}</span></td><td v-if="columns.key"><span class="copy-cell"><span class="pill" :class="item.keyStatus==='ENABLED'?'green':'neutral'" :title="item.maskedKey||''">{{names[item.keyStatus||'']||'—'}}</span><button v-if="primary&&item.keyId" class="icon-button" :aria-label="`复制${item.nickname}的 Key`" @click="copyKey(item)"><span v-if="copied===item.keyId">✓</span><PromoterIcon v-else name="copy"/></button></span></td><td v-if="columns.enabled"><span class="pill" :class="item.enabled?'green':'red'">● {{item.enabled?'正常':'停用'}}</span></td><td v-if="columns.amount" class="numeric balance-centered">{{formatAmount(item.availableAmount4)}}</td><td v-if="columns.points" class="numeric balance-centered">{{formatAmount(item.availablePoints4)}}</td><td><div class="row-actions"><button type="button" class="view-user" :aria-label="`查看用户 ${item.nickname}`" @click="view(item,$event)"><PromoterIcon name="eye"/>查看</button><details><summary :aria-label="`${item.nickname}的更多操作`">•••</summary><div class="row-menu"><button @click="item.id&&edit(item.id,$event)">编辑用户</button><button :disabled="acting" @click="item.id&&status([item.id],!item.enabled)">{{item.enabled?'停用用户':'启用用户'}}</button></div></details></div></td></tr></tbody></table><p v-if="!busy&&!error&&!items.length" class="directory-empty">暂无符合条件的用户</p></div>
 <footer class="directory-pagination"><div class="selection-actions">已选择 {{selected.length}} 项 <select aria-label="批量操作" :disabled="!selected.length||acting||busy" value="" @change="status([...selected],($event.target as HTMLSelectElement).value==='enable');($event.target as HTMLSelectElement).value='' "><option value="" disabled>批量操作</option><option value="enable">启用用户</option><option value="disable">停用用户</option></select></div><div class="page-controls"><span>共 {{page?.total?.toLocaleString()??'—'}} 条</span><button aria-label="上一页" :disabled="busy||current===1" @click="go(current-1)">‹</button><button v-for="n in pageButtons" :key="n" :aria-current="n===current?'page':undefined" :disabled="busy" @click="go(n)">{{n}}</button><button aria-label="下一页" :disabled="busy||current>=pages" @click="go(current+1)">›</button><select v-model="limit" aria-label="每页条数" @change="offset=0;load()"><option :value="10">10 条/页</option><option :value="20">20 条/页</option><option :value="50">50 条/页</option></select></div></footer>
 </section>
 <dialog ref="drawer" class="user-detail-drawer" aria-labelledby="user-detail-title" @cancel.prevent="closeDetail"><header><h2 id="user-detail-title">用户详情</h2><button type="button" class="drawer-close" aria-label="关闭用户详情" @click="closeDetail">×</button></header><UserDetail v-if="detailId&&detailAllowed" :key="detailId" ref="detailView" :user-id="detailId" @changed="changed"/></dialog>
 <UserManagement ref="editor" embedded @changed="changed"/>
</section><section v-else><h1>无权限</h1></section>
</template>
