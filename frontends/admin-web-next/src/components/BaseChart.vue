<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue';
import type { EChartsCoreOption, EChartsType } from 'echarts/core';
import { init } from '../charts/engine';
const props = withDefaults(defineProps<{ option: EChartsCoreOption; label: string; height?: number }>(), { height: 280 });
const root = ref<HTMLDivElement>();
let chart: EChartsType | undefined, resize: ResizeObserver | undefined, visible: IntersectionObserver | undefined;
let frame = 0, disposed = false, initializing = false;
async function initialize() {
  if (disposed || initializing || chart || !root.value) return;
  initializing = true;
  try {
    if (disposed || !root.value) return;
    chart = init(root.value, undefined, { renderer: 'canvas', devicePixelRatio: Math.min(window.devicePixelRatio || 1, 2) });
    render();
  } finally { initializing = false; }
}
function render() {
  chart?.setOption({ ...props.option, animation: !window.matchMedia?.('(prefers-reduced-motion: reduce)').matches,
    aria: { enabled: true, description: props.label }, textStyle: { fontFamily: 'Segoe UI, PingFang SC, Microsoft YaHei, sans-serif' } }, { notMerge: true, lazyUpdate: true });
}
function scheduleResize() {
  cancelAnimationFrame(frame);
  frame = requestAnimationFrame(() => { if (!disposed && root.value?.clientWidth) chart?.resize(); });
}
watch(() => props.option, render);
onMounted(() => {
  if (!root.value) return;
  if (typeof ResizeObserver !== 'undefined') { resize = new ResizeObserver(scheduleResize); resize.observe(root.value); }
  else window.addEventListener('resize', scheduleResize);
  if (typeof IntersectionObserver !== 'undefined') {
    visible = new IntersectionObserver(entries => { if (entries.some(entry => entry.isIntersecting)) { visible?.disconnect(); void initialize(); } }, { rootMargin: '100px' });
    visible.observe(root.value);
  } else void initialize();
});
onBeforeUnmount(() => { disposed = true; resize?.disconnect(); visible?.disconnect(); window.removeEventListener('resize', scheduleResize); cancelAnimationFrame(frame); chart?.dispose(); chart = undefined; });
</script>
<template><div ref="root" class="echart" role="img" :aria-label="label" :style="{ height: `${height}px` }" /></template>
<style scoped>.echart { width:100%;min-width:0; }</style>
