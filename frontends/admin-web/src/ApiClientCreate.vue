<script setup lang="ts">
import {computed,inject,nextTick,onBeforeUnmount,onMounted,ref} from 'vue';
import {adminSessionKey} from './adminSession';
import {ApiError,refreshSession,userCreatePostApiV1AdminUsers,paymentAdminAccountPatchApiV1AdminPaymentsAccountsId,paymentManualPostApiV1AdminPaymentsRecharges,billingGetGetApiV1AdminBillingMediaPrices,paymentApiCreationOptionsGetApiV1AdminPaymentsApiCreationOptions,paymentAdminOptionsGetApiV1AdminPaymentsRechargeOptions,billingGetGetApiV1AdminBillingApiUsersOwnerIdMediaPrices,billingSavePutApiV1AdminBillingApiUsersOwnerIdMediaPrices,type RequestBillingDomainPricingMediaPrice as Price} from './api';
import ApiPriceSettings from './ApiPriceSettings.vue';
import './api-client-create.css';
const emit=defineEmits<{created:[userId:string];cancel:[];busy:[value:boolean]}>();
const session=inject(adminSessionKey),primary=computed(()=>session?.currentAdmin.value?.role==='ADMIN'&&session.currentAdmin.value.primary===true);
const nickname=ref(''),phone=ref(''),amount=ref('0'),remark=ref(''),mode=ref('default'),prices=ref<Price[]>([]);
const priceSettingsOpen=ref(false),settingsMode=ref('custom'),priceSettingsTrigger=ref<HTMLInputElement>();
function openPrices(value:string){if(busy.value||!ready.value||attempt.value&&attempt.value.rejectedStage!=='pricing')return;settingsMode.value=value;priceSettingsOpen.value=true;}
async function closePrices(){priceSettingsOpen.value=false;await nextTick();priceSettingsTrigger.value?.focus();}
function applyPrices(value:Price[],valueMode:string){prices.value=value;mode.value=valueMode;void closePrices();}
const defaults=ref<NonNullable<Awaited<ReturnType<typeof billingGetGetApiV1AdminBillingMediaPrices>>['data']['prices']>>([]),rate=ref(''),ready=ref(false),busy=ref(false),error=ref('');
type Attempt={body:Parameters<typeof userCreatePostApiV1AdminUsers>[0];accountRequestId:string;priceRequestId:string;rechargeRequestId:string;amount:string;prices:Price[];userId?:string;accountDone?:boolean;priceDone?:boolean;priceRequired?:boolean;priceVersion?:number;rejectedStage?:'pricing'|'funding';uncertainStage?:string};
const attempt=ref<Attempt|null>(null);let storageKey='',alive=true;
const normalizedPhone=computed(()=>{const value=phone.value.replace(/[\s()-]/g,'');return value.startsWith('+')?value:`+86${value}`;});
function units(value:string){if(!/^\d{1,12}(\.\d{1,4})?$/.test(value))return null;const[w,f='']=value.split('.');return BigInt(w!)*10000n+BigInt(f.padEnd(4,'0'));}
const amountUnits=computed(()=>units(amount.value));
const amountValid=computed(()=>/^\d{1,12}(\.\d{1,2})?$/.test(amount.value)&&amountUnits.value!==null);
const pricesValid=computed(()=>prices.value.every(price=>price.accountType==='API'&&units(price.yuanPerUnit||'')!==null&&defaults.value.some(base=>base.enabled&&base.accountType==='API'&&base.type===price.type&&base.model===price.model&&base.resolution===price.resolution)));
const valid=computed(()=>nickname.value.trim().length>0&&nickname.value.trim().length<=64&&/^\+[1-9]\d{7,14}$/.test(normalizedPhone.value)&&amountValid.value&&(mode.value!=='custom'||pricesValid.value));
const canSubmit=computed(()=>!attempt.value?valid.value:attempt.value.rejectedStage==='pricing'?(mode.value==='default'||pricesValid.value):attempt.value.rejectedStage==='funding'?amountValid.value:true);
function remember(){if(!storageKey)return;try{if(attempt.value)sessionStorage.setItem(storageKey,JSON.stringify(attempt.value));else sessionStorage.removeItem(storageKey);}catch{/* ponytail: unresolved writes remain in memory if browser storage is unavailable; server request IDs still prevent same-session duplicates. */}}
async function load(){if(!primary.value)return;ready.value=false;error.value='';try{const auth=await refreshSession();if(!auth.authenticated||!auth.user?.id)throw Error();storageKey=`xzwl-api-create:${auth.user.id}`;const saved=JSON.parse(sessionStorage.getItem(storageKey)||'null');if(saved){if(typeof saved.body?.requestId!=='string'||typeof saved.body.nickname!=='string'||typeof saved.body.phone!=='string'||typeof saved.amount!=='string'||!Array.isArray(saved.prices)||![saved.accountRequestId,saved.priceRequestId,saved.rechargeRequestId].every(v=>typeof v==='string'&&v.length>0))throw Error();attempt.value=saved;nickname.value=saved.body.nickname;phone.value=saved.body.phone;amount.value=saved.amount;remark.value=saved.body.remark||'';prices.value=saved.prices;mode.value=saved.prices.length?'custom':'default';}
 const [catalog,settings]=await Promise.all([billingGetGetApiV1AdminBillingMediaPrices(),paymentApiCreationOptionsGetApiV1AdminPaymentsApiCreationOptions()]);if(!alive)return;defaults.value=(catalog.data.prices||[]).filter(p=>p.accountType==='API'&&p.enabled);rate.value=settings.data.pointsPerYuan4||'';if(attempt.value?.rejectedStage)await prepareCorrection();ready.value=true;
 }catch{if(alive)error.value='无法读取价格或恢复创建请求，请重试。';}}
