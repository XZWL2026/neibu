<script setup lang="ts">
import { computed, inject, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import AdminSearchSelect from '../components/AdminSearchSelect.vue';
import { adminAttributionAdapterKey, type AttributionAdjustment, type AttributionHistory, type PromotionUserOption } from './httpPromotionAdminAdapters';
const adapter = inject(adminAttributionAdapterKey);
if (!adapter) throw new Error('PromotionAttributionsPanel requires an attribution adapter');
const user = ref(''), inviter = ref(''), reason = ref(''), error = ref('');
const errorField = ref('');
const userField = ref<InstanceType<typeof AdminSearchSelect>>(), inviterField = ref<InstanceType<typeof AdminSearchSelect>>(), reasonField = ref<HTMLTextAreaElement>();
const userOptions = ref<PromotionUserOption[]>([]), inviterOptions = ref<PromotionUserOption[]>([]);
const eligibleUsers = computed(() => userOptions.value.filter(option => option.value !== inviter.value));
const eligibleInviters = computed(() => inviterOptions.value.filter(option => option.value !== user.value));
const searches = { user: { query: '', total: 0, offset: 0, revision: 0 }, inviter: { query: '', total: 0, offset: 0, revision: 0 } };
const userMore = ref(false), inviterMore = ref(false), searchingUser = ref(false), searchingInviter = ref(false);
const versions = ref<AttributionHistory[]>([]), total = ref(0), page = ref(0), currentVersion = ref<number | null>(null), busy = ref(false), loading = ref(false);
const directory=ref<Record<string,PromotionUserOption>>({});
const chosenUser=computed(()=>directory.value[user.value]);
const chosenInviter=computed(()=>directory.value[inviter.value]);
const records=ref<AttributionAdjustment[]>([]),recordTotal=ref(0),recordPage=ref(0),recordSize=ref(10),recordsLoading=ref(false),recordsError=ref('');
let recordsRevision=0;
async function loadRecords(nextPage=0){const request=++recordsRevision;recordsLoading.value=true;recordsError.value='';try{const result=await adapter!.records(nextPage,recordSize.value);if(!active||request!==recordsRevision)return;records.value=result.items;recordTotal.value=result.total;recordPage.value=nextPage;}catch{if(active&&request===recordsRevision)recordsError.value='调整记录读取失败，请重试';}finally{if(active&&request===recordsRevision)recordsLoading.value=false;}}
const currentInviter=ref('');
function cancel(){user.value='';inviter.value='';reason.value='';error.value='';}
let active = true, historyRevision = 0;
onUnmounted(() => { active = false; historyRevision++; });
async function search(kind: 'user' | 'inviter', query = '', more = false) {
  const state = searches[kind], options = kind === 'user' ? userOptions : inviterOptions;
  const pending = kind === 'user' ? searchingUser : searchingInviter;
  const request = ++state.revision; pending.value = true;
  if (!more) { state.query = query; state.offset = 0; }
  try {
    const result = await adapter!.search(state.query, more ? state.offset : 0);
    if (!active || request !== state.revision) return;
    for(const option of result.items)directory.value[option.value]=option;
    options.value = more ? [...options.value, ...result.items] : result.items;
    state.offset = (more ? state.offset : 0) + 20; state.total = result.total;
    (kind === 'user' ? userMore : inviterMore).value = state.offset < result.total;
  } catch { if (active && request === state.revision) error.value = '用户列表加载失败，请重试'; }
  finally { if (active && request === state.revision) pending.value = false; }
}
async function history(nextPage = 0) {
  const owner = user.value, request = ++historyRevision;
  page.value = nextPage; versions.value = []; currentVersion.value = null;currentInviter.value='';
  if (!owner) { total.value = 0; loading.value=false; return; }
  loading.value = true;
  try {
    const result = await adapter!.history(owner, nextPage);
    if (!active || request !== historyRevision) return;
    versions.value = result.items; total.value = result.total; currentVersion.value = result.currentVersion;currentInviter.value=result.currentInviterLabel||result.items.find(item=>item.version===result.currentVersion)?.directInviterLabel||result.items.find(item=>item.version===result.currentVersion)?.directInviterId||(result.currentVersion===0?'无':'—');
  } catch { if (active && request === historyRevision) error.value = '关系历史加载失败，请重试'; }
  finally { if (active && request === historyRevision) loading.value = false; }
}
watch(user, () => { reason.value = ''; error.value = ''; errorField.value = ''; void history(); });
onMounted(() => { void search('user'); void search('inviter');void loadRecords(); });
async function save() {
  if (busy.value) return;
  error.value = ''; errorField.value = '';
  if (!user.value) { error.value = '请选择被邀请用户'; errorField.value = 'user'; }
  else if (!inviter.value || user.value === inviter.value) { error.value = '请选择其他用户作为直接邀请人'; errorField.value = 'inviter'; }
  else if (!reason.value.trim()) { error.value = '必须填写绑定或改绑原因'; errorField.value = 'reason'; }
  if (error.value) { await nextTick(); (errorField.value === 'user' ? userField.value : errorField.value === 'inviter' ? inviterField.value : reasonField.value)?.focus(); return; }
  if (currentVersion.value === null) { error.value = '关系历史尚未加载成功'; return; }
  const owner = user.value; busy.value = true;
  try { await adapter!.save(owner, inviter.value, currentVersion.value, reason.value.trim()); if (active && owner === user.value) { reason.value = ''; await Promise.all([history(),loadRecords()]); } }
  catch { if (active && owner === user.value) error.value = '保存失败，请重试或刷新关系历史'; }
  finally { if (active) busy.value = false; }
}
</script>
<template>
<section class="referral-card referral-adjustment">
 <header class="referral-section-header"><span class="referral-section-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="m8.5 10.4 5.7-3.6m-5.7 6.8 5.7 3.6"/><circle cx="6" cy="12" r="3.2"/><circle cx="17" cy="5" r="3.2"/><circle cx="17" cy="19" r="3.2"/></svg></span><div><h2>推荐关系调整</h2><p>管理员可直接调整用户的推荐关系，修改后立即生效，仅影响之后的新订单，历史分佣和积分不变。</p></div></header>
 <form class="referral-steps" @submit.prevent="save" novalidate>
 <section class="referral-step"><header><span>1</span><div><h3>选择代理</h3><p>搜索并选择新的推荐代理（可选择代理或普通用户）</p></div></header>
 <AdminSearchSelect id="attribution-inviter" ref="inviterField" v-model="inviter" label="选择直接邀请人" :options="eligibleInviters" remote :loading="searchingInviter" :has-more="inviterMore" :disabled="busy" :invalid="errorField==='inviter'" @query="search('inviter',$event)" @load-more="search('inviter','',true)"/>
 <div v-if="inviter" class="referral-selected"><span class="referral-avatar">{{(chosenInviter?.label||inviter).slice(0,1)}}</span><div><strong>{{chosenInviter?.label||inviter}}</strong><small>{{chosenInviter?.detail||'—'}}</small></div><button type="button" aria-label="清除推荐人" :disabled="busy" @click="inviter=''">×</button></div>
 </section><span class="referral-step-arrow" aria-hidden="true">→</span>
 <section class="referral-step"><header><span>2</span><div><h3>选择客户</h3><p>搜索并选择需要调整的客户</p></div></header>
 <AdminSearchSelect id="attribution-user" ref="userField" v-model="user" label="选择被邀请用户" :options="eligibleUsers" remote :loading="searchingUser" :has-more="userMore" :disabled="busy" :invalid="errorField==='user'" @query="search('user',$event)" @load-more="search('user','',true)"/>
 <div v-if="user" class="referral-selected"><span class="referral-avatar">{{(chosenUser?.label||user).slice(0,1)}}</span><div><strong>{{chosenUser?.label||user}}</strong><small>{{chosenUser?.detail||'—'}}</small></div><button type="button" aria-label="清除客户" :disabled="busy" @click="user=''">×</button></div>
 </section><span class="referral-step-arrow" aria-hidden="true">→</span>
 <section class="referral-step"><header><span>3</span><div><h3>确认调整</h3><p>确认推荐关系变更</p></div></header>
 <div class="referral-change" aria-label="推荐关系变更"><strong>推荐关系变更</strong><div><span><b>{{chosenUser?.label||user||'待选择客户'}}</b><small>当前推荐人</small><em class="is-previous">{{loading?'读取中…':currentInviter||'—'}}</em></span><span aria-hidden="true">→</span><span><b>{{chosenUser?.label||user||'待选择客户'}}</b><small>新推荐人</small><em>{{chosenInviter?.label||inviter||'待选择推荐人'}}</em></span></div></div>
 <label class="referral-reason">调整原因<textarea id="attribution-reason" ref="reasonField" v-model="reason" aria-label="绑定或改绑原因" :disabled="busy" :aria-invalid="errorField==='reason'" maxlength="2000" rows="1"/></label>
 <div class="referral-confirm-actions"><button type="button" :disabled="busy" @click="cancel">取消</button><button class="referral-confirm" type="submit" :disabled="busy||loading">{{busy?'正在保存…':'确认调整'}}</button></div>
 </section><p v-if="error" role="alert" class="referral-form-error">{{error}}</p>
 </form>
 <section class="referral-records"><header><h3>调整记录</h3><button type="button" :disabled="recordsLoading||busy" @click="loadRecords(recordPage)">刷新</button></header>
 <p v-if="recordsError" role="alert">{{recordsError}}</p>
 <div class="referral-table-scroll" tabindex="0" role="region" aria-label="调整记录"><table><thead><tr><th>时间 ↓</th><th>客户</th><th>原推荐人</th><th>新推荐人</th><th>操作人</th><th>原因</th></tr></thead><tbody>
 <tr v-for="item in records" :key="item.id" :data-testid="`attribution-version-${item.userId}`"><td class="referral-nowrap">{{item.time}}</td><td>{{item.user}}</td><td>{{item.previousInviter}}</td><td>{{item.inviter}}</td><td>{{item.operator}}</td><td>{{item.reason}}</td></tr>
 <tr v-if="!records.length&&!recordsError"><td colspan="6" class="referral-empty">{{recordsLoading?'正在读取…':'暂无调整记录'}}</td></tr></tbody></table></div>
 <nav class="referral-pagination" aria-label="调整记录分页"><span>共 {{recordTotal}} 条</span><div><select v-model.number="recordSize" aria-label="每页调整记录条数" :disabled="recordsLoading||busy" @change="loadRecords()"><option :value="10">10 条/页</option><option :value="20">20 条/页</option><option :value="50">50 条/页</option></select><button type="button" aria-label="上一页调整记录" :disabled="!recordPage||recordsLoading||busy" @click="loadRecords(recordPage-1)">‹</button><span>{{recordPage+1}}</span><button type="button" aria-label="下一页调整记录" :disabled="(recordPage+1)*recordSize>=recordTotal||recordsLoading||busy" @click="loadRecords(recordPage+1)">›</button></div></nav>
 </section>
</section>
</template>
