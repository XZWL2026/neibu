import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import { nextTick } from "vue";
import App from "./App.vue";
import { partnerRoutes } from "./router";
import { localPromotionSessionView } from "./promotion/localPromotionFixtures";
import { createLocalWithdrawalAdapter, type LocalWithdrawalView, type WithdrawalAdapter, type WithdrawalRequest, type WithdrawalResult } from "./promotion/localWithdrawalFixtures";

enableAutoUnmount(afterEach);

const withdrawal: LocalWithdrawalView & { pointDebt4: string } = {
  balance: {
    frozen4: "12.3499",
    available4: "150.0000",
    withdrawing4: "125.0000",
    cumulativePaid4: "430.5000",
    cashDebt4: "0.0000",
    minimumAmount2: "100.00"
  },
  items: [
    { id: "WD-1", amount2: "100.00", status: "WITHDRAWING", createdAt: "2026-08-30 09:00" },
    { id: "WD-2", amount2: "25.00", status: "PENDING_PAYOUT", createdAt: "2026-08-29 09:00" },
    { id: "WD-3", amount2: "88.00", status: "REJECTED", createdAt: "2026-08-28 09:00", rejectionReason: "收款信息需核对" },
    { id: "WD-4", amount2: "200.00", status: "PAID", createdAt: "2026-08-27 09:00" }
  ],
  pointDebt4: "999.0000"
};

async function show(adapter: Pick<WithdrawalAdapter, "submitWithdrawal"> & Partial<WithdrawalAdapter> = { submitWithdrawal: vi.fn() }, sessionWithdrawal: LocalWithdrawalView & { pointDebt4?: string } = withdrawal) {
  const router = createRouter({ history: createMemoryHistory(), routes: partnerRoutes });
  await router.push("/promotion/withdrawals"); await router.isReady();
  const session = { ...localPromotionSessionView, withdrawal: sessionWithdrawal };
  let unresolvedRequest: WithdrawalRequest | null = null;
  const withdrawalAdapter: WithdrawalAdapter = {
    getSnapshot: () => ({ balance: { ...sessionWithdrawal.balance }, items: sessionWithdrawal.items.map((item) => ({ ...item })), unresolvedRequest }),
    setUnresolvedRequest: (request) => { unresolvedRequest = request ? { ...request } : null; },
    ...adapter
  };
  const wrapper = mount(App, { attachTo: document.body, props: { session, withdrawalAdapter }, global: { plugins: [router] } });
  await nextTick();
  return { wrapper, adapter: withdrawalAdapter, router };
}

