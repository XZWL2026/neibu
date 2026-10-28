<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { paymentHistoryGetApiV1AdminPaymentsCorporateTransfers as readTransfers, paymentProofGetApiV1AdminPaymentsCorporateTransfersIdProof as readProof, paymentConfirmManualPostApiV1AdminPaymentsRechargesIdConfirm as confirmReceipt, userDetailGetApiV1AdminUsersId as readUser } from './api';
import { money, when } from './portalAmounts';
type ResponseTransfer=NonNullable<Awaited<ReturnType<typeof readTransfers>>['data']['items']>[number];
type Transfer=ResponseTransfer&{order:NonNullable<ResponseTransfer['order']>};
const rows=ref<Transfer[]>([]),page=ref(0),total=ref(0),busy=ref(false),error=ref(''),confirmation=ref<Transfer>();
type User=Awaited<ReturnType<typeof readUser>>['data'];
const users=ref<Record<string,User>>({}),preview=ref<{url:string;fileName:string;contentType:string}>(),previewDialog=ref<HTMLDialogElement>();
function closePreview(){previewDialog.value?.close();if(preview.value)URL.revokeObjectURL(preview.value.url);preview.value=undefined;}
const size=20,abort=new AbortController();let active=true;
const status=(row:Transfer)=>row.order.status==='PAID'?(row.order.fulfilled?'已到账':'已确认，入账中'):row.order.status==='REJECTED'?'已拒绝':'待确认收款';
async function run(action:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';try{await action();}catch{if(active)error.value='操作未完成，请刷新记录后重试。';}finally{if(active)busy.value=false;}}
async function load(){
 const result=(await readTransfers({page:page.value,size},{signal:abort.signal})).data;
 if(!active)return;
 rows.value=(result.items||[]).map(row=>{if(!row.order?.orderId||row.order.source!=='CORPORATE')throw Error('invalid transfer');return {...row,order:row.order};});total.value=result.total;users.value={};
 const ids=[...new Set(rows.value.map(row=>row.order.userId).filter((id):id is string=>!!id))];
 const details=await Promise.allSettled(ids.map(id=>readUser(id,{signal:abort.signal})));
 if(active)users.value=Object.fromEntries(details.flatMap((result,index)=>result.status==='fulfilled'&&result.value.data.id===ids[index]?[[ids[index],result.value.data]]:[]));
}
async function changePage(next:number){if(busy.value)return;page.value=next;await run(load);}
async function receipt(row:Transfer,download=false){await run(async()=>{
 const id=row.order.orderId;if(!id)throw Error('missing order');
 const proof=(await readProof(id,{signal:abort.signal})).data;if(!active)return;
 if(!proof.base64||!proof.fileName||!['image/jpeg','image/png','application/pdf'].includes(proof.contentType||''))throw Error('invalid receipt');
 const data=Uint8Array.from(atob(proof.base64),c=>c.charCodeAt(0));
 const url=URL.createObjectURL(new Blob([data],{type:proof.contentType!}));
 if(download){const link=document.createElement('a');link.href=url;link.download=proof.fileName;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);return;}
 closePreview();preview.value={url,fileName:proof.fileName,contentType:proof.contentType!};await nextTick();if(active)previewDialog.value?.showModal();
});}
async function confirm(){const id=confirmation.value?.order.orderId;if(!id)return;await run(async()=>{await confirmReceipt(id,{signal:abort.signal});if(!active)return;confirmation.value=undefined;await load();});}
onMounted(()=>void run(load));onBeforeUnmount(()=>{active=false;abort.abort();closePreview();});
</script>
<template>
 <section class="corporate-transfers"><header class="page-heading"><div><p class="page-eyebrow">CORPORATE TRANSFERS</p><h1>对公转账记录</h1></div><button class="secondary-action" :disabled="busy" @click="run(load)">刷新记录</button></header>
 <p v-if="error" role="alert">{{error}}</p>
 <article v-if="confirmation" class="admin-card" aria-label="确认对公收款"><h2>确认收款</h2><p>昵称：{{users[confirmation.order.userId||'']?.nickname||'—'}} · 手机号：{{users[confirmation.order.userId||'']?.phone||'—'}}</p><p>记录号：{{confirmation.order.orderId}} · 账户：{{confirmation.order.userId}}</p><p>转账金额：{{money(confirmation.order.amount4)}} · 到账积分：{{confirmation.order.expectedPoints4}}</p><p>备注：{{confirmation.order.remark||'—'}}</p><button class="secondary-action" :disabled="busy" @click="receipt(confirmation,true)">下载转账凭证</button><button class="primary-action" :disabled="busy" @click="confirm">确认已收到款项并发放积分</button><button class="secondary-action" :disabled="busy" @click="confirmation=undefined">取消</button></article>
 <article class="admin-card"><p v-if="busy&&!rows.length" role="status">正在读取转账记录…</p><p v-else-if="!rows.length">暂无对公转账记录</p><div v-else class="table-wrap"><table><thead><tr><th>昵称</th><th>手机号</th><th>转账金额（元）</th><th>到账积分</th><th>转账凭证</th><th>备注</th><th>提交时间</th><th>状态</th><th>确认人 / 时间</th><th>操作</th></tr></thead><tbody><tr v-for="row in rows" :key="row.order.orderId||undefined"><td>{{users[row.order.userId||'']?.nickname||'—'}}</td><td>{{users[row.order.userId||'']?.phone||'—'}}</td><td>{{money(row.order.amount4)}}</td><td>{{row.order.expectedPoints4}}</td><td><button class="secondary-action" :disabled="busy" @click="receipt(row)" aria-haspopup="dialog">{{row.fileName}}</button><button class="secondary-action" :disabled="busy" @click="receipt(row,true)">下载</button></td><td>{{row.order.remark||'—'}}</td><td>{{when(row.order.createdAt)}}</td><td>{{status(row)}}</td><td>{{row.order.confirmedBy||'—'}}<small>{{when(row.confirmedAt)}}</small></td><td><button v-if="row.order.status==='REVIEW_PENDING'" class="primary-action" :disabled="busy" @click="confirmation=row">确认收款</button><span v-else>—</span></td></tr></tbody></table></div>
 <footer><span>共 {{total}} 条 · 第 {{page+1}} 页</span><button class="secondary-action" :disabled="busy||page===0" @click="changePage(page-1)">上一页</button><button class="secondary-action" :disabled="busy||(page+1)*size>=total" @click="changePage(page+1)">下一页</button></footer></article>
 <dialog v-if="preview" ref="previewDialog" class="receipt-preview" aria-label="转账凭证预览" @cancel.prevent="closePreview" @close="closePreview" @click="event=>{if(event.target===previewDialog)closePreview();}"><header><h2>{{preview.fileName}}</h2><a class="secondary-action" :href="preview.url" :download="preview.fileName">下载凭证</a><button class="secondary-action" aria-label="关闭凭证预览" autofocus @click="closePreview">关闭</button></header><img v-if="preview.contentType.startsWith('image/')" :src="preview.url" alt="转账凭证"/><object v-else :data="preview.url" type="application/pdf" aria-label="PDF 转账凭证"><a :href="preview.url" target="_blank" rel="noopener noreferrer">在新窗口预览 PDF</a></object></dialog>
 </section>
