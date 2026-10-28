<script setup lang="ts">
import {ref,onMounted,onUnmounted,inject,computed} from 'vue';
import {adminSessionKey} from '../adminSession';
import {promotionApplicationsGetApiV1AdminPromotionsApplications,promotionReviewPostApiV1AdminPromotionsApplicationsApplicationIdReview,userDetailGetApiV1AdminUsersId,ApiError} from '../api';
type Row=NonNullable<Awaited<ReturnType<typeof promotionApplicationsGetApiV1AdminPromotionsApplications>>['data']['items']>[number];
type Review=Parameters<typeof promotionReviewPostApiV1AdminPromotionsApplicationsApplicationIdReview>[1];
const session=inject(adminSessionKey),allowed=computed(()=>session?.currentAdmin.value?.role==='ADMIN'&&session.currentAdmin.value.primary===true);
const rows=ref<Row[]>([]),page=ref(0),size=ref(10),total=ref(0),status=ref('PENDING'),busy=ref(false),error=ref('');
const reasons=ref<Record<string,string>>({}),users=ref<Record<string,{name:string;phone:string}>>({}),pending=ref<Record<string,Review>>({}),rejecting=ref(''),selected=ref<string[]>([]);
const counts=ref<Record<string,number|null>>({PENDING:null,APPROVED:null,REJECTED:null});
const states:Record<string,string>={PENDING:'待审核',APPROVED:'已通过',REJECTED:'已驳回'},types:Record<string,string>={PERSONAL:'个人',ENTERPRISE:'企业',API:'API'};
const storageKey=computed(()=>`xzwl-agent-reviews:${session?.currentAdmin.value?.userId||session?.currentAdmin.value?.username}`);
const allSelected=computed(()=>rows.value.length>0&&rows.value.every(row=>selected.value.includes(row.id||'')));
let active=true;
onUnmounted(()=>{active=false;});
function remember(){try{sessionStorage.setItem(storageKey.value,JSON.stringify(pending.value));}catch{}}
function date(value?:string|null){return value?new Date(value).toLocaleString('sv-SE',{timeZone:'Asia/Shanghai'}).slice(0,16):'—';}
async function load(){if(!allowed.value)return;busy.value=true;error.value='';try{
 const [result,...totals]=await Promise.all([promotionApplicationsGetApiV1AdminPromotionsApplications({page:page.value,size:size.value,status:status.value}),...Object.keys(states).map(status=>promotionApplicationsGetApiV1AdminPromotionsApplications({page:0,size:1,status}))]);
 if(!active)return;rows.value=result.data.items||[];total.value=result.data.total||0;selected.value=[];
 Object.keys(states).forEach((key,index)=>{counts.value[key]=totals[index]!.data.total;});
 await Promise.all([...new Set(rows.value.map(row=>row.userId).filter((id):id is string=>!!id))].map(async id=>{try{const {data:user}=await userDetailGetApiV1AdminUsersId(id);if(active)users.value[id]={name:user.nickname||id,phone:(user.phone?.replace(/^\+86/,'')||'—').replace(/^(\d{3})\d+(\d{4})$/,'$1 **** $2')};}catch{if(active)users.value[id]={name:id,phone:'—'};}}));
 for(const row of rows.value)if(row.id&&row.status!=='PENDING'&&pending.value[row.id]){delete pending.value[row.id];remember();}
 }catch{if(active)error.value='申请列表读取失败，请重试。';}finally{if(active)busy.value=false;}}
