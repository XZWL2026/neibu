<script setup lang="ts">
import LoadingValue from './components/LoadingValue.vue';
import StatusActionDialog from './components/StatusActionDialog.vue';
import TableLoadingRows from './components/TableLoadingRows.vue';
import TableUpdating from './components/TableUpdating.vue';
import {computed,h,inject,nextTick,onBeforeUnmount,onMounted,reactive,ref,watch} from 'vue';
import {NDataTable,type DataTableColumns} from 'naive-ui';
import {units4} from './portalAmounts';
import {useRoute,useRouter} from 'vue-router';
import {adminSessionKey,canAccessAdminPath} from './adminSession';
import {userDirectoryGetApiV1AdminUsersDirectory,userStatusPatchApiV1AdminUsersIdStatus,accessAdminSecretGetApiV1AdminAccessKeysIdSecret,type UserDirectoryGetApiV1AdminUsersDirectoryParams} from './api';
import {formatAmount} from './shared/formatAmount';
import PromoterIcon from './PromoterIcon.vue';
import UserManagement from './UserManagement.vue';
import UserDetail from './UserDetail.vue';
import './user-management.css';
const route=useRoute(),router=useRouter(),session=inject(adminSessionKey);
const permitted=computed(()=>!session?.currentAdmin.value||canAccessAdminPath('/admin/users',session.currentAdmin.value));
const primary=computed(()=>session?.currentAdmin.value?.role==='ADMIN'&&session.currentAdmin.value.primary===true);
type Page=Awaited<ReturnType<typeof userDirectoryGetApiV1AdminUsersDirectory>>['data'];
type Item=NonNullable<Page['items']>[number];
const page=ref<Page|null>(null),busy=ref(false),error=ref(''),exporting=ref(false),acting=ref(false),offset=ref(0),limit=ref(10),selected=ref<string[]>([]);
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
const tableColumns=computed<DataTableColumns<Item>>(()=>{
 const output:DataTableColumns<Item>=[
  {key:'selection',title:()=>h('input',{type:'checkbox','aria-label':'选择本页全部用户',checked:allSelected.value,disabled:busy.value,onChange:selectAll}),width:42,render:item=>h('input',{type:'checkbox','aria-label':`选择用户 ${item.nickname}`,checked:selected.value.includes(item.id||''),disabled:busy.value,onChange:()=>{if(!item.id)return;selected.value=selected.value.includes(item.id)?selected.value.filter(id=>id!==item.id):[...selected.value,item.id];}})},
  {key:'nickname',title:'用户',width:180,render:item=>h('button',{type:'button',class:'user-name',onClick:(event:MouseEvent)=>view(item,event)},[h('span',{class:'user-avatar'},item.nickname?.slice(0,1)||'用'),item.nickname||'—'])},
 ];
 const pill=(text:string,tone:string)=>h('span',{class:['pill',tone]},text);
 if(columns.phone)output.push({key:'phone',title:'手机号',width:155,render:item=>h('span',{class:'copy-cell'},[item.phone?.replace(/^\+86/,''),h('button',{class:'icon-button','aria-label':`复制${item.nickname}的手机号`,onClick:()=>copy(item.phone,item.id||'')},[h(PromoterIcon,{name:copied.value===item.id?'check':'copy'})])])});
 if(columns.registeredAt)output.push({key:'registeredAt',title:'注册时间',width:158,sorter:(a,b)=>Date.parse(a.registeredAt||'')-Date.parse(b.registeredAt||''),render:item=>date(item.registeredAt)});
 if(columns.source)output.push({key:'source',title:'来源',width:110,render:item=>pill(names[item.source||'']||'—',item.source==='REFERRAL'?'red':'green')});
 if(columns.accountType)output.push({key:'accountType',title:'账户类型',width:105,render:item=>pill(names[item.accountType||'UNSET']||'—',item.accountType==='API'?'api-type':item.accountType==='ENTERPRISE'?'purple':'blue')});
 if(columns.key)output.push({key:'key',title:'Key',width:115,render:item=>h('span',{class:'copy-cell'},[h('span',{class:['pill',item.keyStatus==='ENABLED'?'green':'neutral'],title:item.maskedKey||''},names[item.keyStatus||'']||'—'),primary.value&&item.keyId?h('button',{class:'icon-button','aria-label':`复制${item.nickname}的 Key`,onClick:()=>copyKey(item)},[h(PromoterIcon,{name:copied.value===item.keyId?'check':'copy'})]):null])});
 if(columns.enabled)output.push({key:'enabled',title:'用户状态',width:105,render:item=>pill(item.enabled?'● 正常':'● 停用',item.enabled?'green':'red')});
 for(const [shown,key,title] of [[columns.amount,'availableAmount4','可用金额（元）'],[columns.points,'availablePoints4','可用积分']] as const){if(shown)output.push({key,title,width:140,align:'right',sorter:(a,b)=>{const x=units4(a[key]),y=units4(b[key]);return x===null?1:y===null?-1:x<y?-1:x>y?1:0;},render:item=>formatAmount(item[key])});}
 output.push({key:'actions',title:'操作',width:150,fixed:'right',render:item=>h('div',{class:'row-actions'},[
  h('button',{type:'button',class:'view-user','aria-label':`查看用户 ${item.nickname}`,onClick:(event:MouseEvent)=>view(item,event)},[h(PromoterIcon,{name:'eye'}),'查看']),
  h('details',{},[h('summary',{'aria-label':`${item.nickname}的更多操作`},'更多'),h('div',{class:'row-menu'},[h('button',{onClick:(event:MouseEvent)=>item.id&&edit(item.id,event)},'编辑用户'),h('button',{disabled:acting.value,onClick:()=>item.id&&requestStatus([item.id],!item.enabled)},item.enabled?'停用用户':'启用用户')])]),
 ])});
 return output;
});
const tableWidth=computed(()=>tableColumns.value.reduce((sum,column)=>sum+Number(column.width||120),0));
const detailAllowed=ref(true);
const detailId=computed(()=>typeof route.params.userRef==='string'?route.params.userRef:'');
const date=(value:string|null|undefined)=>value?new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date(value)).replaceAll('/','-'):'—';
const selectedPeriod=ref(0),copied=ref(''),advancedFilters=ref(false);
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
const pendingStatus=ref<{id:string;label:string}[]>([]),statusNotice=ref(''),statusFailures=ref<string[]>([]);
function requestStatus(ids:string[],enabled:boolean){
 if(acting.value||!ids.length)return;
 if(enabled){void status(ids,true);return;}
 pendingStatus.value=[...new Set(ids)].map(id=>({id,label:items.value.find(row=>row.id===id)?.nickname||id}));
}
async function confirmStatus(){const owner=lifecycle;await status(pendingStatus.value.map(target=>target.id),false);if(owner===lifecycle)pendingStatus.value=[];}
async function status(ids:string[],enabled:boolean){
 if(acting.value||!ids.length)return;
 const owner=lifecycle;acting.value=true;error.value='';statusNotice.value='';statusFailures.value=[];
 const targets=ids.map(id=>({id,label:items.value.find(row=>row.id===id)?.nickname||id}));
 let succeeded=0;const failed:string[]=[];
 try{
  for(let index=0;index<targets.length;index++){
   if(owner!==lifecycle)return;
   const target=targets[index]!;
   try{await userStatusPatchApiV1AdminUsersIdStatus(target.id,{enabled});succeeded++;}
   catch(cause){failed.push(target.label+'（'+target.id+'）');if([401,403].includes((cause as {status?:number})?.status??0)){failed.push(...targets.slice(index+1).map(item=>item.label+'（未执行）'));break;}}
  }
  if(owner!==lifecycle)return;
  await changed();
  if(owner===lifecycle){statusNotice.value=(enabled?'启用':'停用')+'完成：成功 '+succeeded+' 位，未完成 '+failed.length+' 位。';statusFailures.value=failed;}
 }finally{if(owner===lifecycle)acting.value=false;}
}
async function exportRows(){const request=++exportVersion;exporting.value=true;error.value='';const source:Item[]=[];try{for(let read=0;;){const result=await userDirectoryGetApiV1AdminUsersDirectory({...applied,offset:read,limit:100});if(request!==exportVersion)return;const rows=result.data.items;if(!rows||result.data.total==null||(!rows.length&&read<result.data.total))throw Error();source.push(...rows);read+=rows.length;if(read>=result.data.total)break;}
 const cell=(value:unknown)=>{const raw=String(value??'');return '"'+(/^[=+@\-\t\r]/.test(raw)?"'"+raw:raw).replaceAll('"','""')+'"';};
 const csv=[['昵称','手机号','注册时间','来源','账户类型','Key状态','用户状态','可用金额（元）','可用积分'],...source.map(item=>[item.nickname,item.phone,date(item.registeredAt),names[item.source||''],names[item.accountType||''],names[item.keyStatus||''],item.enabled?'正常':'停用',item.availableAmount4,item.availablePoints4])].map(row=>row.map(cell).join(',')).join('\r\n');
 const url=URL.createObjectURL(new Blob(['\ufeff'+csv],{type:'text/csv;charset=utf-8'}));const link=document.createElement('a');link.href=url;link.download='用户管理.csv';link.click();URL.revokeObjectURL(url);
 }catch{if(request===exportVersion)error.value='导出失败，请重试。';}finally{exporting.value=false;}}
