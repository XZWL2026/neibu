<script setup lang="ts">
import { computed, inject, onBeforeUnmount, ref, watch } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import ApiDashboard from './ApiDashboard.vue';
import ApiBilling from './ApiBilling.vue';
import ApiTransfer from './ApiTransfer.vue';
import ApiDocumentation from './ApiDocumentation.vue';
import { adminSessionKey } from './adminSession';
import {
  accessListGetApiV1AccessKeys as listKeys,
  billingConsumptionsGetApiV1BillingPortalConsumptions as listConsumption,
  billingEntriesGetApiV1BillingWalletsKeyIdLedger as readLedger,
  userSelfGetApiV1UsersMe as readSelf
} from './api';
import { beforePoints, money, points, shown, when } from './portalAmounts';
const route = useRoute();
const session = inject(adminSessionKey);
if (!session) throw new Error('ApiPage requires a session provider');
const allowed = computed(() => session.currentAdmin.value?.role === 'API_USER');
const owner = computed(() => session.currentAdmin.value?.userId ?? '');
const pageName = computed(() => String(route.name ?? ''));
const titles: Record<string, string> = { 'api-dashboard': 'API 仪表盘', 'api-keys': 'API 密钥', 'api-consumption': '调用量', 'api-billing': '充值记录', 'api-transfer': '公户转账', 'api-docs': 'API 文档', 'api-account': '账户设置' };
const title = computed(() => titles[pageName.value] ?? 'API 控制台');
type Key = Awaited<ReturnType<typeof listKeys>>['data'][number];
type Consumption = NonNullable<Awaited<ReturnType<typeof listConsumption>>['data']['items']>[number];
const keys = ref<Key[]>([]), records = ref<Consumption[]>([]);
const profile = ref<Awaited<ReturnType<typeof readSelf>>['data'] | null>(null);
const balances = ref<Record<string, { before: string | null; after: string | null }>>({});
const loading = ref(false), error = ref(''), offset = ref(0), total = ref(0);
const pageSize = 20;
let revision = 0, disposed = false, abort: AbortController | undefined;
watch([pageName, owner], () => { offset.value = 0; }, { flush: 'sync' });
watch([allowed, owner, pageName, offset], () => void load(), { immediate: true, flush: 'sync' });
onBeforeUnmount(() => { disposed = true; revision++; abort?.abort(); });
async function load() {
  if (disposed) return;
  const current = ++revision, userId = owner.value, name = pageName.value;
  abort?.abort(); abort = new AbortController(); const options = { signal: abort.signal };

  keys.value = []; records.value = []; profile.value = null; balances.value = {}; total.value = 0; error.value = ''; loading.value = false;
  if (!allowed.value || !titles[name] || ['api-docs','api-dashboard','api-billing','api-transfer'].includes(name)) return;
  if (!userId) { error.value = '无法读取真实账户：登录身份缺少业务用户，请重新登录'; return; }
  loading.value = true;
  try {
    if (name === 'api-account') {
      const result = (await readSelf(options)).data;
      if (result.id !== userId) throw new Error('账户身份不匹配');
      if (current === revision) profile.value = result;
    } else {
      const ownedKeys = (await listKeys(options)).data;
      if (ownedKeys.some(key => key.ownerId !== userId)) throw new Error('密钥归属不匹配');
      if (current !== revision) return;
      keys.value = ownedKeys;
      if (name === 'api-consumption') {
        const result = (await listConsumption({ offset: offset.value, limit: pageSize }, options)).data;
        if (current !== revision) return;
        const rows = result.items ?? [];
        if (rows.some(item => item.ownerId !== userId)) throw new Error('消费归属不匹配');
        const beforeAfter: typeof balances.value = {};
        const keyIds = [...new Set(rows.map(row => row.keyId).filter((key): key is string => !!key && ownedKeys.some(item => item.keyId === key)))];
        await Promise.all(keyIds.map(async key => {
          const ledger = (await readLedger(key, { limit: 100 }, options)).data;
          for (const row of rows.filter(item => item.keyId === key)) {
            const entry = ledger.find(item => item.kind === 'CONSUME' && item.requestId === row.taskId);
            if (entry && row.id) beforeAfter[row.id] = { before: beforePoints(entry.balanceAfter4, entry.points4), after: entry.balanceAfter4 };
          }
        }));
        if (current !== revision) return;
        records.value = rows; total.value = result.total; balances.value = beforeAfter;
      }
    }
  } catch (failure) { if (current === revision) error.value = `无法读取真实数据：${failure instanceof Error ? failure.message : '服务暂时不可用'}`; }
  finally { if (current === revision) loading.value = false; }
}
</script>

