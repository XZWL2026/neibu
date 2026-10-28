import { fixtureSession } from "../fixtureSession.test-support";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import * as matchers from "vitest-axe/matchers";
import { nextTick } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import App from "../App.vue";
import { adminRoutes } from "../router";
import {
  createLocalAdminBenefitAuditAdapter,
  localBenefitAuditSnapshot,
  type AdminBenefitAuditAdapter,
  type BenefitAuditSnapshot
} from "./localBenefitAuditFixtures";

enableAutoUnmount(afterEach);
expect.extend(matchers);

function snapshot(overrides: Partial<BenefitAuditSnapshot> = {}): BenefitAuditSnapshot {
  return {
    summary: { cashCount: 17, pointCount: 13, cashDebtCount: 3, pointDebtCount: 2 },
    entries: [{ ...localBenefitAuditSnapshot.entries[0], id: "AUD-001", before4: "12.3456", after4: "22.3456", delta4: "10.0000" }],
    ...overrides
  };
}

function adapter(overrides: Partial<AdminBenefitAuditAdapter> = {}): AdminBenefitAuditAdapter {
  return { load: async () => snapshot(), query: async () => snapshot(), ...overrides };
}

async function mountAt(path = "/admin/login", benefitAuditAdapter: AdminBenefitAuditAdapter = adapter()) {
  const router = createRouter({ history: createMemoryHistory(), routes: adminRoutes });
  await router.push(path);
  await router.isReady();
  const wrapper = mount(App, { attachTo: document.body, props: { session: fixtureSession(), adminBenefitAuditAdapter: benefitAuditAdapter }, global: { plugins: [router] } });
  await nextTick();
  return { router, wrapper };
}

async function loginAndOpen(role: "ADMIN" | "NORMAL_ADMIN" = "ADMIN", benefitAuditAdapter: AdminBenefitAuditAdapter = adapter()) {
  const result = await mountAt("/admin/login", benefitAuditAdapter);
  await result.wrapper.get('input[autocomplete="username"]').setValue(role === "ADMIN" ? "primary.admin" : "content.ops");
  await result.wrapper.get("form").trigger("submit");
  await flushPromises();
  await result.router.push("/admin/promotions/benefit-audit");
  await nextTick();
  return result;
}

