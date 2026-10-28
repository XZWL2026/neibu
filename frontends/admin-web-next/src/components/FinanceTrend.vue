<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { NButton, NSkeleton } from 'naive-ui';
import { billingDailyFinanceGetApiV1AdminBillingDailyFinance as readFinance } from '../api';
import { lineOption, plottedAmount } from '../charts/options';
import BaseChart from './BaseChart.vue';
import { formatAmount } from '../shared/formatAmount';
import { presetRange, validRange, type DateRange } from '../shared/dateRange';
const props = defineProps<{ days: number; revision: number; range?: DateRange }>();
type Report = Awaited<ReturnType<typeof readFinance>>['data'];
const report = ref<Report | null>(null), loading = ref(false), error = ref('');
let version = 0, controller: AbortController | undefined;
const range = computed(() => props.range ?? presetRange(props.days < 0 ? 7 : props.days));
const rangeLabel = computed(() => range.value.from && range.value.to ? `${range.value.from} – ${range.value.to}` : '全部日期');
const rows = computed(() => [...(report.value?.items ?? [])].sort((a,b) => (a.date ?? '').localeCompare(b.date ?? '')));
const dates = computed(() => rows.value.map(row => row.date!));
const option = computed(() => lineOption(dates.value, [
  { name: '充值金额', values: rows.value.map(row => plottedAmount(row.rechargeAmount4)) },
  { name: '消费金额', values: rows.value.map(row => plottedAmount(row.consumptionAmount4)) },
]));
async function load() {
  const own = ++version; controller?.abort(); controller = new AbortController(); loading.value = true; error.value = '';
  report.value = null;
  try {
    if (!validRange(range.value)) throw Error('invalid date range');
    const query = { ...range.value }, items: NonNullable<Report['items']> = [];
    let complete: Report | undefined;
    for (let offset = 0; ; ) {
      const result = await readFinance({ ...query, offset, limit:30 }, { signal:controller.signal });
      if (own !== version) return;
      const data = result.data;
      if (!Array.isArray(data.items) || !Number.isSafeInteger(data.totalDays) || data.totalDays < 0
        || data.offset !== offset || data.limit !== 30 || data.items.length > 30
        || (complete && complete.totalDays !== data.totalDays)
        || data.items.some(row => !row.date || !validRange({from:row.date,to:row.date})
          || (query.from && row.date < query.from) || (query.to && row.date > query.to))) throw Error('invalid report');
      complete = data;
      items.push(...data.items); offset += data.items.length;
      if (new Set(items.map(row => row.date)).size !== items.length || offset > data.totalDays) throw Error('invalid pagination');
      if (offset === data.totalDays) break;
      if (!data.items.length) throw Error('incomplete pagination');
    }
    report.value = { ...complete!, items };
  } catch { if (own === version) { report.value = null; error.value = '趋势数据读取失败，请重试。'; } }
  finally { if (own === version) loading.value = false; }
}
watch([() => JSON.stringify(range.value), () => props.revision], () => void load(), {immediate:true});
onBeforeUnmount(() => { version++; controller?.abort(); });
</script>
<template><article class="finance-trend-panel" :aria-busy="loading"><header><div><h2>资金趋势</h2><p>{{rangeLabel}} · 充值与消费（元）</p></div><span class="trend-note">未知金额不参与绘图</span></header><NSkeleton v-if="loading" height="260px" /><div v-else-if="error" class="trend-error" role="alert">{{error}}<NButton text type="primary" @click="load">重试</NButton></div><p v-else-if="!rows.length" class="trend-empty" role="status">该日期范围暂无账务记录</p><template v-else><BaseChart :option="option" label="每日充值和消费金额趋势（元）" :height="260"/><details class="chart-accessible-data"><summary>查看每日金额</summary><table><thead><tr><th>日期</th><th>充值金额（元）</th><th>消费金额（元）</th></tr></thead><tbody><tr v-for="row in rows" :key="row.date||''"><td>{{row.date}}</td><td>{{formatAmount(row.rechargeAmount4)}}</td><td>{{formatAmount(row.consumptionAmount4)}}</td></tr></tbody></table></details></template></article></template>
<style scoped>.finance-trend-panel { background:#fff;border:1px solid #dce2ec;border-radius:8px;padding:20px;min-width:0; }header { display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:10px; }h2 { margin:0;font-size:16px;font-weight:600; }p { margin:4px 0 0;font-size:12px;color:#627087; }.trend-note { font-size:11px;color:#627087; }.trend-error { min-height:260px;display:flex;align-items:center;justify-content:center;gap:12px;color:#bc3545; }@media(max-width:600px) {.trend-note{display:none;}.finance-trend-panel{padding:16px;}}</style>
