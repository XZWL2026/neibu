<script setup lang="ts">
import PromoterIcon from './PromoterIcon.vue';
import LoadingValue from './components/LoadingValue.vue';
import {computed,inject,onBeforeUnmount,ref,watch} from 'vue';
import DailyFinanceLists from './DailyFinanceLists.vue';
import {adminSessionKey} from './adminSession';
import {mediaNativeVideoUsageGetApiV1AdminMediaNativeVideoUsage as query,
 type ResponseMediaApplicationTaskNativeVideoUsageQueryReport as Report,
 type ResponseMediaApplicationTaskNativeVideoUsageQueryMetric as Metric} from './api';

const props=defineProps<{days?:number}>();
const emit=defineEmits<{refresh:[];custom:[];range:[value:{from?:string;to?:string}]}>();
const session=inject(adminSessionKey);
const permitted=computed(()=>['ADMIN','NORMAL_ADMIN'].includes(session?.currentAdmin.value?.role||''));
function today(daysAgo=0){return new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date(Date.now()-daysAgo*86400000));}
const from=ref(today()),to=ref(today()),busy=ref(false),error=ref(''),report=ref<Report|null>(null);
// The existing date-range API accepts four-digit years; its full date domain includes all records.
const allDates={from:'0000-01-01',to:'9999-12-31'};
const fields=['native480','native720','native1080','enhanced720','enhanced1080','enhanced4k','total'] as const;
const resolutions=[{label:'480P',keys:['native480']},{label:'720P',keys:['native720','enhanced720']},{label:'1080P',keys:['native1080','enhanced1080']},{label:'4K',keys:['enhanced4k']},{label:'合计',keys:['total']}] as const;
const models=computed(()=>{
 if(!report.value)return [];
 const grouped=new Map<string,{name:string;metrics:{tasks:number;seconds:number}[]}>();
 // Labels match JdbcNativeVideoUsageQuery's existing model groups.
 for(const name of ['Seedance 2.5','Seedance 2.0','Seedance 2.0 Fast','Seedance 2.0 Mini'])grouped.set(name,{name,metrics:resolutions.map(()=>({tasks:0,seconds:0}))});
 for(const item of report.value?.items||[]){
  const model=grouped.get(item.model!)||{name:item.model!,metrics:resolutions.map(()=>({tasks:0,seconds:0}))};
  resolutions.forEach((resolution,index)=>{for(const key of resolution.keys){model.metrics[index]!.tasks+=item[key]!.tasks!;model.metrics[index]!.seconds+=item[key]!.seconds!;}});
  grouped.set(model.name,model);
 }
 return [...grouped.values()];
});
const financeRange=ref<{from?:string;to?:string}>({}),financeRevision=ref(0);
let version=0;
function number(value?:number){return value==null?'—':value.toLocaleString('zh-CN');}
function validMetric(value?:Metric|null){return !!value && Number.isSafeInteger(value.tasks) && value.tasks!>=0 && Number.isSafeInteger(value.seconds) && value.seconds!>=0;}
async function load(clear=false,refresh=true){
 const own=++version;if(clear){report.value=null;}error.value='';busy.value=false;
 if(!permitted.value)return;
 if(!clear&&(!from.value||!to.value||from.value>to.value)){error.value='请选择有效日期范围。';return;}
 if(!clear&&refresh)emit('refresh');
 financeRange.value=clear?{}:{from:from.value,to:to.value};financeRevision.value++;
 emit('range',financeRange.value);
 busy.value=true;
 try {
  const data=(await query(clear?allDates:{from:from.value,to:to.value})).data;
  if(own!==version)return;
  if(!Array.isArray(data.items)||!validMetric(data.total)||!data.from||!data.to||data.items.some(item=>!item.date||!item.model||fields.some(field=>!validMetric(item[field]))))throw Error('invalid report');
  report.value=data;
 }catch{if(own===version){report.value=null;error.value='火山生成统计读取失败，请重试。';}}
 finally{if(own===version)busy.value=false;}
}
function openDate(event:Event){try{(event.target as HTMLInputElement).showPicker?.();}catch{/* Native keyboard date entry remains available. */}}
function loadPreset(){
 if(props.days===undefined||props.days===0)return load(true,false);
 if(props.days!==-1){to.value=today();from.value=today(props.days-1);}
 return load(false,false);
}
function submitRange(){if(from.value&&to.value&&from.value<=to.value)emit('custom');void load();}
watch(()=>props.days,()=>{if(props.days!==-1)void loadPreset();});
watch(()=>{const user=session?.currentAdmin.value;return JSON.stringify([user?.userId,user?.username,user?.role,user?.primary]);},()=>void loadPreset(),{immediate:true,flush:'sync'});
onBeforeUnmount(()=>{version++;});
defineExpose({reload:()=>loadPreset()});
</script>

