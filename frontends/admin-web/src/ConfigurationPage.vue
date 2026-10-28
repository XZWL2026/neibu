<script setup lang="ts">
import { computed, inject } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import { adminSessionKey, canAccessAdminPath } from './adminSession';
import PricingSettings from './PricingSettings.vue';
import RoutingSettings from './RoutingSettings.vue';
const route = useRoute();
const session = inject(adminSessionKey)!;
const allowed = computed(() => canAccessAdminPath(route.path, session.currentAdmin.value));
const pricing = computed(() => route.name === 'admin-pricing');
</script>
<template>
  <section class="page-panel" aria-labelledby="configuration-title">
    <h1 v-if="!allowed" id="configuration-title">{{ allowed ? pricing ? '定价配置' : '供应商与模型' : '无权限' }}</h1>
    <template v-if="!allowed"><p>仅主 ADMIN 可以查看和修改配置。</p><RouterLink to="/admin/login">管理员登录</RouterLink></template>
    <PricingSettings v-else-if="pricing" />
    <RoutingSettings v-else />
  </section>
</template>