watch(detailId,async id=>{detailAllowed.value=true;await nextTick();if(id&&!drawer.value?.open)drawer.value?.showModal();else if(!id)drawer.value?.close();},{immediate:true});
watch(()=>session?.currentAdmin.value,()=>{lifecycle++;pendingStatus.value=[];statusNotice.value='';statusFailures.value=[];acting.value=false;version++;exportVersion++;page.value=null;detailAllowed.value=false;drawer.value?.close();if(session?.currentAdmin.value)void load();},{flush:'sync'});
onMounted(()=>load());onBeforeUnmount(()=>{lifecycle++;version++;exportVersion++;});
</script>
<template>
<section v-if="permitted" class="page-panel user-directory">
 <header class="directory-heading"><h1>用户管理</h1><p>管理平台用户，查看账户信息、Key 与资金情况，掌握用户增长与活跃情况。</p><button class="primary-action new-user" :disabled="!editor?.createReady" @click="editor?.openNew($event)"><PromoterIcon name="plus"/>新增用户</button></header>
 <details class="directory-statistics"><summary>用户统计 <span>总用户 {{page?.summary?.totalUsers?.toLocaleString()??'—'}} 位</span><PromoterIcon name="chevron-down"/></summary><section class="directory-stats user-stat-cards" aria-label="用户统计">
  <article v-for="stat in stats" :key="stat.field" :data-stat="stat.field" :title="stat.hint">
   <span class="stat-icon" :class="stat.tone"><PromoterIcon :name="stat.field==='totalUsers'?'users':stat.field==='newUsers'?'user-plus':stat.field==='activeUsers'?'activity':stat.field==='paidUsers'?'user-check':'user'"/></span>
   <div class="stat-body"><h2>{{stat.label}}</h2><strong><LoadingValue :loading="busy&&!page">{{page?.summary?.[stat.field]?.toLocaleString()??'—'}}</LoadingValue></strong>
    <div class="stat-change" :class="changes[stat.field]===null?'unknown':changes[stat.field]!<0?'down':'up'"><span>{{changes[stat.field]===null?'—':`${changes[stat.field]!<0?'↓':'↑'} ${Math.abs(changes[stat.field]!).toLocaleString()}`}}</span><small>较昨日</small></div>
   </div>
  </article>
 </section></details>
 <div class="records-workspace"><form class="directory-filters directory-panel" @submit.prevent="search"><div class="directory-search-row"><label class="directory-search"><span class="sr-only">搜索用户</span><PromoterIcon name="search"/><input v-model="filters.search" aria-label="搜索用户" placeholder="昵称 / 手机号 / 用户 ID / 脱敏 Key" maxlength="128"/></label><button class="primary-action" type="submit" :disabled="busy">搜索</button><button type="button" @click="advancedFilters=!advancedFilters" :aria-expanded="advancedFilters" aria-controls="user-advanced-filters">{{advancedFilters?'收起筛选':'更多筛选'}}<span v-if="filters.source||filters.accountType||filters.enabled||filters.keyStatus||filters.from||filters.to">（已设置）</span></button><button type="button" @click="reset">重置</button></div><div v-show="advancedFilters" id="user-advanced-filters" class="directory-advanced"><div class="filter-fields"><label>来源<select v-model="filters.source" aria-label="来源筛选"><option value="">全部</option><option value="NATURAL">自然注册</option><option value="REFERRAL">代理</option><option value="ADMIN">后台创建</option></select></label><label>账户类型<select v-model="filters.accountType" aria-label="账户类型筛选"><option value="">全部</option><option value="UNSET">普通用户</option><option value="PERSONAL">个人用户</option><option value="ENTERPRISE">企业用户</option><option value="API">API 用户</option></select></label><label>用户状态<select v-model="filters.enabled" aria-label="用户状态筛选"><option value="">全部</option><option value="true">正常</option><option value="false">停用</option></select></label><label>Key 状态<select v-model="filters.keyStatus" aria-label="Key 状态筛选"><option value="">全部</option><option value="NONE">未生成</option><option value="ENABLED">已生成</option><option value="DISABLED">已停用</option></select></label><fieldset class="directory-dates"><legend>注册时间</legend><input v-model="filters.from" type="date" aria-label="开始日期" @input="selectedPeriod=0"/><span>→</span><input v-model="filters.to" type="date" aria-label="结束日期" @input="selectedPeriod=0"/></fieldset></div><div class="period-buttons"><button v-for="days in [7,30,90]" :key="days" type="button" :aria-pressed="selectedPeriod===days" @click="period(days)">{{days===90?'近3个月':`近${days}天`}}</button><button type="button" :aria-pressed="selectedPeriod===0&&!!(filters.from||filters.to)" @click="selectedPeriod=0;($el.querySelector('input[type=date]') as HTMLInputElement)?.focus()">自定义</button></div></div></form>
 <p v-if="error" role="alert">{{error}}</p><p v-if="statusNotice" role="status" class="status-action-result">{{statusNotice}}</p><div v-if="statusFailures.length" role="alert"><p>以下用户未完成更新，请核对后重试：</p><ul><li v-for="name in statusFailures" :key="name">{{name}}</li></ul></div>
 <section class="directory-panel directory-list" aria-label="用户列表"><header><h2><PromoterIcon name="users"/>用户列表 <small>共 {{page?.total?.toLocaleString()??'—'}} 个用户</small></h2><div class="list-tools"><button type="button" :disabled="exporting||busy||!page" @click="exportRows"><PromoterIcon name="download"/>{{exporting?'导出中…':'导出数据'}}</button><details><summary><PromoterIcon name="settings"/>列设置</summary><div class="column-settings"><label v-for="(label,key) in columnNames" :key="key"><input v-model="columns[key]" type="checkbox"/>{{label}}</label></div></details></div></header>
 <div class="directory-table data-table-region" role="region" tabindex="0" aria-label="用户列表，可横向滚动" :aria-busy="busy"><NDataTable :columns="tableColumns" :data="items" :row-key="item=>item.id||''" :loading="busy" :scroll-x="tableWidth" :bordered="false" size="small"><template #empty>{{error?'用户列表暂不可用':'暂无符合条件的用户'}}</template></NDataTable></div>
 <footer class="directory-pagination"><div class="selection-actions">已选择 {{selected.length}} 项 <select aria-label="批量操作" :disabled="!selected.length||acting||busy" value="" @change="requestStatus([...selected],($event.target as HTMLSelectElement).value==='enable');($event.target as HTMLSelectElement).value='' "><option value="" disabled>批量操作</option><option value="enable">启用用户</option><option value="disable">停用用户</option></select></div><div class="page-controls"><span>共 {{page?.total?.toLocaleString()??'—'}} 条</span><button aria-label="上一页" :disabled="busy||current===1" @click="go(current-1)"><PromoterIcon name="chevron-left"/></button><button v-for="n in pageButtons" :key="n" :aria-current="n===current?'page':undefined" :disabled="busy" @click="go(n)">{{n}}</button><button aria-label="下一页" :disabled="busy||current>=pages" @click="go(current+1)"><PromoterIcon name="chevron-right"/></button><select v-model="limit" aria-label="每页条数" @change="offset=0;load()"><option :value="10">10 条/页</option><option :value="20">20 条/页</option><option :value="50">50 条/页</option></select></div></footer>
 </section></div>
 <StatusActionDialog v-if="pendingStatus.length" :targets="pendingStatus" :busy="acting" @cancel="pendingStatus=[]" @confirm="confirmStatus"/><dialog ref="drawer" class="user-detail-drawer" aria-labelledby="user-detail-title" @cancel.prevent="closeDetail"><header><h2 id="user-detail-title">用户详情</h2><button type="button" class="drawer-close" aria-label="关闭用户详情" @click="closeDetail"><PromoterIcon name="close"/></button></header><UserDetail v-if="detailId&&detailAllowed" :key="detailId" ref="detailView" :user-id="detailId" @changed="changed"/></dialog>
 <UserManagement ref="editor" embedded @changed="changed"/>
</section><section v-else><h1>无权限</h1></section>
</template>
