import Panel from "./PromotionWithdrawalsPanel.vue";
import {adminWithdrawalAdapterKey} from "./localWithdrawalFixtures";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { readFileSync } from "node:fs";
import { afterEach, describe, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import { createLocalAdminWithdrawalAdapter } from "./localWithdrawalFixtures";

enableAutoUnmount(afterEach);

type WithdrawalStatus = "WITHDRAWING" | "PENDING_PAYOUT" | "PAID" | "REJECTED";
type WithdrawalView = {
  id: string;
  promoter: string;
  amount: string;
  status: WithdrawalStatus;
  payoutAccountSnapshot: string;
  requestedAt: string;
  rejectionReason?: string;
};
type WithdrawalAdapter = {
  getSnapshot: () => readonly WithdrawalView[];
  approve: (id: string, operationKey: string) => Promise<WithdrawalView>;
  reject: (id: string, reason: string, operationKey: string) => Promise<WithdrawalView>;
  markPaid: (id: string, operationKey: string) => Promise<WithdrawalView>;
};

const withdrawing = (): WithdrawalView => ({
  id: "WD-TEST-001",
  promoter: "Nova · USR-1388",
  amount: "¥ 128.00",
  status: "WITHDRAWING",
  payoutAccountSnapshot: "银行卡 · 尾号 7890",
  requestedAt: "2026-09-04 09:30"
});

async function loginAndOpen(_role: "ADMIN" | "NORMAL_ADMIN" = "ADMIN", source: WithdrawalAdapter = createLocalAdminWithdrawalAdapter()) {
  const wrapper = mount(Panel, {attachTo:document.body,global:{provide:{[adminWithdrawalAdapterKey as symbol]:source}}});
  await nextTick();
  return {wrapper};
}

describe("local promotion withdrawal review", () => {
  it("replays completed adapter commands with the same operation semantics", async () => {
    const adapter = createLocalAdminWithdrawalAdapter([
      withdrawing(),
      { ...withdrawing(), id: "WD-TEST-002" },
      { ...withdrawing(), id: "WD-TEST-003", status: "PENDING_PAYOUT" }
    ]);

    const approved = await adapter.approve("WD-TEST-001", "operation-approve");
    expect(await adapter.approve("WD-TEST-001", "operation-approve")).toEqual(approved);
    expect(approved.status).toBe("PENDING_PAYOUT");

    const rejected = await adapter.reject("WD-TEST-002", "资料不完整", "operation-reject");
    expect(await adapter.reject("WD-TEST-002", "资料不完整", "operation-reject")).toEqual(rejected);
    expect(rejected).toMatchObject({ status: "REJECTED", rejectionReason: "资料不完整" });

    const paid = await adapter.markPaid("WD-TEST-003", "operation-paid");
    expect(await adapter.markPaid("WD-TEST-003", "operation-paid")).toEqual(paid);
    expect(paid.status).toBe("PAID");
  });

  it("rejects a reused operation key with conflicting action semantics without changing state", async () => {
    const adapter = createLocalAdminWithdrawalAdapter([
      withdrawing(),
      { ...withdrawing(), id: "WD-TEST-002" }
    ]);
    await adapter.approve("WD-TEST-001", "operation-conflict");
    await expect(adapter.markPaid("WD-TEST-001", "operation-conflict")).rejects.toThrow("operationKey conflicts");
    expect(adapter.getSnapshot().find((item) => item.id === "WD-TEST-001")?.status).toBe("PENDING_PAYOUT");

    await adapter.reject("WD-TEST-002", "资料不完整", "operation-reason");
    await expect(adapter.reject("WD-TEST-002", "账户不匹配", "operation-reason")).rejects.toThrow("operationKey conflicts");
    expect(adapter.getSnapshot().find((item) => item.id === "WD-TEST-002")).toMatchObject({
      status: "REJECTED",
      rejectionReason: "资料不完整"
    });
  });


  it("shows the required withdrawal snapshot fields and fixed status labels", async () => {
    const { wrapper } = await loginAndOpen();
    const headings = wrapper.findAll("th").map((item) => item.text());
    expect(headings).toEqual(["推广人", "金额", "状态", "收款账户", "申请时间", "拒绝原因", "操作"]);
    expect(wrapper.text()).toContain("银行卡 · 尾号 7890");
    expect(wrapper.text()).toContain("审核中");
    expect(wrapper.text()).toContain("待发放");
    expect(wrapper.text()).toContain("已发放");
    expect(wrapper.text()).toContain("已拒绝");
    expect(wrapper.text()).toContain("收款信息不完整");
  });

  it("approves to pending payout and persists the local state across routing", async () => {
    const source = createLocalAdminWithdrawalAdapter();
    const { wrapper } = await loginAndOpen("ADMIN", source);
    await wrapper.get('[data-testid="approve-WD-LOCAL-001"]').trigger("click");
    await flushPromises();
    expect(wrapper.get('[data-testid="status-WD-LOCAL-001"]').text()).toBe("待发放");
    expect(wrapper.find('[data-testid="approve-WD-LOCAL-001"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="reject-WD-LOCAL-001"]').exists()).toBe(false);
    expect(wrapper.get('[role="status"]').text()).toContain("已通过，等待线下发放");

    wrapper.unmount();
    const again = await loginAndOpen("ADMIN", source);
    expect(again.wrapper.get('[data-testid="status-WD-LOCAL-001"]').text()).toBe("待发放");
  });

  it("requires a nearby rejection reason and displays it after rejection", async () => {
    const { wrapper } = await loginAndOpen();
    await wrapper.get('[data-testid="reject-WD-LOCAL-001"]').trigger("click");
    const error = wrapper.get('[data-testid="rejection-error-WD-LOCAL-001"]');
    const input = wrapper.get('input[aria-label="WD-LOCAL-001 拒绝原因"]');
    expect(error.text()).toContain("必须填写拒绝原因");
    expect(input.attributes("aria-describedby")).toBe(error.attributes("id"));
    expect(wrapper.get('[data-testid="status-WD-LOCAL-001"]').text()).toBe("审核中");

    await input.setValue("收款信息不完整");
    await wrapper.get('[data-testid="reject-WD-LOCAL-001"]').trigger("click");
    await flushPromises();
    expect(wrapper.get('[data-testid="status-WD-LOCAL-001"]').text()).toBe("已拒绝");
    expect(wrapper.get('[data-testid="row-WD-LOCAL-001"]').text()).toContain("收款信息不完整");
  });

  it("marks pending payout as paid only after explicit offline-payment confirmation", async () => {
    const { wrapper } = await loginAndOpen();
    await wrapper.get('[data-testid="mark-paid-WD-LOCAL-002"]').trigger("click");
    expect(wrapper.get('[data-testid="paid-confirmation-WD-LOCAL-002"]').text()).toContain("线下款已实际完成后再操作");
    expect(wrapper.get('[data-testid="status-WD-LOCAL-002"]').text()).toBe("待发放");
    await wrapper.get('[data-testid="confirm-paid-WD-LOCAL-002"]').trigger("click");
    await flushPromises();
    expect(wrapper.get('[data-testid="status-WD-LOCAL-002"]').text()).toBe("已发放");
    expect(wrapper.find('[data-testid="mark-paid-WD-LOCAL-002"]').exists()).toBe(false);
    expect(wrapper.get('[role="status"]').text()).toContain("已标记为已发放");
  });

  it("keeps terminal records immutable and omits transfer evidence concepts", async () => {
    const { wrapper } = await loginAndOpen();
    expect(wrapper.get('[data-testid="row-WD-LOCAL-003"]').findAll("button")).toHaveLength(0);
    expect(wrapper.get('[data-testid="row-WD-LOCAL-004"]').findAll("button")).toHaveLength(0);
    expect(wrapper.text()).not.toMatch(/上传凭证|凭证URL|流水号|交易号|自动转账/);
  });

  it("locks an in-flight action so rapid clicks call the adapter once", async () => {
    let finish!: (view: WithdrawalView) => void;
    const approve = vi.fn(() => new Promise<WithdrawalView>((resolve) => { finish = resolve; }));
    const adapter: WithdrawalAdapter = {
      getSnapshot: () => [withdrawing()], approve, reject: vi.fn(), markPaid: vi.fn()
    };
    const { wrapper } = await loginAndOpen("ADMIN", adapter);
    const button = wrapper.get('[data-testid="approve-WD-TEST-001"]');
    await button.trigger("click");
    await button.trigger("click");
    expect(approve).toHaveBeenCalledTimes(1);
    expect(button.attributes("disabled")).toBeDefined();
    finish({ ...withdrawing(), status: "PENDING_PAYOUT" });
    await flushPromises();
    expect(wrapper.get('[data-testid="status-WD-TEST-001"]').text()).toBe("待发放");
  });

  it("reports an async failure without changing status and reuses its operation key on retry", async () => {
    const approve = vi.fn()
      .mockRejectedValueOnce(new Error("response unknown"))
      .mockResolvedValueOnce({ ...withdrawing(), status: "PENDING_PAYOUT" });
    const adapter: WithdrawalAdapter = {
      getSnapshot: () => [withdrawing()],
      approve,
      reject: vi.fn(),
      markPaid: vi.fn()
    };
    const { wrapper } = await loginAndOpen("ADMIN", adapter);
    await wrapper.get('[data-testid="approve-WD-TEST-001"]').trigger("click");
    await flushPromises();
    expect(wrapper.get('[data-testid="status-WD-TEST-001"]').text()).toBe("审核中");
    expect(wrapper.get('[role="status"]').text()).toContain("操作失败，请重试");
    await wrapper.get('[data-testid="approve-WD-TEST-001"]').trigger("click");
    await flushPromises();
    expect(approve).toHaveBeenCalledTimes(2);
    expect(approve.mock.calls[0]?.[1]).toMatch(/^withdrawal-operation-/);
    expect(approve.mock.calls[1]?.[1]).toBe(approve.mock.calls[0]?.[1]);
    expect(wrapper.get('[data-testid="status-WD-TEST-001"]').text()).toBe("待发放");
  });

  it("uses a semantic table, visible labels, and 44px action targets", async () => {
    const { wrapper } = await loginAndOpen();
    expect(wrapper.get("table").element.tagName).toBe("TABLE");
    expect(wrapper.get("caption").text()).toContain("提现审核列表");
    expect(wrapper.get('label[for="rejection-WD-LOCAL-001"]').text()).toContain("拒绝原因");
    const styles = readFileSync("src/styles.css", "utf8");
    expect(styles).toMatch(/\.withdrawal-review-actions[^}]*gap:\s*(?:[89]|[1-9]\d+)px/s);
    expect(styles).toMatch(/\.withdrawal-review-actions button[^}]*min-height:\s*44px/s);
  });
});