async function prepareCorrection(){const pending=attempt.value;if(!pending?.userId)return;
 if(pending.rejectedStage==='pricing'){const result=await billingGetGetApiV1AdminBillingApiUsersOwnerIdMediaPrices(pending.userId);if(!alive)return;defaults.value=result.data.defaults||[];pending.priceVersion=result.data.version;const dimension=(p:Price)=>[p.type,p.model,p.resolution].join('/');const allowed=new Set(defaults.value.map(dimension));prices.value=pending.prices.filter(p=>allowed.has(dimension(p))).map(p=>({...p}));}
 if(pending.rejectedStage==='funding'){const result=await paymentAdminOptionsGetApiV1AdminPaymentsRechargeOptions({userId:pending.userId});if(!alive)return;rate.value=result.data.pointsPerYuan4||'';}
 remember();}
async function create(){if(!primary.value||!ready.value||busy.value||!canSubmit.value)return;busy.value=true;emit('busy',true);error.value='';
 if(!attempt.value){attempt.value={body:{requestId:crypto.randomUUID(),nickname:nickname.value.trim(),phone:normalizedPhone.value,source:'ADMIN',remark:remark.value||null},accountRequestId:crypto.randomUUID(),priceRequestId:crypto.randomUUID(),rechargeRequestId:crypto.randomUUID(),amount:amount.value,prices:mode.value==='custom'?prices.value.map(p=>({...p})):[],priceRequired:mode.value==='custom',priceVersion:0};remember();}
 const pending=attempt.value;let stage='user';
 if(pending.rejectedStage==='pricing'){pending.prices=mode.value==='custom'?prices.value.map(p=>({...p})):[];pending.priceRequestId=crypto.randomUUID();pending.priceRequired=true;}
 if(pending.rejectedStage==='funding'){pending.amount=amount.value;pending.rechargeRequestId=crypto.randomUUID();}
 if(pending.rejectedStage){delete pending.rejectedStage;delete pending.uncertainStage;remember();}
 try{if(!pending.userId){const result=await userCreatePostApiV1AdminUsers(pending.body);if(!result.data.id)throw Error();pending.userId=result.data.id;remember();}
 if(!alive||!primary.value)return;stage='account';
 if(!pending.accountDone){await paymentAdminAccountPatchApiV1AdminPaymentsAccountsId(pending.userId,{requestId:pending.accountRequestId,accountType:'API',createKey:true});pending.accountDone=true;remember();}
 if(!alive||!primary.value)return;stage='pricing';
 if(!pending.priceDone&&(pending.priceRequired||pending.prices.length)){await billingSavePutApiV1AdminBillingApiUsersOwnerIdMediaPrices(pending.userId,{requestId:pending.priceRequestId,expectedVersion:pending.priceVersion??0,prices:pending.prices});pending.priceDone=true;remember();}
 if(!alive||!primary.value)return;stage='funding';
 if((units(pending.amount)??0n)>0n)await paymentManualPostApiV1AdminPaymentsRecharges({requestId:pending.rechargeRequestId,userId:pending.userId,amount4:pending.amount,accountType:'API',remark:pending.body.remark||'API 客户初始额度充值申请'});
 const id=pending.userId;attempt.value=null;remember();if(alive)emit('created',id);
 }catch(cause){if(alive){const definitive=cause instanceof ApiError&&([400,422].includes(cause.status)||cause.status===409&&pending.uncertainStage!==stage);
 if(!pending.userId&&definitive){attempt.value=null;remember();error.value='创建未受理，请核对企业名称、手机号和权限后重试。';}
 else if(pending.userId&&definitive&&(stage==='pricing'||stage==='funding')){pending.rejectedStage=stage;remember();error.value=stage==='pricing'?'客户已创建，价格修改未受理；请核对价格后继续。':'客户已创建，充值未受理；请核对金额后继续。';try{await prepareCorrection();}catch{ready.value=false;error.value+='读取当前配置失败，请重试。';}}
 else{pending.uncertainStage=stage;remember();error.value=pending.userId?'客户已创建，后续设置或充值提交尚未完成。请重试继续。':'创建结果尚未确认，请重试原请求。';}}}
 finally{busy.value=false;emit('busy',false);}}
