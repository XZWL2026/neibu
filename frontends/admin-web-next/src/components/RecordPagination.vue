<script setup lang="ts">
withDefaults(defineProps<{ page: number; total: number; pageSize?: number; label: string; disabled?: boolean }>(), { pageSize: 10, disabled: false });
defineEmits<{ 'update:page': [page: number] }>();
</script>

<template>
  <nav v-if="total" class="directory-pagination" :aria-label="label">
    <span>共 {{ total }} 条 · 每页 {{ pageSize }} 条</span>
    <div class="record-pagination-actions">
      <button type="button" :disabled="disabled || page <= 1" @click="$emit('update:page', page - 1)">上一页</button>
      <span>{{ page }} / {{ Math.max(1, Math.ceil(total / pageSize)) }}</span>
      <button type="button" :disabled="disabled || page * pageSize >= total" @click="$emit('update:page', page + 1)">下一页</button>
    </div>
  </nav>
</template>

<style scoped>
.record-pagination-actions { display: flex; align-items: center; gap: 8px; white-space: nowrap; }
</style>
