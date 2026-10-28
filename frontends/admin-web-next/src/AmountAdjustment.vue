<script setup lang="ts">
import PromoterIcon from './PromoterIcon.vue';
import { formatAmount } from './shared/formatAmount';

import {computed,onMounted,ref,watch} from 'vue';
import {ApiError,refreshSession,paymentManualPostApiV1AdminPaymentsRecharges,billingDebitPostApiV1AdminBillingDebits,paymentAdminOptionsGetApiV1AdminPaymentsRechargeOptions} from './api';
type Options=Awaited<ReturnType<typeof paymentAdminOptionsGetApiV1AdminPaymentsRechargeOptions>>['data'];
type Manual=Parameters<typeof paymentManualPostApiV1AdminPaymentsRecharges>[0];
type Debit=Parameters<typeof billingDebitPostApiV1AdminBillingDebits>[0];
type Attempt={kind:'recharge';body:Manual}|{kind:'debit';body:Debit};
const props=defineProps<{userId?:string;keyId?:string;options?:Options|null;reference?:boolean;availableAmount4?:string|null;apiClient?:boolean}>();
const emit=defineEmits<{changed:[];pending:[value:boolean];cancel:[]}>();
const amount=ref(''),enterprise=ref(''),remark=ref(''),notice=ref(''),error=ref(''),busy=ref(false),attempt=ref<Attempt|null>(null),storageKey=ref(''),initialized=ref(false);
const names:Record<string,string>={PERSONAL:'个人',ENTERPRISE:'企业',API:'API'};
const rule=computed(()=>props.options?.rules?.find(item=>item.accountType===props.options?.accountType));
const yuan=(value:string)=>{const[w,f='']=value.split('.');return `${w}.${f.padEnd(2,'0').slice(0,2)}`;};
const units=(value:string,digits:number)=>{const[w,f='']=value.split('.');return BigInt(w)*10n**BigInt(digits)+BigInt(f.padEnd(digits,'0'));};
const positive=computed(()=>/^\d+(\.\d{1,2})?$/.test(amount.value)&&units(amount.value,2)>0n);
const ready=computed(()=>initialized.value&&!!props.userId&&!!props.keyId&&props.options?.userId===props.userId&&['PERSONAL','ENTERPRISE','API'].includes(props.options.accountType||''));
const points=computed(()=>positive.value&&/^\d+(\.\d{1,4})?$/.test(props.options?.pointsPerYuan4||'')?(()=>{const value=units(amount.value,2)*units(props.options!.pointsPerYuan4!,4)/100n;return `${value/10000n}.${String(value%10000n).padStart(4,'0')}`;})():'—');
const direction=ref<'recharge'|'debit'>('recharge');
const preview=computed(()=>{if(!positive.value||!/^\d+(\.\d{1,4})?$/.test(props.availableAmount4||''))return null;const delta=units(amount.value,4),value=units(props.availableAmount4!,4)+(direction.value==='recharge'?delta:-delta);return `${value<0n?'-':''}${(value<0n?-value:value)/10000n}.${String((value<0n?-value:value)%10000n).padStart(4,'0')}`;});
function step(delta:number){if(busy.value||attempt.value)return;const value=(positive.value?units(amount.value,2):0n)+BigInt(delta)*100n;amount.value=value>0n?`${value/100n}.${String(value%100n).padStart(2,'0')}`:'0.00';}
const valid=computed(()=>ready.value&&positive.value&&!!remark.value.trim());
const rechargeValid=computed(()=>valid.value&&(props.reference||props.options?.accountType!=='ENTERPRISE'||!!enterprise.value.trim()));
function remember(value:Attempt|null){attempt.value=value;emit('pending',!!value);try{if(storageKey.value){if(value)sessionStorage.setItem(storageKey.value,JSON.stringify(value));else sessionStorage.removeItem(storageKey.value);}}catch{/* Uncertain requests remain in memory when storage is unavailable. */}}
async function restore(){initialized.value=false;const session=await refreshSession();const adminId=session.authenticated?session.user?.id:'';storageKey.value=adminId&&props.userId?`xzwl-amount-adjustment:${adminId}:${props.userId}`:'';attempt.value=null;emit('pending',false);initialized.value=true;if(!storageKey.value)return;try{const value=JSON.parse(sessionStorage.getItem(storageKey.value)||'null');if(value&&['recharge','debit'].includes(value.kind)&&typeof value.body?.requestId==='string'&&typeof value.body?.amount4==='string'&&typeof value.body?.remark==='string'&&(value.kind==='recharge'?value.body.userId===props.userId:value.body.keyId===props.keyId)){attempt.value=value;direction.value=value.kind;amount.value=yuan(value.body.amount4);remark.value=value.body.remark;enterprise.value=value.body.enterpriseName||'';emit('pending',true);}}catch{/* Malformed browser drafts are ignored. */}}
watch(()=>[props.userId,props.keyId],()=>{notice.value='';error.value='';void restore().catch(()=>{error.value='无法验证当前管理员，请重试。';});});
watch(()=>rule.value,()=>{if(!amount.value&&!attempt.value&&rule.value?.presetAmounts4?.[0])amount.value=yuan(rule.value.presetAmounts4[0]);},{immediate:true});
onMounted(()=>void restore().catch(()=>{error.value='无法验证当前管理员，请重试。';}));
async function submit(kind:'recharge'|'debit'){
 if(busy.value||(!attempt.value&&!(kind==='recharge'?rechargeValid.value:valid.value)))return;
 busy.value=true;error.value='';notice.value='';
 const retry=!!attempt.value;const current=attempt.value||(kind==='recharge'?{kind,body:{requestId:crypto.randomUUID(),userId:props.userId,amount4:amount.value,accountType:props.options!.accountType as 'PERSONAL'|'ENTERPRISE'|'API',enterpriseName:!props.reference&&props.options!.accountType==='ENTERPRISE'?enterprise.value.trim():undefined,remark:remark.value.trim()}}:{kind,body:{requestId:crypto.randomUUID(),keyId:props.keyId,amount4:amount.value,remark:remark.value.trim()}});
 remember(current);
 try{if(current.kind==='recharge')await paymentManualPostApiV1AdminPaymentsRecharges(current.body);else await billingDebitPostApiV1AdminBillingDebits(current.body);
 remember(null);notice.value=current.kind==='recharge'?'人工充值申请已提交，待独立审核确认；尚未到账。':'额度扣减成功，已刷新余额与流水。';emit('changed');
 }catch(cause){if(cause instanceof ApiError&&([400,422].includes(cause.status)||(!retry&&[403,409].includes(cause.status))))remember(null);error.value=cause instanceof ApiError&&[400,422].includes(cause.status)?'服务端未受理，请核对金额、账户和可用额度；输入已保留，可修改后重试':cause instanceof ApiError&&cause.status===403?'当前管理员没有此操作权限。':cause instanceof ApiError&&cause.status===409?'申请冲突、账户状态变化或可用额度不足。请刷新核对；输入已保留。':'操作失败或响应未确认，输入和原请求已保留。请重试原请求。';}
 finally{busy.value=false;}
}
</script>
<template>
 <section class="amount-adjustment" :class="{'reference-adjustment':reference}" aria-label="额度调整"><h2>{{apiClient?'额度管理':'额度调整'}}</h2>
 <p v-if="!userId||!keyId">请先保存用户并生成真实主 Key，再办理额度调整。</p>
 <p v-if="error" role="alert">{{error}}</p><p v-if="notice" role="status">{{notice}}</p>
 <p v-if="attempt" role="status">原{{attempt.kind==='recharge'?'人工申请':'扣减申请'}}尚未确认受理，将使用原请求号和原金额重试。</p>
 <form @submit.prevent="submit(reference?direction:'recharge')"><fieldset :disabled="busy||!!attempt||!ready">
 <div v-if="reference" class="adjustment-direction"><button type="button" aria-label="增加额度" :aria-pressed="direction==='recharge'" @click="direction='recharge'"><PromoterIcon name="plus"/>增加额度</button><button type="button" aria-label="减少额度" :aria-pressed="direction==='debit'" @click="direction='debit'"><PromoterIcon name="minus"/>减少额度</button></div>
 <div v-if="!reference" class="amount-presets"><button v-for="value in rule?.presetAmounts4" :key="value" type="button" class="secondary-action" @click="amount=yuan(value)">¥ {{formatAmount(value)}}</button></div>
 <label :class="{'reference-amount':reference}"><span :class="{'sr-only':reference}">人民币金额（元）</span><span class="amount-stepper"><button v-if="reference" type="button" aria-label="金额减少 100 元" @click="step(-100)"><PromoterIcon name="minus"/></button><input aria-label="人民币金额（元）" v-model="amount" type="text" inputmode="decimal" required placeholder="输入金额"/><button v-if="reference" type="button" aria-label="金额增加 100 元" @click="step(100)"><PromoterIcon name="plus"/></button></span></label>
 <template v-if="reference"><p v-if="!apiClient" class="manual-amount-hint">支持手动输入金额</p><div class="reference-presets"><button v-for="value in apiClient?[5000,10000,20000,50000]:[500,1000,5000,10000]" :key="value" type="button" :aria-pressed="amount===String(value)" @click="amount=String(value)">{{direction==='recharge'?'+':'−'}}{{value}}</button></div><div class="adjustment-preview"><div><span>当前金额</span><strong :title="availableAmount4||undefined">{{availableAmount4==null?'—':'¥ '+yuan(availableAmount4)}}</strong></div><span>→</span><div><span>本次调整</span><strong :class="direction==='recharge'?'positive':'negative'">{{direction==='recharge'?'+':'−'}} ¥ {{positive?yuan(amount):'—'}}</strong></div><span>→</span><div><span>{{direction==='recharge'?'审核到账后':'调整后金额'}}</span><strong data-testid="adjusted-balance" :title="preview||undefined">{{preview===null?'—':'¥ '+yuan(preview)}}</strong></div></div></template>

 <label v-if="!reference&&options?.accountType==='ENTERPRISE'">企业名称<input v-model="enterprise" aria-label="企业名称" maxlength="200"/></label>
 <label>{{reference?'调整备注（必填）':'到账凭证与审核备注'}}<input aria-label="到账凭证与审核备注" v-model="remark" required :maxlength="reference?100:1000" placeholder="请输入调整原因…"/></label>
 <p v-if="!apiClient">{{names[options?.accountType||'']||'账户待保存'}} · 1 元 = {{ formatAmount(options?.pointsPerYuan4||'—') }} 积分 · 本次 {{ formatAmount(points) }} 积分</p>
 <p v-if="!apiClient&&(!reference||direction==='recharge')">充值为人工申请，须另行核实真实收款并审核确认；不会产生收钱吧付款记录。</p>
 <div v-if="!reference" class="adjustment-actions"><button type="submit" class="primary-action" :disabled="!rechargeValid">充值</button><button type="button" class="secondary-action" :disabled="!valid" @click="submit('debit')">扣减</button></div>
 </fieldset>
 <div v-if="reference" class="reference-adjustment-actions"><button type="button" :disabled="busy" @click="emit('cancel')">取消</button><button class="primary-action" type="submit" :disabled="busy||!!attempt||(direction==='recharge'?!rechargeValid:!valid||preview?.startsWith('-'))">{{apiClient&&direction==='recharge'?'提交充值审核':'确认调整'}}</button></div>
 </form>

 <button v-if="attempt" class="primary-action" :disabled="busy" @click="submit(attempt.kind)">重试原{{attempt.kind==='recharge'?'人工申请':'扣减申请'}}</button>
 </section>
</template>
<style scoped>
.amount-adjustment{display:grid;gap:12px}.amount-adjustment h2,.amount-adjustment p{margin:0}.amount-adjustment p{font-size:13px;color:#a9b7b7;line-height:1.6}.amount-adjustment [role=alert]{color:#ffb7a2}.amount-adjustment fieldset{display:grid;gap:16px;min-width:0;padding:0;border:0}.amount-adjustment label{display:grid;gap:8px}.amount-adjustment input{width:100%;min-width:0;min-height:44px;padding:10px 12px;border:1px solid #3a4646;border-radius:8px;color:#eef3f3;background:#0d1111}.amount-presets,.adjustment-actions{display:flex;flex-wrap:wrap;gap:8px}.adjustment-actions>*{flex:1}.amount-adjustment button:disabled{opacity:.45;cursor:not-allowed}
</style>