</template>
<style scoped>
.receipt-preview{box-sizing:border-box;width:min(1000px,calc(100vw - 32px));max-height:calc(100dvh - 40px);padding:24px;border:1px solid #374242;border-radius:18px;background:#141818;color:#eef3f3;overflow:auto}.receipt-preview::backdrop{background:rgb(0 0 0 / .7)}.receipt-preview header{display:flex;align-items:center;gap:12px;margin-bottom:20px}.receipt-preview h2{flex:1;min-width:0;overflow-wrap:anywhere;margin:0;font-size:20px}.receipt-preview img{display:block;max-width:100%;height:auto;margin:auto}.receipt-preview object{display:block;width:100%;height:65vh;background:white}.corporate-transfers{display:grid;gap:20px}.corporate-transfers table{min-width:1100px}.corporate-transfers td{max-width:230px;white-space:normal;overflow-wrap:anywhere}.corporate-transfers small{display:block;color:#9baaaa;margin-top:6px}.corporate-transfers footer{display:flex;align-items:center;justify-content:flex-end;gap:12px;margin-top:20px;flex-wrap:wrap}.corporate-transfers footer span{margin-right:auto}.corporate-transfers [role=alert]{color:#ff8585}.corporate-transfers article>button{margin:8px 12px 0 0}
</style>
