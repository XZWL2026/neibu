import { fixtureSession } from "./fixtureSession.test-support";
import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import { nextTick } from "vue";
import App from "./App.vue";
import { adminRoutes } from "./router";

afterEach(() => localStorage.clear());

async function open(path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: adminRoutes });
  await router.push(path);
  await router.isReady();
  const wrapper = mount(App, { props: { session: fixtureSession() }, global: { plugins: [router] } });
  return { router, wrapper };
}

async function openPromoter(path = "/promoter/overview") {
  const page = await open("/admin/login");
  await page.wrapper.get('input[autocomplete="username"]').setValue("promoter.demo");
  await page.wrapper.get("form").trigger("submit");
  await flushPromises();
  await page.router.push(path);
  await nextTick();
  return page;
}

describe("agent and promotion settlement loop", () => {
  it("registers an agent list and detail route", () => {
    expect(adminRoutes.map(({ name }) => name)).toContain("admin-agents");
    expect(adminRoutes.map(({ name }) => name)).toContain("admin-agent-detail");
  });

  it("registers separated recharge and detail routes", () => {
    expect(adminRoutes.map(({ name }) => name)).toEqual(expect.arrayContaining([
      "admin-recharge-review", "admin-online-recharges", "admin-consumption", "admin-media-detail"
    ]));
  });

  it("does not expose consumption fixtures before administrator authentication", async () => {
    const ledger = await open("/admin/consumption");
    expect(ledger.wrapper.text()).toContain("无权限");
    expect(ledger.wrapper.text()).not.toContain("CON-0829");
  });

  it("does not expose promotion fixtures as user management data", async () => {
    localStorage.setItem("xzwl.admin.users", JSON.stringify([{ name: "cached-promotion-user" }]));
    const admin = await open("/admin/users");
    await flushPromises();
    expect(admin.wrapper.get("h1").text()).toBe("用户管理");
    expect(admin.wrapper.text()).not.toContain("cached-promotion-user");
    expect(admin.wrapper.text()).not.toContain("累计充值");
  });



  it("preserves API financial and point columns without a bound owner fixture", async () => {
    const api = await open("/admin/login");
    await api.wrapper.get('input[autocomplete="username"]').setValue("api.demo");
    await api.wrapper.get("form").trigger("submit"); await flushPromises();
    expect(api.wrapper.text()).toContain("充值总额"); expect(api.wrapper.text()).toContain("缺少业务用户");
    expect(api.wrapper.text()).not.toContain("成功率");
    await api.router.push("/api/consumption"); await nextTick();
    expect(api.wrapper.text()).toContain("余额前"); expect(api.wrapper.text()).toContain("积分后");
    api.wrapper.unmount();
  });

  it("preserves promoter overview financial categories while unknown summaries stay unavailable", async () => {
    const promoter = await openPromoter();
    for (const label of ["总消耗", "释放金额", "总充值", "冻结金额", "总佣金", "提现佣金", "消费记录", "充值记录", "发放记录"]) expect(promoter.wrapper.text()).toContain(label);
    expect(promoter.wrapper.find('.withdrawal-records').exists()).toBe(true);
    expect(promoter.wrapper.text()).toContain("缺少业务用户"); promoter.wrapper.unmount();
  });

  it("keeps month filtering limited to server records instead of resurrecting withdrawal fixtures", async () => {
    localStorage.setItem("xzwl.promoter.settlements", JSON.stringify([{ id: "private-cached-withdrawal", commission: "¥ 500" }]));
    const promoter = await openPromoter("/promoter/settlements");
    await promoter.wrapper.get('[data-testid="settlement-withdrawal-month"]').setValue("2026-07");
    expect(promoter.wrapper.text()).toContain("按本地月份筛选全部记录");
    expect(promoter.wrapper.text()).not.toMatch(/private-cached-withdrawal|WD-20260730|WD-20260829/); promoter.wrapper.unmount();
  });

  it("renders compact promoter columns without exposing data to an unbound identity", async () => {
    const promoter = await openPromoter("/promoter/users");
    for (const field of ["用户", "手机号", "注册时间", "状态", "操作"]) expect(promoter.wrapper.text()).toContain(field);
    await promoter.router.push("/promoter/consumption"); await nextTick();
    for (const field of ["用户", "消费类型", "消费金额", "佣金", "积分扣减", "时间", "操作"]) expect(promoter.wrapper.text()).toContain(field);
    promoter.wrapper.unmount();
  });

  it("keeps the legacy video route without exposing supplier fixture fields", async () => {
    const media = await open("/admin/media/VID-0829-063");
    expect(media.router.currentRoute.value.path).toBe("/admin/consumption/VID-0829-063");
    expect(media.wrapper.text()).not.toMatch(/火山任务 ID|腾讯任务 ID|TOS 视频|COS 视频/);
  });

  it("never grants withdrawal success or financial persistence without a bound real session", async () => {
    const first = await openPromoter("/promoter/settlements");
    expect(first.wrapper.find('[data-testid="withdrawal-amount"]').exists()).toBe(false);
    expect(first.wrapper.find('[data-testid="withdrawal-submit"]').exists()).toBe(false);
    expect(first.wrapper.text()).not.toContain("提现申请已提交"); expect(localStorage.getItem("xzwl.promoter.settlements")).toBeNull();
    first.wrapper.unmount(); const second = await openPromoter("/promoter/settlements");
    expect(second.wrapper.text()).not.toContain("¥ 500"); second.wrapper.unmount();
  });

  it("shows the reference bank form without fake saves, uploads or automatic payout promises", async () => {
    const promoter = await openPromoter("/promoter/account");
    for (const label of ["收款账户", "持卡人姓名", "银行卡号", "开户银行", "账户状态"]) expect(promoter.wrapper.text()).toContain(label);
    expect(promoter.wrapper.text()).not.toContain("每月 15 日自动发放");
    expect(promoter.wrapper.find('input[type="file"]').exists()).toBe(false);
    expect(promoter.wrapper.get('fieldset').attributes("disabled")).toBeDefined();
    expect(localStorage.getItem("xzwl.promoter.payout-methods")).toBeNull(); promoter.wrapper.unmount();
  });

});
