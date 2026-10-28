import { fixtureSession } from "../fixtureSession.test-support";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import App from "../App.vue";
import { adminRoutes } from "../router";
import { createLocalAdminReconciliationAdapter, localReconciliationSnapshot, type ReconciliationSnapshot } from "./localReconciliationFixtures";

enableAutoUnmount(afterEach);

type AnomalyType = "ALL" | "PENDING_DEPENDENCY" | "CASH_DEBT" | "POINT_DEBT";
type Filters = { type: AnomalyType; keyword: string };
type Entry = {
  id: string;
  type: Exclude<AnomalyType, "ALL">;
  maskedSubject: string;
  sourceId: string;
  orderId: string | null;
  lotId: string | null;
  allocationId: string | null;
  expected4: string;
  actual4: string;
  difference4: string;
  detectedAt: string;
  status: "OPEN" | "REVIEWING";
};
type Snapshot = {
  summary: { total: number; pendingDependency: number; cashDebt: number; pointDebt: number };
  entries: readonly Entry[];
};
type DryRunPreview = {
  dryRun: true;
  executable: false;
  anomalyId: string;
  generatedAt: string;
  changes: readonly { label: string; before4: string; after4: string; difference4: string }[];
};
type Adapter = {
  load: () => Promise<Snapshot>;
  query: (filters: Filters) => Promise<Snapshot>;
  repairDryRun: (anomalyId: string, reason: string) => Promise<DryRunPreview>;
};

const snapshot = (overrides: Partial<Snapshot> = {}): Snapshot => ({
  summary: { total: 41, pendingDependency: 17, cashDebt: 13, pointDebt: 11 },
  entries: [{
    id: "REC-001",
    type: "CASH_DEBT",
    maskedSubject: "用***88 · U***388",
    sourceId: "SRC-101",
    orderId: "ORD-201",
    lotId: "LOT-301",
    allocationId: "ALLOC-401",
    expected4: "12.3456",
    actual4: "10.3456",
    difference4: "-2.0000",
    detectedAt: "2026-09-04 10:20:30",
    status: "OPEN"
  }],
  ...overrides
});

function adapter(overrides: Partial<Adapter> = {}): Adapter {
  return {
    load: async () => snapshot(),
    query: async () => snapshot(),
    repairDryRun: async (anomalyId) => ({
      dryRun: true,
      executable: false,
      anomalyId,
      generatedAt: "2026-09-04 10:30:00",
      changes: [{ label: "现金账务", before4: "10.3456", after4: "12.3456", difference4: "2.0000" }]
    }),
    ...overrides
  };
}

async function mountAt(path = "/admin/login", reconciliationAdapter: Adapter = adapter()) {
  const router = createRouter({ history: createMemoryHistory(), routes: adminRoutes });
  await router.push(path);
  await router.isReady();
  const wrapper = mount(App, {
    attachTo: document.body,
    props: { session: fixtureSession(), adminReconciliationAdapter: reconciliationAdapter } as never,
    global: { plugins: [router] }
  });
  await nextTick();
  return { router, wrapper };
}

async function loginAndOpen(role: "ADMIN" | "NORMAL_ADMIN" = "ADMIN", reconciliationAdapter: Adapter = adapter()) {
  const result = await mountAt("/admin/login", reconciliationAdapter);
  await result.wrapper.get('input[autocomplete="username"]').setValue(role === "ADMIN" ? "primary.admin" : "content.ops");
  await result.wrapper.get("form").trigger("submit");
  await flushPromises();
  await result.router.push("/admin/promotions/reconciliation");
  await nextTick();
  return result;
}

