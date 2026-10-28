<script setup lang="ts">
import LoadingValue from './components/LoadingValue.vue';
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { billingConsumptionsGetApiV1BillingPortalConsumptions as listConsumption } from './api';
import { dateRange, monthDates, sumPoints } from './apiConsolePresentation';
import { money } from './portalAmounts';

const props = defineProps<{ owner: string }>();
type Row = NonNullable<Awaited<ReturnType<typeof listConsumption>>['data']['items']>[number];
const dates = ref(monthDates()), selected = ref(''), loading = ref(false), error = ref('');
const rows = ref<Row[]>([]);
let revision = 0, controller = new AbortController();
const models = computed(() => {
  const groups = new Map<string, Row[]>();
  for (const row of rows.value) {
    const name = row.model || '未提供模型名称';
    groups.set(name, [...(groups.get(name) || []), row]);
  }
  return [...groups].map(([name, items]) => {
    const resolutions = new Map<string, Row[]>();
    for (const item of items) {
      const resolution = item.resolution?.trim() || '—';
      resolutions.set(resolution, [...(resolutions.get(resolution) || []), item]);
    }
    return { name, calls: items.length, amount: sumPoints(items.map(item => item.amount4)), seconds: totalSeconds(items),
      resolutions: [...resolutions].map(([resolution, entries]) => ({ resolution, seconds: totalSeconds(entries), amount: sumPoints(entries.map(item => item.amount4)) })) };
  });
});
function totalSeconds(items: Row[]) {
  let seconds = 0;
  for (const item of items) {
    if (item.type !== 'VIDEO' || item.durationSeconds == null || !Number.isSafeInteger(item.durationSeconds) || item.durationSeconds <= 0) return null;
    // Billing charges video by the recorded duration, not duration multiplied by count.
    seconds += item.durationSeconds;
    if (!Number.isSafeInteger(seconds)) return null;
  }
  return seconds;
}
const visibleModels = computed(() => models.value.filter(model => !selected.value || model.name === selected.value));
async function load() {
  const current = ++revision, owner = props.owner;
  controller.abort(); controller = new AbortController();
  rows.value = []; error.value = ''; loading.value = false; selected.value = '';
  const range = dateRange(dates.value);
  if (!owner) return;
  if (!range) { error.value = '请选择有效日期范围'; return; }
  loading.value = true;
  try {
    const collected: Row[] = [], ids = new Set<string>();
    let offset = 0;
    while (true) {
      const page = (await listConsumption({ ...range, offset, limit: 100 }, { signal: controller.signal })).data;
      if (current !== revision) return;
      if (page.total > 10000) throw Error('记录较多，请缩小日期范围后重试');
      for (const item of page.items || []) {
        if (item.ownerId !== owner) throw Error('消费归属不匹配');
        if (!item.id || ids.has(item.id)) throw Error('消费记录发生变化，请重试');
        ids.add(item.id); collected.push(item);
      }
      offset = collected.length;
      if (offset >= page.total) break;
      if (!page.items?.length) throw Error('消费记录未读取完整，请重试');
    }
    if (current === revision) rows.value = collected;
  } catch (e) { if (current === revision) error.value = e instanceof Error ? e.message : '模型消费暂不可用'; }
  finally { if (current === revision) loading.value = false; }
}
watch([() => props.owner, () => dates.value.from, () => dates.value.to], () => void load(), { immediate: true });
onBeforeUnmount(() => { revision++; controller.abort(); });
</script>

<template>
  <section class="api-record-panel api-model-usage" aria-labelledby="api-model-usage-title" :aria-busy="loading">
    <header>
      <h2 id="api-model-usage-title">模型分辨率消耗明细</h2>
      <div class="api-model-filters">
        <div class="api-date-range"><input type="date" aria-label="模型消耗开始日期" v-model="dates.from" :max="dates.to"/><span>～</span><input type="date" aria-label="模型消耗结束日期" v-model="dates.to" :min="dates.from"/></div>
        <select aria-label="消耗模型筛选" v-model="selected" :disabled="loading || !models.length"><option value="">全部模型</option><option v-for="model in models" :key="model.name" :value="model.name">{{ model.name }}</option></select>
      </div>
    </header>
    <p v-if="error" role="alert">{{ error }} <button @click="load">重试</button></p>
    <div v-else-if="loading" class="api-model-grid" aria-busy="true"><article v-for="n in 3" :key="n" class="api-model-card blue" aria-label="正在读取模型消费"><h3><LoadingValue :loading="true"/></h3><p v-for="line in 3" :key="line"><LoadingValue :loading="true"/></p></article></div>
    <p v-else-if="!models.length" class="api-model-empty">当前日期范围暂无模型消费记录</p>
    <template v-else>
      <div class="api-model-grid">
        <article v-for="(model,index) in visibleModels" :key="model.name" class="api-model-card" :class="['blue','green','amber','pink'][index%4]">
          <h3>{{ model.name }}</h3>
          <table :aria-label="`${model.name} 消耗明细`"><thead><tr><th>分辨率</th><th>总时长（秒）</th><th>总费用（元）</th></tr></thead><tbody><tr v-for="entry in model.resolutions" :key="entry.resolution"><td><span class="api-resolution-badge">{{ entry.resolution }}</span></td><td>{{ entry.seconds ?? '—' }}</td><td>{{ money(entry.amount) }}</td></tr></tbody><tfoot><tr><th>合计</th><td>{{ model.seconds ?? '—' }}</td><td>{{ money(model.amount) }}</td></tr></tfoot></table>
          <span class="api-model-calls">{{ model.calls }} 次调用</span>
        </article>
      </div>
    </template>
  </section>
</template>
