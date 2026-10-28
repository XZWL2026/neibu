import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import App from "./App.vue";
import { partnerRoutes } from "./router";

enableAutoUnmount(afterEach);

type AccountInput =
  | { type: "BANK_CARD"; holderName: string; cardNumber: string; bankName: string }
  | { type: "ALIPAY"; realName: string; account: string; qrCode?: string }
  | { type: "WECHAT"; realName: string; phone: string; qrCode?: string };

type AccountView = {
  id: string;
  type: AccountInput["type"];
  ownerName: string;
  maskedAccount: string;
  qrConfigured: boolean;
  status: "ACTIVE" | "DISABLED";
};

type AccountAdapter = {
  getSnapshot: () => readonly AccountView[];
  createAccount: (input: AccountInput) => Promise<AccountView>;
  updateAccount: (id: string, input: AccountInput) => Promise<AccountView>;
  disableAccount: (id: string) => Promise<AccountView>;
};

async function show(adapter?: AccountAdapter) {
  const router = createRouter({ history: createMemoryHistory(), routes: partnerRoutes });
  await router.push("/promotion/payout-accounts");
  await router.isReady();
  const props = adapter ? { payoutAccountAdapter: adapter } : {};
  const wrapper = mount(App, { attachTo: document.body, props: props as never, global: { plugins: [router] } });
  await nextTick();
  return { router, wrapper };
}

async function fillBankCard(wrapper: Awaited<ReturnType<typeof show>>["wrapper"], cardNumber = "6222021234567890") {
  await wrapper.get('input[aria-label="持卡人"]').setValue("张三");
  await wrapper.get('input[aria-label="银行卡号"]').setValue(cardNumber);
  await wrapper.get('input[aria-label="开户银行"]').setValue("测试银行");
}

async function submit(wrapper: Awaited<ReturnType<typeof show>>["wrapper"]) {
  await wrapper.get("form").trigger("submit");
  await flushPromises();
}

