<script setup lang="ts">
import {computed,inject,onBeforeUnmount,ref,watch} from 'vue';
import {useRoute,useRouter} from 'vue-router';
import {adminSessionKey} from './adminSession';
import PromoterIcon from './PromoterIcon.vue';
import {
 mediaListGetApiV1AdminMediaProviderLogs as listLogs,
 mediaTasksGetApiV1AdminMediaProviderLogsTasks as listTasks,
 mediaDetailGetApiV1AdminMediaProviderLogsId as readLog,
 type ResponseMediaApplicationProviderProviderDiagnosticQueryEntry as Entry,
 type ResponseMediaApplicationProviderProviderDiagnosticQueryTaskGroup as TaskGroup,
 type MediaListGetApiV1AdminMediaProviderLogsParams as Query
} from './api';
import './provider-logs.css';

const session=inject(adminSessionKey),route=useRoute(),router=useRouter();
const permitted=computed(()=>session?.currentAdmin.value?.role==='ADMIN'&&session.currentAdmin.value.primary===true);
const taskId=ref(''),phase=ref(''),period=ref('24'),busy=ref(false),error=ref('');
const rows=ref<TaskGroup[]>([]),total=ref(0),offset=ref(0),selected=ref(''),detailBusy=ref(false),detailError=ref('');
const selectedTask=ref(''),taskRows=ref<Entry[]>([]),taskTotal=ref(0),taskOffset=ref(0),taskBusy=ref(false),taskError=ref('');
const raw=ref(''),evidence=ref<Record<string,unknown>>({}),entry=ref<Entry|null>(null),copyState=ref('');
const phases:Record<string,string>={ARK_SENDING:'生成 · 提交',ARK_RUNNING:'生成 · 查询',MPS_SENDING:'增强 · 提交',MPS_RUNNING:'增强 · 查询'};
const pageSize=20;
let active:Query={},version=0,detailVersion=0,taskVersion=0,listAbort:AbortController|undefined,detailAbort:AbortController|undefined,taskAbort:AbortController|undefined;
const pages=computed(()=>Math.max(1,Math.ceil(total.value/pageSize))),page=computed(()=>Math.floor(offset.value/pageSize)+1);
function object(value:unknown):Record<string,unknown>{return value!==null&&typeof value==='object'&&!Array.isArray(value)?value as Record<string,unknown>:{};}
const request=computed(()=>object(evidence.value.request)),response=computed(()=>object(evidence.value.response));
const suppliedError=computed(()=>object(response.value.error||object(response.value.data).error||object(response.value.Response).Error||object(response.value.ResponseMetadata).Error));
function shown(value:unknown){return typeof value==='string'||typeof value==='number'?String(value):'未记录';}
function pretty(value:unknown){return JSON.stringify(value??null,null,2);}
function time(value:number){return new Date(value).toLocaleString('zh-CN',{hour12:false});}
function eventLabel(row:Entry){
 if(row.event==='request_started')return '开始提交';
 if(row.event==='transport_failed')return '网络异常';
 if(row.errorCode)return '上游报错';
 if(row.httpStatus!==null&&row.httpStatus>=400)return row.httpStatus<500?'请求未被接受':'上游异常';
 return row.event==='response_received'?'收到响应':'其他记录';
}
function isError(row:Entry){return !!row.errorCode||row.event==='transport_failed'||(row.httpStatus??0)>=400;}
function clearDetail(){detailVersion++;detailAbort?.abort();selected.value='';entry.value=null;evidence.value={};raw.value='';detailError.value='';copyState.value='';detailBusy.value=false;}
function clearTask(){taskVersion++;taskAbort?.abort();selectedTask.value='';taskRows.value=[];taskTotal.value=0;taskOffset.value=0;taskError.value='';taskBusy.value=false;clearDetail();}
async function loadTask(){
 const own=++taskVersion;taskAbort?.abort();clearDetail();taskRows.value=[];taskTotal.value=0;taskError.value='';
 if(!selectedTask.value||!permitted.value){taskBusy.value=false;return;}
 const task=selectedTask.value;taskBusy.value=true;taskAbort=new AbortController();
 try{
  const result=(await listLogs({taskId:task,from:0,to:active.to,offset:taskOffset.value,limit:pageSize},{signal:taskAbort.signal})).data;
  if(own!==taskVersion)return;
  if(!Array.isArray(result.items)||!Number.isFinite(result.total)||result.items.some(row=>!row.id||row.taskId!==task))throw Error('invalid task logs');
  taskRows.value=result.items;taskTotal.value=result.total;
  if(result.items[0])void open(result.items[0]);
 }catch{if(own===taskVersion)taskError.value='任务记录读取失败，请重试。';}
 finally{if(own===taskVersion)taskBusy.value=false;}
}
function openTask(group:TaskGroup){if(!group.taskId||!permitted.value)return;clearTask();selectedTask.value=group.taskId;void loadTask();}
function moveTask(delta:number){taskOffset.value+=delta*pageSize;void loadTask();}
async function open(row:Entry){
 if(!row.id||!permitted.value)return;
 clearDetail();const own=detailVersion;selected.value=row.id;detailBusy.value=true;detailAbort=new AbortController();
 try{
  const result=(await readLog(row.id,{signal:detailAbort.signal})).data;
  if(own!==detailVersion)return;
  if(!result.entry||result.entry.id!==row.id||typeof result.detailJson!=='string')throw Error('invalid diagnostic');
  const parsed=JSON.parse(result.detailJson);if(!parsed||typeof parsed!=='object'||Array.isArray(parsed))throw Error('invalid evidence');
  entry.value=result.entry;evidence.value=parsed;raw.value=pretty(parsed);
 }catch{if(own===detailVersion)detailError.value='日志详情读取失败，记录可能已清理。请重试或刷新列表。';}
 finally{if(own===detailVersion)detailBusy.value=false;}
}
async function load(){
 const own=++version;listAbort?.abort();clearTask();error.value='';rows.value=[];total.value=0;
 if(!permitted.value){busy.value=false;return;}
 busy.value=true;listAbort=new AbortController();
 try{
  const result=(await listTasks({...active,offset:offset.value,limit:pageSize},{signal:listAbort.signal})).data;
  if(own!==version)return;
  if(!Array.isArray(result.items)||!Number.isFinite(result.total)||result.items.some(row=>!row.taskId||!row.latest||row.latest.taskId!==row.taskId))throw Error('invalid logs');
  rows.value=result.items;total.value=result.total;
  if(result.items[0])openTask(result.items[0]);
 }catch{if(own===version)error.value='生成日志读取失败，请检查连接后重试。';}
 finally{if(own===version)busy.value=false;}
}
function refresh(){
 offset.value=0;const end=Date.now();
 active={taskId:taskId.value.trim()||undefined,phase:phase.value||undefined,to:end,
  from:period.value==='all'&&taskId.value.trim()?0:end-Number(period.value==='all'?'24':period.value)*3600000};
 void load();
}
async function apply(){
 if(taskId.value.trim()&&!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$/.test(taskId.value.trim())){error.value='请输入完整且有效的任务 ID。';return;}
 const query={...(taskId.value.trim()?{taskId:taskId.value.trim()}:{}),...(phase.value?{phase:phase.value}:{}),period:period.value==='all'&&!taskId.value.trim()?'24':period.value};
 if(JSON.stringify(query)===JSON.stringify(route.query))refresh();else await router.replace({query});
}
function move(delta:number){offset.value+=delta*pageSize;void load();}
async function copy(value:string,label:string){
 const own=detailVersion;
 try{await navigator.clipboard.writeText(value);if(own===detailVersion)copyState.value=label+'已复制';}
 catch{if(own===detailVersion)copyState.value='复制失败，请选中文本后手动复制';}
}
watch(()=>[route.query.taskId,route.query.phase,route.query.period,permitted.value],()=>{
 taskId.value=typeof route.query.taskId==='string'?route.query.taskId:'';
 phase.value=typeof route.query.phase==='string'&&phases[route.query.phase]?route.query.phase:'';
 const requested=typeof route.query.period==='string'?route.query.period:taskId.value?'all':'24';
 period.value=['24','168','720','all'].includes(requested)?requested:'24';
 if(period.value==='all'&&!taskId.value)period.value='24';
 refresh();
},{immediate:true});
onBeforeUnmount(()=>{version++;listAbort?.abort();clearTask();});
</script>