it('creates a sub-100 monthly payout and requires actual payment proof', async () => {
  const base = createLocalAdminWithdrawalAdapter([]);
  const row = { ...withdrawing(), id: 'MONTHLY-1', amount: '0.0100', status: 'PENDING_PAYOUT' as const, settlementMonth: '2026-08' };
  const create = vi.fn().mockResolvedValue(row), markPaid = vi.fn().mockResolvedValue({ ...row, status: 'PAID' });
  const source = { ...base, query: vi.fn().mockResolvedValue({ items: [row], total: 1, page: 0, size: 20 }), create, markPaid,
    promoters: vi.fn().mockResolvedValue({ items: [{ value: 'promoter-a', label: '真实推广人' }], total: 1 }),
    accounts: vi.fn().mockResolvedValue({ items: [{ value: 'account-a', label: '银行卡 · ***1234' }], total: 1 }) };
  const { wrapper } = await loginAndOpen('ADMIN', source); await flushPromises();
  const select = wrapper.findComponent({ name: 'AdminSearchSelect' }); await select.setValue('promoter-a'); await flushPromises();
  await wrapper.get('[aria-label="归属月份"]').setValue('2026-08'); await wrapper.get('[aria-label="发放金额"]').setValue('0.01'); await wrapper.get('[aria-label="收款账户"]').setValue('account-a');
  await wrapper.findAll('form')[0].trigger('submit'); await flushPromises();
  expect(create).toHaveBeenCalledWith('promoter-a', '2026-08', '0.01', 'account-a', undefined);
  await wrapper.get('[data-testid="mark-paid-MONTHLY-1"]').trigger('click'); await wrapper.get('[data-testid="confirm-paid-MONTHLY-1"]').trigger('click');
  expect(markPaid).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('转账参考号和实际付款时间');
  await wrapper.get('[aria-label="转账参考号"]').setValue('BANK-REF-1'); await wrapper.get('[aria-label="实际付款时间"]').setValue('2026-09-15T10:00');
  await wrapper.get('[data-testid="confirm-paid-MONTHLY-1"]').trigger('click'); await flushPromises();
  expect(markPaid).toHaveBeenCalledWith('MONTHLY-1', expect.any(String), { transferReference: 'BANK-REF-1', paidAt: new Date('2026-09-15T10:00').toISOString() });
});

it('resubmits a rejected month with the original payout id', async () => {
  const row = { ...withdrawing(), id: 'rejected-month', promoter: 'promoter-a', amount: '0.0100', status: 'REJECTED' as const, settlementMonth: '2026-08', payoutAccountId: 'account-a' };
  const source = { ...createLocalAdminWithdrawalAdapter([row]), query: vi.fn().mockResolvedValue({ items: [row], total: 1, page: 0, size: 20 }), create: vi.fn().mockResolvedValue(row), promoters: vi.fn().mockResolvedValue({ items: [{ value: 'promoter-a', label: '推广人' }], total: 1 }), accounts: vi.fn().mockResolvedValue({ items: [{ value: 'account-a', label: '账号' }], total: 1 }) };
  const { wrapper } = await loginAndOpen('ADMIN', source); await flushPromises();
  await wrapper.findAll('button').find(button => button.text() === '重新提交')!.trigger('click'); await flushPromises();
  await wrapper.findAll('form')[0].trigger('submit'); await flushPromises();
  expect(source.create).toHaveBeenCalledWith('promoter-a', '2026-08', '0.01', 'account-a', 'rejected-month');
});
