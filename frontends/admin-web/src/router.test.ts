import { fixtureSession } from "./fixtureSession.test-support";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import { adminNavigation } from "./navigation";
import { adminRouter, adminRoutes } from "./router";
import App from "./App.vue";

enableAutoUnmount(afterEach);

const make = () => createRouter({ history: createMemoryHistory(), routes: adminRoutes });
async function show(path: string) {
  const router = make(); await router.push(path); await router.isReady();
  const wrapper = mount(App, { props: { session: fixtureSession() }, global: { plugins: [router] } });
  await flushPromises();
  return { router, wrapper };
}

describe("admin business routes", () => {
  it("keeps the complete confirmed operations inventory", () => {
    expect(adminRoutes.map(({ path }) => path)).toEqual([
      "/api", "/api/login", "/api/portal-entry", "/", "/admin/portal-entry", "/admin/login", "/admin/overview", "/admin/users", "/admin/users/:userRef/status", "/admin/users/:userRef",
      "/admin/agents", "/admin/agents/:agentRef", "/admin/api-users", "/admin/api-users/:userRef",
      "/admin/keys", "/admin/keys/:keyRef", "/admin/payments", "/admin/recharges/corporate", "/admin/recharges/review", "/admin/recharges/online", "/admin/consumption", "/admin/consumption/:recordRef", "/admin/provider-logs", "/admin/media", "/admin/media/:taskRef",
      "/admin/promotions/applications", "/admin/promotions/attributions", "/admin/promotions/withdrawals", "/admin/promotions/withdrawal-report",
      "/admin/promotions/benefit-settings", "/admin/promotions/reconciliation", "/admin/promotions/benefit-audit", "/admin/pricing", "/admin/providers", "/admin/admins",
      "/promoter/overview", "/promoter/users", "/promoter/consumption", "/promoter/settlements",
      "/promoter/account", "/api/dashboard", "/api/keys", "/api/consumption", "/api/billing",
      "/api/transfer", "/api/docs", "/api/account", "/:pathMatch(.*)*"
    ]);
    expect(adminNavigation.map((item) => item.to)).toEqual([
      "/admin/overview", "/admin/users", "/admin/agents", "/admin/api-users", "/admin/consumption", "/admin/provider-logs", "/admin/payments", "/admin/recharges/review",
      "/admin/promotions/attributions", "/admin/promotions/withdrawals",
      "/admin/promotions/benefit-settings",
      "/admin/providers", "/admin/pricing", "/admin/admins"
    ]);
    expect(adminRouter.options.history.base).toBe("");
  });

  it("redirects root", async () => {
    const router = make(); await router.push("/"); await router.isReady();
    expect(router.currentRoute.value.path).toBe("/admin/login");
  });

  it.each([
    ["/admin/login", "后台登录"], ["/admin/overview", "运营概览"], ["/admin/users", "用户管理"],
    ["/admin/users/USR-1388/status", "用户管理"], ["/admin/keys", "用户管理"],
    ["/admin/payments", "无权限"], ["/admin/media", "无权限"], ["/admin/pricing", "无权限"],
    ["/admin/admins", "无权限"], ["/missing", "页面不存在"]
  ])("opens %s", async (path, title) => {
    const { wrapper } = await show(path);
    expect(wrapper.get("h1").text()).toBe(title);
  });

  it("supports a custom deployment base", async () => {
    const router = createRouter({ history: createMemoryHistory("/ops/"), routes: adminRoutes });
    await router.push("/admin/media"); await router.isReady();
    expect(router.resolve("/admin/media").href).toBe("/ops/admin/media");
  });

  it("redirects a legacy user status deep link to the real detail route", async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: adminRoutes });
    await router.push("/admin/users/USR-1388/status"); await router.isReady();
    const wrapper = mount(App, { props: { session: fixtureSession() }, global: { plugins: [router] } });
    expect(wrapper.get("h1").text()).toBe("用户管理");
  });

  it("registers isolated promoter and API route namespaces", () => {
    expect(adminRoutes.map(({ path }) => path)).toEqual([
      "/api", "/api/login", "/api/portal-entry", "/", "/admin/portal-entry", "/admin/login", "/admin/overview", "/admin/users", "/admin/users/:userRef/status", "/admin/users/:userRef",
      "/admin/agents", "/admin/agents/:agentRef", "/admin/api-users", "/admin/api-users/:userRef",
      "/admin/keys", "/admin/keys/:keyRef", "/admin/payments", "/admin/recharges/corporate", "/admin/recharges/review", "/admin/recharges/online", "/admin/consumption", "/admin/consumption/:recordRef", "/admin/provider-logs", "/admin/media", "/admin/media/:taskRef",
      "/admin/promotions/applications", "/admin/promotions/attributions", "/admin/promotions/withdrawals", "/admin/promotions/withdrawal-report",
      "/admin/promotions/benefit-settings", "/admin/promotions/reconciliation", "/admin/promotions/benefit-audit", "/admin/pricing", "/admin/providers", "/admin/admins",
      "/promoter/overview", "/promoter/users", "/promoter/consumption", "/promoter/settlements",
      "/promoter/account", "/api/dashboard", "/api/keys", "/api/consumption", "/api/billing",
      "/api/transfer", "/api/docs", "/api/account", "/:pathMatch(.*)*"
    ]);
  });

  it("shows permission state on direct role-mismatched links", async () => {
    const { wrapper } = await show("/promoter/users");
    expect(wrapper.get("h1").text()).toBe("无权限");
    expect(wrapper.text()).not.toContain("邀请用户");
  });
});
