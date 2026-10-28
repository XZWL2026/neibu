<script setup lang="ts">
import { formatAmount } from '../../../shared/formatAmount';

import { computed, onUnmounted, ref } from "vue";
import { copyPromotionWithLifecycle, type ClipboardAdapter } from "../promotion/clipboardAdapter";
import { type PromotionFinanceAdapter } from "../promotion/localFinanceFixtures";
import type { LocalPromotionProfileView, LocalReferralView } from "../promotion/localPromotionFixtures";

const props = defineProps<{ clipboard: ClipboardAdapter; profile: LocalPromotionProfileView; referrals: readonly LocalReferralView[]; financeAdapter: PromotionFinanceAdapter }>();
const finance = props.financeAdapter.getSnapshot();
const copyStatus = ref("");
const copying = ref(false);
let disposed = false;
onUnmounted(() => { disposed = true; });
const level1Count = computed(() => props.referrals.filter((item) => item.level === "LEVEL_1").length);
const level2Count = computed(() => props.referrals.filter((item) => item.level === "LEVEL_2").length);
async function copyPromotionLink() {
  if (copying.value) return;
  copying.value = true;
  await copyPromotionWithLifecycle(props.clipboard, props.profile.link, {
    isActive: () => !disposed,
    success: () => { copyStatus.value = "推广链接已复制"; },
    failure: () => { copyStatus.value = "复制失败，请手动复制推广链接"; },
    settled: () => { copying.value = false; }
  });
}
const financeCards = [
  { label: "冻结佣金", value: finance.balance.frozen4 },
  { label: "可提现佣金", value: finance.balance.available4 },
  { label: "提现中", value: finance.balance.withdrawing4 },
  { label: "累计已提现", value: finance.balance.cumulativePaid4 }
];
</script>

<template>
  <section class="partner-page" aria-labelledby="partner-page-title">
    <header class="page-heading"><div><p class="eyebrow">PROMOTION OVERVIEW</p><h1 id="partner-page-title">推广概览</h1><p>沿用当前用户会话中的同一账号、同一邀请码与链接；财务金额直接来自服务端快照。</p></div><span class="local-chip">本地 fixture</span></header>
    <article class="profile-card">
      <span class="profile-avatar" aria-hidden="true">{{ props.profile.avatarText }}</span>
      <div><strong>{{ props.profile.nickname }}</strong><span>用户编号 {{ props.profile.userNumber }}</span><span>推广人编号 {{ props.profile.promoterNumber }}</span></div>
      <dl><div data-testid="level-1-count"><dt>一级用户</dt><dd>{{ level1Count }} 人</dd></div><div data-testid="level-2-count"><dt>二级用户</dt><dd>{{ level2Count }} 人</dd></div></dl>
    </article>
    <section class="promotion-link-card" aria-labelledby="promotion-link-title"><div><p class="eyebrow">PERMANENT REFERRAL</p><h2 id="promotion-link-title">永久邀请码 {{ props.profile.code }}</h2></div><label>永久邀请链接<input :value="props.profile.link" readonly></label><button type="button" :aria-label="copying ? '正在复制永久邀请链接' : '复制推广链接'" :disabled="copying" @click="copyPromotionLink">{{ copying ? '复制中…' : '复制永久邀请链接' }}</button><p v-if="copyStatus" role="status" aria-live="polite">{{ copyStatus }}</p></section>
    <section class="finance-grid" aria-label="推广佣金状态"><article v-for="card in financeCards" :key="card.label" class="finance-card"><span>{{ card.label }}</span><strong>¥{{ formatAmount(card.value) }}</strong><small>服务端快照</small></article></section>
  </section>
</template>
