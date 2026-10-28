import Panel from "./PromotionWithdrawalReportPanel.vue";
import {adminWithdrawalReportAdapterKey} from "./localWithdrawalReportFixtures";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import { createLocalAdminWithdrawalReportAdapter } from "./localWithdrawalReportFixtures";

enableAutoUnmount(afterEach);

type ReportStatus = "ALL" | "WITHDRAWING" | "PENDING_PAYOUT" | "PAID" | "REJECTED";
type ReportFilters = { status: ReportStatus; promoterKeyword: string; startDate: string; endDate: string };
type ReportBucket = { amount4: string; count: number };
type ReportSnapshot = {
  summary: {
    total: ReportBucket;
    withdrawing: ReportBucket;
    pendingPayout: ReportBucket;
    paid: ReportBucket;
    rejected: ReportBucket;
  };
  entries: readonly {
    id: string;
    promoter: string;
    amount4: string;
    status: Exclude<ReportStatus, "ALL">;
    requestedDate: string;
  }[];
};
type ReportAdapter = {
  getSnapshot: () => ReportSnapshot;
  query: (filters: ReportFilters) => Promise<ReportSnapshot>;
};

const initialSnapshot = (): ReportSnapshot => ({
  summary: {
    total: { amount4: "1234.5678", count: 40 },
    withdrawing: { amount4: "111.1199", count: 4 },
    pendingPayout: { amount4: "222.2299", count: 5 },
    paid: { amount4: "888.8899", count: 28 },
    rejected: { amount4: "12.3499", count: 3 }
  },
  entries: [
    { id: "WD-R-001", promoter: "Nova · USR-1388", amount4: "9.9999", status: "WITHDRAWING", requestedDate: "2026-09-04" }
  ]
});

function adapter(overrides: Partial<ReportAdapter> = {}): ReportAdapter {
  return {
    getSnapshot: () => initialSnapshot(),
    query: async () => initialSnapshot(),
    ...overrides
  };
}

async function loginAndOpen(_role: "ADMIN" | "NORMAL_ADMIN" = "ADMIN", source: ReportAdapter = adapter()) {
  const wrapper = mount(Panel, {attachTo:document.body,global:{provide:{[adminWithdrawalReportAdapterKey as symbol]:source}}});
  await nextTick();
  return {wrapper};
}

