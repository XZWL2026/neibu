<script setup lang="ts">
import {computed, inject, nextTick, ref, watch} from 'vue';
import {adminSessionKey} from '../adminSession';
import { NSelect, NButton } from 'naive-ui';
import { RefreshCw } from '@lucide/vue';
import OverviewFinanceSummary from '../OverviewFinanceSummary.vue';
import NativeVideoUsage from '../NativeVideoUsage.vue';
import FinanceTrend from '../components/FinanceTrend.vue';
import { adminRequestCache } from '../data/requestCache';
import { presetRange, type DateRange } from '../shared/dateRange';
const session=inject(adminSessionKey);
const permitted=computed(()=>['ADMIN','NORMAL_ADMIN'].includes(session?.currentAdmin.value?.role||''));
const days = ref(7), revision = ref(0);
const range = ref<DateRange>(presetRange(7));
const native = ref<InstanceType<typeof NativeVideoUsage>>();
const periods = [{label:'近7天',value:7},{label:'近30天',value:30},{label:'今天',value:1},{label:'全部时间',value:0},{label:'自定义',value:-1}];
watch(days, async value => {
  if (value !== -1) range.value = presetRange(value);
  else { await nextTick(); document.getElementById('native-from')?.focus(); }
});
function refresh() { adminRequestCache.invalidate(); revision.value++; void native.value?.reload(); }
</script>
<template><section v-if="permitted" class="page-panel overview-page" aria-labelledby="overview-title"><header class="page-heading"><div><h1 id="overview-title">运营概览</h1><p class="page-description">掌握平台资金变化与模型生成情况。</p></div><div class="overview-actions"><NButton aria-label="刷新运营概览" @click="refresh"><template #icon><RefreshCw :size="16" /></template>刷新</NButton><NSelect v-model:value="days" :options="periods" aria-label="运营概览时间范围" style="width:130px"/></div></header><OverviewFinanceSummary :revision="revision"/><FinanceTrend :days="days" :range="range" :revision="revision"/><NativeVideoUsage ref="native" :days="days" @refresh="revision++" @custom="days=-1" @range="range=$event"/></section></template>
<style scoped>.overview-actions { display:flex;align-items:center;gap:10px; }@media(max-width:560px){.page-heading{align-items:flex-start;flex-direction:column;}.overview-actions{width:100%;justify-content:flex-end;}}</style>
