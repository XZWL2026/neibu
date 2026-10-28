<script setup lang="ts">
import LoadingValue from './components/LoadingValue.vue';
import {computed,inject,onBeforeUnmount,ref,watch} from 'vue';
import {adminSessionKey} from './adminSession';
import {billingDailyFinanceGetApiV1AdminBillingDailyFinance as query} from './api';
import {units4} from './portalAmounts';
import {formatAmount} from './shared/formatAmount';
const props=defineProps<{revision:number}>();
const session=inject(adminSessionKey);
const permitted=computed(()=>['ADMIN','NORMAL_ADMIN'].includes(session?.currentAdmin.value?.role||''));
const amounts=ref<(string|null)[]>([null,null,null,null]),busy=ref(false);
const previous=ref<(string|null)[]>([null,null,null,null]);
const trends=computed(()=>amounts.value.map((value,index)=>{
 const current=units4(value),before=units4(previous.value[index]);
 if(current===null||before===null||before===0n&&current!==0n)return {text:'—',direction:'unknown'};
 const delta=current-before;
 if(delta===0n)return {text:'0%',direction:'flat'};
 const base=before<0n?-before:before,change=delta<0n?-delta:delta;
 const hundredths=(change*10000n+base/2n)/base;
 const percent=`${hundredths/100n}.${String(hundredths%100n).padStart(2,'0')}`.replace(/\.?0+$/,'');
 return {text:`${delta>0n?'↑':'↓'} ${percent}%`,direction:delta>0n?'up':'down'};
}));
const cards=[
 {label:'总充值',color:'green'}, {label:'总消耗',color:'cyan'},
 {label:'今日消耗',color:'gold'}, {label:'昨日消耗',color:'purple'}
];
const dateFormat=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'});
function validAmount(value:unknown):value is string|null{return value===null||typeof value==='string'&&units4(value)!==null;}
let version=0;
async function load(){
 const own=++version;busy.value=false;
 if(!permitted.value)return;
 const now=Date.now(),today=dateFormat.format(new Date(now)),yesterday=dateFormat.format(new Date(now-86400000)),beforeYesterday=dateFormat.format(new Date(now-2*86400000));
 busy.value=true;
 const [all,recent,prior]=await Promise.allSettled([query({offset:0,limit:1}),query({from:beforeYesterday,to:today,offset:0,limit:3}),query({from:'0001-01-01',to:yesterday,offset:0,limit:1})]);
 if(own!==version)return;
 amounts.value=[null,null,null,null];previous.value=[null,null,null,null];
 if(all.status==='fulfilled'){
  const data=all.value.data;
  if(data&&units4(data.totalRechargeAmount4)!==null&&validAmount(data.totalConsumptionAmount4)){
   amounts.value[0]=data.totalRechargeAmount4!;amounts.value[1]=data.totalConsumptionAmount4;
  }
 }
 if(prior.status==='fulfilled'){
  const data=prior.value.data;
  if(data&&units4(data.totalRechargeAmount4)!==null)previous.value[0]=data.totalRechargeAmount4!;
  if(data&&validAmount(data.totalConsumptionAmount4))previous.value[1]=data.totalConsumptionAmount4;
 }
 if(recent.status==='fulfilled'){
  const data=recent.value.data;
  if(data&&Array.isArray(data.items)&&data.items.length<=3&&data.totalDays===data.items.length&&
    new Set(data.items.map(day=>day.date)).size===data.items.length&&
    data.items.every(day=>[today,yesterday,beforeYesterday].includes(day.date||'')&&validAmount(day.consumptionAmount4))){
   for(const [index,date] of [[2,today],[3,yesterday]] as const){
    const day=data.items.find(day=>day.date===date);amounts.value[index]=day?day.consumptionAmount4!: '0';
   }
   previous.value[2]=amounts.value[3]!;
   const priorDay=data.items.find(day=>day.date===beforeYesterday);previous.value[3]=priorDay?priorDay.consumptionAmount4!:'0';
  }
 }
 busy.value=false;
}
watch([()=>props.revision,()=>{const user=session?.currentAdmin.value;return JSON.stringify([user?.userId,user?.username,user?.role,user?.primary]);}],(next,prior)=>{if(next[1]!==prior?.[1]){amounts.value=[null,null,null,null];previous.value=[null,null,null,null];}void load();},{immediate:true,flush:'sync'});
onBeforeUnmount(()=>{version++;});
</script>

