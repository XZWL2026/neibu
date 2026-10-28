<script setup lang="ts">
import { computed, inject, nextTick, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import PromoterIcon from "../PromoterIcon.vue";
import "./benefit-rules.css";
import AdminSearchSelect from "../components/AdminSearchSelect.vue";
import {
  adminBenefitSettingsAdapterKey,
  copyValidBenefitSettingsHistorySnapshot,
  copyValidBenefitSettingsSnapshot,
  InvalidBenefitSettingsHistoryRateError,
  isBenefitRate,
  type AdminBenefitSettingsAdapter,
  type BenefitCashLevel,
  type BenefitSettingsHistoryFilters,
  type BenefitSettingsHistoryScope,
  type BenefitSettingsHistorySnapshot,
  type BenefitSettingsHistoryRecord,
  type BenefitSettingsSnapshot
} from "./localBenefitSettingsFixtures";

const injectedAdapter = inject(adminBenefitSettingsAdapterKey);
if (!injectedAdapter) throw new Error("PromotionBenefitSettingsPanel requires a benefit settings adapter");
const adapter: AdminBenefitSettingsAdapter = injectedAdapter;
const snapshot = ref<BenefitSettingsSnapshot | null>(null);
const selectedPromoter = ref("");
const promoterOptions = computed(() => (snapshot.value?.promoters ?? []).map(promoter => ({ value: promoter.id, label: promoter.name, detail: promoter.id })));
const visiblePromoters = computed(() => (snapshot.value?.promoters ?? []).filter(promoter => promoter.id === selectedPromoter.value));
const loading = ref(true);
const saving = ref(false);
const error = ref("");
const feedback = ref("");
const history = ref<BenefitSettingsHistorySnapshot | null>(null);
const historyLoading = ref(true);
const historyQuerying = ref(false);
const historyLoadError = ref(false);
const historyFieldError = ref("");
const historyFeedback = ref("");
const historyScope = ref<"ALL" | BenefitSettingsHistoryScope>("ALL");
const historyPromoterKeyword = ref("");
const historyStartDate = ref("");
const historyEndDate = ref("");
let historyRequestGeneration = 0;
const globalRates = reactive({ invitationPointsRate: "", defaultLevel1CashRate: "" });
const promoterRates = reactive<Record<string, Record<BenefitCashLevel, string>>>({});
const globalLabels = {
  invitationPointsRate: "全局邀请积分比例",
  defaultLevel1CashRate: "默认返佣比例"
};
const globalNames = ["defaultLevel1CashRate", "invitationPointsRate"] as const;
const cashLevels: { name: BenefitCashLevel; label: string }[] = [
  { name: "level1CashRate", label: "返佣覆盖" }
];

function ratePercent(rate: string) {
  return (Number(rate) * 100).toFixed(4).replace(/\.?0+$/, "");
}

function loadSnapshot(next: BenefitSettingsSnapshot) {
  const valid = copyValidBenefitSettingsSnapshot(next);
  snapshot.value = valid;
  for (const name of globalNames) globalRates[name] = ratePercent(valid.global[name]);
  for (const promoter of valid.promoters) {
    promoterRates[promoter.id] = {
      level1CashRate: promoter.level1CashRate === null ? "" : ratePercent(promoter.level1CashRate),
      level2CashRate: promoter.level2CashRate ?? ""
    };
  }
}

onMounted(async () => {
  try {
    loadSnapshot(await adapter.load());
  } catch {
    feedback.value = "加载失败，暂时无法编辑推广规则";
  } finally {
    loading.value = false;
  }
});

function currentHistoryFilters(): BenefitSettingsHistoryFilters {
  return {
    scope: historyScope.value,
    promoterKeyword: historyPromoterKeyword.value,
    startDate: historyStartDate.value,
    endDate: historyEndDate.value
  };
}

async function loadHistory() {
  const generation = ++historyRequestGeneration;
  historyLoading.value = true;
  historyLoadError.value = false;
  historyFeedback.value = "";
  try {
    const next = copyValidBenefitSettingsHistorySnapshot(await adapter.loadHistory());
    if (generation === historyRequestGeneration) history.value = next;
  } catch {
    if (generation === historyRequestGeneration) {
      history.value = null;
      historyLoadError.value = true;
    }
  } finally {
    if (generation === historyRequestGeneration) historyLoading.value = false;
  }
}

onMounted(loadHistory);

async function queryHistory() {
  historyFieldError.value = historyStartDate.value && historyEndDate.value && historyStartDate.value > historyEndDate.value
    ? "开始日期不能晚于结束日期"
    : "";
  if (historyFieldError.value || historyQuerying.value) return;
  const generation = ++historyRequestGeneration;
  historyLoading.value = false;
  historyQuerying.value = true;
  historyFeedback.value = "";
  try {
    const next = copyValidBenefitSettingsHistorySnapshot(await adapter.queryHistory(currentHistoryFilters()));
    if (generation === historyRequestGeneration) {
      history.value = next;
      historyLoadError.value = false;
    }
  } catch (cause) {
    if (generation === historyRequestGeneration) {
      historyLoadError.value = history.value === null;
      historyFeedback.value = cause instanceof InvalidBenefitSettingsHistoryRateError
        ? "历史比例格式错误，已保留上次历史"
        : "历史查询失败，已保留上次历史";
    }
  } finally {
    if (generation === historyRequestGeneration) historyQuerying.value = false;
  }
}

async function refreshHistoryAfterSave() {
  const generation = ++historyRequestGeneration;
  historyLoading.value = false;
  historyQuerying.value = false;
  historyFeedback.value = "";
  try {
    const next = copyValidBenefitSettingsHistorySnapshot(await adapter.queryHistory(currentHistoryFilters()));
    if (generation === historyRequestGeneration) {
      history.value = next;
      historyLoadError.value = false;
    }
  } catch (cause) {
    if (generation === historyRequestGeneration) {
      historyLoadError.value = history.value === null;
      historyFeedback.value = cause instanceof InvalidBenefitSettingsHistoryRateError
        ? "配置已保存，历史比例格式错误，已保留上次历史"
        : "配置已保存，版本历史刷新失败，已保留上次历史";
    }
  }
}

async function save(action: () => Promise<BenefitSettingsSnapshot>, updateSubmittedDraft: (valid: BenefitSettingsSnapshot) => void) {
  if (saving.value) return;
  saving.value = true;
  error.value = "";
  feedback.value = "";
  try {
    const valid = copyValidBenefitSettingsSnapshot(await action());
    updateSubmittedDraft(valid);
    for (const promoter of valid.promoters) {
      promoterRates[promoter.id] ??= { level1CashRate: promoter.level1CashRate === null ? "" : ratePercent(promoter.level1CashRate), level2CashRate: promoter.level2CashRate ?? "" };
    }
    snapshot.value = valid;
    await refreshHistoryAfterSave();
    feedback.value = "推广规则已保存";
    return true;
  } catch {
    feedback.value = "保存失败，已保留原配置快照";
    return false;
  } finally {
    saving.value = false;
  }
}

function saveGlobal(name: keyof typeof globalRates) {
  const percent = globalRates[name];
  const rate = percentRate(percent);
  error.value = !isBenefitRate(rate) ? "百分比必须是 0 到 100 之间、最多 4 位小数的数字" : "";
  if (error.value) return;
  return save(() => adapter.saveGlobalRate(name, rate, "调整收益比例"), (valid) => {
    if (globalRates[name] === percent) {
      globalRates[name] = ratePercent(valid.global[name]);
    }
  });
}

function savePromoter(promoterId: string, level: BenefitCashLevel) {
  const rawRate = promoterRates[promoterId]?.[level] ?? "";
  const rate = rawRate === "" ? null : percentRate(rawRate);
  error.value = rawRate && !isBenefitRate(rate) ? "百分比必须是 0 到 100 之间、最多 4 位小数的数字" : "";
  if (error.value) return;
  return save(() => adapter.savePromoterRate(promoterId, level, rate, "调整收益比例"), (valid) => {
    const promoter = valid.promoters.find((item) => item.id === promoterId);
    if (!promoter) throw new Error("saved promoter missing from snapshot");
    if (promoterRates[promoterId][level] === rawRate) {
      promoterRates[promoterId][level] = promoter[level] === null ? "" : ratePercent(promoter[level]!);
    }
  });
}

function percentRate(value: string) {
  return /^(?:0|[1-9]\d?|100)(?:\.\d{1,4})?$/.test(value) && Number(value) <= 100
    ? (Number(value)/100).toFixed(6).replace(/\.?0+$/, "") : "";
}
const editingGlobal=reactive({invitationPointsRate:false,defaultLevel1CashRate:false});
const globalTitles={invitationPointsRate:"客户推广默认返积分比例",defaultLevel1CashRate:"代理默认分佣比例"};
type Kind="agent"|"customer";
const ruleSearch=reactive({agent:"",customer:""}), rulePage=reactive({agent:0,customer:0}),ruleSize=reactive({agent:10,customer:10});
const groups=computed(()=>[
  {kind:"agent" as Kind,title:"特殊代理分佣规则",label:"代理",rows:(snapshot.value?.promoters??[]).filter(p=>p.level1CashRate!==null).map(p=>({...p,rate:p.level1CashRate!})),defaultRate:snapshot.value?.global.defaultLevel1CashRate??"0"},
  {kind:"customer" as Kind,title:"特殊客户积分规则",label:"客户",rows:(snapshot.value?.customers??[]).map(c=>({...c,rate:c.pointsRate,enabled:c.enabled!==false&&!c.cashAgent})),defaultRate:snapshot.value?.global.invitationPointsRate??"0"}
].map(g=>{const search=ruleSearch[g.kind].trim().toLowerCase();const filtered=g.rows.filter(r=>`${r.name} ${r.phone??""} ${r.id}`.toLowerCase().includes(search));return {...g,total:filtered.length,rows:filtered.slice(rulePage[g.kind]*ruleSize[g.kind],(rulePage[g.kind]+1)*ruleSize[g.kind])};}));
for(const kind of ["agent","customer"] as const){
  watch(()=>[ruleSearch[kind],ruleSize[kind]],()=>{rulePage[kind]=0;});
  watch(()=>groups.value.find(g=>g.kind===kind)?.total??0,total=>{rulePage[kind]=Math.min(rulePage[kind],Math.max(0,Math.ceil(total/ruleSize[kind])-1));});
}
const agentDialog=ref<HTMLDialogElement>(),customerDialog=ref<HTMLDialogElement>();
let dialogTrigger:HTMLElement|undefined;
function openAgent(event:Event,id="") {if(saving.value)return;error.value="";feedback.value="";selectedPromoter.value=id;dialogTrigger=event.currentTarget as HTMLElement;agentDialog.value?.showModal();}
function closeDialog(kind:Kind) {if(saving.value)return;(kind==="agent"?agentDialog:customerDialog).value?.close();error.value="";dialogTrigger?.focus();}
async function submitAgent(id:string,level:BenefitCashLevel){if(await savePromoter(id,level))closeDialog("agent");}
const selectedCustomer=ref(""),customerRate=ref(""),customerName=ref(""),customerEditing=ref(false);
const customerOptions=ref<{value:string;label:string;detail?:string}[]>([]),customerTotal=ref(0),customerOffset=ref(0),customerQuery=ref(""),customerLoading=ref(false),customerSearchError=ref("");
let customerGeneration=0,alive=true;
onUnmounted(()=>{alive=false;customerGeneration++;historyRequestGeneration++;});
async function searchCustomers(query:string,more=false){
 const generation=++customerGeneration;customerLoading.value=true;customerSearchError.value="";
 if(!more){customerQuery.value=query;customerOptions.value=[];customerOffset.value=0;customerTotal.value=0;}
 const offset=more?customerOffset.value:0;
 try{const result=await adapter.searchCustomers(query,offset);if(!alive||generation!==customerGeneration)return;
  customerOptions.value=more?[...customerOptions.value,...result.items]:result.items;customerOffset.value=offset+20;customerTotal.value=result.total;
 }catch{if(alive&&generation===customerGeneration)customerSearchError.value="客户查询失败，请重试。";}
 finally{if(alive&&generation===customerGeneration)customerLoading.value=false;}
}
function openCustomer(event:Event,id=""){
 if(saving.value)return;error.value="";feedback.value="";customerSearchError.value="";customerGeneration++;customerLoading.value=false;
 const row=snapshot.value?.customers?.find(c=>c.id===id);customerEditing.value=!!row;selectedCustomer.value=id;customerName.value=row?.name??"";
 customerRate.value=row?ratePercent(row.pointsRate):"";dialogTrigger=event.currentTarget as HTMLElement;customerDialog.value?.showModal();
 if(!row)void searchCustomers("");
}
async function submitCustomer(){
 const rate=percentRate(customerRate.value);
 error.value=!selectedCustomer.value?"请选择客户":!isBenefitRate(rate)?"百分比必须是 0 到 100 之间、最多 4 位小数的数字":"";
 if(error.value)return;
 if(await save(()=>adapter.saveCustomerRate(selectedCustomer.value,rate,"调整收益比例"),()=>{}))closeDialog("customer");
}
async function removeRule(kind:Kind,id:string){
 if(saving.value)return;
 await save(()=>kind==="agent"?adapter.savePromoterRate(id,"level1CashRate",null,"调整收益比例"):adapter.saveCustomerRate(id,null,"调整收益比例"),()=>{if(kind==="agent")promoterRates[id].level1CashRate="";});
}
const historyPage=ref(0),historySize=ref(10);
const visibleHistory=computed(()=>history.value?.records.slice(historyPage.value*historySize.value,(historyPage.value+1)*historySize.value)??[]);
watch(()=>[history.value,historySize.value],()=>{historyPage.value=0;});
function historyLabel(record:BenefitSettingsHistoryRecord){
 if(record.scope==="CUSTOMER_OVERRIDE")return "特殊客户返积分比例";
 if(record.scope==="PROMOTER_OVERRIDE")return "特殊代理分佣比例";
 return record.field==="invitationPointsRate"?"客户默认返积分比例":record.field==="defaultLevel1CashRate"?"代理默认分佣比例":record.field==="defaultLevel2CashRate"?"历史间接分佣比例":"默认比例配置";
}
function historyAfter(record:BenefitSettingsHistoryRecord){
 if(record.afterRate!==undefined)return record.afterRate===null?"恢复默认":ratePercent(record.afterRate)+"%";
 return record.scope==="GLOBAL"?`积分 ${ratePercent(record.point!)}% / 分佣 ${ratePercent(record.defaultL1!)}%`:record.promoterL1===null?"恢复默认":ratePercent(record.promoterL1)+"%";
}
</script>
<template>
 <div class="benefit-rules">
  <p v-if="loading" data-testid="benefit-settings-loading">推广规则加载中…</p>
  <template v-else-if="snapshot">
   <div class="benefit-defaults">
    <section v-for="name in globalNames" :key="name" class="benefit-default-card" :aria-label="globalTitles[name]">
     <i :class="{pink:name==='invitationPointsRate'}"><PromoterIcon :name="name==='invitationPointsRate'?'gift':'users'"/></i>
     <div class="benefit-default-value"><h2>{{globalTitles[name]}}</h2><strong>{{ratePercent(snapshot.global[name])}}%</strong><p>{{name==='invitationPointsRate'?'适用于：普通用户推广':'适用于：所有代理用户'}}</p></div>
     <div class="benefit-example"><b>计算示例</b><span>{{name==='invitationPointsRate'?'下级客户消费积分 10,000':'下级客户消费 ¥10,000'}}</span><span>× {{name==='invitationPointsRate'?'返积分':'分佣'}}比例 {{ratePercent(snapshot.global[name])}}%</span><strong>= {{name==='invitationPointsRate'?'推广奖励积分 ':'代理收益 ¥'}}{{(Number(snapshot.global[name])*10000).toLocaleString('zh-CN',{maximumFractionDigits:4})}}</strong></div>
     <button class="benefit-edit-default" :aria-label="`修改${globalLabels[name]}`" :disabled="saving" @click="editingGlobal[name]=!editingGlobal[name]">修改比例</button>
     <div v-show="editingGlobal[name]" class="benefit-inline-edit">
      <label>{{globalLabels[name]}}<span class="benefit-percent-input"><input v-model="globalRates[name]" :aria-label="globalLabels[name]" aria-describedby="global-percent-unit" inputmode="decimal" autocomplete="off"><span aria-hidden="true">%</span></span></label>
      <button data-benefit-save class="primary" :aria-label="`保存${globalLabels[name]}`" :disabled="saving" @click="saveGlobal(name)">保存</button>
     </div>
    </section>
   </div>
   <span id="global-percent-unit" class="sr-only">百分比</span>
   <div class="benefit-rule-columns">
    <section v-for="group in groups" :key="group.kind" class="benefit-rule-card" :data-testid="`rules-${group.kind}`" :aria-label="group.title">
     <header><h2>{{group.title}}</h2><button class="primary" data-benefit-save :aria-label="`添加特殊${group.label}`" :disabled="saving" @click="group.kind==='agent'?openAgent($event):openCustomer($event)">＋ 添加特殊{{group.label}}</button></header>
     <label class="benefit-search"><PromoterIcon name="search"/><input v-model="ruleSearch[group.kind]" :aria-label="`搜索特殊${group.label}`" :placeholder="group.kind==='agent'?'搜索代理名称 / 手机号':'搜索用户昵称 / 手机号'"></label>
     <div class="benefit-table-wrap"><table><caption class="sr-only">{{group.title}}</caption><thead><tr><th>{{group.kind==='agent'?'代理名称':'用户昵称'}}</th><th>手机号</th><th>默认比例</th><th>特殊比例</th><th>生效时间</th><th>状态</th><th>操作</th></tr></thead><tbody>
      <tr v-for="row in group.rows" :key="row.id"><td>{{row.name}}</td><td>{{row.phone||'—'}}</td><td>{{ratePercent(group.defaultRate)}}%</td><td class="benefit-special">{{ratePercent(row.rate)}}%</td><td>{{row.effectiveAt?.slice(0,10)||'—'}}</td><td><span class="benefit-state" :class="{inactive:row.enabled===false}">{{row.enabled===false?'未生效':'生效'}}</span></td><td><div class="benefit-actions"><button data-benefit-save class="text-action" :aria-label="`编辑${group.label} ${row.name}`" :disabled="saving||row.enabled===false" @click="group.kind==='agent'?openAgent($event,row.id):openCustomer($event,row.id)">编辑</button><button data-benefit-save class="text-action danger" :aria-label="`删除${group.label}规则 ${row.id}`" :disabled="saving" @click="removeRule(group.kind,row.id)">删除</button><RouterLink :to="group.kind==='agent'?`/admin/agents/${encodeURIComponent(row.id)}`:`/admin/users/${encodeURIComponent(row.id)}`">查看{{group.label}} ↗</RouterLink></div></td></tr>
      <tr v-if="!group.rows.length"><td class="benefit-empty" colspan="7">暂无匹配规则</td></tr>
     </tbody></table></div>
     <footer class="benefit-pagination"><span>共 {{group.total}} 条</span><div><button :aria-label="`${group.label}规则上一页`" :disabled="rulePage[group.kind]===0" @click="rulePage[group.kind]--">‹</button><span class="current-page">{{rulePage[group.kind]+1}}</span><button :aria-label="`${group.label}规则下一页`" :disabled="(rulePage[group.kind]+1)*ruleSize[group.kind]>=group.total" @click="rulePage[group.kind]++">›</button></div><select v-model.number="ruleSize[group.kind]" :aria-label="`${group.label}每页条数`"><option :value="10">10 条/页</option><option :value="20">20 条/页</option><option :value="50">50 条/页</option></select></footer>
    </section>
   </div>
   <section class="benefit-history benefit-rule-card" aria-labelledby="benefit-history-title">
    <header><h2 id="benefit-history-title">规则修改记录</h2>
     <form data-testid="benefit-history-filter-form" @submit.prevent="queryHistory">
      <select v-model="historyScope" aria-label="历史范围"><option value="ALL">全部类型</option><option value="GLOBAL">默认比例</option><option value="PROMOTER_OVERRIDE">特殊代理</option><option value="CUSTOMER_OVERRIDE">特殊客户</option></select>
      <input v-model="historyPromoterKeyword" aria-label="历史脱敏推广人关键词" placeholder="搜索规则对象">
      <input v-model="historyStartDate" aria-label="历史开始日期" type="date"><span>至</span><input v-model="historyEndDate" aria-label="历史结束日期" type="date">
      <button :disabled="historyQuerying">{{historyQuerying?'筛选中…':'筛选'}}</button>
     </form>
    </header>
    <p v-if="historyFieldError" role="alert">{{historyFieldError}}</p>
    <p v-if="historyLoading" data-testid="benefit-history-loading">收益比例版本历史加载中…</p>
    <div v-else-if="historyLoadError" data-testid="benefit-history-load-error" role="alert"><p>收益比例版本历史加载失败</p><button data-testid="benefit-history-retry" @click="loadHistory">重试</button></div>
    <template v-else-if="history">
     <div class="benefit-table-wrap" data-testid="benefit-history-table"><table><caption class="sr-only">规则修改记录</caption><thead><tr><th>时间</th><th>规则类型</th><th>修改前</th><th>修改后</th><th>操作人</th><th>备注</th></tr></thead><tbody>
      <tr v-for="record in visibleHistory" :key="`${record.version}-${record.scope}-${record.maskedPromoter??'global'}`" :data-testid="`benefit-history-row-${record.version}`"><td>{{record.effectiveAt}}</td><td>{{historyLabel(record)}}<small v-if="record.maskedPromoter">{{record.maskedPromoter}}</small></td><td>{{record.beforeRate==null?'—':ratePercent(record.beforeRate)+'%'}}</td><td>{{historyAfter(record)}}</td><td>{{record.admin}}</td><td>{{record.reason}}</td></tr>
      <tr v-if="!visibleHistory.length"><td colspan="6" class="benefit-empty" data-testid="benefit-history-empty">当前筛选没有版本记录</td></tr>
     </tbody></table></div>
     <footer class="benefit-pagination"><span data-testid="benefit-history-count">共 {{history.count}} 条</span><div><button aria-label="历史上一页" :disabled="historyPage===0" @click="historyPage--">‹</button><span class="current-page">{{historyPage+1}}</span><button aria-label="历史下一页" :disabled="(historyPage+1)*historySize>=history.records.length" @click="historyPage++">›</button></div><select v-model.number="historySize" aria-label="历史每页条数"><option :value="10">10 条/页</option><option :value="20">20 条/页</option><option :value="50">50 条/页</option></select></footer>
    </template>
   </section>
   <dialog ref="agentDialog" class="benefit-dialog" aria-labelledby="agent-edit-title" @cancel.prevent="closeDialog('agent')">
    <header><h2 id="agent-edit-title">特殊代理分佣比例</h2><button aria-label="取消代理编辑" :disabled="saving" @click="closeDialog('agent')">×</button></header>
    <label>选择推广人<AdminSearchSelect id="benefit-promoter-select" v-model="selectedPromoter" label="选择推广人" :options="promoterOptions" :disabled="saving"/></label>
    <article v-for="promoter in visiblePromoters" :key="promoter.id" class="benefit-promoter-card"><header><strong>{{promoter.name}}</strong><span>{{promoter.id}}</span></header><div v-for="level in cashLevels" :key="level.name" class="benefit-setting-row"><label>{{level.label}}（%）<span class="benefit-percent-input"><input v-model="promoterRates[promoter.id][level.name]" :aria-label="`${promoter.id} ${level.label}`" inputmode="decimal" placeholder="使用默认值" autocomplete="off" :disabled="saving"><span>%</span></span></label><button data-benefit-save class="primary" :aria-label="`保存 ${promoter.id} ${level.label}`" :disabled="saving" @click="submitAgent(promoter.id,level.name)">保存</button></div></article>
    <p v-if="error" role="alert">{{error}}</p><p v-if="feedback" role="status">{{feedback}}</p>
   </dialog>
   <dialog ref="customerDialog" class="benefit-dialog" aria-labelledby="customer-edit-title" @cancel.prevent="closeDialog('customer')">
    <header><h2 id="customer-edit-title">{{customerEditing?'编辑':'添加特殊'}}客户返积分比例</h2><button aria-label="取消客户编辑" :disabled="saving" @click="closeDialog('customer')">×</button></header>
    <p v-if="customerEditing">{{customerName}}</p><label v-else>选择客户<AdminSearchSelect id="benefit-customer-select" v-model="selectedCustomer" label="选择客户" :options="customerOptions" remote :loading="customerLoading" :has-more="customerOffset<customerTotal" :disabled="saving" @query="searchCustomers($event)" @load-more="searchCustomers(customerQuery,true)"/></label>
    <p v-if="customerSearchError" role="alert">{{customerSearchError}}</p>
    <form @submit.prevent="submitCustomer"><label>返积分比例（%）<span class="benefit-percent-input"><input v-model="customerRate" aria-label="客户返积分比例" inputmode="decimal" autocomplete="off" :disabled="saving"><span>%</span></span></label><p v-if="error" role="alert">{{error}}</p><p v-if="feedback" role="status">{{feedback}}</p><footer><button type="button" :disabled="saving" @click="closeDialog('customer')">取消</button><button data-benefit-save class="primary" aria-label="保存客户返积分比例" :disabled="saving">{{saving?'保存中…':'保存'}}</button></footer></form>
   </dialog>
  </template>
  <p v-if="error&&!agentDialog?.open&&!customerDialog?.open" class="field-error" role="alert">{{error}}</p>
  <p v-if="feedback&&!agentDialog?.open&&!customerDialog?.open" role="status" aria-live="polite">{{feedback}}</p>
  <p v-if="historyFeedback" data-testid="benefit-history-feedback" role="status" aria-live="polite">{{historyFeedback}}</p>
 </div>
</template>