describe("promotion reconciliation", () => {
  it("computes the default dry-run delta as expected minus actual", async () => {
    const preview = await createLocalAdminReconciliationAdapter().repairDryRun("REC-LOCAL-002", "人工核对");
    expect(preview.changes[0]).toEqual({
      label: "账务差异预览",
      before4: "20.0000",
      after4: "28.3456",
      difference4: "8.3456"
    });
  });

  it("keeps an exact negative dry-run delta beyond Number precision", async () => {
    const initial: ReconciliationSnapshot = {
      summary: { total: 1, pendingDependency: 0, cashDebt: 1, pointDebt: 0 },
      entries: [{
        ...localReconciliationSnapshot.entries[1],
        id: "REC-REVERSE",
        expected4: "9007199254740992.9999",
        actual4: "9007199254740993.0001",
        difference4: "0.0002"
      }]
    };
    const preview = await createLocalAdminReconciliationAdapter(initial).repairDryRun("REC-REVERSE", "人工核对");
    expect(preview.changes[0].difference4).toBe("-0.0002");
  });

  it("combines default adapter type and user/order/lot filters and returns filtered summary", async () => {
    const source = {
      summary: { total: 4, pendingDependency: 1, cashDebt: 2, pointDebt: 1 },
      entries: [
        { ...snapshot().entries[0], id: "REC-USER", subjectRef: "USR-2046", type: "CASH_DEBT" },
        { ...snapshot().entries[0], id: "REC-ORDER", subjectRef: "USR-9999", orderId: "ORD-2046", type: "CASH_DEBT" },
        { ...snapshot().entries[0], id: "REC-LOT", subjectRef: "USR-8888", lotId: "LOT-2046", type: "POINT_DEBT" },
        { ...snapshot().entries[0], id: "REC-TYPE", subjectRef: "USR-7777", orderId: "ORD-2046", type: "PENDING_DEPENDENCY" }
      ]
    } as unknown as ReconciliationSnapshot;
    const local = createLocalAdminReconciliationAdapter(source);

    const byUser = await local.query({ type: "CASH_DEBT", keyword: "  usr-2046  " });
    expect(byUser.entries.map((entry) => entry.id)).toEqual(["REC-USER"]);
    expect(byUser.summary).toEqual({ total: 1, pendingDependency: 0, cashDebt: 1, pointDebt: 0 });

    const byOrder = await local.query({ type: "CASH_DEBT", keyword: "ord-2046" });
    expect(byOrder.entries.map((entry) => entry.id)).toEqual(["REC-ORDER"]);
    expect(byOrder.summary.cashDebt).toBe(1);

    const byLot = await local.query({ type: "POINT_DEBT", keyword: "lot-2046" });
    expect(byLot.entries.map((entry) => entry.id)).toEqual(["REC-LOT"]);
    expect(byLot.summary.pointDebt).toBe(1);

    const beforeDryRun = await local.load();
    const preview = await local.repairDryRun("REC-USER", "人工核对");
    expect(preview).toMatchObject({ dryRun: true, executable: false, anomalyId: "REC-USER" });
    expect(await local.load()).toEqual(beforeDryRun);
  });

  it("shows navigation only for the primary ADMIN", async () => {
    const normal = await loginAndOpen("NORMAL_ADMIN");
    expect(normal.wrapper.find('a[href="/admin/promotions/reconciliation"]').exists()).toBe(false);
    expect(normal.wrapper.get("h1").text()).toBe("无权限");

    const primary = await loginAndOpen();
    expect(primary.wrapper.find('a[href="/admin/promotions/reconciliation"]').exists()).toBe(true);
    expect(primary.wrapper.get("h1").text()).toBe("推广收益对账");

    const signedOut = await mountAt("/admin/promotions/reconciliation");
    expect(signedOut.wrapper.get("h1").text()).toBe("无权限");
  });

  it("shows loading, then renders adapter summary and strict Decimal4 strings without page totals", async () => {
    let finish!: (value: Snapshot) => void;
    const load = vi.fn(() => new Promise<Snapshot>((resolve) => { finish = resolve; }));
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ load }));
    expect(wrapper.get('[data-testid="reconciliation-loading"]').text()).toContain("加载中");

    finish(snapshot());
    await flushPromises();
    expect(wrapper.get('[data-testid="reconciliation-total"]').text()).toContain("41");
    expect(wrapper.get('[data-testid="reconciliation-pending-dependency"]').text()).toContain("17");
    expect(wrapper.get('[data-testid="reconciliation-cash-debt"]').text()).toContain("13");
    expect(wrapper.get('[data-testid="reconciliation-point-debt"]').text()).toContain("11");
    const row = wrapper.get('[data-testid="reconciliation-row-REC-001"]');
    expect(row.text()).toContain("12.3456");
    expect(row.text()).toContain("10.3456");
    expect(row.text()).toContain("-2");
  });

  it("renders only masked subjects and approved identifiers without leaking sensitive extras", async () => {
    const unsafe = {
      ...snapshot().entries[0],
      phone: "13800138000",
      email: "private@example.com",
      key: "sk-secret",
      prompt: "private prompt",
      media: "private material",
      result: "private result",
      vendorError: "provider stack trace"
    };
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ load: async () => snapshot({ entries: [unsafe] }) }));
    await flushPromises();
    expect(wrapper.text()).toContain("用***88 · U***388");
    expect(wrapper.text()).toContain("SRC-101");
    expect(wrapper.text()).toContain("ORD-201");
    expect(wrapper.text()).toContain("LOT-301");
    expect(wrapper.text()).toContain("ALLOC-401");
    expect(wrapper.text()).not.toMatch(/13800138000|private@example\.com|sk-secret|private prompt|private material|private result|provider stack trace/);
  });

  it("passes type and user/order/lot keyword filters to the adapter and uses its replacement summary", async () => {
    const replacement = snapshot({
      summary: { total: 3, pendingDependency: 2, cashDebt: 0, pointDebt: 1 },
      entries: []
    });
    const query = vi.fn(async () => replacement);
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ query }));
    await flushPromises();
    await wrapper.get('select[aria-label="异常类型"]').setValue("PENDING_DEPENDENCY");
    await wrapper.get('input[aria-label="对账关键词"]').setValue("  ORD-201  ");
    await wrapper.get('[data-testid="reconciliation-filter-form"]').trigger("submit");
    await flushPromises();

    expect(query).toHaveBeenCalledWith({ type: "PENDING_DEPENDENCY", keyword: "  ORD-201  " });
    expect(wrapper.get('[data-testid="reconciliation-total"]').text()).toContain("3");
    expect(wrapper.get('[data-testid="reconciliation-pending-dependency"]').text()).toContain("2");
    expect(wrapper.get('[data-testid="reconciliation-empty"]').text()).toContain("当前筛选没有异常");
  });

  it("shows a stable load error, sanitizes it, and retries", async () => {
    const load = vi.fn()
      .mockRejectedValueOnce(new Error("vendor private failure"))
      .mockResolvedValueOnce(snapshot());
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ load }));
    await flushPromises();
    expect(wrapper.get('[data-testid="reconciliation-load-error"]').text()).toContain("加载失败");
    expect(wrapper.text()).not.toContain("vendor private failure");
    await wrapper.get('[data-testid="reconciliation-retry"]').trigger("click");
    await flushPromises();
    expect(load).toHaveBeenCalledTimes(2);
    expect(wrapper.find('[data-testid="reconciliation-row-REC-001"]').exists()).toBe(true);
  });

  it("keeps the prior snapshot when a query returns invalid Decimal4 or fails", async () => {
    const invalid = snapshot();
    invalid.entries = [{ ...invalid.entries[0], expected4: "12.34secret" }];
    const query = vi.fn()
      .mockResolvedValueOnce(invalid)
      .mockRejectedValueOnce(new Error("private adapter failure"));
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ query }));
    await flushPromises();
    await wrapper.get('[data-testid="reconciliation-filter-form"]').trigger("submit");
    await flushPromises();
    expect(wrapper.get('[data-testid="reconciliation-row-REC-001"]').text()).toContain("12.3456");
    expect(wrapper.text()).not.toContain("12.34secret");
    expect(wrapper.get('[role="status"]').text()).toContain("金额格式错误，已保留原快照");

    await wrapper.get('[data-testid="reconciliation-filter-form"]').trigger("submit");
    await flushPromises();
    expect(wrapper.get('[data-testid="reconciliation-row-REC-001"]').text()).toContain("12.3456");
    expect(wrapper.text()).not.toContain("private adapter failure");
    expect(wrapper.get('[role="status"]').text()).toContain("筛选失败，已保留原快照");
  });

  it("requires an administrator reason and locks concurrent repair dry-runs", async () => {
    let finish!: (value: DryRunPreview) => void;
    const repairDryRun = vi.fn(() => new Promise<DryRunPreview>((resolve) => { finish = resolve; }));
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ repairDryRun }));
    await flushPromises();
    const button = wrapper.get('[data-testid="reconciliation-dry-run-REC-001"]');
    await button.trigger("click");
    expect(repairDryRun).not.toHaveBeenCalled();
    expect(wrapper.get('[role="alert"]').text()).toContain("必须填写管理员原因");

    await wrapper.get('input[aria-label="管理员原因"]').setValue("  核对现金账务差异  ");
    await button.trigger("click");
    await button.trigger("click");
    expect(repairDryRun).toHaveBeenCalledTimes(1);
    expect(repairDryRun).toHaveBeenCalledWith("REC-001", "核对现金账务差异");
    expect(button.attributes("disabled")).toBeDefined();

    finish({
      dryRun: true,
      executable: false,
      anomalyId: "REC-001",
      generatedAt: "2026-09-04 10:30:00",
      changes: [{ label: "现金账务", before4: "10.3456", after4: "12.3456", difference4: "2.0000" }]
    });
    await flushPromises();
    expect(wrapper.get('[data-testid="reconciliation-dry-run-preview"]').text()).toMatch(/dry-run|不可执行/);
    expect(wrapper.get('[data-testid="reconciliation-dry-run-preview"]').text()).toMatch(/10\.3456.*12\.3456.*2/);
    expect(button.attributes("disabled")).toBeUndefined();
  });

  it("retains the last preview and original snapshot after a dry-run failure", async () => {
    const repairDryRun = vi.fn()
      .mockResolvedValueOnce({
        dryRun: true as const,
        executable: false as const,
        anomalyId: "REC-001",
        generatedAt: "2026-09-04 10:30:00",
        changes: [{ label: "现金账务", before4: "10.3456", after4: "12.3456", difference4: "2.0000" }]
      })
      .mockRejectedValueOnce(new Error("vendor repair detail"));
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ repairDryRun }));
    await flushPromises();
    await wrapper.get('input[aria-label="管理员原因"]').setValue("人工复核");
    await wrapper.get('[data-testid="reconciliation-dry-run-REC-001"]').trigger("click");
    await flushPromises();
    await wrapper.get('[data-testid="reconciliation-dry-run-REC-001"]').trigger("click");
    await flushPromises();

    expect(wrapper.get('[data-testid="reconciliation-dry-run-preview"]').text()).toContain("12.3456");
    expect(wrapper.get('[data-testid="reconciliation-row-REC-001"]').text()).toContain("10.3456");
    expect(wrapper.get('[role="status"]').text()).toContain("预演失败，已保留原快照与上次预览");
    expect(wrapper.text()).not.toContain("vendor repair detail");
  });
});

