<script setup lang="ts">
import { computed } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import PromoterIcon from '../PromoterIcon.vue';
const props = defineProps<{ items: readonly { label: string; to: string; icon: string; group: string }[]; collapsed?: boolean }>();
const route = useRoute();
const groups = computed(() => [...new Set(props.items.map(item => item.group))].map(label => ({ label, items: props.items.filter(item => item.group === label) })));
function current(path: string) {
  return route.path === path || route.path.startsWith(path + '/') ||
    (path === '/admin/promotions/attributions' && route.path === '/admin/promotions/applications') ||
    (path === '/admin/promotions/withdrawals' && route.path === '/admin/promotions/withdrawal-report');
}
</script>
<template><nav class="primary-nav" aria-label="管理端导航"><div v-for="group in groups" :key="group.label" class="nav-group"><p v-if="!collapsed" class="nav-group-label">{{ group.label }}</p><RouterLink v-for="item in group.items" :key="item.to" :to="item.to" :aria-current="current(item.to) ? 'page' : undefined" :title="collapsed ? item.label : undefined" :aria-label="collapsed ? item.label : undefined"><PromoterIcon :name="item.icon" /><span v-if="!collapsed">{{ item.label }}</span><PromoterIcon v-if="!collapsed && current(item.to)" class="nav-current-indicator" name="chevron-right" /></RouterLink></div></nav></template>
