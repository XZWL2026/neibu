<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue';
import { ApiError, paymentSubmitPostApiV1PaymentsCorporateTransfers as submitTransfer, paymentOptionsGetApiV1PaymentsRechargeOptions as readOptions, paymentGetGetApiV1PaymentsRechargesId as readOrder } from './api';
import { money, units4 } from './portalAmounts';
import Icon from './PromoterIcon.vue';
import ApiRecharge from './ApiRecharge.vue';
const props=defineProps<{owner:string}>();
type Options=Awaited<ReturnType<typeof readOptions>>['data'];
type Order=Awaited<ReturnType<typeof readOrder>>['data'];
type Attempt={requestId:string;amount4:string;remark:string;fileName:string;fileSize:number;lastModified:number;orderId?:string};
const amount=ref(''),remark=ref(''),file=shallowRef<File>(),options=ref<Options>(),order=ref<Order>(),attempt=ref<Attempt>(),busy=ref(false),error=ref('');
let revision=0,abort=new AbortController();
const preview=ref('');
watch(file,(value,previous,onCleanup)=>{const url=value?URL.createObjectURL(value):'';preview.value=url;onCleanup(()=>{if(url)URL.revokeObjectURL(url);});});
const minimum=computed(()=>options.value?.rules?.find(r=>r.accountType==='API')?.minimumAmount4);
const compatible=computed(()=>options.value&&(options.value.accountType===null||options.value.accountType==='API'));
const valid=computed(()=>{const value=amount.value.length<=64&&/^\d+(\.\d{1,2})?$/.test(amount.value)?units4(amount.value):null,min=units4(minimum.value);return compatible.value&&value!==null&&min!==null&&value>0n&&value>=min&&!!file.value&&remark.value.length<=200;});
function remember(value?:Attempt){attempt.value=value;try{const key=`xzwl-transfer:${props.owner}`;if(value)sessionStorage.setItem(key,JSON.stringify(value));else sessionStorage.removeItem(key);}catch{}}
function restore(){try{const value=JSON.parse(sessionStorage.getItem(`xzwl-transfer:${props.owner}`)||'null');if(value&&typeof value.requestId==='string'&&value.requestId.length<=128&&typeof value.amount4==='string'&&value.amount4.length<=64&&typeof value.remark==='string'&&value.remark.length<=200&&typeof value.fileName==='string'&&value.fileName.length<=200&&Number.isInteger(value.fileSize)&&Number.isFinite(value.lastModified)&&(!value.orderId||typeof value.orderId==='string')){attempt.value=value;amount.value=value.amount4;remark.value=value.remark;}}catch{}}
function chooseFile(event:Event){
 const input=event.target as HTMLInputElement,next=input.files?.[0];error.value='';
 if(!next)return;
 file.value=undefined;
 if(!['image/jpeg','image/png','application/pdf'].includes(next.type)||next.size===0||next.size>10*1024*1024){input.value='';error.value='请选择不超过 10MB 的 JPG、PNG 或 PDF 凭证。';return;}
 const previous=attempt.value;
 if(previous&&(next.name!==previous.fileName||next.size!==previous.fileSize||next.lastModified!==previous.lastModified)){input.value='';error.value='请重新选择上次提交的同一份凭证后重试。';return;}
 file.value=next;
}
async function run(action:(current:number)=>Promise<void>){if(busy.value||!props.owner)return;const current=revision;busy.value=true;error.value='';try{await action(current);}catch(cause){if(current===revision)error.value=cause instanceof ApiError&&cause.status===400?'金额、备注或凭证不符合要求，请检查后重试。':cause instanceof Error&&!(cause instanceof ApiError)?cause.message:'请求未完成，请重试本次提交或刷新记录。';}finally{if(current===revision)busy.value=false;}}
function receive(value:Order,current:number){if(current!==revision)return;if(value.userId!==props.owner||value.accountType!=='API'||value.source!=='CORPORATE'||!value.orderId)throw Error('转账记录归属不匹配');order.value=value;if(attempt.value)remember({...attempt.value,orderId:value.orderId});}
async function refresh(){await run(async current=>{const result=(await readOptions({signal:abort.signal})).data;if(current!==revision)return;if(result.userId!==props.owner)throw Error('账户归属不匹配');options.value=result;if(attempt.value?.orderId)receive((await readOrder(attempt.value.orderId,{signal:abort.signal})).data,current);});}
async function submit(){if(!valid.value||order.value)return;await run(async current=>{
 const value=attempt.value||{requestId:crypto.randomUUID(),amount4:amount.value,remark:remark.value,fileName:file.value!.name,fileSize:file.value!.size,lastModified:file.value!.lastModified};remember(value);
 try{receive((await submitTransfer({requestId:value.requestId,amount4:value.amount4,remark:value.remark,file:file.value!},{signal:abort.signal})).data,current);}
 catch(cause){if(current===revision&&cause instanceof ApiError&&cause.status===400)remember();throw cause;}
});}
function another(){remember();order.value=undefined;amount.value='';remark.value='';file.value=undefined;error.value='';}
watch(()=>props.owner,()=>{revision++;abort.abort();abort=new AbortController();options.value=undefined;order.value=undefined;attempt.value=undefined;file.value=undefined;amount.value='';remark.value='';error.value='';busy.value=false;restore();void refresh();},{immediate:true});
onBeforeUnmount(()=>{revision++;abort.abort();});
const rechargeOpen=ref(false);
const fields=[{label:'公司名称',value:'寻知未来（深圳）科技有限公司'},{label:'开户银行',value:'中国银行股份有限公司深圳南头支行'},{label:'银行账号',value:'770580898450'},{label:'开户行联行号',value:''},{label:'公司税号',value:''}];
const copyError=ref('');
async function copy(value:string){copyError.value='';try{await navigator.clipboard.writeText(value);}catch{copyError.value='复制失败，请手动选择并复制。';}}
</script>
<template>
 <section class="api-reference-page" aria-labelledby="api-transfer-title">
  <header class="api-reference-heading"><div><p>财务中心 / <span>公户转账</span></p><h1 id="api-transfer-title">公户转账</h1><p>通过公司对公账户进行转账，转账后提交凭证。</p></div><div class="api-page-actions"><button @click="rechargeOpen=!rechargeOpen"><Icon name="card"/>充值</button><button class="api-primary" disabled><Icon name="headset"/>在线客服</button></div></header>
  <ApiRecharge v-if="rechargeOpen" :key="owner" :owner="owner"/>
  <div class="api-transfer-grid">
   <section class="api-record-panel"><header class="api-transfer-step"><b>1</b><div><h2>公司账号信息</h2><p>请通过银行对公转账至以下账户</p></div></header><dl class="api-bank-details"><div v-for="field in fields" :key="field.label"><dt>{{field.label}}</dt><dd><span>{{field.value||'—'}}</span><button :disabled="!field.value" :aria-label="`复制${field.label}`" @click="copy(field.value)"><Icon name="copy"/>复制</button></dd></div></dl><p v-if="copyError" role="alert">{{copyError}}</p><aside class="api-transfer-tips"><h3>ⓘ 温馨提示</h3><ol><li>请务必从对公账户转账，并在备注中填写您的账号或订单号。</li><li>转账完成后，请提交转账凭证。</li><li>审核通过后，充值金额将计入您的账户。</li><li>如有疑问，请联系在线客服。</li></ol></aside></section>
   <section class="api-record-panel"><header class="api-transfer-step"><b>2</b><div><h2>提交转账凭证</h2><p>请上传转账凭证并填写相关信息</p></div></header><p v-if="error" role="alert">{{error}}</p>
   <div v-if="order" class="transfer-result" role="status"><h3>{{order.status==='PAID'?(order.fulfilled?'积分已到账':'已确认收款，积分入账中'):order.status==='REJECTED'?'审核未通过':'凭证已提交，等待确认收款'}}</h3><p>记录号：{{order.orderId}}</p><p>转账金额：{{money(order.amount4)}} · 积分：{{order.expectedPoints4}}</p><button type="button" :disabled="busy" @click="refresh">刷新状态</button><button type="button" :disabled="busy" @click="another">提交另一笔转账</button></div>
   <form v-else :key="owner" class="api-transfer-form" @submit.prevent="submit"><label>转账金额（元）<em>*</em><input v-model="amount" :disabled="busy||!!attempt" inputmode="decimal"  :placeholder="minimum?`API 最低起充 ${money(minimum)}`:'请输入转账金额'" required aria-label="转账金额（元）" aria-describedby="api-transfer-minimum"/><small v-if="minimum" id="api-transfer-minimum">API 最低起充 {{money(minimum)}}</small></label><label>转账凭证 <em>*</em><span class="api-upload"><Icon name="upload"/><strong>{{file?.name||'点击上传转账凭证'}}</strong><small>支持 JPG / PNG / PDF，单个文件不超过 10MB</small><input type="file" :disabled="busy||!!attempt&&!!file" accept="image/jpeg,image/png,application/pdf" aria-label="转账凭证" @change="chooseFile"/></span><small v-if="attempt&&!file">请重新选择 {{attempt.fileName}} 后重试本次提交。</small></label><div v-if="preview" class="transfer-preview"><a :href="preview" target="_blank" rel="noopener noreferrer" aria-label="预览转账凭证"><img v-if="file?.type.startsWith('image/')" :src="preview" alt="转账凭证预览"/><span>{{file?.type==='application/pdf'?'预览 PDF 凭证 ↗':'查看原图 ↗'}}</span></a></div><label>备注（选填）<span class="api-remark"><textarea v-model="remark" :disabled="busy||!!attempt" maxlength="200" placeholder="如：订单号 / 其他说明"/><small>{{remark.length}}/200</small></span></label><button class="api-primary api-transfer-submit" :disabled="busy||!valid"><Icon name="send"/>{{busy?'正在提交…':attempt?'重试本次提交':'提交凭证'}}</button><button v-if="!options" type="button" :disabled="busy" @click="refresh">重新读取充值规则</button><p v-if="options&&!compatible">当前账户不能办理 API 对公充值。</p></form></section>
  </div>
 </section>
</template>

<style scoped>
.transfer-preview a{display:grid;gap:8px;width:fit-content;color:#1677ff}.transfer-preview img{display:block;max-width:100%;max-height:240px;object-fit:contain;border:1px solid #d9e6ff;border-radius:8px}.api-upload{position:relative;cursor:pointer}.api-upload input{display:block;position:absolute;inset:0;width:100%;height:100%;opacity:0;cursor:pointer}.api-upload:focus-within{outline:2px solid #1677ff;outline-offset:3px}.api-upload strong{max-width:100%;overflow-wrap:anywhere;padding:0 12px}.api-transfer-form small{color:#617ba8}.transfer-result{overflow-wrap:anywhere}.transfer-result button{margin:8px 12px 0 0}[role=alert]{color:#b42318}
</style>