describe("local promotion withdrawal report", () => {
  it("filters the default adapter by status, promoter, and date and returns matching totals", async () => {
    const source = initialSnapshot();
    source.entries = [
      { id: "WD-MATCH", promoter: "Nova Studio", amount4: "10.1299", status: "PAID", requestedDate: "2026-09-03" },
      { id: "WD-STATUS", promoter: "Nova Studio", amount4: "20.0000", status: "WITHDRAWING", requestedDate: "2026-09-03" },
      { id: "WD-DATE", promoter: "Nova Studio", amount4: "30.0000", status: "PAID", requestedDate: "2026-08-31" },
      { id: "WD-PROMOTER", promoter: "Other Studio", amount4: "40.0000", status: "PAID", requestedDate: "2026-09-03" }
    ];
    const result = await createLocalAdminWithdrawalReportAdapter(source).query({
      status: "PAID",
      promoterKeyword: "  nova  ",
      startDate: "2026-09-01",
      endDate: "2026-09-04"
    });

    expect(result.entries.map((entry) => entry.id)).toEqual(["WD-MATCH"]);
    expect(result.summary).toEqual({
      total: { amount4: "10.1299", count: 1 },
      withdrawing: { amount4: "0.0000", count: 0 },
      pendingPayout: { amount4: "0.0000", count: 0 },
      paid: { amount4: "10.1299", count: 1 },
      rejected: { amount4: "0.0000", count: 0 }
    });
  });


  it("renders five server summary buckets without recomputing them from entries", async () => {
    const { wrapper } = await loginAndOpen();
    expect(wrapper.get('[data-testid="report-total"]').text()).toContain("1234.56");
    expect(wrapper.get('[data-testid="report-total"]').text()).toContain("40 笔");
    expect(wrapper.get('[data-testid="report-withdrawing"]').text()).toContain("111.11");
    expect(wrapper.get('[data-testid="report-pending-payout"]').text()).toContain("222.22");
    expect(wrapper.get('[data-testid="report-paid"]').text()).toContain("888.88");
    expect(wrapper.get('[data-testid="report-rejected"]').text()).toContain("12.34");
    expect(wrapper.get('[data-testid="row-WD-R-001"]').text()).toContain("9.99");
  });

  it("passes raw filters to the adapter and replaces the whole snapshot", async () => {
    const replacement = initialSnapshot();
    replacement.summary.total = { amount4: "88.8899", count: 2 };
    replacement.entries = [{ id: "WD-R-002", promoter: "星河企业", amount4: "66.6699", status: "PAID", requestedDate: "2026-09-03" }];
    const query = vi.fn(async () => replacement);
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ query }));
    await wrapper.get('select[aria-label="提现状态"]').setValue("PAID");
    await wrapper.get('input[aria-label="推广人关键词"]').setValue("  星河  ");
    await wrapper.get('input[aria-label="开始日期"]').setValue("2026-09-01");
    await wrapper.get('input[aria-label="结束日期"]').setValue("2026-09-04");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(query).toHaveBeenCalledWith({ status: "PAID", promoterKeyword: "  星河  ", startDate: "2026-09-01", endDate: "2026-09-04" });
    expect(wrapper.get('[data-testid="report-total"]').text()).toContain("88.88");
    expect(wrapper.find('[data-testid="row-WD-R-001"]').exists()).toBe(false);
    expect(wrapper.get('[data-testid="row-WD-R-002"]').text()).toContain("星河企业");
  });

  it("rejects an inverted date range without querying", async () => {
    const query = vi.fn(async () => initialSnapshot());
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ query }));
    await wrapper.get('input[aria-label="开始日期"]').setValue("2026-09-05");
    await wrapper.get('input[aria-label="结束日期"]').setValue("2026-09-04");
    await wrapper.get("form").trigger("submit");
    expect(query).not.toHaveBeenCalled();
    expect(wrapper.get('[role="alert"]').text()).toContain("开始日期不能晚于结束日期");
  });

  it("locks concurrent submissions while a query is in flight", async () => {
    let finish!: (value: ReportSnapshot) => void;
    const query = vi.fn(() => new Promise<ReportSnapshot>((resolve) => { finish = resolve; }));
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ query }));
    const button = wrapper.get('button[type="submit"]');
    await button.trigger("click");
    await button.trigger("click");
    expect(query).toHaveBeenCalledTimes(1);
    expect(button.attributes("disabled")).toBeDefined();
    finish(initialSnapshot());
    await flushPromises();
    expect(button.attributes("disabled")).toBeUndefined();
  });

  it("keeps the previous report after a failed query", async () => {
    const query = vi.fn(async () => { throw new Error("private upstream detail"); });
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ query }));
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(wrapper.get('[data-testid="report-total"]').text()).toContain("1234.56");
    expect(wrapper.get('[role="status"]').text()).toContain("查询失败，已保留上次报表");
    expect(wrapper.text()).not.toContain("private upstream detail");
  });

  it("rejects an invalid decimal without leaking it or replacing the previous report", async () => {
    const invalid = initialSnapshot();
    invalid.summary.total.amount4 = "12.3secret-value";
    const { wrapper } = await loginAndOpen("ADMIN", adapter({ query: async () => invalid }));
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(wrapper.get('[data-testid="report-total"]').text()).toContain("1234.56");
    expect(wrapper.get('[role="status"]').text()).toContain("报表金额格式错误，已保留上次报表");
    expect(wrapper.text()).not.toContain("12.3secret-value");
  });

  it("is a semantic read-only report without payout or evidence actions", async () => {
    const { wrapper } = await loginAndOpen();
    expect(wrapper.get("form").element.tagName).toBe("FORM");
    expect(wrapper.get("table").element.tagName).toBe("TABLE");
    expect(wrapper.get("caption").text()).toContain("提现报表明细");
    expect(wrapper.get('label[for="withdrawal-report-keyword"]').text()).toContain("推广人关键词");
    expect(wrapper.findAll("tbody button")).toHaveLength(0);
    expect(wrapper.text()).not.toMatch(/自动转账|上传凭证|凭证URL|流水号|交易号/);
  });
});

it('requests real month pages and preserves full filtered totals', async () => {
  const query = vi.fn().mockResolvedValue({ ...initialSnapshot(), page: 1, size: 20, total: 40 });
  const source = { live: true, load: vi.fn().mockResolvedValue({ ...initialSnapshot(), page: 0, size: 20, total: 40 }), query };
  const { wrapper } = await loginAndOpen('ADMIN', source as never); await flushPromises();
  expect(wrapper.get('[data-testid="report-total"]').text()).toContain('1234.5678');
  await wrapper.get('[aria-label="报表归属月份"]').setValue('2026-08');
  const next = wrapper.findAll('button').find(b => b.text() === '下一页')!;
  await next.trigger('click'); await flushPromises();
  expect(query).toHaveBeenCalledWith(expect.objectContaining({ month: '2026-08', page: 1 }));
  expect(wrapper.get('[data-testid="report-total"]').text()).toContain('40 笔');
});
