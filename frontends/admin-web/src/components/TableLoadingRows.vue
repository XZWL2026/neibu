<script setup lang="ts">
withDefaults(defineProps<{ columns: number; rows?: number; label?: string }>(), { rows: 6, label: '正在加载数据…' });
</script>

<template>
  <tr v-for="row in rows" :key="row" class="table-loading-row" :aria-hidden="row === 1 ? undefined : true">
    <td v-for="column in columns" :key="column">
      <span v-if="row === 1 && column === 1" class="sr-only" role="status">{{ label }}</span>
      <span class="table-loading-line" :class="{ short: (row + column) % 3 === 0 }" aria-hidden="true"></span>
    </td>
  </tr>
</template>

<style scoped>
.table-loading-row td { height: 48px; }
.table-loading-line { display: block; width: 76%; min-width: 12px; max-width: 130px; height: 10px; margin-block: 7px; border-radius: 3px; background: color-mix(in srgb, currentColor 10%, transparent); }
.table-loading-line.short { width: 52%; }
</style>
