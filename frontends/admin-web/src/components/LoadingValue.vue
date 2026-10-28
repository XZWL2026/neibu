<script setup lang="ts">
defineProps<{ loading: boolean }>();
</script>

<template>
  <span v-if="loading" class="loading-value" aria-label="正在加载"><span aria-hidden="true">&nbsp;</span></span>
  <slot v-else />
</template>

<style scoped>
.loading-value { display: inline-block; width: 4.5em; max-width: 100%; height: .8em; border-radius: 4px; vertical-align: baseline; background: color-mix(in srgb, currentColor 10%, transparent); }
</style>