<template>
 <section v-if="permitted" class="native-usage" data-testid="native-video-usage" aria-labelledby="native-usage-title">
  <div class="native-panel">
   <header class="native-header">
    <div class="native-title"><span class="native-icon"><PromoterIcon name="chart"/></span><div><h2 id="native-usage-title">模型生成统计</h2><p>各模型生成数据（所选时间段）</p></div></div>
    <form class="native-filters" @submit.prevent="submitRange">
     <label for="native-from" class="sr-only">开始日期</label><input id="native-from" v-model="from" type="date" required :max="to||undefined" @click="openDate">
     <span aria-hidden="true">→</span><label for="native-to" class="sr-only">结束日期</label><input id="native-to" v-model="to" type="date" required :min="from||undefined" @click="openDate">
     <button type="submit" class="primary-action" :disabled="busy">{{ busy?'查询中…':'查询' }}</button>
    </form>
   </header>
   <p v-if="error" class="native-error" role="alert">{{ error }}</p>
   <div class="native-model-grid" :aria-busy="busy">
    <template v-if="busy&&!report"><article v-for="n in 4" :key="n" class="native-model-card" aria-label="正在读取模型统计"><h3><LoadingValue :loading="true"/></h3><dl><div v-for="resolution in resolutions" :key="resolution.label" :class="{'native-model-total':resolution.label==='合计'}"><dt>{{resolution.label}}</dt><dd><LoadingValue :loading="true"/></dd></div></dl></article></template>
    <article v-for="model in models" :key="model.name" class="native-model-card"><h3>{{ model.name }}</h3><dl><div v-for="(resolution,index) in resolutions" :key="resolution.label" :class="{'native-model-total':resolution.label==='合计'}"><dt>{{resolution.label}}</dt><dd :class="{'has-usage':model.metrics[index]!.tasks>0}">{{number(model.metrics[index]!.tasks)}} / {{number(model.metrics[index]!.seconds)}} 秒</dd></div></dl></article>
    <p v-if="!busy&&!report?.items?.length" class="native-empty">{{ busy?'正在读取生成记录…':error?'统计暂不可用':'该日期范围暂无生成记录' }}</p>
   </div>
  </div>
  <DailyFinanceLists :range="financeRange" :revision="financeRevision" />
 </section>
</template>

<style scoped>
.native-usage{min-width:0;display:grid;gap:18px}.native-panel{padding:16px 18px 12px;border:1px solid var(--line);border-radius:14px;background:var(--panel);box-shadow:0 12px 28px #17448c0c}
.native-header{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:14px}.native-title{display:flex;align-items:center;gap:14px;min-width:0}.native-title>div{display:flex;align-items:center;flex-wrap:wrap;gap:6px 14px}.native-icon{flex:none;display:grid;place-items:center;width:48px;height:48px;border-radius:12px;background:linear-gradient(135deg,#e7f5ff,#cfe3ff);color:#0070ff;box-shadow:inset 0 0 12px #fff}.native-icon svg{width:28px;height:28px;filter:drop-shadow(0 1px 2px #0070ff40)}
h2{margin:0;font-size:23px;white-space:nowrap}.native-title p{margin:0;color:#344d83;font-size:13px}.native-filters{display:flex;align-items:center;gap:12px;min-width:0}.native-filters input{width:160px;min-width:0;color-scheme:light;padding:10px;border:1px solid var(--line);border-radius:8px;background:white;color:inherit}.native-filters button{min-width:90px;border-radius:8px;color:white;background:linear-gradient(#298aff,#0060ff);box-shadow:0 5px 12px #006aff25}
.native-model-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px}.native-model-card{min-width:0;border:1px solid #dce6f7;border-radius:10px;background:linear-gradient(125deg,white,#f7faff);box-shadow:0 5px 10px #1a58ac08;overflow:hidden}.native-model-card h3{margin:0;padding:9px 18px;background:#f5f8fd;font-size:18px;overflow-wrap:anywhere}.native-model-card dl{margin:0;padding:0 18px}.native-model-card dl>div{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:4px 0;font-size:14px;color:#263b77}.native-model-card dd{margin:0;white-space:nowrap;font-variant-numeric:tabular-nums}.native-model-card dd.has-usage{color:#0068ff;font-weight:700}.native-model-card .native-model-total{border-top:1px solid var(--line);font-weight:750;font-size:16px;padding:8px 0}.native-model-total dt{color:#0b1533}.native-empty{grid-column:1/-1;text-align:center;padding:58px 0;color:var(--muted)}.native-error{color:#bb3232}
@media(max-width:1200px){.native-header{flex-wrap:wrap}.native-model-grid{gap:10px}.native-model-card h3{font-size:15px;padding:12px}.native-model-card dl{padding:0 12px}.native-model-card dl>div{font-size:12px}}
@media(max-width:950px){.native-model-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:560px){.native-panel{padding:14px}.native-filters{display:grid;grid-template-columns:minmax(0,1fr) auto minmax(0,1fr);width:100%}.native-filters input{width:100%}.native-filters button{grid-column:1/-1}.native-model-grid{grid-template-columns:1fr}}
</style>