onMounted(()=>void load());onBeforeUnmount(()=>{alive=false;});
</script>
<template>
 <div v-if="primary" class="api-client-create">
  <ApiPriceSettings v-if="priceSettingsOpen" :prices="defaults" :initial-prices="prices" :initial-mode="settingsMode" :points-per-yuan4="rate" title-id="api-create-title" @cancel="closePrices" @save="applyPrices"/>
  <template v-else>
   <header class="api-create-simple-header">
    <h2 id="api-create-title">添加 API 客户</h2>
    <button type="button" aria-label="关闭新增 API 客户" :disabled="busy" @click="emit('cancel')"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg></button>
   </header>
   <form class="api-create-simple-form" @submit.prevent="create">
    <p v-if="error" class="api-create-simple-error" role="alert">{{error}} <button v-if="!ready" type="button" @click="load">重试</button></p>
    <div class="api-create-fields-scroll"><fieldset :disabled="busy||(!!attempt&&!attempt.rejectedStage)||!ready">
     <label class="api-create-simple-field"><span>企业名称 <em>*</em></span><input v-model="nickname" aria-label="企业名称" placeholder="请输入企业名称" maxlength="64" required autofocus :disabled="!!attempt"/></label>
     <label class="api-create-simple-field"><span>手机号 <em>*</em></span><span class="api-create-simple-phone"><select aria-label="手机区号" :disabled="!!attempt"><option value="+86">+86</option></select><input v-model="phone" aria-label="手机号" type="tel" placeholder="请输入手机号" required :disabled="!!attempt"/></span></label>
     <label class="api-create-simple-field"><span>初始可用金额（元） <em>*</em></span><span class="api-create-simple-money"><span aria-hidden="true">¥</span><input v-model="amount" aria-label="初始可用金额（元）" inputmode="decimal" placeholder="请输入金额" :disabled="!!attempt&&attempt.rejectedStage!=='funding'" required/></span></label>
     <div class="api-create-simple-pricing">
      <span id="api-price-choice-label">价格配置 <em>*</em></span>
      <div class="api-create-simple-segments" role="radiogroup" aria-labelledby="api-price-choice-label">
       <label :class="{selected:mode==='default'}"><input v-model="mode" type="radio" value="default" name="api-price-mode" :disabled="!!attempt&&attempt.rejectedStage!=='pricing'"/><span>使用默认价格</span></label>
       <label :class="{selected:mode==='custom'}"><input ref="priceSettingsTrigger" type="radio" value="custom" name="api-price-mode" :checked="mode==='custom'" @click="openPrices('custom')" @change="openPrices('custom')" :disabled="!!attempt&&attempt.rejectedStage!=='pricing'"/><span>自定义价格</span></label>
      </div>
     </div>
     <details class="api-create-notes"><summary>备注（选填）</summary><label class="api-create-simple-field"><span class="sr-only">API 客户备注</span><textarea v-model="remark" aria-label="API 客户备注" maxlength="200" :disabled="!!attempt" placeholder="例如：合作来源、合同信息、客户行业等"/><small>{{remark.length}}/200</small></label></details>
    </fieldset></div>
    <footer class="api-create-simple-footer">
     <button type="button" :disabled="busy" @click="emit('cancel')">取消</button>
     <button type="submit" class="primary-action" :disabled="!ready||busy||!canSubmit">{{busy?'正在创建…':attempt?'重试并继续创建':amountUnits&&amountUnits>0n?'创建客户并提交充值审核':'创建客户'}}</button>
    </footer>
   </form>
  </template>
 </div>
 <p v-else>无权限</p>
</template>
