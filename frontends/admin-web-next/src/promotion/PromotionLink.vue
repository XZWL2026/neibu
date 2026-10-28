<script setup lang="ts">
import { computed, ref, watch } from 'vue';
const props = defineProps<{ code?: string | null }>();
const link = computed(() => {
  if (!props.code) return '';
  const base = import.meta.env.VITE_USER_WEB_URL || window.location.origin;
  const url = new URL('home', `${base.replace(/\/$/, '')}/`);
  url.searchParams.set('ref', props.code);
  return url.href;
});
const copying = ref(false), feedback = ref('');
watch(link, () => { feedback.value = ''; });
async function copy() {
  if (!link.value || copying.value) return;
  const value = link.value;
  copying.value = true;
  feedback.value = '';
  try {
    await navigator.clipboard.writeText(value);
    if (link.value === value) feedback.value = '已复制';
  } catch {
    if (link.value === value) feedback.value = '复制失败，重试';
  } finally { copying.value = false; }
}
</script>

<template>
  <div class="promotion-link">
    <label>推广链接<input :value="link" readonly @focus="($event.target as HTMLInputElement).select()"></label>
    <button class="primary-action" type="button" :disabled="!link || copying" @click="copy">{{ feedback || '复制链接' }}</button>
  </div>
</template>

<style scoped>
.promotion-link { display: flex; flex-wrap: wrap; align-items: end; gap: 10px; min-width: 0; margin-top: 8px; }
.promotion-link label { display: grid; flex: 1 1 180px; gap: 8px; min-width: 0; color: var(--muted); font-size: 12px; }
.promotion-link input { width: 100%; min-width: 0; min-height: 44px; padding: 10px 12px; border: 1px solid var(--line); border-radius: 10px; color: #b8eded; background: #0d1414; font: inherit; }
.promotion-link button { flex: 0 0 auto; font-size: 13px; }
.promotion-link :is(input, button):focus-visible { outline: 2px solid #4ed9df; outline-offset: 3px; }
.promotion-link button:hover:not(:disabled) { filter: brightness(1.1); }
.promotion-link button:disabled { opacity: .5; cursor: not-allowed; }
</style>