it('keeps actual ledger differences separate from debt and previews without exposing a repair action', async () => {
  const value = { differences: [{ userId: 'user-real', dimension: 'AVAILABLE', expected4: '12.3456', actual4: '10.0000', difference4: '2.3456' }], debts: [{ userId: 'debt-user', unit: 'POINT', amount4: '3.0000' }], pendingRewardDeliveries: 2, generatedAt: '2026-09-15T00:00:00Z', dryRun: true as const, executable: false as const };
  const previewBalances = vi.fn().mockResolvedValue(value);
  const { wrapper } = await loginAndOpen('ADMIN', { balances: vi.fn().mockResolvedValue(value), previewBalances } as never); await flushPromises();
  expect(wrapper.text()).toContain('账实差异1'); expect(wrapper.text()).toContain('待到账奖励2'); expect(wrapper.text()).toContain('待追回账户1');
  expect(wrapper.text()).toContain('debt-user');
  await wrapper.get('[aria-label="管理员原因"]').setValue('复核差异'); await wrapper.get('form').trigger('submit'); await flushPromises();
  expect(previewBalances).toHaveBeenCalledWith('复核差异');
  expect(wrapper.get('[data-testid="reconciliation-dry-run-preview"]').text()).toContain('10.0000 → 12.3456');
  expect(wrapper.findAll('button').some(button => /执行修复|确认修复/.test(button.text()))).toBe(false);
});
