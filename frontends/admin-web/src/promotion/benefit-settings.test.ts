import { fixtureSession } from "../fixtureSession.test-support";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import App from "../App.vue";
import AdminSearchSelect from "../components/AdminSearchSelect.vue";
import { adminRoutes } from "../router";
import { createLocalAdminBenefitSettingsAdapter } from "./localBenefitSettingsFixtures";

enableAutoUnmount(afterEach);

type GlobalRateName = "invitationPointsRate" | "defaultLevel1CashRate" | "defaultLevel2CashRate";
type CashLevel = "level1CashRate" | "level2CashRate";
type BenefitSnapshot = {
  version: string;
  effectiveAt: string;
  operator: string;
  reason: string;
  global: Record<GlobalRateName, string>;
  promoters: readonly {
    id: string;
    name: string;
    level1CashRate: string | null;
    level2CashRate: string | null;
  }[];
};
type HistoryScope = "GLOBAL" | "PROMOTER_OVERRIDE";
type HistoryFilters = {
  scope: "ALL" | HistoryScope;
  promoterKeyword: string;
  startDate: string;
  endDate: string;
};
type HistoryRecord = {
  version: string;
  scope: HistoryScope;
  maskedPromoter: string | null;
  point: string | null;
  defaultL1: string | null;
  defaultL2: string | null;
  promoterL1: string | null;
  promoterL2: string | null;
  effectiveAt: string;
  admin: string;
  reason: string;
};
type HistorySnapshot = { count: number; records: readonly HistoryRecord[] };
type BenefitAdapter = {
  load: () => Promise<BenefitSnapshot>;
  saveGlobalRate: (name: GlobalRateName, rate: string, reason: string) => Promise<BenefitSnapshot>;
  savePromoterRate: (promoterId: string, level: CashLevel, rate: string | null, reason: string) => Promise<BenefitSnapshot>;
  loadHistory: () => Promise<HistorySnapshot>;
  queryHistory: (filters: HistoryFilters) => Promise<HistorySnapshot>;
};

const snapshot = (overrides: Partial<BenefitSnapshot> = {}): BenefitSnapshot => ({
  version: "BENEFIT-V7",
  effectiveAt: "2026-09-04 10:30",
  operator: "primary.admin",
  reason: "季度渠道政策",
  global: {
    invitationPointsRate: "0.125",
    defaultLevel1CashRate: "0.08",
    defaultLevel2CashRate: "0.03"
  },
  promoters: [
    { id: "USR-1388", name: "Nova", level1CashRate: "0.12", level2CashRate: null },
    { id: "USR-2046", name: "星河企业", level1CashRate: null, level2CashRate: "0.05" }
  ],
  ...overrides
});

const historyRecord = (overrides: Partial<HistoryRecord> = {}): HistoryRecord => ({
  version: "BENEFIT-V7",
  scope: "GLOBAL",
  maskedPromoter: null,
  point: "0.125",
  defaultL1: "0.08",
  defaultL2: "0.03",
  promoterL1: null,
  promoterL2: null,
  effectiveAt: "2026-09-04 10:30",
  admin: "primary.admin",
  reason: "季度渠道政策",
  ...overrides
});

const historySnapshot = (overrides: Partial<HistorySnapshot> = {}): HistorySnapshot => ({
  count: 7,
  records: [historyRecord(), historyRecord({
    version: "BENEFIT-V6",
    scope: "PROMOTER_OVERRIDE",
    maskedPromoter: "N*** · USR-1388",
    point: null,
    defaultL1: null,
    defaultL2: null,
    promoterL1: "0.12",
    promoterL2: null,
    effectiveAt: "2026-09-03 09:00",
    reason: "重点推广人调整"
  })],
  ...overrides
});

function adapter(overrides: Partial<BenefitAdapter> = {}): BenefitAdapter {
  return {
    load: async () => snapshot(),
    saveGlobalRate: async () => snapshot({ version: "BENEFIT-V8", reason: "更新全局比例" }),
    savePromoterRate: async () => snapshot({ version: "BENEFIT-V8", reason: "更新推广人比例" }),
    loadHistory: async () => historySnapshot(),
    queryHistory: async () => historySnapshot(),
    ...overrides
  };
}