describe("promotion benefit audit", () => {
  it("rejects cross-unit actions and invalid cash tiers before they can pollute summary buckets", () => {
    const dirtyEntries = [
      { ...localBenefitAuditSnapshot.entries[5], action: "CASH_DEBT_RECORDED" },
      { ...localBenefitAuditSnapshot.entries[0], action: "POINT_DEBT_RECORDED" },
      { ...localBenefitAuditSnapshot.entries[0], cashTier: "LEVEL_1" }
    ];

    for (const entry of dirtyEntries) {
      const dirty = snapshot({ entries: [entry] } as unknown as Partial<BenefitAuditSnapshot>);
      expect(() => createLocalAdminBenefitAuditAdapter(dirty)).toThrow("inconsistent benefit audit unit, action, or cash tier");
    }
  });

  it("combines unit, action, masked user, order/lot, and date filters in the default adapter", async () => {
    const cashEntry = localBenefitAuditSnapshot.entries.find((entry) => entry.unit === "CASH")!;
    const entries = [
      { ...cashEntry, id: "MATCH", maskedBeneficiary: "N***a · U***388", orderId: "ORD-KEEP", occurredAt: "2026-09-03 10:00:00" },
      { ...cashEntry, id: "ACTION", action: "CASH_COMMISSION_RELEASED" as const, maskedBeneficiary: "N***a · U***388", orderId: "ORD-KEEP", occurredAt: "2026-09-03 10:00:00" },
      { ...cashEntry, id: "USER", maskedBeneficiary: "O***r · U***999", orderId: "ORD-KEEP", occurredAt: "2026-09-03 10:00:00" },
      { ...cashEntry, id: "DATE", maskedBeneficiary: "N***a · U***388", orderId: "ORD-KEEP", occurredAt: "2026-08-30 10:00:00" },
      { ...localBenefitAuditSnapshot.entries[5], id: "UNIT", maskedBeneficiary: "N***a · U***388", orderId: "ORD-KEEP", occurredAt: "2026-09-03 10:00:00" }
    ];
    const local = createLocalAdminBenefitAuditAdapter({
      summary: { cashCount: 4, pointCount: 1, cashDebtCount: 0, pointDebtCount: 0 }, entries
    });
    const result = await local.query({
      unit: "CASH", action: "CASH_COMMISSION_FROZEN", userKeyword: "  n***A  ", referenceKeyword: " ord-keep ", startDate: "2026-09-01", endDate: "2026-09-04"
    });
    expect(result.entries.map((entry) => entry.id)).toEqual(["MATCH"]);
    expect(result.summary).toEqual({ cashCount: 1, pointCount: 0, cashDebtCount: 0, pointDebtCount: 0 });

    const byLot = await createLocalAdminBenefitAuditAdapter().query({ unit: "CASH", action: "ALL", userKeyword: "", referenceKeyword: "lot-local-302", startDate: "", endDate: "" });
    expect(byLot.entries.map((entry) => entry.id)).toEqual(["AUD-LOCAL-003"]);
  });

  it("covers every confirmed cash and point audit action", () => {
    expect(localBenefitAuditSnapshot.entries.map((entry) => entry.action)).toEqual([
      "CASH_COMMISSION_FROZEN", "CASH_COMMISSION_RELEASED", "CASH_COMMISSION_REVERSED", "CASH_REFUND_RECOVERY", "CASH_DEBT_RECORDED",
      "POINT_GRANTED", "POINT_REVERSED", "POINT_DEBT_RECORDED", "POINT_OFFSET"
    ]);
  });

  it("shows navigation only for primary ADMIN", async () => {
    const normal = await loginAndOpen("NORMAL_ADMIN");
    expect(normal.wrapper.find('a[href="/admin/promotions/benefit-audit"]').exists()).toBe(false);
    expect(normal.wrapper.get("h1").text()).toBe("无权限");

    const primary = await loginAndOpen();
    expect(primary.wrapper.find('a[href="/admin/promotions/benefit-audit"]').exists()).toBe(true);
    expect(primary.wrapper.get("h1").text()).toBe("推广收益审计");

    const signedOut = await mountAt("/admin/promotions/benefit-audit");
    expect(signedOut.wrapper.get("h1").text()).toBe("无权限");
  });

  it("shows loading then renders adapter counts and immutable Decimal4 rows with explicit units", async () => {
    let finish!: (value: BenefitAuditSnapshot) => void;
    const load = vi.fn(() => new Promise<BenefitAuditSnapshot>((resolve) => { finish = resolve; }));
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ load }));
    expect(wrapper.get('[data-testid="benefit-audit-loading"]').text()).toContain("加载中");
    finish(snapshot());
    await flushPromises();

    expect(wrapper.get('[data-testid="benefit-audit-cash-count"]').text()).toContain("17 条");
    expect(wrapper.get('[data-testid="benefit-audit-point-count"]').text()).toContain("13 条");
    const row = wrapper.get('[data-testid="benefit-audit-row-AUD-001"]');
    expect(row.text()).toMatch(/现金佣金冻结.*现金/);
    expect(row.text()).toContain("N***a · U***388");
    expect(row.text()).toMatch(/sourceEvent EVT-LOCAL-101.*order ORD-LOCAL-201.*lot LOT-LOCAL-301.*allocation ALLOC-LOCAL-401.*withdrawal —/);
    expect(row.text()).toMatch(/现金 · 12\.3456.*现金 · 22\.3456.*现金 · 10/);
    expect(row.text()).toContain("直接推广");
    expect(row.text()).toContain("订单支付完成");
    expect(row.text()).toContain("2026-09-01 09:12:30");
  });

  it("passes raw filters to the adapter and renders its replacement summary and empty state", async () => {
    const query = vi.fn(async () => snapshot({ summary: { cashCount: 0, pointCount: 0, cashDebtCount: 0, pointDebtCount: 0 }, entries: [] }));
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ query }));
    await flushPromises();
    await wrapper.get('select[aria-label="审计单位"]').setValue("POINT");
    await wrapper.get('select[aria-label="审计动作"]').setValue("POINT_OFFSET");
    await wrapper.get('input[aria-label="脱敏用户关键词"]').setValue("  U***088  ");
    await wrapper.get('input[aria-label="订单批次关键词"]').setValue("  LOT-9  ");
    await wrapper.get('input[aria-label="审计开始日期"]').setValue("2026-09-01");
    await wrapper.get('input[aria-label="审计结束日期"]').setValue("2026-09-04");
    await wrapper.get('[data-testid="benefit-audit-filter-form"]').trigger("submit");
    await flushPromises();
    expect(query).toHaveBeenCalledWith({ unit: "POINT", action: "POINT_OFFSET", userKeyword: "  U***088  ", referenceKeyword: "  LOT-9  ", startDate: "2026-09-01", endDate: "2026-09-04" });
    expect(wrapper.get('[data-testid="benefit-audit-cash-count"]').text()).toContain("0 条");
    expect(wrapper.get('[data-testid="benefit-audit-empty"]').text()).toContain("没有审计明细");
  });

  it("rejects an inverted date range without querying", async () => {
    const query = vi.fn(async () => snapshot());
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ query }));
    await flushPromises();
    await wrapper.get('input[aria-label="审计开始日期"]').setValue("2026-09-05");
    await wrapper.get('input[aria-label="审计结束日期"]').setValue("2026-09-04");
    await wrapper.get('[data-testid="benefit-audit-filter-form"]').trigger("submit");
    expect(query).not.toHaveBeenCalled();
    expect(wrapper.get('[role="alert"]').text()).toContain("开始日期不能晚于结束日期");
  });

  it("sanitizes load errors and retries", async () => {
    const load = vi.fn().mockRejectedValueOnce(new Error("private provider error")).mockResolvedValueOnce(snapshot());
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ load }));
    await flushPromises();
    expect(wrapper.get('[data-testid="benefit-audit-load-error"]').text()).toContain("加载失败");
    expect(wrapper.text()).not.toContain("private provider error");
    await wrapper.get('[data-testid="benefit-audit-retry"]').trigger("click");
    await flushPromises();
    expect(load).toHaveBeenCalledTimes(2);
    expect(wrapper.find('[data-testid="benefit-audit-row-AUD-001"]').exists()).toBe(true);
  });

  it("keeps the prior snapshot when a query returns an invalid Decimal4", async () => {
    const invalid = snapshot({ entries: [{ ...snapshot().entries[0], delta4: "10.00secret" }] });
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ query: async () => invalid }));
    await flushPromises();
    await wrapper.get('[data-testid="benefit-audit-filter-form"]').trigger("submit");
    await flushPromises();
    expect(wrapper.get('[data-testid="benefit-audit-row-AUD-001"]').text()).toContain("10");
    expect(wrapper.text()).not.toContain("10secret");
    expect(wrapper.get('[role="status"]').text()).toContain("数值格式错误，已保留上次快照");
  });

  it("renders only approved masked fields and exposes no mutation or export action", async () => {
    const unsafe = { ...snapshot().entries[0], phone: "13800138000", email: "private@example.com", key: "sk-secret", prompt: "private prompt", material: "private material", result: "private result", vendorError: "provider stack trace" };
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ load: async () => snapshot({ entries: [unsafe] }) }));
    await flushPromises();
    expect(wrapper.text()).toContain("N***a · U***388");
    expect(wrapper.text()).not.toMatch(/13800138000|private@example\.com|sk-secret|private prompt|private material|private result|provider stack trace/);
    expect(wrapper.findAll("tbody button")).toHaveLength(0);
    expect(wrapper.text()).not.toMatch(/编辑|repair|发放操作|提现操作|导出/);
  });

  it("keeps the read-only filters and table accessible", async () => {
    const { wrapper } = await loginAndOpen();
    await flushPromises();
    expect(await axe(wrapper.element, { rules: { "color-contrast": { enabled: false } } })).toHaveNoViolations();
  });
});

it('shows real typed amounts and server paging without inventing before and after balances', async () => {
  const records = vi.fn().mockResolvedValue({ items: [{ id: 'event-real', unit: 'POINT', beneficiaryUserId: 'user-real', action: 'GRANT', amount4: '25.0000', sourceEventId: 'source-real', orderId: 'order-real', fundingLotId: 'lot-real', allocationId: 'allocation-real', withdrawalId: null, status: 'DELIVERED', occurredAt: '2026-09-15T00:00:00Z' }], total: 41, page: 0, size: 20 });
  const { wrapper } = await loginAndOpen('ADMIN', { records }); await flushPromises();
  expect(wrapper.text()).toContain('25'); expect(wrapper.text()).toContain('source-real'); expect(wrapper.text()).toContain('41 条');
  expect(wrapper.findAll('th').map(h => h.text())).not.toEqual(expect.arrayContaining(['before', 'after']));
  await wrapper.findAll('button').find(b => b.text() === '下一页')!.trigger('click'); await flushPromises();
  expect(records).toHaveBeenLastCalledWith({ unit: 'ALL', userId: '', reference: '', page: 1 });
});
