<script setup lang="ts">
import { computed } from "vue";
import type { LocalReferralView } from "../promotion/localPromotionFixtures";
const props = defineProps<{ referrals: readonly LocalReferralView[] }>();
const level1 = computed(() => props.referrals.filter((item) => item.level === "LEVEL_1"));
const level2 = computed(() => props.referrals.filter((item) => item.level === "LEVEL_2"));
</script>

<template>
  <section class="partner-page" aria-labelledby="partner-page-title">
    <header class="page-heading"><div><p class="eyebrow">REFERRED USERS</p><h1 id="partner-page-title">被推广用户</h1><p>一级与二级关系分开展示；仅显示脱敏身份、绑定时间和来源。</p></div><span class="local-chip">本地 fixture</span></header>
    <template v-if="referrals.length">
      <section class="referral-level-section" aria-labelledby="level-1-title">
        <header><div><h2 id="level-1-title">一级用户</h2><p>一级用户任务成功：积分受益人无；现金归当前推广人（LEVEL_1）。</p></div><span>{{ level1.length }} 人</span></header>
        <div class="stage-notes"><span>任务成功奖励将在下一阶段启用</span><span>佣金将在下一阶段启用</span></div>
        <div class="referral-table-wrap"><table><caption>一级用户列表</caption><thead><tr><th>用户编号</th><th>脱敏用户</th><th>绑定时间</th><th>来源</th></tr></thead><tbody><tr v-for="user in level1" :key="user.id"><td>{{ user.id }}</td><td>{{ user.maskedIdentity }}</td><td>{{ user.boundAt }}</td><td><span class="source-badge">{{ user.source }}</span></td></tr><tr v-if="!level1.length"><td colspan="4">暂无一级用户</td></tr></tbody></table></div>
      </section>
      <section class="referral-level-section" aria-labelledby="level-2-title">
        <header><div><h2 id="level-2-title">二级用户</h2><p>二级用户任务成功：积分归其直接邀请人；现金归当前推广人（LEVEL_2）。</p></div><span>{{ level2.length }} 人</span></header>
        <div class="stage-notes"><span>任务成功奖励将在下一阶段启用</span><span>佣金将在下一阶段启用</span></div>
        <div class="referral-table-wrap"><table><caption>二级用户列表</caption><thead><tr><th>用户编号</th><th>脱敏用户</th><th>绑定时间</th><th>来源</th></tr></thead><tbody><tr v-for="user in level2" :key="user.id"><td>{{ user.id }}</td><td>{{ user.maskedIdentity }}</td><td>{{ user.boundAt }}</td><td><span class="source-badge">{{ user.source }}</span></td></tr><tr v-if="!level2.length"><td colspan="4">暂无二级用户</td></tr></tbody></table></div>
      </section>
    </template>
    <div v-else class="empty-state"><strong>暂无一级或二级用户</strong><p>分享永久邀请链接后，新绑定用户会按层级显示在这里。</p></div>
  </section>
</template>