async function mountAt(path = "/admin/login", benefitAdapter: BenefitAdapter = adapter()) {
  const router = createRouter({ history: createMemoryHistory(), routes: adminRoutes });
  await router.push(path);
  await router.isReady();
  const wrapper = mount(App, {
    attachTo: document.body,
    props: { session: fixtureSession(), adminBenefitSettingsAdapter: benefitAdapter } as never,
    global: { plugins: [router] }
  });
  await nextTick();
  return { router, wrapper };
}

async function loginAndOpen(role: "ADMIN" | "NORMAL_ADMIN" = "ADMIN", benefitAdapter: BenefitAdapter = adapter()) {
  const result = await mountAt("/admin/login", benefitAdapter);
  await result.wrapper.get('input[autocomplete="username"]').setValue(role === "ADMIN" ? "primary.admin" : "content.ops");
  await result.wrapper.get("form").trigger("submit");
  await flushPromises();
  await result.router.push("/admin/promotions/benefit-settings");
  await nextTick();
  return result;
}

describe("local promotion benefit settings", () => {
  it("combines scope, masked promoter, and date filters in the default adapter and returns its own count", async () => {
    const local = createLocalAdminBenefitSettingsAdapter() as unknown as BenefitAdapter;
    expect(typeof local.queryHistory).toBe("function");
    if (typeof local.queryHistory !== "function") return;

    const result = await local.queryHistory({
      scope: "PROMOTER_OVERRIDE",
      promoterKeyword: "  n***  ",
      startDate: "2026-09-01",
      endDate: "2026-09-04"
    });
    expect(result.count).toBe(1);
    expect(result.records.map((record) => record.maskedPromoter)).toEqual(["N*** · USR-1388"]);
  });

  it("adds typed global and promoter history snapshots after successful local saves", async () => {
    const local = createLocalAdminBenefitSettingsAdapter();
    const before = await local.loadHistory();
    await local.saveGlobalRate("invitationPointsRate", "0.2", "全局版本");
    await local.savePromoterRate("USR-1388", "level2CashRate", "0.04", "覆盖版本");
    const after = await local.loadHistory();

    expect(after.count).toBe(before.count + 2);
    expect(after.records.slice(0, 2)).toMatchObject([
      { scope: "PROMOTER_OVERRIDE", maskedPromoter: "N*** · USR-1388", point: null, defaultL1: null, defaultL2: null, promoterL1: "0.12", promoterL2: "0.04", admin: "primary.admin", reason: "覆盖版本" },
      { scope: "GLOBAL", maskedPromoter: null, point: "0.2", defaultL1: "0.08", defaultL2: "0.03", promoterL1: null, promoterL2: null, admin: "primary.admin", reason: "全局版本" }
    ]);
  });

  it("keeps at most one original character when masking one- and two-character promoter names", async () => {
    const local = createLocalAdminBenefitSettingsAdapter(snapshot({ promoters: [
      { id: "USR-0001", name: "张", level1CashRate: null, level2CashRate: null },
      { id: "USR-0002", name: "张三", level1CashRate: null, level2CashRate: null }
    ] }) as never, historySnapshot({ count: 0, records: [] }) as never);

    await local.savePromoterRate("USR-0001", "level1CashRate", "0.1", "单字符掩码");
    await local.savePromoterRate("USR-0002", "level1CashRate", "0.1", "双字符掩码");
    const history = await local.loadHistory();

    expect(history.records.slice(0, 2).map((record) => record.maskedPromoter)).toEqual(["张* · USR-0002", "* · USR-0001"]);
  });

  it("adds an ADMIN-only route and navigation entry", async () => {
    const normal = await loginAndOpen("NORMAL_ADMIN");
    expect(normal.wrapper.find('a[href="/admin/promotions/benefit-settings"]').exists()).toBe(false);
    expect(normal.wrapper.get("h1").text()).toBe("无权限");

    const primary = await loginAndOpen();
    expect(primary.wrapper.get('a[href="/admin/promotions/benefit-settings"]').text()).toContain("推广规则");
    expect(primary.wrapper.get("h1").text()).toBe("推广规则");

    const signedOut = await mountAt("/admin/promotions/benefit-settings");
    expect(signedOut.wrapper.get("h1").text()).toBe("无权限");
  });

  it("shows loading before rendering the adapter default rates", async () => {
    let finish!: (value: BenefitSnapshot) => void;
    const load = vi.fn(() => new Promise<BenefitSnapshot>((resolve) => { finish = resolve; }));
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ load }));
    expect(wrapper.get('[data-testid="benefit-settings-loading"]').text()).toContain("加载中");
    expect(wrapper.text()).not.toContain("BENEFIT-V7");

    finish(snapshot());
    await flushPromises();
    expect(wrapper.get('.benefit-defaults').text()).toContain("12.5%");
  });

  it("shows history loading then renders percentage audit fields without tier columns", async () => {
    let finish!: (value: HistorySnapshot) => void;
    const loadHistory = vi.fn(() => new Promise<HistorySnapshot>((resolve) => { finish = resolve; }));
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ loadHistory }));
    expect(wrapper.get('[data-testid="benefit-history-loading"]').text()).toContain("加载中");

    finish(historySnapshot());
    await flushPromises();
    expect(wrapper.get('[data-testid="benefit-history-count"]').text()).toContain("7 条");
    const global = wrapper.get('[data-testid="benefit-history-row-BENEFIT-V7"]');
    expect(global.text()).toMatch(/默认比例配置.*积分 12\.5%.*分佣 8%/);
    expect(global.text()).not.toContain("0.03");
    expect(wrapper.get("[data-testid=benefit-history-table]").text()).not.toMatch(/defaultL[12]|promoterL[12]/);
    const promoter = wrapper.get('[data-testid="benefit-history-row-BENEFIT-V6"]');
    expect(promoter.text()).toMatch(/特殊代理分佣比例.*N\*\*\* · USR-1388.*12%/);
    expect(promoter.text()).toContain("primary.admin");
    expect(promoter.text()).toContain("重点推广人调整");
  });

  it("passes raw history filters to the adapter and trusts its replacement count and empty records", async () => {
    const queryHistory = vi.fn(async () => historySnapshot({ count: 23, records: [] }));
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ queryHistory }));
    await flushPromises();
    await wrapper.get('select[aria-label="历史范围"]').setValue("PROMOTER_OVERRIDE");
    await wrapper.get('input[aria-label="历史脱敏推广人关键词"]').setValue("  USR-1388  ");
    await wrapper.get('input[aria-label="历史开始日期"]').setValue("2026-09-01");
    await wrapper.get('input[aria-label="历史结束日期"]').setValue("2026-09-04");
    await wrapper.get('[data-testid="benefit-history-filter-form"]').trigger("submit");
    await flushPromises();

    expect(queryHistory).toHaveBeenCalledWith({ scope: "PROMOTER_OVERRIDE", promoterKeyword: "  USR-1388  ", startDate: "2026-09-01", endDate: "2026-09-04" });
    expect(wrapper.get('[data-testid="benefit-history-count"]').text()).toContain("23 条");
    expect(wrapper.get('[data-testid="benefit-history-empty"]').text()).toContain("没有版本记录");
  });

  it("rejects an inverted history date range without querying", async () => {
    const queryHistory = vi.fn(async () => historySnapshot());
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ queryHistory }));
    await flushPromises();
    await wrapper.get('input[aria-label="历史开始日期"]').setValue("2026-09-05");
    await wrapper.get('input[aria-label="历史结束日期"]').setValue("2026-09-04");
    await wrapper.get('[data-testid="benefit-history-filter-form"]').trigger("submit");
    expect(queryHistory).not.toHaveBeenCalled();
    expect(wrapper.get('[role="alert"]').text()).toContain("开始日期不能晚于结束日期");
  });

  it("sanitizes history load errors and retries", async () => {
    const loadHistory = vi.fn().mockRejectedValueOnce(new Error("private provider history error")).mockResolvedValueOnce(historySnapshot());
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ loadHistory }));
    await flushPromises();
    expect(wrapper.get('[data-testid="benefit-history-load-error"]').text()).toContain("加载失败");
    expect(wrapper.text()).not.toContain("private provider history error");
    await wrapper.get('[data-testid="benefit-history-retry"]').trigger("click");
    await flushPromises();
    expect(loadHistory).toHaveBeenCalledTimes(2);
    expect(wrapper.find('[data-testid="benefit-history-row-BENEFIT-V7"]').exists()).toBe(true);
  });

  it("clears the initial history load error after a successful save refresh", async () => {
    const refreshed = historySnapshot({ count: 1, records: [historyRecord({ version: "BENEFIT-V8", point: "0.2", reason: "恢复历史" })] });
    const loadHistory = vi.fn(async () => { throw new Error("initial failure"); });
    const queryHistory = vi.fn(async () => refreshed);
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ loadHistory, queryHistory }));
    await flushPromises();
    expect(wrapper.find('[data-testid="benefit-history-load-error"]').exists()).toBe(true);

    await wrapper.get('input[aria-label="全局邀请积分比例"]').setValue("20");
    await wrapper.get('button[aria-label="保存全局邀请积分比例"]').trigger("click");
    await flushPromises();

    expect(wrapper.find('[data-testid="benefit-history-load-error"]').exists()).toBe(false);
    expect(wrapper.get('[data-testid="benefit-history-row-BENEFIT-V8"]').text()).toContain("恢复历史");
  });

  it("ignores an older initial history load that finishes after a save refresh", async () => {
    let finishInitial!: (value: HistorySnapshot) => void;
    let finishRefresh!: (value: HistorySnapshot) => void;
    const loadHistory = vi.fn(() => new Promise<HistorySnapshot>((resolve) => { finishInitial = resolve; }));
    const queryHistory = vi.fn(() => new Promise<HistorySnapshot>((resolve) => { finishRefresh = resolve; }));
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ loadHistory, queryHistory }));
    await wrapper.get('input[aria-label="全局邀请积分比例"]').setValue("20");
    await wrapper.get('button[aria-label="保存全局邀请积分比例"]').trigger("click");
    await flushPromises();

    finishRefresh(historySnapshot({ count: 1, records: [historyRecord({ version: "BENEFIT-V8", point: "0.2", reason: "保存刷新" })] }));
    await flushPromises();
    finishInitial(historySnapshot({ count: 1, records: [historyRecord({ version: "BENEFIT-STALE", reason: "旧初始加载" })] }));
    await flushPromises();

    expect(wrapper.find('[data-testid="benefit-history-row-BENEFIT-V8"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="benefit-history-row-BENEFIT-STALE"]').exists()).toBe(false);
  });

  it("keeps the save refresh when an older manual query finishes later", async () => {
    let finishManual!: (value: HistorySnapshot) => void;
    let finishRefresh!: (value: HistorySnapshot) => void;
    const queryHistory = vi.fn()
      .mockImplementationOnce(() => new Promise<HistorySnapshot>((resolve) => { finishManual = resolve; }))
      .mockImplementationOnce(() => new Promise<HistorySnapshot>((resolve) => { finishRefresh = resolve; }));
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ queryHistory }));
    await flushPromises();
    await wrapper.get('[data-testid="benefit-history-filter-form"]').trigger("submit");
    await flushPromises();

    await wrapper.get('input[aria-label="全局邀请积分比例"]').setValue("20");
    await wrapper.get('button[aria-label="保存全局邀请积分比例"]').trigger("click");
    await flushPromises();
    finishRefresh(historySnapshot({ count: 1, records: [historyRecord({ version: "BENEFIT-V8", point: "0.2", reason: "保存刷新" })] }));
    await flushPromises();
    finishManual(historySnapshot({ count: 1, records: [historyRecord({ version: "BENEFIT-STALE", reason: "旧筛选" })] }));
    await flushPromises();

    expect(wrapper.find('[data-testid="benefit-history-row-BENEFIT-V8"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="benefit-history-row-BENEFIT-STALE"]').exists()).toBe(false);
  });

  it("shows the retryable load error when a save refresh fails before any history exists", async () => {
    let finishInitial!: (value: HistorySnapshot) => void;
    const loadHistory = vi.fn(() => new Promise<HistorySnapshot>((resolve) => { finishInitial = resolve; }));
    const queryHistory = vi.fn(async () => { throw new Error("refresh failed"); });
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ loadHistory, queryHistory }));
    await wrapper.get('input[aria-label="全局邀请积分比例"]').setValue("20");
    await wrapper.get('button[aria-label="保存全局邀请积分比例"]').trigger("click");
    await flushPromises();

    expect(wrapper.find('[data-testid="benefit-history-load-error"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="benefit-history-retry"]').exists()).toBe(true);
    finishInitial(historySnapshot({ records: [historyRecord({ version: "BENEFIT-STALE" })] }));
    await flushPromises();
    expect(wrapper.find('[data-testid="benefit-history-load-error"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="benefit-history-row-BENEFIT-STALE"]').exists()).toBe(false);
  });

  it("keeps existing history visible when a save refresh fails", async () => {
    const queryHistory = vi.fn(async () => { throw new Error("refresh failed"); });
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ queryHistory }));
    await flushPromises();
    await wrapper.get('input[aria-label="全局邀请积分比例"]').setValue("20");
    await wrapper.get('button[aria-label="保存全局邀请积分比例"]').trigger("click");
    await flushPromises();

    expect(wrapper.find('[data-testid="benefit-history-load-error"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="benefit-history-row-BENEFIT-V7"]').exists()).toBe(true);
  });

  it("keeps prior history when a query returns an invalid Rate6", async () => {
    const queryHistory = vi.fn(async () => historySnapshot({ records: [historyRecord({ point: "0.1234567secret" })] }));
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ queryHistory }));
    await flushPromises();
    await wrapper.get('[data-testid="benefit-history-filter-form"]').trigger("submit");
    await flushPromises();
    expect(wrapper.get('[data-testid="benefit-history-row-BENEFIT-V7"]').text()).toContain("12.5%");
    expect(wrapper.text()).not.toContain("0.1234567secret");
    expect(wrapper.get('[data-testid="benefit-history-feedback"]').text()).toContain("比例格式错误，已保留上次历史");
  });

  it("rejects a numeric history rate payload instead of coercing it to Rate6", async () => {
    const numeric = historyRecord({ point: 0.1 as unknown as string });
    const queryHistory = vi.fn(async () => historySnapshot({ records: [numeric] }));
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ queryHistory }));
    await flushPromises();
    await wrapper.get('[data-testid="benefit-history-filter-form"]').trigger("submit");
    await flushPromises();

    expect(wrapper.get('[data-testid="benefit-history-row-BENEFIT-V7"]').text()).toContain("12.5%");
    expect(wrapper.get('[data-testid="benefit-history-feedback"]').text()).toContain("比例格式错误，已保留上次历史");
  });

  it("renders only masked history identity fields and exposes no history mutation action", async () => {
    const unsafe = { ...historyRecord({ version: "BENEFIT-UNSAFE", scope: "PROMOTER_OVERRIDE", maskedPromoter: "N*** · USR-1388", point: null, defaultL1: null, defaultL2: null, promoterL1: "0.1" }), phone: "13800138000", email: "private@example.com" };
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ loadHistory: async () => historySnapshot({ count: 1, records: [unsafe] }) }));
    await flushPromises();
    expect(wrapper.text()).toContain("N*** · USR-1388");
    expect(wrapper.text()).not.toMatch(/13800138000|private@example\.com/);
    expect(wrapper.findAll('[data-testid^="benefit-history-row-"] button')).toHaveLength(0);
    expect(wrapper.get('[data-testid="benefit-history-table"]').text()).not.toMatch(/回滚|删除|改写|编辑/);
  });

  it("rejects a masked promoter payload containing a decorated full name and separated phone number", () => {
    const unsafe = historySnapshot({ records: [historyRecord({
      scope: "PROMOTER_OVERRIDE",
      maskedPromoter: "Alice (CEO)* · +86 138 0013 8000",
      point: null,
      defaultL1: null,
      defaultL2: null,
      promoterL1: "0.1"
    })] });

    expect(() => createLocalAdminBenefitSettingsAdapter(undefined, unsafe as never)).toThrow("inconsistent promoter benefit settings history record");
  });

  it.each(["Nova* · USR-1388", "张三*"])("rejects a full promoter label disguised with a trailing star: %s", (maskedPromoter) => {
    const unsafe = historySnapshot({ records: [historyRecord({
      scope: "PROMOTER_OVERRIDE",
      maskedPromoter,
      point: null,
      defaultL1: null,
      defaultL2: null,
      promoterL1: "0.1"
    })] });

    expect(() => createLocalAdminBenefitSettingsAdapter(undefined, unsafe as never)).toThrow("inconsistent promoter benefit settings history record");
  });

  it.each(["N**a · USR-1388", "张*三"])("rejects a visible promoter character after masking starts: %s", (maskedPromoter) => {
    const unsafe = historySnapshot({ records: [historyRecord({
      scope: "PROMOTER_OVERRIDE",
      maskedPromoter,
      point: null,
      defaultL1: null,
      defaultL2: null,
      promoterL1: "0.1"
    })] });

    expect(() => createLocalAdminBenefitSettingsAdapter(undefined, unsafe as never)).toThrow("inconsistent promoter benefit settings history record");
  });

  it("accepts a fully masked promoter label", () => {
    const fullyMasked = historySnapshot({ records: [historyRecord({
      scope: "PROMOTER_OVERRIDE",
      maskedPromoter: "*** · USR-1388",
      point: null,
      defaultL1: null,
      defaultL2: null,
      promoterL1: "0.1"
    })] });

    expect(() => createLocalAdminBenefitSettingsAdapter(undefined, fullyMasked as never)).not.toThrow();
  });

  it("displays global ratios as percentages with a visible unit", async () => {
    const { wrapper } = await loginAndOpen(); await flushPromises();
    expect(wrapper.findAll(".benefit-defaults .benefit-percent-input input")).toHaveLength(2);
    expect(wrapper.text()).not.toMatch(/一级|二级/);
    for (const [label, value] of [["全局邀请积分比例", "12.5"], ["默认返佣比例", "8"]]) {
      const input = wrapper.get<HTMLInputElement>(`input[aria-label="${label}"]`);
      expect(input.element.value).toBe(value);
      expect(input.element.parentElement?.textContent).toContain("%");
    }
  });

  it.each([
    ["invitationPointsRate", "全局邀请积分比例", "0.2", "20"],
    ["defaultLevel1CashRate", "默认返佣比例", "0.15", "15"],
    ["invitationPointsRate", "全局邀请积分比例", "0", "0"],
    ["invitationPointsRate", "全局邀请积分比例", "1", "100"],
    ["invitationPointsRate", "全局邀请积分比例", "0.000001", "0.0001"],
    ["invitationPointsRate", "全局邀请积分比例", "0.123456", "12.3456"]
  ] as const)("saves %s independently without asking for a reason", async (name, label, rate, percent) => {
    const saved = snapshot({ version: `saved-${name}`, reason: "调整收益比例", global: { ...snapshot().global, [name]: rate } });
    const saveGlobalRate = vi.fn(async () => saved);
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ saveGlobalRate }));
    await flushPromises();
    expect(wrapper.find('input[aria-label$="管理员原因"]').exists()).toBe(false);
    await wrapper.get(`input[aria-label="${label}"]`).setValue(percent);
    await wrapper.get(`button[aria-label="保存${label}"]`).trigger("click");
    await flushPromises();

    expect(saveGlobalRate).toHaveBeenCalledWith(name, rate, "调整收益比例");
    expect(wrapper.get<HTMLInputElement>(`input[aria-label="${label}"]`).element.value).toBe(percent);
    expect(wrapper.get(`section[aria-label="${name==='invitationPointsRate'?'客户推广默认返积分比例':'代理默认分佣比例'}"] .benefit-default-value strong`).text()).toBe(percent+"%");
  });

  it("searches before opening one promoter and preserves drafts when switching", async () => {
    const {wrapper}=await loginAndOpen();await flushPromises();
    expect(wrapper.findAll('.benefit-promoter-card')).toHaveLength(0);
    await wrapper.get('[aria-label="添加特殊代理"]').trigger("click");
    const select=wrapper.getComponent(AdminSearchSelect);
    await select.get('[role="combobox"]').trigger('click');await flushPromises();
    await select.get('input[aria-label="搜索选择推广人"]').setValue('Nova');
    await select.get('button[aria-label="查询选择推广人"]').trigger('click');await flushPromises();
    const options=select.findAll('[role="option"]').filter(option=>option.isVisible());
    expect(options).toHaveLength(1);expect(options[0]!.text()).toContain('Nova');
    await options[0]!.trigger('click');await flushPromises();
    expect(wrapper.findAll('.benefit-promoter-card')).toHaveLength(1);
    expect(wrapper.findAll('.benefit-promoter-card input')).toHaveLength(1);
    expect(wrapper.get('.benefit-promoter-card').text()).not.toMatch(/一级|二级/);
    await wrapper.get('input[aria-label="USR-1388 返佣覆盖"]').setValue('25');
    select.vm.$emit('update:modelValue','USR-2046');await nextTick();
    expect(wrapper.get('.benefit-promoter-card').text()).toContain('星河企业');
    select.vm.$emit('update:modelValue','USR-1388');await nextTick();
    expect(wrapper.get<HTMLInputElement>('input[aria-label="USR-1388 返佣覆盖"]').element.value).toBe('25');
  });

  it("saves each promoter cash override and supports using the default value", async () => {
    const savePromoterRate = vi.fn(async (promoterId: string, level: CashLevel, rate: string | null, reason: string) => snapshot({
      version: `${promoterId}-${level}`,
      reason,
      promoters: snapshot().promoters.map((item) => item.id === promoterId ? { ...item, [level]: rate } : item)
    }));
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ savePromoterRate }));
    await flushPromises();

    wrapper.getComponent(AdminSearchSelect).vm.$emit("update:modelValue", "USR-1388");await nextTick();
    expect(wrapper.find('input[aria-label$="管理员原因"]').exists()).toBe(false);
    await wrapper.get('input[aria-label="USR-1388 返佣覆盖"]').setValue("22");
    await wrapper.get('button[aria-label="保存 USR-1388 返佣覆盖"]').trigger("click");
    await flushPromises();
    expect(savePromoterRate).toHaveBeenNthCalledWith(1, "USR-1388", "level1CashRate", "0.22", "调整收益比例");

    wrapper.getComponent(AdminSearchSelect).vm.$emit("update:modelValue", "USR-2046");await nextTick();
    await wrapper.get('input[aria-label="USR-2046 返佣覆盖"]').setValue("");
    await wrapper.get('button[aria-label="保存 USR-2046 返佣覆盖"]').trigger("click");
    await flushPromises();
    expect(savePromoterRate).toHaveBeenNthCalledWith(2, "USR-2046", "level1CashRate", null, "调整收益比例");
    expect(savePromoterRate).toHaveBeenCalledTimes(2);
  });

  it.each(["-0.1", "100.0001", "0.12345", "01", ".5", "1e-2", "NaN", "", "10%"])("rejects invalid rate %s with a stable error", async (rate) => {
    const saveGlobalRate = vi.fn(async () => snapshot());
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ saveGlobalRate }));
    await flushPromises();
    await wrapper.get('input[aria-label="全局邀请积分比例"]').setValue(rate);
    await wrapper.get('button[aria-label="保存全局邀请积分比例"]').trigger("click");

    expect(saveGlobalRate).not.toHaveBeenCalled();
    expect(wrapper.get('[role="alert"]').text()).toBe("百分比必须是 0 到 100 之间、最多 4 位小数的数字");
  });

  it("saves without a reason and locks all saves while one is pending", async () => {
    let finish!: (value: BenefitSnapshot) => void;
    const saveGlobalRate = vi.fn(() => new Promise<BenefitSnapshot>((resolve) => { finish = resolve; }));
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ saveGlobalRate }));
    await flushPromises();
    await wrapper.get('input[aria-label="全局邀请积分比例"]').setValue("20");
    await wrapper.get('button[aria-label="保存全局邀请积分比例"]').trigger("click");
    expect(saveGlobalRate).toHaveBeenCalledWith("invitationPointsRate", "0.2", "调整收益比例");

    const buttons = wrapper.findAll('button[data-benefit-save]');
    await wrapper.get('button[aria-label="保存全局邀请积分比例"]').trigger("click");
    await wrapper.get('button[aria-label="保存默认返佣比例"]').trigger("click");
    expect(saveGlobalRate).toHaveBeenCalledTimes(1);
    expect(buttons.every((button) => button.attributes("disabled") !== undefined)).toBe(true);
    finish(snapshot({ version: "BENEFIT-V8" }));
    await flushPromises();
    expect(buttons.every((button) => button.attributes("disabled") === undefined)).toBe(true);
  });

  it("preserves an unsaved L1 draft after saving the points rate", async () => {
    const nextHistory = historySnapshot({ count: 8, records: [historyRecord({ version: "BENEFIT-V8", point: "0.2", reason: "先调整积分" }), ...historySnapshot().records] });
    const queryHistory = vi.fn(async () => nextHistory);
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ queryHistory }));
    await flushPromises();
    const level1Rate = wrapper.get('input[aria-label="默认返佣比例"]');
    await level1Rate.setValue("19");
    await wrapper.get('input[aria-label="全局邀请积分比例"]').setValue("20");
    await wrapper.get('button[aria-label="保存全局邀请积分比例"]').trigger("click");
    await flushPromises();

    expect(level1Rate.element).toHaveProperty("value", "19");
    expect(queryHistory).toHaveBeenCalledTimes(1);
    expect(wrapper.get('[data-testid="benefit-history-count"]').text()).toContain("8 条");
    expect(wrapper.get('[data-testid="benefit-history-row-BENEFIT-V8"]').text()).toContain("先调整积分");
  });

  it("does not overwrite newer edits to the submitted field when its response arrives", async () => {
    let finish!: (value: BenefitSnapshot) => void;
    const saveGlobalRate = vi.fn(() => new Promise<BenefitSnapshot>((resolve) => { finish = resolve; }));
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ saveGlobalRate }));
    await flushPromises();
    const rate = wrapper.get('input[aria-label="全局邀请积分比例"]');
    await rate.setValue("20");
    await wrapper.get('button[aria-label="保存全局邀请积分比例"]').trigger("click");
    await rate.setValue("25");
    finish(snapshot({ version: "BENEFIT-V8", global: { ...snapshot().global, invitationPointsRate: "0.2" } }));
    await flushPromises();

    expect(rate.element).toHaveProperty("value", "25");
    expect(wrapper.get('section[aria-label="客户推广默认返积分比例"] .benefit-default-value strong').text()).toBe("20%");
  });

  it("keeps the previous snapshot after save failure without leaking details", async () => {
    const saveGlobalRate = vi.fn(async () => { throw new Error("private adapter detail"); });
    const queryHistory = vi.fn(async () => historySnapshot({ count: 99 }));
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ saveGlobalRate, queryHistory }));
    await flushPromises();
    await wrapper.get('input[aria-label="全局邀请积分比例"]').setValue("20");
    await wrapper.get('button[aria-label="保存全局邀请积分比例"]').trigger("click");
    await flushPromises();

    expect(wrapper.get('.benefit-defaults').text()).toContain("12.5%");
    expect(wrapper.get('input[aria-label="全局邀请积分比例"]').element).toHaveProperty("value", "20");
    expect(wrapper.get('[data-testid="benefit-history-count"]').text()).toContain("7 条");
    expect(queryHistory).not.toHaveBeenCalled();
    expect(wrapper.get('[role="status"]').text()).toBe("保存失败，已保留原配置快照");
    expect(wrapper.text()).not.toContain("private adapter detail");
  });
});