describe("local payout account workflow", () => {
  it("supports the payout-account deep link with visible labels", async () => {
    const { wrapper } = await show();
    expect(wrapper.get("h1").text()).toBe("收款账户");
    expect(wrapper.get('a[aria-current="page"]').text()).toContain("收款账户");
    expect(wrapper.get('label[for="payout-account-type"]').text()).toContain("收款类型");
    expect(wrapper.get('label[for="payout-holder-name"]').text()).toContain("持卡人");
    expect(wrapper.get('label[for="payout-card-number"]').text()).toContain("银行卡号");
    expect(wrapper.get('label[for="payout-bank-name"]').text()).toContain("开户银行");
  });

  it("creates all three account types and only renders masked display data", async () => {
    const { wrapper } = await show();
    await fillBankCard(wrapper);
    await submit(wrapper);
    expect(wrapper.text()).toContain("尾号 7890");
    expect(wrapper.text()).not.toContain("6222021234567890");

    await wrapper.get('select[aria-label="收款类型"]').setValue("ALIPAY");
    await wrapper.get('input[aria-label="支付宝实名"]').setValue("李四");
    await wrapper.get('input[aria-label="支付宝账号"]').setValue("alice@example.com");
    await wrapper.get('input[aria-label="支付宝收款码"]').setValue("alipay-secret-code");
    await submit(wrapper);
    expect(wrapper.text()).toContain("a***@example.com");
    expect(wrapper.text()).not.toContain("alice@example.com");
    expect(wrapper.text()).not.toContain("alipay-secret-code");
    expect(wrapper.text()).toContain("已配置");

    await wrapper.get('select[aria-label="收款类型"]').setValue("WECHAT");
    await wrapper.get('input[aria-label="微信实名"]').setValue("王五");
    await wrapper.get('input[aria-label="绑定手机号"]').setValue("13812345678");
    await submit(wrapper);
    expect(wrapper.text()).toContain("138****5678");
    expect(wrapper.text()).not.toContain("13812345678");
    expect(wrapper.text()).toContain("未配置");
    expect(wrapper.findAll(".payout-account-row")).toHaveLength(3);
  });

  it("clears fields on type change and never mixes another type into the payload", async () => {
    const createAccount = vi.fn().mockResolvedValue({
      id: "PA-1", type: "ALIPAY", ownerName: "李四", maskedAccount: "a***@example.com", qrConfigured: false, status: "ACTIVE"
    });
    const adapter: AccountAdapter = {
      getSnapshot: () => [], createAccount,
      updateAccount: vi.fn(), disableAccount: vi.fn()
    };
    const { wrapper } = await show(adapter);
    await fillBankCard(wrapper);
    await wrapper.get('select[aria-label="收款类型"]').setValue("ALIPAY");
    expect(wrapper.find('input[aria-label="银行卡号"]').exists()).toBe(false);
    await wrapper.get('input[aria-label="支付宝实名"]').setValue("李四");
    await wrapper.get('input[aria-label="支付宝账号"]').setValue("alice@example.com");
    await submit(wrapper);

    expect(createAccount).toHaveBeenCalledWith({
      type: "ALIPAY", realName: "李四", account: "alice@example.com", qrCode: undefined
    });
    expect(JSON.stringify(createAccount.mock.calls[0]?.[0])).not.toMatch(/holderName|cardNumber|bankName|phone/);
  });

  it.each([
    ["BANK_CARD", "银行卡号", "123abc", "银行卡号应为 10 至 32 位数字"],
    ["WECHAT", "绑定手机号", "12800000000", "请输入有效的中国大陆手机号"],
    ["ALIPAY", "支付宝实名", "名".repeat(101), "支付宝实名不能超过 100 个字符"]
  ])("rejects invalid %s input near %s", async (type, label, value, message) => {
    const createAccount = vi.fn();
    const adapter: AccountAdapter = {
      getSnapshot: () => [], createAccount,
      updateAccount: vi.fn(), disableAccount: vi.fn()
    };
    const { wrapper } = await show(adapter);
    await wrapper.get('select[aria-label="收款类型"]').setValue(type);
    if (type === "BANK_CARD") {
      await wrapper.get('input[aria-label="持卡人"]').setValue("张三");
      await wrapper.get('input[aria-label="开户银行"]').setValue("测试银行");
    } else if (type === "WECHAT") {
      await wrapper.get('input[aria-label="微信实名"]').setValue("王五");
    } else {
      await wrapper.get('input[aria-label="支付宝账号"]').setValue("alice@example.com");
    }
    await wrapper.get(`input[aria-label="${label}"]`).setValue(value);
    await submit(wrapper);

    const error = wrapper.get('[role="alert"]');
    expect(error.text()).toContain(message);
    expect(wrapper.get(`input[aria-label="${label}"]`).attributes("aria-describedby")).toBe(error.attributes("id"));
    expect(createAccount).not.toHaveBeenCalled();
  });

  it("updates an account and replaces its masked display", async () => {
    const { wrapper } = await show();
    await fillBankCard(wrapper);
    await submit(wrapper);
    expect(wrapper.text()).toContain("尾号 7890");

    await wrapper.get('button[aria-label^="修改收款账户"]').trigger("click");
    await fillBankCard(wrapper, "6222020000004321");
    await submit(wrapper);

    expect(wrapper.text()).toContain("尾号 4321");
    expect(wrapper.text()).not.toContain("尾号 7890");
    expect(wrapper.get('[role="status"]').text()).toContain("收款账户已更新");
  });

  it("confirms before disabling and never offers a second disable", async () => {
    const { wrapper } = await show();
    await fillBankCard(wrapper);
    await submit(wrapper);
    await wrapper.get('button[aria-label^="停用收款账户"]').trigger("click");
    expect(wrapper.text()).toContain("确认停用这个收款账户？");
    await wrapper.get('button[aria-label^="确认停用收款账户"]').trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("已停用");
    expect(wrapper.find('button[aria-label^="停用收款账户"]').exists()).toBe(false);
    expect(wrapper.get('[role="status"]').text()).toContain("收款账户已停用");
  });

  it("leaves edit mode and clears sensitive fields when the edited account is disabled", async () => {
    const { wrapper } = await show();
    await fillBankCard(wrapper);
    await submit(wrapper);

    await wrapper.get('button[aria-label^="修改收款账户"]').trigger("click");
    await fillBankCard(wrapper, "6222020000004321");
    await wrapper.get('button[aria-label^="停用收款账户"]').trigger("click");
    await wrapper.get('button[aria-label^="确认停用收款账户"]').trigger("click");
    await flushPromises();

    expect(wrapper.get(".payout-form-heading h2").text()).toBe("新增收款账户");
    expect(wrapper.get('input[aria-label="持卡人"]').element).toHaveProperty("value", "");
    expect(wrapper.get('input[aria-label="银行卡号"]').element).toHaveProperty("value", "");
    expect(wrapper.get('input[aria-label="开户银行"]').element).toHaveProperty("value", "");
    expect(wrapper.get(".payout-account-row").text()).toContain("已停用");
  });

  it("locks an in-flight create so rapid submits call the adapter once", async () => {
    let finish!: (view: AccountView) => void;
    const createAccount = vi.fn(() => new Promise<AccountView>((resolve) => { finish = resolve; }));
    const adapter: AccountAdapter = {
      getSnapshot: () => [], createAccount,
      updateAccount: vi.fn(), disableAccount: vi.fn()
    };
    const { wrapper } = await show(adapter);
    await fillBankCard(wrapper);
    await wrapper.get("form").trigger("submit");
    await wrapper.get("form").trigger("submit");
    expect(createAccount).toHaveBeenCalledTimes(1);
    expect(wrapper.get('button[type="submit"]').attributes("disabled")).toBeDefined();

    finish({ id: "PA-LOCK", type: "BANK_CARD", ownerName: "张三", maskedAccount: "尾号 7890", qrConfigured: false, status: "ACTIVE" });
    await flushPromises();
    expect(wrapper.get('[role="status"]').text()).toContain("收款账户已添加");
  });

  it("shows a retryable text error when saving fails", async () => {
    const adapter: AccountAdapter = {
      getSnapshot: () => [], createAccount: vi.fn().mockRejectedValue(new Error("offline")),
      updateAccount: vi.fn(), disableAccount: vi.fn()
    };
    const { wrapper } = await show(adapter);
    await fillBankCard(wrapper);
    await submit(wrapper);
    expect(wrapper.get('[role="alert"]').text()).toContain("保存失败，请检查后重试");
  });
});