function filter(value:string){status.value=value;page.value=0;rejecting.value='';void load();}
async function review(row:Row,decision:'APPROVE'|'REJECT'){
 if(busy.value||!allowed.value||!row.id)return;const reason=reasons.value[row.id]?.trim();
 if(!pending.value[row.id]&&decision==='REJECT'&&!reason){error.value='必须填写拒绝原因';return;}
 const body=pending.value[row.id]||{commandId:crypto.randomUUID(),decision,rejectionReason:decision==='REJECT'?reason:null,decidedAt:new Date().toISOString()};pending.value[row.id]=body;remember();busy.value=true;error.value='';
 try{await promotionReviewPostApiV1AdminPromotionsApplicationsApplicationIdReview(row.id,body);delete pending.value[row.id];remember();if(active){rejecting.value='';await load();}}
 catch(cause){if(cause instanceof ApiError&&[400,422].includes(cause.status)){delete pending.value[row.id];remember();}if(active)error.value='审核尚未完成，请重试原操作。';}finally{if(active)busy.value=false;}
}
onMounted(()=>{try{const saved=JSON.parse(sessionStorage.getItem(storageKey.value)||'{}');for(const [id,body] of Object.entries(saved)){const value=body as Review;if(typeof value.commandId==='string'&&['APPROVE','REJECT'].includes(value.decision||'')&&typeof value.decidedAt==='string')pending.value[id]=value;}}catch{}void load();});
</script>
<template>
<section v-if="allowed" class="referral-card referral-applications" :aria-busy="busy">
 <header class="referral-section-header"><span class="referral-section-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="m8.5 10.4 5.7-3.6m-5.7 6.8 5.7 3.6"/><circle cx="6" cy="12" r="3.2"/><circle cx="17" cy="5" r="3.2"/><circle cx="17" cy="19" r="3.2"/></svg></span><div><h2>代理申请审核</h2><p>审核用户的代理申请，通过后将成为代理，享受代理分佣规则。</p></div><div class="referral-status-cards"><button v-for="(name,key) in states" :key="key" :aria-label="`${name}申请`" :aria-pressed="status===key" :disabled="busy" :class="`referral-status-${key}`" @click="filter(key)"><span aria-hidden="true">{{key==='PENDING'?'◷':key==='APPROVED'?'✓':'×'}}</span>{{name}} <strong>{{counts[key]??'—'}}</strong></button></div></header>
 <p v-if="error" role="alert">{{error}} <button :disabled="busy" @click="load">重试</button></p>
 <div class="referral-table-scroll" role="region" aria-label="代理申请列表" tabindex="0"><table><caption class="sr-only">代理申请审核</caption><thead><tr><th><input type="checkbox" aria-label="选择本页申请" :checked="allSelected" :disabled="busy||!rows.length" @change="selected=allSelected?[]:rows.map(row=>row.id||'')"/></th><th>申请时间 ↓</th><th>用户名</th><th>手机号</th><th>账号类型</th><th>申请理由</th><th>操作</th></tr></thead><tbody>
 <tr v-for="row in rows" :key="row.id||undefined"><td><input v-model="selected" type="checkbox" :value="row.id" :aria-label="`选择申请 ${row.id}`"/></td><td class="referral-nowrap">{{date(row.submittedAt)}}</td><td>{{users[row.userId||'']?.name||row.userId}}</td><td class="referral-nowrap">{{users[row.userId||'']?.phone||'—'}}</td><td><span class="referral-type" :class="{'is-personal':row.accountType==='PERSONAL'}">{{types[row.accountType||'']||'—'}}</span></td><td class="referral-application-reason"><span :title="row.plannedChannel||undefined">{{row.reason||'—'}}</span></td><td>
 <div v-if="row.status==='PENDING'" class="referral-review-actions"><button v-if="pending[row.id||'']" :disabled="busy" @click="review(row,pending[row.id||''].decision!)">重试原审核</button><template v-else><button :disabled="busy" @click="review(row,'APPROVE')">通过</button><button class="referral-reject" :disabled="busy" @click="rejecting=row.id||''">驳回</button></template></div><span v-else :class="`referral-status-${row.status}`">{{states[row.status||'']||row.status}}</span>
 <div v-if="rejecting===row.id&&!pending[row.id||'']" class="referral-rejection"><label>拒绝原因<input v-model="reasons[row.id||'']" :aria-label="`${row.id} 拒绝原因`" maxlength="2000" :disabled="busy"/></label><button :disabled="busy" @click="review(row,'REJECT')">确认驳回</button><button :disabled="busy" @click="rejecting=''">取消</button></div><small v-if="row.rejectionReason">{{row.rejectionReason}}</small>
 </td></tr><tr v-if="!rows.length"><td colspan="7" class="referral-empty">{{busy?'正在读取…':'暂无申请'}}</td></tr>
 </tbody></table></div>
 <nav class="referral-pagination" aria-label="申请列表分页"><span>共 {{total}} 条<template v-if="selected.length"> · 已选择 {{selected.length}} 项</template></span><div><select v-model.number="size" aria-label="每页申请条数" :disabled="busy" @change="page=0;load()"><option :value="10">10 条/页</option><option :value="20">20 条/页</option><option :value="50">50 条/页</option></select><button aria-label="上一页申请" :disabled="busy||page===0" @click="page--;load()">‹</button><span class="referral-current-page">{{page+1}}</span><button aria-label="下一页申请" :disabled="busy||(page+1)*size>=total" @click="page++;load()">›</button></div></nav>
</section>
</template>
