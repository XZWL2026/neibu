<script setup lang="ts">
import { getPlatformSmoke } from "@xzwl/api-contracts";
import { RouterLink, RouterView, useRoute } from "vue-router";
import { nextTick, ref, watch } from "vue";
import { createClipboardAdapter, type ClipboardAdapter } from "./promotion/clipboardAdapter";
import { createLocalPromotionFinanceAdapter, type PromotionFinanceAdapter } from "./promotion/localFinanceFixtures";
import { createLocalPayoutAccountAdapter, type PayoutAccountAdapter } from "./promotion/localPayoutAccountFixtures";
import { localPromotionSessionView, type LocalPromotionSessionView } from "./promotion/localPromotionFixtures";
import { createLocalWithdrawalAdapter, localWithdrawalView, type WithdrawalAdapter } from "./promotion/localWithdrawalFixtures";
void getPlatformSmoke;

const props = withDefaults(defineProps<{ clipboard?: ClipboardAdapter; session?: LocalPromotionSessionView; withdrawalAdapter?: WithdrawalAdapter; payoutAccountAdapter?: PayoutAccountAdapter; financeAdapter?: PromotionFinanceAdapter }>(), { clipboard: () => createClipboardAdapter(), session: () => localPromotionSessionView, withdrawalAdapter: () => createLocalWithdrawalAdapter(), payoutAccountAdapter: () => createLocalPayoutAccountAdapter(), financeAdapter: () => createLocalPromotionFinanceAdapter() });
const route = useRoute();
const mainElement = ref<HTMLElement | null>(null);
watch(() => route.fullPath, async () => { await nextTick(); mainElement.value?.focus(); }, { immediate: true });
</script>

<template>
  <div class="partner-shell">
    <a class="skip-link" href="#partner-main">跳到主要内容</a>
    <aside class="partner-sidebar">
      <RouterLink class="brand" to="/promotion/overview"><span class="brand-mark" aria-hidden="true">◇</span><span><strong>XZWLab</strong><small>推广工作台</small></span></RouterLink>
      <nav aria-label="推广工作台导航"><RouterLink to="/promotion/overview" :aria-current="route.path === '/promotion/overview' ? 'page' : undefined">推广概览</RouterLink><RouterLink to="/promotion/referrals" :aria-current="route.path === '/promotion/referrals' ? 'page' : undefined">被推广用户</RouterLink><RouterLink to="/promotion/commission-entries" :aria-current="route.path === '/promotion/commission-entries' ? 'page' : undefined">佣金明细</RouterLink><RouterLink to="/promotion/transactions" :aria-current="route.path === '/promotion/transactions' ? 'page' : undefined">消费明细</RouterLink><RouterLink to="/promotion/payout-accounts" :aria-current="route.path === '/promotion/payout-accounts' ? 'page' : undefined">收款账户</RouterLink><RouterLink to="/promotion/withdrawals" :aria-current="route.path === '/promotion/withdrawals' ? 'page' : undefined">佣金提现</RouterLink></nav>
      <div class="session-card"><span class="session-avatar" aria-hidden="true">{{ props.session.profile.avatarText }}</span><div><strong>{{ props.session.profile.nickname }}</strong><small>同一用户会话 · {{ props.session.profile.userNumber }}</small></div></div>
    </aside>
    <main id="partner-main" ref="mainElement" class="partner-main" tabindex="-1"><RouterView v-slot="{ Component }"><component v-if="route.name === 'promotion-overview'" :is="Component" :clipboard="props.clipboard" :profile="props.session.profile" :referrals="props.session.referrals" :finance-adapter="props.financeAdapter" /><component v-else-if="route.name === 'promotion-referrals'" :is="Component" :referrals="props.session.referrals" /><component v-else-if="route.name === 'promotion-commission-entries' || route.name === 'promotion-transactions'" :is="Component" :adapter="props.financeAdapter" /><component v-else-if="route.name === 'promotion-payout-accounts'" :is="Component" :adapter="props.payoutAccountAdapter" /><component v-else-if="route.name === 'promotion-withdrawals'" :is="Component" :view="props.session.withdrawal ?? localWithdrawalView" :adapter="props.withdrawalAdapter" /><component v-else :is="Component" /></RouterView></main>
  </div>
</template>
