import { enableAutoUnmount, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it } from "vitest";
import { nextTick } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import App from "./App.vue";
import { partnerRoutes } from "./router";
import { truncateDecimal2 } from "./promotion/localFinanceFixtures";

enableAutoUnmount(afterEach);

const customSnapshot = {
  balance: {
    frozen4: "12.3499",
    available4: "34.5678",
    withdrawing4: "56.7890",
    cumulativePaid4: "78.9012"
  },
  commissionTotal4: "81.2399",
  commissionEntries: [
    { id: "CE-CUSTOM-1", occurredAt: "2026-09-04 10:00", action: "FROZEN", amount4: "1.2399", balanceBefore4: "0.0000", balanceAfter4: "1.2399", reference: "ORD-CUSTOM-1 · TASK-CUSTOM-1", level: "LEVEL_1" },
    { id: "CE-CUSTOM-2", occurredAt: "2026-09-04 11:00", action: "REVERSED", amount4: "-0.1299", balanceBefore4: "1.2399", balanceAfter4: "1.1100", reference: "ORD-CUSTOM-2 · TASK-CUSTOM-2", level: "LEVEL_2" }
  ],
  transactionTotals: { successfulPoints4: "66.6699", cashCommission4: "8.8899" },
  transactions: [
    { id: "TX-CUSTOM-1", maskedUser: "林＊ · 138****8031", level: "LEVEL_1", taskSettlementRef: "TASK-CUSTOM-1 · SET-CUSTOM-1", successfulPoints4: "22.2299", cashCommission4: "3.3399", fundingType: "REAL_RECHARGE", status: "SUCCESS", occurredAt: "2026-09-04 10:00" },
    { id: "TX-CUSTOM-2", maskedUser: "A＊＊ · api***@example.com", level: "LEVEL_2", taskSettlementRef: "TASK-CUSTOM-2 · SET-CUSTOM-2", successfulPoints4: "44.4400", cashCommission4: "5.5500", fundingType: "REAL_RECHARGE", status: "SUCCESS", occurredAt: "2026-09-04 11:00" }
  ]
} as const;

async function show(path: string, snapshot?: object) {
  const router = createRouter({ history: createMemoryHistory(), routes: partnerRoutes });
  await router.push(path);
  await router.isReady();
  const props = snapshot ? { financeAdapter: { getSnapshot: () => snapshot } } : {};
  const wrapper = mount(App, { attachTo: document.body, props: props as never, global: { plugins: [router] } });
  await nextTick();
  return { router, wrapper };
}

describe("promotion commission and transaction ledgers", () => {
  it("truncates valid decimal strings and rejects malformed financial values", () => {
    expect(truncateDecimal2("-12.3499")).toBe("-12.34");
    expect(truncateDecimal2("12")).toBe("12.00");
    for (const value of ["12.34.56", "12.3oops", ""]) {
      expect(() => truncateDecimal2(value)).toThrow("invalid decimal value");
    }
  });

  it("renders the four overview balances directly from the adapter snapshot", async () => {
    const { wrapper } = await show("/promotion/overview", customSnapshot);
    expect(wrapper.findAll(".finance-card").map((card) => card.get("strong").text())).toEqual([
      "¥12.3499", "¥34.5678", "¥56.789", "¥78.9012"
    ]);
  });

  it("adds commission and transaction deep links to the navigation", async () => {
    const { router, wrapper } = await show("/promotion/commission-entries", customSnapshot);
    expect(wrapper.get("h1").text()).toBe("佣金明细");
    expect(wrapper.get('a[aria-current="page"]').text()).toBe("佣金明细");
    expect(wrapper.get('a[href="/promotion/transactions"]').text()).toBe("消费明细");

    await router.push("/promotion/transactions");
    await nextTick();
    expect(wrapper.get("h1").text()).toBe("消费明细");
    expect(wrapper.get('a[aria-current="page"]').text()).toBe("消费明细");
  });

  it("shows every commission action, balance movement, two levels, and truncates without rounding", async () => {
    const { wrapper } = await show("/promotion/commission-entries");
    const text = wrapper.text();
    for (const label of ["冻结", "释放", "冲回", "提现占用", "拒绝退回", "已发放", "现金待追偿", "抵扣"]) {
      expect(text).toContain(label);
    }
    expect(text).toContain("LEVEL_1");
    expect(text).toContain("LEVEL_2");
    expect(text).not.toMatch(/LEVEL_3|三级/);
    expect(text).toContain("¥12.34");
    expect(text).not.toContain("¥12.35");
    expect(wrapper.get("thead").text()).toContain("变更前");
    expect(wrapper.get("thead").text()).toContain("变更后");
    expect(text).toContain("ORD-LOCAL-001");
    expect(text).toContain("TASK-LOCAL-001");
  });

  it("filters commission rows without recalculating the server total", async () => {
    const { wrapper } = await show("/promotion/commission-entries", customSnapshot);
    expect(wrapper.get('[data-testid="commission-total"]').text()).toContain("¥81.23");
    await wrapper.get('select[aria-label="筛选佣金层级"]').setValue("LEVEL_2");
    expect(wrapper.findAll(".commission-entry-row")).toHaveLength(1);
    expect(wrapper.get('[data-testid="commission-total"]').text()).toContain("¥81.23");
  });

  it("shows only final successful unrefunded consumption and prevents reward commission loops", async () => {
    const { wrapper } = await show("/promotion/transactions");
    const text = wrapper.text();
    expect(text).toContain("最终成功");
    expect(text).toContain("REFERRAL_REWARD");
    expect(wrapper.get('[data-testid="transaction-reward-row"]').text()).toContain("¥0");
    expect(text).not.toMatch(/TASK-PROCESSING|TASK-FAILED|TASK-REFUNDED/);
    expect(text).not.toMatch(/13800138000|alice@example\.com|secret-key|prompt|resultURL|内部异常/);
    expect(text).not.toMatch(/LEVEL_3|三级/);
  });

  it("filters transaction rows while keeping snapshot totals unchanged", async () => {
    const { wrapper } = await show("/promotion/transactions", customSnapshot);
    expect(wrapper.get('[data-testid="successful-points-total"]').text()).toContain("66.66");
    expect(wrapper.get('[data-testid="transaction-cash-total"]').text()).toContain("¥8.88");
    await wrapper.get('select[aria-label="筛选消费层级"]').setValue("LEVEL_1");
    await wrapper.get('select[aria-label="筛选消费状态"]').setValue("SUCCESS");
    expect(wrapper.findAll(".transaction-row")).toHaveLength(1);
    expect(wrapper.get('[data-testid="successful-points-total"]').text()).toContain("66.66");
    expect(wrapper.get('[data-testid="transaction-cash-total"]').text()).toContain("¥8.88");
  });
});