<template>
 <section class="page-panel provider-logs" aria-label="生成日志工作区">
  <header class="page-heading"><div><h1>{{permitted?'生成日志':'无权限'}}</h1><p class="page-description">{{permitted?'按任务追踪上游请求，查看生成失败的具体原因。':'仅主管理员可以查看生成日志。'}}</p></div></header>
  <template v-if="permitted">
   <form class="provider-log-filters" @submit.prevent="apply">
    <label class="provider-log-task-filter">任务 ID<input v-model="taskId" placeholder="粘贴完整任务 ID" maxlength="128" autocomplete="off" spellcheck="false" /></label>
    <label>阶段<select v-model="phase"><option value="">全部阶段</option><option v-for="(label,key) in phases" :key="key" :value="key">{{label}}</option></select></label>
    <label>时间范围<select v-model="period"><option value="24">最近 24 小时</option><option value="168">最近 7 天</option><option value="720">最近 30 天</option><option value="all" :disabled="!taskId.trim()">该任务全部日志</option></select></label>
    <button type="submit" class="primary-action" :disabled="busy">查询日志</button><button type="button" :disabled="busy" @click="refresh"><PromoterIcon name="refresh"/>刷新</button>
   </form>
   <p class="provider-log-note">同一任务合并展示，按最近匹配记录排序。选中任务可查看全部已采集记录；上线前的请求不会补录。</p>
   <div v-if="error" class="provider-log-error" role="alert">{{error}} <button type="button" @click="load">重试读取</button></div>
   <div class="provider-log-workspace">
    <section class="provider-log-list" aria-label="生成日志列表" :aria-busy="busy">
     <header><h2>任务列表 <span>{{busy?'读取中':total+' 个任务'}}</span></h2><small>最近更新在前</small></header>
     <p v-if="busy" class="provider-log-empty" role="status">正在读取生成日志…</p>
     <div v-else-if="!rows.length&&!error" class="provider-log-empty"><PromoterIcon name="document"/><h3>暂无匹配的日志</h3><p>检查任务 ID 或扩大时间范围。新任务提交后，点击刷新查看记录。</p></div>
     <ol v-else class="provider-log-rows">
      <li v-for="group in rows" :key="group.taskId!">
       <button type="button" class="provider-log-row" :class="{selected:selectedTask===group.taskId}" :aria-pressed="selectedTask===group.taskId" @click="openTask(group)">
        <span class="provider-log-row-top"><span class="provider-log-badge" :class="{error:isError(group.latest!)}">{{eventLabel(group.latest!)}}<span v-if="group.latest?.httpStatus"> · {{group.latest.httpStatus}}</span></span><time>{{time(group.latest!.createdAt)}}</time></span>
        <strong>{{group.latest?.errorCode||group.latest?.model||'上游请求'}}</strong>
        <span class="provider-log-row-message">{{group.latest?.message||'选择任务查看请求与响应。'}}</span>
        <span class="provider-log-row-bottom"><code>{{group.taskId}}</code><span>匹配 {{group.recordCount}} 条记录</span></span>
       </button>
      </li>
     </ol>
     <footer v-if="total" class="provider-log-pagination"><span>第 {{page}} / {{pages}} 页</span><div><button type="button" aria-label="上一页日志" :disabled="busy||offset===0" @click="move(-1)">上一页</button><button type="button" aria-label="下一页日志" :disabled="busy||offset+pageSize>=total||offset+pageSize>10000" @click="move(1)">下一页</button></div></footer>
    </section>
    <section class="provider-log-detail" aria-label="日志详情" :aria-busy="detailBusy||taskBusy">
     <section v-if="selectedTask" class="provider-log-timeline" aria-label="该任务全部记录">
      <header><h2>任务日志 <span>{{taskBusy?'读取中':taskTotal+' 条'}}</span></h2><p><code>{{selectedTask}}</code></p><small>全部阶段 · 截至本次查询 · 最新记录在前</small></header>
      <p v-if="taskBusy" class="provider-log-timeline-message" role="status">正在读取任务记录…</p>
      <div v-else-if="taskError" class="provider-log-error" role="alert">{{taskError}}<button type="button" @click="loadTask">重试任务记录</button></div>
      <p v-else-if="!taskRows.length" class="provider-log-timeline-message">暂无保留记录，请刷新任务列表。</p>
      <ol v-else>
       <li v-for="row in taskRows" :key="row.id!">
        <button type="button" :class="{selected:selected===row.id}" :aria-pressed="selected===row.id" @click="open(row)">
         <span class="provider-log-badge" :class="{error:isError(row)}">{{eventLabel(row)}}{{row.httpStatus?' · '+row.httpStatus:''}}</span>
         <span>{{phases[row.phase||'']||row.phase}}</span><time>{{time(row.createdAt)}}</time>
        </button>
       </li>
      </ol>
      <footer v-if="taskTotal>pageSize" class="provider-log-pagination"><span>第 {{Math.floor(taskOffset/pageSize)+1}} / {{Math.ceil(taskTotal/pageSize)}} 页记录</span><div><button type="button" aria-label="上一页任务记录" :disabled="taskBusy||taskOffset===0" @click="moveTask(-1)">上一页</button><button type="button" aria-label="下一页任务记录" :disabled="taskBusy||taskOffset+pageSize>=taskTotal||taskOffset+pageSize>10000" @click="moveTask(1)">下一页</button></div></footer>
     </section>
     <p v-if="detailBusy" class="provider-log-empty" role="status">正在读取详情…</p>
     <div v-else-if="detailError" class="provider-log-error" role="alert">{{detailError}}<button type="button" @click="taskRows.find(row=>row.id===selected)&&open(taskRows.find(row=>row.id===selected)!)">重试详情</button></div>
     <template v-else-if="entry">
      <header><div><h2>日志详情</h2><p>{{time(entry.createdAt)}} · {{phases[entry.phase||'']||entry.phase}}</p></div><button type="button" @click="copy(raw,'完整日志')"><PromoterIcon name="copy"/>复制日志</button></header>
      <div class="provider-log-result" :class="{error:isError(entry)}"><span class="provider-log-badge" :class="{error:isError(entry)}">{{eventLabel(entry)}}{{entry.httpStatus?' · HTTP '+entry.httpStatus:''}}</span><h3 v-if="entry.errorCode">{{entry.errorCode}}</h3><p>{{entry.message||(entry.event==='request_started'?'请求参数已记录，响应将在另一条日志中显示。':'上游响应已记录，请结合任务状态判断生成结果。')}}</p></div>
      <dl class="provider-log-facts"><dt>任务 ID</dt><dd><code>{{entry.taskId}}</code><button type="button" class="provider-log-copy" aria-label="复制任务 ID" @click="copy(entry.taskId||'','任务 ID')"><PromoterIcon name="copy"/></button></dd><dt>上游请求 ID</dt><dd><code>{{entry.requestId||'上游未返回'}}</code></dd><dt>请求耗时</dt><dd>{{entry.elapsedMs===null?'等待响应':entry.elapsedMs+' ms'}}</dd><dt>请求地址</dt><dd><code>{{shown(evidence.endpoint)}}</code></dd><template v-if="suppliedError.param"><dt>错误参数</dt><dd>{{shown(suppliedError.param)}}</dd></template></dl>
      <section class="provider-log-parameters"><h3>参数对照</h3><table><thead><tr><th scope="col">参数</th><th scope="col">用户选择</th><th scope="col">实际发送</th></tr></thead><tbody><tr><th scope="row">分辨率</th><td>{{shown(evidence.requestedResolution)}}</td><td>{{shown(request.resolution||request.size)}}</td></tr><tr><th scope="row">时长（秒）</th><td>{{shown(evidence.requestedDurationSeconds)}}</td><td>{{shown(request.duration)}}</td></tr><tr><th scope="row">画面比例</th><td>{{shown(evidence.requestedAspectRatio)}}</td><td>{{shown(request.ratio)}}</td></tr></tbody></table><p>参考素材 {{shown(evidence.referenceCount)}} 个 · 提示词 {{shown(evidence.promptCharacters)}} 字符 / {{shown(evidence.promptUtf8Bytes)}} 字节</p></section>
      <details :key="selected+'response'" :open="entry.event!=='request_started'"><summary>上游响应</summary><pre tabindex="0" aria-label="脱敏上游响应">{{pretty(evidence.response??{failureKind:evidence.failureKind})}}</pre></details>
      <details :key="selected+'request'"><summary>实际请求参数（脱敏）</summary><pre tabindex="0" aria-label="脱敏请求参数">{{pretty(evidence.request)}}</pre></details>
      <details :key="selected+'raw'"><summary>完整诊断记录</summary><pre tabindex="0" aria-label="完整诊断记录">{{raw}}</pre></details>
      <p class="provider-log-copy-state" role="status">{{copyState}}</p>
     </template>
     <div v-else-if="!selectedTask" class="provider-log-empty"><PromoterIcon name="document"/><h3>按任务查看完整过程</h3><p>选择一个任务，查看它的全部记录、错误说明和参数对照。</p></div>
    </section>
   </div>
  </template>
 </section>
</template>