<template>
 <section v-if="permitted" class="overview-finance-summary" data-testid="overview-finance-summary" aria-label="账务总览" :aria-busy="busy">
  <article v-for="(card,index) in cards" :key="card.label" class="summary-card" :class="card.color" :aria-labelledby="`summary-title-${index}`">
   <h2 :id="`summary-title-${index}`">{{ card.label }}<span>（元）</span></h2>
   <p class="summary-amount"><LoadingValue :loading="busy&&amounts[index]==null">{{ formatAmount(amounts[index]) }}</LoadingValue></p>
   <div class="summary-trend" :class="trends[index]?.direction"><strong>{{trends[index]?.text}}</strong><small>较昨日</small></div>
  </article>
 </section>
</template>

<style scoped>
.overview-finance-summary{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;container-type:inline-size}
.summary-card{--accent:#0074ff;--light:#36b3ff;--deep:#004ee8;--wash:#dbeaff;display:grid;grid-template-columns:70px minmax(0,1fr) auto;grid-template-rows:auto auto;align-content:center;column-gap:10px;min-width:0;padding:15px 14px;border:1px solid #edf3fd;border-radius:12px;background:linear-gradient(115deg,white,#fbfdff);box-shadow:0 10px 22px #16448510}
.summary-card.cyan{--accent:#7800ee;--light:#b150ff;--deep:#5100ce;--wash:#f0e2ff}.summary-card.gold{--accent:#ff8709;--light:#ffbf49;--deep:#ed6200;--wash:#fff1df}.summary-card.purple{--accent:#00bf70;--light:#25e699;--deep:#00964c;--wash:#ddfaf0}
.summary-icon{grid-row:1/3;align-self:center;width:70px;height:76px}.summary-icon svg{width:100%;height:100%;overflow:visible}.summary-icon-object{filter:drop-shadow(0 4px 2px color-mix(in srgb,var(--accent) 40%,transparent))}
.summary-card h2{margin:4px 0 8px;color:#131e3d;font-size:14px;font-weight:650;line-height:1.4;white-space:nowrap}.summary-card .summary-amount{margin:0;color:#080f29;font-size:clamp(20px,1.65vw,32px);font-weight:800;line-height:1.15;letter-spacing:-.025em;font-variant-numeric:tabular-nums;overflow-wrap:anywhere}
.summary-trend{grid-column:3;grid-row:1/3;align-self:center;text-align:right;white-space:nowrap}.summary-trend strong{display:block;font-size:15px;color:#00bf70}.summary-trend.down strong{color:#fa2545}.summary-trend.unknown strong{color:#8092b4}.summary-trend small{display:block;margin-top:8px;color:#8092b4;font-size:12px;font-weight:500}
.summary-card { grid-template-columns: 52px minmax(0,1fr); row-gap: 8px; }
.summary-icon { width: 52px; height: 58px; grid-row: 1; }
.summary-card h2 { margin: 0; align-self: center; white-space: normal; }
.summary-card .summary-amount { grid-column: 1 / -1; font-size: clamp(20px,2.2cqi,30px); line-height: 1.35; white-space: nowrap; overflow-x: auto; overflow-y: hidden; overflow-wrap: normal; }
.summary-trend { grid-column: 1 / -1; grid-row: 3; display: flex; align-items: baseline; gap: 8px; text-align: left; }
.summary-trend small { margin-top: 0; color: #526789; }
.summary-trend strong { color: #087653; font-size: 13px; }
.summary-trend.down strong { color: #bf2547; }
.summary-trend.unknown strong { color: #526789; }
@media(max-width:1100px){.overview-finance-summary{grid-template-columns:repeat(2,minmax(0,1fr))}.summary-card .summary-amount{font-size:28px}}
@media(max-width:600px){.overview-finance-summary{grid-template-columns:minmax(0,1fr);gap:10px}.summary-card{padding:12px 18px}.summary-card .summary-amount{font-size:28px}}
</style>
