<script setup lang="ts">
import LoadingValue from './components/LoadingValue.vue';
import StatusActionDialog from './components/StatusActionDialog.vue';
import TableLoadingRows from './components/TableLoadingRows.vue';
import TableUpdating from './components/TableUpdating.vue';
import {computed,inject,nextTick,onBeforeUnmount,onMounted,reactive,ref,watch} from 'vue';
import {useRoute,useRouter} from 'vue-router';
import {adminSessionKey,canAccessAdminPath} from './adminSession';
import {userApiDirectoryGetApiV1AdminApiUsersDirectory,accessAdminSecretGetApiV1AdminAccessKeysIdSecret,userStatusPatchApiV1AdminUsersIdStatus} from './api';
import PromoterIcon from './PromoterIcon.vue';
import {units4} from './portalAmounts';
import ApiClientDetail from './ApiClientDetail.vue';
import ApiClientCreate from './ApiClientCreate.vue';
import './user-management.css';
import './api-management.css';
const route=useRoute(),router=useRouter(),session=inject(adminSessionKey);
const primary=computed(()=>session?.currentAdmin.value?.role==='ADMIN'&&session.currentAdmin.value.primary===true),permitted=computed(()=>!!session?.currentAdmin.value&&canAccessAdminPath('/admin/api-users',session.currentAdmin.value));
type Page=Awaited<ReturnType<typeof userApiDirectoryGetApiV1AdminApiUsersDirectory>>['data'];
type Item=NonNullable<Page['items']>[number];
type Query=Parameters<typeof userApiDirectoryGetApiV1AdminApiUsersDirectory>[0];
const page=ref<Page|null>(null),busy=ref(false),acting=ref(false),error=ref(''),offset=ref(0),limit=ref(10),selected=ref<string[]>([]),copied=ref('');
const filters=reactive({search:'',enabled:'',source:'',from:'',to:''});
const drawer=ref<HTMLDialogElement>(),createDialog=ref<HTMLDialogElement>(),creationOpen=ref(false),creationBusy=ref(false),detailView=ref<InstanceType<typeof ApiClientDetail>>();
const detailId=computed(()=>typeof route.params.userRef==='string'?route.params.userRef:'');
const identity=computed(()=>JSON.stringify(session?.currentAdmin.value));
const items=computed(()=>page.value?.items||[]),current=computed(()=>Math.floor(offset.value/limit.value)+1),pages=computed(()=>Math.max(1,Math.ceil((page.value?.total||0)/limit.value)));
const pageButtons=computed(()=>Array.from(new Set([1,current.value-1,current.value,current.value+1,pages.value])).filter(n=>n>0&&n<=pages.value).sort((a,b)=>a-b));
const allSelected=computed(()=>items.value.length>0&&items.value.every(row=>row.user?.id&&selected.value.includes(row.user.id)));
const stats=[{field:'totalApiUsers',label:'API 客户总数',previous:'previousMonthTotalApiUsers',period:'较上月',tone:'blue'},{field:'todayCalls',label:'今日调用次数',previous:'yesterdayCalls',period:'较昨日',tone:'blue'},{field:'todaySeconds',label:'今日生成秒数',previous:'yesterdaySeconds',period:'较昨日',tone:'blue'},{field:'todayAmount4',label:'今日消费金额（元）',previous:'yesterdayAmount4',period:'较昨日',tone:'orange'}] as const;
const comparisons=computed(()=>stats.map(stat=>{
  const summary=page.value?.summary,value=summary?.[stat.field],previous=summary?.[stat.previous];
  const now=value==null?null:units4(String(value)),before=previous==null?null:units4(String(previous));
  if(now===null||before===null||(before===0n&&now!==0n))return {text:'—',direction:'unknown'};
  const delta=now-before,base=before<0n?-before:before,change=delta<0n?-delta:delta;
  const hundredths=base===0n?0n:(change*10000n+base/2n)/base;
  const percent=`${hundredths/100n}.${String(hundredths%100n).padStart(2,'0')}`.replace(/\.?0+$/,'');
  return {text:`${delta<0n?'↓':'↑'} ${percent}%`,direction:delta<0n?'down':'up'};
}));
const keyNames:Record<string,string>={NONE:'未生成',ENABLED:'已生成',DISABLED:'已停用'};
let applied:Query={},version=0,lifecycle=0,trigger:HTMLElement|null=null,createTrigger:HTMLElement|null=null;
function yuan(value:string|null|undefined){if(value==null)return '—';const[w,f='']=value.split('.');return `${w!.replace(/\B(?=(\d{3})+(?!\d))/g,',')}.${f.padEnd(2,'0').slice(0,2)}`;}
function lastCall(value:string|null|undefined){if(!value)return '—';const elapsed=Math.max(0,Date.now()-Date.parse(value));if(!Number.isFinite(elapsed))return '—';return elapsed<60000?'刚刚':elapsed<3600000?`${Math.floor(elapsed/60000)}分钟前`:elapsed<86400000?`${Math.floor(elapsed/3600000)}小时前`:`${Math.floor(elapsed/86400000)}天前`;}
async function load(){if(!permitted.value)return;const request=++version;busy.value=true;error.value='';selected.value=[];try{const result=await userApiDirectoryGetApiV1AdminApiUsersDirectory({...applied,offset:offset.value,limit:limit.value});if(request!==version)return;if(!result.data.items||!result.data.summary)throw Error();page.value=result.data;}catch{if(request===version){page.value=null;error.value='无法读取 API 客户，请重试。';}}finally{if(request===version)busy.value=false;}}
async function search(){try{const from=filters.from?new Date(`${filters.from}T00:00:00+08:00`).toISOString():undefined,to=filters.to?new Date(Date.parse(`${filters.to}T00:00:00+08:00`)+86400000).toISOString():undefined;if(from&&to&&from>=to)throw Error('结束日期不能早于开始日期。');if(/^sk[-_]/i.test(filters.search.trim())&&!filters.search.includes('*'))throw Error('请使用 Key ID 或脱敏 Key 查询。');applied={search:filters.search.trim()||undefined,source:filters.source||undefined,enabled:filters.enabled===''?undefined:filters.enabled==='true',from,to};offset.value=0;await load();}catch(cause){error.value=(cause as Error).message;}}
function reset(){Object.assign(filters,{search:'',enabled:'',source:'',from:'',to:''});void search();}
function go(n:number){offset.value=(n-1)*limit.value;void load();}
function selectAll(){selected.value=allSelected.value?[]:items.value.flatMap(row=>row.user?.id?[row.user.id]:[]);}
async function view(row:Item,event:Event){if(!row.user?.id)return;trigger=event.currentTarget as HTMLElement;await router.push(`/admin/api-users/${encodeURIComponent(row.user.id)}`);}
async function closeDetail(){drawer.value?.close();await router.push('/admin/api-users');await nextTick();trigger?.focus();}
async function newClient(event:Event){if(!primary.value)return;createTrigger=event.currentTarget as HTMLElement;creationOpen.value=true;await nextTick();createDialog.value?.showModal();}
function closeCreate(){if(creationBusy.value)return;createDialog.value?.close();creationOpen.value=false;createTrigger?.focus();}
async function created(id:string){creationBusy.value=false;closeCreate();await load();await router.push(`/admin/api-users/${encodeURIComponent(id)}`);}
async function changed(){await load();await detailView.value?.reload();}
const pendingStatus=ref<{id:string;label:string}[]>([]),statusNotice=ref(''),statusFailures=ref<string[]>([]);
function requestStatus(ids:string[],enabled:boolean){
 if(acting.value||!ids.length)return;
 if(enabled){void status(ids,true);return;}
 pendingStatus.value=[...new Set(ids)].map(id=>({id,label:items.value.find(row=>row.user?.id===id)?.user?.nickname||id}));
}
async function confirmStatus(){const owner=lifecycle;await status(pendingStatus.value.map(target=>target.id),false);if(owner===lifecycle)pendingStatus.value=[];}
async function status(ids:string[],enabled:boolean){
 if(acting.value||!ids.length)return;
 const owner=lifecycle;acting.value=true;error.value='';statusNotice.value='';statusFailures.value=[];
 const targets=ids.map(id=>({id,label:items.value.find(row=>row.user?.id===id)?.user?.nickname||id}));
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
async function copyKey(row:Item){const key=row.user?.keyId;if(!primary.value||!key)return;const request=version;try{const result=await accessAdminSecretGetApiV1AdminAccessKeysIdSecret(key);if(request===version&&primary.value&&result.data.secret){await navigator.clipboard.writeText(result.data.secret);if(request===version)copied.value=key;}}catch{if(request===version)error.value='复制 Key 失败，请重试。';}}
watch(detailId,async id=>{await nextTick();if(id&&permitted.value&&!drawer.value?.open)drawer.value?.showModal();else if(!id)drawer.value?.close();},{immediate:true});
watch(identity,()=>{version++;lifecycle++;acting.value=false;pendingStatus.value=[];statusNotice.value='';statusFailures.value=[];page.value=null;selected.value=[];drawer.value?.close();createDialog.value?.close();creationOpen.value=false;void load();},{flush:'sync'});
onMounted(()=>void load());onBeforeUnmount(()=>{version++;lifecycle++;});
</script>
<template><section v-if="permitted" class="page-panel user-directory api-management"><header class="directory-heading"><h1>API 管理</h1><p>管理 API 客户的密钥、调用数据与消费情况。</p><button v-if="primary" type="button" class="primary-action new-user" @click="newClient"><PromoterIcon name="plus"/>新增 API 客户</button></header>
<section class="api-stats user-stat-cards" aria-label="API 客户统计"><article v-for="(stat,index) in stats" :key="stat.field" :data-stat="stat.field">
<span class="stat-icon" :class="stat.tone"><PromoterIcon :name="['users','activity','clock','wallet'][index] || 'globe'"/></span><div class="stat-body"><h2>{{stat.label}}</h2><strong><LoadingValue :loading="busy&&!page">{{stat.field==='todayAmount4'?yuan(page?.summary?.todayAmount4):page?.summary?.[stat.field]?.toLocaleString()??'—'}}</LoadingValue></strong><div class="stat-change" :class="comparisons[index].direction"><span>{{comparisons[index].text}}</span><small>{{stat.period}}</small></div></div>
</article></section>
<section class="directory-panel api-directory-panel"><form class="api-filters" @submit.prevent="search"><label class="directory-search"><span class="sr-only">搜索 API 客户</span><PromoterIcon name="search"/><input v-model="filters.search" aria-label="搜索 API 客户" placeholder="搜索企业名称 / 手机号 / 脱敏 Key…" maxlength="128"/></label><select v-model="filters.enabled" aria-label="状态筛选"><option value="">全部状态</option><option value="true">正常</option><option value="false">停用</option></select><select v-model="filters.source" aria-label="来源筛选"><option value="">全部来源</option><option value="NATURAL">自然注册</option><option value="REFERRAL">代理</option><option value="ADMIN">后台创建</option></select><fieldset class="directory-dates"><legend class="sr-only">加入日期</legend><PromoterIcon name="calendar"/><input v-model="filters.from" type="date" aria-label="开始日期"/><span>—</span><input v-model="filters.to" type="date" aria-label="结束日期"/></fieldset><button type="submit" :disabled="busy">搜索</button><button type="button" :disabled="busy" @click="reset"><PromoterIcon name="refresh"/> 重置</button></form><p v-if="error" role="alert">{{error}}</p><p v-if="statusNotice" role="status" class="status-action-result">{{statusNotice}}</p><div v-if="statusFailures.length" role="alert"><p>以下用户未完成更新，请核对后重试：</p><ul><li v-for="name in statusFailures" :key="name">{{name}}</li></ul></div>
<div class="directory-table api-directory-table data-table-region" :aria-busy="busy"><TableUpdating v-if="busy&&items.length"/><table><thead><tr><th><input type="checkbox" aria-label="选择本页全部 API 客户" :checked="allSelected" :disabled="busy" @change="selectAll"/></th><th>企业名称</th><th>手机号</th><th>Key 状态</th><th class="balance-centered">今日调用</th><th class="balance-centered">生成秒数</th><th class="balance-centered">今日消费（元）</th><th class="balance-centered">可用金额（元）</th><th>最后调用</th><th>状态</th><th>操作</th></tr></thead><tbody><TableLoadingRows v-if="busy&&!items.length" :columns="11" label="正在读取 API 客户…"/><tr v-for="row in items" :key="row.user?.id||undefined" :class="{'is-selected':selected.includes(row.user?.id||'')}"><td><input v-if="row.user?.id" v-model="selected" type="checkbox" :value="row.user.id" :aria-label="`选择 ${row.user.nickname}`" :disabled="busy"/></td><td><button class="user-name" type="button" @click="view(row,$event)">{{row.user?.nickname||'—'}}</button></td><td>{{row.user?.phone?.replace(/^\+86/,'')||'—'}}</td><td><span class="copy-cell"><span class="pill" :class="row.user?.keyStatus==='ENABLED'?'green':'neutral'">● {{keyNames[row.user?.keyStatus||'']||'—'}}</span><code :title="row.user?.maskedKey||undefined">{{row.user?.maskedKey||'—'}}</code><button v-if="primary&&row.user?.keyId" class="icon-button" :aria-label="`复制${row.user.nickname}的 Key`" @click="copyKey(row)"><PromoterIcon v-if="copied===row.user.keyId" name="check-single"/><PromoterIcon v-else name="copy"/></button></span></td><td class="numeric balance-centered">{{row.todayCalls?.toLocaleString()??'—'}}</td><td class="numeric balance-centered">{{row.todaySeconds?.toLocaleString()??'—'}}</td><td class="numeric balance-centered" :title="row.todayAmount4||undefined">{{yuan(row.todayAmount4)}}</td><td class="numeric balance-centered" :title="row.user?.availableAmount4||undefined">{{yuan(row.user?.availableAmount4)}}</td><td :title="row.lastCallAt||undefined">{{lastCall(row.lastCallAt)}}</td><td><span class="pill" :class="row.user?.enabled?'green':'red'">● {{row.user?.enabled?'正常':'停用'}}</span></td><td><div class="row-actions"><button type="button" class="view-user" :aria-label="`查看 API 客户 ${row.user?.nickname}`" @click="view(row,$event)">查看</button><details><summary :aria-label="`${row.user?.nickname}的更多操作`"><PromoterIcon name="more"/></summary><div class="row-menu"><button type="button" :disabled="acting||busy" @click="row.user?.id&&requestStatus([row.user.id],!row.user.enabled)">{{row.user?.enabled?'停用客户':'启用客户'}}</button></div></details></div></td></tr></tbody></table><p v-if="!busy&&!error&&!items.length" class="directory-empty">暂无符合条件的 API 客户</p></div>
<footer class="directory-pagination"><div class="selection-actions">已选择 {{selected.length}} 项 <select aria-label="批量操作" value="" :disabled="!selected.length||acting||busy" @change="requestStatus([...selected],($event.target as HTMLSelectElement).value==='enable');($event.target as HTMLSelectElement).value='' "><option value="" disabled>批量操作</option><option value="enable">启用客户</option><option value="disable">停用客户</option></select></div><div class="page-controls"><span>共 {{page?.total?.toLocaleString()??'—'}} 条记录</span><button aria-label="上一页" :disabled="busy||current===1" @click="go(current-1)"><PromoterIcon name="chevron-left"/></button><button v-for="n in pageButtons" :key="n" :aria-current="n===current?'page':undefined" :disabled="busy" @click="go(n)">{{n}}</button><button aria-label="下一页" :disabled="busy||current>=pages" @click="go(current+1)"><PromoterIcon name="chevron-right"/></button><select v-model="limit" aria-label="每页条数" @change="offset=0;load()"><option :value="10">10 条/页</option><option :value="20">20 条/页</option><option :value="50">50 条/页</option></select></div></footer></section>
<StatusActionDialog v-if="pendingStatus.length" :targets="pendingStatus" :busy="acting" @cancel="pendingStatus=[]" @confirm="confirmStatus"/><dialog ref="drawer" class="api-detail-drawer" aria-labelledby="api-detail-title" @cancel.prevent="closeDetail"><header><h2 id="api-detail-title">API 客户详情</h2><button type="button" class="drawer-close" aria-label="关闭 API 客户详情" @click="closeDetail"><PromoterIcon name="close"/></button></header><ApiClientDetail v-if="detailId" :key="identity+detailId" ref="detailView" :user-id="detailId" @changed="changed"/></dialog>
<dialog ref="createDialog" class="api-create-dialog" aria-labelledby="api-create-title" @cancel.prevent="closeCreate"><ApiClientCreate v-if="creationOpen&&primary" :key="identity" @busy="creationBusy=$event" @cancel="closeCreate" @created="created"/></dialog>
</section><section v-else><h1>无权限</h1></section></template>