describe("local withdrawal workflow", () => {
  const submitted = (amount2: string, id: string): WithdrawalResult => ({
    balance: { ...withdrawal.balance, available4: "250.0000", withdrawing4: "225.0000" },
    withdrawal: { id, amount2, status: "WITHDRAWING", createdAt: "刚刚" }
  });

  it("shows four cash balances and the fixed withdrawal status wording", async () => {
    const { wrapper } = await show();
    expect(wrapper.get("h1").text()).toBe("佣金提现");
    expect(wrapper.text()).toContain("冻结佣金");
    expect(wrapper.text()).toContain("¥12.34");
    expect(wrapper.text()).toContain("可提现佣金");
    expect(wrapper.text()).toContain("提现中");
    expect(wrapper.text()).toContain("累计已提现");
    expect(wrapper.text()).toContain("审核中");
    expect(wrapper.text()).toContain("待发放");
    expect(wrapper.text()).toContain("已拒绝");
    expect(wrapper.text()).toContain("收款信息需核对");
    expect(wrapper.text()).toContain("已发放");
    expect(wrapper.text()).not.toContain("积分待追回");
    expect(wrapper.text()).not.toMatch(/凭证|流水号/);
  });

  it.each([
    ["99.99", "最低提现金额为 ¥100"],
    ["100.001", "提现金额最多保留两位小数"],
    ["150.01", "提现金额不能超过可提现佣金"]
  ])("rejects invalid amount %s without calling the adapter", async (amount, message) => {
    const submitWithdrawal = vi.fn();
    const { wrapper } = await show({ submitWithdrawal });
    await wrapper.get('input[aria-label="提现金额"]').setValue(amount);
    await wrapper.get("form").trigger("submit");
    expect(wrapper.get('[role="alert"]').text()).toContain(message);
    expect(submitWithdrawal).not.toHaveBeenCalled();
  });

  it("blocks cash debt without displaying point debt", async () => {
    const debtView = { ...withdrawal, balance: { ...withdrawal.balance, cashDebt4: "0.0001" } };
    const submitWithdrawal = vi.fn();
    const { wrapper } = await show({ submitWithdrawal }, debtView);
    expect(wrapper.text()).toContain("存在现金待追偿，暂时无法提现");
    expect(wrapper.text()).not.toContain("积分待追回");
    expect(wrapper.get('button[type="submit"]').attributes("disabled")).toBeDefined();
    expect(submitWithdrawal).not.toHaveBeenCalled();
  });

  it("locks rapid submits to one adapter call and refreshes balances from its result", async () => {
    let finish!: (value: WithdrawalResult) => void;
    const submitWithdrawal = vi.fn((_request: WithdrawalRequest) => new Promise<WithdrawalResult>((resolve) => { finish = resolve; }));
    const { wrapper } = await show({ submitWithdrawal });
    await wrapper.get('input[aria-label="提现金额"]').setValue("100.00");
    await wrapper.get("form").trigger("submit");
    await wrapper.get("form").trigger("submit");
    expect(submitWithdrawal).toHaveBeenCalledTimes(1);
    const request = submitWithdrawal.mock.calls[0]?.[0];
    expect(request).toMatchObject({ amount2: "100.00" });
    expect(request?.idempotencyKey).toBeTruthy();
    expect(wrapper.get('button[type="submit"]').attributes("disabled")).toBeDefined();

    finish({
      balance: { ...withdrawal.balance, available4: "50.0000", withdrawing4: "225.0000" },
      withdrawal: { id: "WD-NEW", amount2: "100.00", status: "WITHDRAWING", createdAt: "刚刚" }
    });
    await flushPromises();
    expect(wrapper.get('[data-testid="available-balance"]').text()).toContain("¥50");
    expect(wrapper.get('[data-testid="withdrawing-balance"]').text()).toContain("¥225");
    expect(wrapper.get('[role="status"]').text()).toContain("提现申请已提交");
    expect(wrapper.text()).toContain("WD-NEW");
  });

  it("reuses the idempotency key when an uncertain failure retries the same amount", async () => {
    const submitWithdrawal = vi.fn()
      .mockRejectedValueOnce(new Error("response lost"))
      .mockResolvedValueOnce(submitted("100.00", "WD-RETRY"));
    const { wrapper } = await show({ submitWithdrawal });
    await wrapper.get('input[aria-label="提现金额"]').setValue("100.00");
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(submitWithdrawal).toHaveBeenCalledTimes(2);
    expect(submitWithdrawal.mock.calls[1]?.[0].idempotencyKey).toBe(submitWithdrawal.mock.calls[0]?.[0].idempotencyKey);
    expect(wrapper.get('[role="status"]').text()).toContain("提现申请已提交");
  });

  it("locks an uncertain amount to the same idempotency key until the request succeeds", async () => {
    const submitWithdrawal = vi.fn()
      .mockRejectedValueOnce(new Error("response lost"))
      .mockResolvedValueOnce(submitted("100.00", "WD-RETRY"))
      .mockResolvedValueOnce(submitted("120.00", "WD-AFTER-SUCCESS"));
    const { wrapper } = await show({ submitWithdrawal });
    const input = wrapper.get('input[aria-label="提现金额"]');
    await input.setValue("100.00");
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(input.attributes("disabled")).toBeDefined();
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(input.attributes("disabled")).toBeUndefined();
    await input.setValue("120.00");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    const keys = submitWithdrawal.mock.calls.map(([request]) => request.idempotencyKey);
    expect(submitWithdrawal.mock.calls[1]?.[0].amount2).toBe("100.00");
    expect(keys[1]).toBe(keys[0]);
    expect(keys[2]).not.toBe(keys[1]);
  });

  it("restores an uncertain request after leaving and retries the same amount and idempotency key", async () => {
    let unresolvedRequest: WithdrawalRequest | null = null;
    const submitWithdrawal = vi.fn()
      .mockRejectedValueOnce(new Error("response lost"))
      .mockResolvedValueOnce(submitted("100.00", "WD-ROUTE-RETRY"));
    const adapter = {
      getSnapshot: () => ({
        balance: { ...withdrawal.balance },
        items: withdrawal.items.map((item) => ({ ...item })),
        unresolvedRequest: unresolvedRequest ? { ...unresolvedRequest } : null
      }),
      setUnresolvedRequest: (request: WithdrawalRequest | null) => {
        unresolvedRequest = request ? { ...request } : null;
      },
      submitWithdrawal
    } as WithdrawalAdapter;
    const { wrapper, router } = await show(adapter);
    await wrapper.get('input[aria-label="提现金额"]').setValue("100.00");
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    const firstRequest = submitWithdrawal.mock.calls[0]?.[0];

    await router.push("/promotion/overview");
    await nextTick();
    await router.push("/promotion/withdrawals");
    await nextTick();

    const restoredInput = wrapper.get('input[aria-label="提现金额"]');
    expect((restoredInput.element as HTMLInputElement).value).toBe("100.00");
    expect(restoredInput.attributes("disabled")).toBeDefined();
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(submitWithdrawal).toHaveBeenCalledTimes(2);
    expect(submitWithdrawal.mock.calls[1]?.[0]).toEqual(firstRequest);
    expect(wrapper.text()).toContain("WD-ROUTE-RETRY");

    await router.push("/promotion/overview");
    await nextTick();
    await router.push("/promotion/withdrawals");
    await nextTick();
    const settledInput = wrapper.get('input[aria-label="提现金额"]');
    expect((settledInput.element as HTMLInputElement).value).toBe("");
    expect(settledInput.attributes("disabled")).toBeUndefined();
  });

  it("allows an uncertain request retry when cash debt appears while the page is away", async () => {
    let unresolvedRequest: WithdrawalRequest | null = null;
    let cashDebt4 = "0.0000";
    const submitWithdrawal = vi.fn()
      .mockRejectedValueOnce(new Error("response lost"))
      .mockResolvedValueOnce(submitted("100.00", "WD-DEBT-RETRY"));
    const adapter = {
      getSnapshot: () => ({
        balance: { ...withdrawal.balance, cashDebt4 },
        items: withdrawal.items.map((item) => ({ ...item })),
        unresolvedRequest: unresolvedRequest ? { ...unresolvedRequest } : null
      }),
      setUnresolvedRequest: (request: WithdrawalRequest | null) => {
        unresolvedRequest = request ? { ...request } : null;
      },
      submitWithdrawal
    } as WithdrawalAdapter;
    const { wrapper, router } = await show(adapter);
    await wrapper.get('input[aria-label="提现金额"]').setValue("100.00");
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    const firstRequest = submitWithdrawal.mock.calls[0]?.[0];

    cashDebt4 = "1.0000";
    await router.push("/promotion/overview");
    await nextTick();
    await router.push("/promotion/withdrawals");
    await nextTick();

    expect(wrapper.get('[role="alert"]').text()).toContain("存在现金待追偿");
    expect(wrapper.get('input[aria-label="提现金额"]').attributes("disabled")).toBeDefined();
    expect(wrapper.get('button[type="submit"]').attributes("disabled")).toBeUndefined();
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(submitWithdrawal).toHaveBeenCalledTimes(2);
    expect(submitWithdrawal.mock.calls[1]?.[0]).toEqual(firstRequest);
    expect(wrapper.text()).toContain("WD-DEBT-RETRY");
  });

  it("reads adapter state after a pending submission settles while the page is away", async () => {
    let snapshot: LocalWithdrawalView = {
      balance: { ...withdrawal.balance },
      items: withdrawal.items.map((item) => ({ ...item }))
    };
    let finish!: (result: WithdrawalResult) => void;
    const adapter = {
      getSnapshot: () => ({ balance: { ...snapshot.balance }, items: snapshot.items.map((item) => ({ ...item })), unresolvedRequest: null }),
      setUnresolvedRequest: () => undefined,
      submitWithdrawal: vi.fn(() => new Promise<WithdrawalResult>((resolve) => {
        finish = (result) => {
          snapshot = { balance: { ...result.balance }, items: [{ ...result.withdrawal }, ...snapshot.items] };
          resolve(result);
        };
      }))
    } as WithdrawalAdapter;
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const { wrapper, router } = await show(adapter);
    await wrapper.get('input[aria-label="提现金额"]').setValue("100.00");
    await wrapper.get("form").trigger("submit");
    await router.push("/promotion/overview");
    await nextTick();
    finish({
      balance: { ...withdrawal.balance, available4: "50.0000", withdrawing4: "225.0000" },
      withdrawal: { id: "WD-AWAY", amount2: "100.00", status: "WITHDRAWING", createdAt: "刚刚" }
    });
    await flushPromises();
    await router.push("/promotion/withdrawals");
    await nextTick();

    expect(wrapper.get('[data-testid="available-balance"]').text()).toContain("¥50");
    expect(wrapper.text()).toContain("WD-AWAY");
    expect(consoleError).not.toHaveBeenCalled();
  });

  it("keeps a successful default-adapter withdrawal after leaving and returning", async () => {
    const adapter = createLocalWithdrawalAdapter();
    const { wrapper, router } = await show(adapter, localPromotionSessionView.withdrawal!);
    await wrapper.get('input[aria-label="提现金额"]').setValue("100.00");
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(wrapper.text()).toContain("WD-LOCAL-005");

    await router.push("/promotion/overview");
    await nextTick();
    await router.push("/promotion/withdrawals");
    await nextTick();

    expect(wrapper.get('[data-testid="available-balance"]').text()).toContain("¥50");
    expect(wrapper.get('[data-testid="withdrawing-balance"]').text()).toContain("¥225");
    expect(wrapper.text()).toContain("WD-LOCAL-005");
  });
});