<template>
  <section v-if="!allowed" class="page-panel"><header class="page-heading"><div><h1>无权限</h1><p>当前账号不能访问 API 控制台页面。</p></div></header><div class="empty-state"><strong>需要 API 用户权限</strong><RouterLink to="/admin/login">返回登录</RouterLink></div></section>
  <ApiDocumentation v-else-if="pageName === 'api-docs'" />
  <ApiDashboard v-else-if="pageName === 'api-dashboard'" :owner="owner"/>
  <ApiBilling v-else-if="pageName === 'api-billing'" :owner="owner"/>
  <ApiTransfer v-else-if="pageName === 'api-transfer'" :owner="owner"/>
  <section v-else class="page-panel api-console" aria-labelledby="api-page-title" :aria-busy="loading">
    <header class="page-heading"><div><h1 id="api-page-title">{{ title }}</h1><p class="page-description">{{ session.currentAdmin.value?.username }} · 本人账户数据</p></div></header>
    <p v-if="loading" role="status">正在读取真实数据…</p>
    <div v-if="error" role="alert" class="notice">{{ error }} <button data-testid="portal-retry" type="button" @click="load">重试</button></div>
    <template v-if="pageName === 'api-keys'">
      <article class="admin-card"><header><h2>当前 API 密钥</h2><span>{{ keys.length }} 把</span></header><div v-for="key in keys" :key="key.keyId ?? ''"><p class="api-key-mask">{{ shown(key.maskedKey) }}</p><p>{{ key.enabled ? '已启用' : '已停用' }} · {{ shown(key.accountType) }}</p></div><p v-if="!loading && !error && !keys.length" class="empty-state">暂无密钥</p><p class="permission-note">本页只查看脱敏标识。平台 Key 请由授权管理员通过安全方式提供。</p></article>
    </template>
    <template v-else-if="pageName === 'api-consumption'">
      <p class="permission-note">每行是一笔已记账任务。人民币余额不可用；积分前后来自匹配的最近 100 条本人钱包流水，未匹配的历史记录显示 —。</p>
      <div class="table-wrap"><table><caption class="sr-only">API 调用量</caption><thead><tr><th>记录</th><th>模型 / 类型</th><th>调用次数</th><th>原始扣费金额</th><th>余额前 / 余额后（人民币）</th><th>积分前 / 积分后</th><th>净扣积分</th><th>时间</th></tr></thead><tbody><tr v-for="item in records" :key="item.id ?? ''"><td>{{ shown(item.id) }}</td><td>{{ shown(item.model) }} / {{ shown(item.type) }}</td><td>1</td><td>{{ money(item.amount4) }}</td><td>— / —</td><td>{{ points(balances[item.id ?? '']?.before) }} / {{ points(balances[item.id ?? '']?.after) }}</td><td>{{ points(item.netPoints4) }}</td><td>{{ when(item.createdAt) }}</td></tr><tr v-if="!loading && !error && !records.length"><td colspan="8" class="table-empty">暂无已记账调用</td></tr></tbody></table></div>
      <nav class="table-toolbar" aria-label="调用记录分页"><button type="button" :disabled="loading || offset === 0" @click="offset -= pageSize">上一页</button><span>第 {{ offset / pageSize + 1 }} 页 · 共 {{ total }} 条</span><button type="button" :disabled="loading || offset + pageSize >= total" @click="offset += pageSize">下一页</button></nav>
    </template>
    <template v-else-if="pageName === 'api-account'">
      <div class="detail-grid"><article class="admin-card"><span>账户</span><strong>{{ shown(profile?.nickname) }}</strong><small>{{ session.currentAdmin.value?.username }}</small></article><article class="admin-card"><span>手机号</span><strong>{{ shown(profile?.phone) }}</strong><small>{{ profile ? profile.enabled ? '已启用' : '已停用' : '—' }}</small></article><article class="admin-card"><span>当前身份</span><strong>API_USER</strong><small>API 控制台</small></article></div>
    </template>
  </section>
</template>
