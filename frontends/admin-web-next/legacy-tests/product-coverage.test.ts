import { fixtureSession } from "./fixtureSession.test-support";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { afterEach, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import * as matchers from "vitest-axe/matchers";
import { createMemoryHistory, createRouter } from "vue-router";
import App from "./App.vue";
import { adminRoutes } from "./router";

expect.extend(matchers);
enableAutoUnmount(afterEach);
afterEach(() => { vi.unstubAllGlobals(); });

async function renderRoute(path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: adminRoutes });
  await router.push(path);
  await router.isReady();
  const wrapper = mount(App, { props: { session: fixtureSession() }, attachTo: document.body, global: { plugins: [router] } });
  return { router, wrapper };
}

async function expectAccessible(path: string) {
  const { wrapper } = await renderRoute(path);
  expect(await axe(wrapper.element, { rules: { "color-contrast": { enabled: false } } })).toHaveNoViolations();
  wrapper.unmount();
}

it("does not substitute local users when the real directory is unavailable", async () => {
  const router = createRouter({ history: createMemoryHistory(), routes: adminRoutes });
  await router.push("/admin/users");
  await router.isReady();
  const wrapper = mount(App, { props: { session: fixtureSession() }, global: { plugins: [router] } });
  expect(wrapper.get("h1").text()).toBe("用户管理");
  await flushPromises();
  expect(wrapper.findAll("tbody tr")).toHaveLength(0);
  expect(wrapper.text()).toContain("无法读取用户列表");
});

it("redirects the old production user detail route", async () => {
  const { router, wrapper } = await renderRoute("/admin/users/USR-1388/status");
  expect(router.currentRoute.value.path).toBe("/admin/users/USR-1388");
  expect(wrapper.get("h1").text()).toBe("用户管理");
});

it("shows user lookup failures without fabricating local records", async () => {
  const network = vi.fn(() => { throw new Error("network access is forbidden"); });
  vi.stubGlobal("fetch", network);
  const router = createRouter({ history: createMemoryHistory(), routes: adminRoutes });
  await router.push("/admin/login");
  await router.isReady();
  const login = mount(App, { props: { session: fixtureSession() }, global: { plugins: [router] } });
  expect(login.get("main").element).toBeTruthy();
  login.unmount();
  for (const path of ["/admin/users", "/admin/users/USR-1388/status", "/admin/pricing"]) {
    const { wrapper } = await renderRoute(path);
    expect(wrapper.get("main").element).toBeTruthy();
    await flushPromises();
    expect(wrapper.text()).not.toContain("Nova");
    wrapper.unmount();
  }
});

it("remains accessible on production admin login, table, detail, and form routes", async () => {
  const router = createRouter({ history: createMemoryHistory(), routes: adminRoutes });
  await router.push("/admin/login");
  await router.isReady();
  const wrapper = mount(App, { props: { session: fixtureSession() }, global: { plugins: [router] } });
  expect(await axe(wrapper.element, { rules: { "color-contrast": { enabled: false } } })).toHaveNoViolations();
  wrapper.unmount();
  await expectAccessible("/admin/users");
  await expectAccessible("/admin/users/USR-1388/status");
  const pricing = await renderRoute("/admin/login");
  await pricing.wrapper.get("form").trigger("submit");
  await flushPromises();
  await pricing.router.push("/admin/pricing");
  expect(await axe(pricing.wrapper.element, { rules: { "color-contrast": { enabled: false } } })).toHaveNoViolations();
  pricing.wrapper.unmount();
});
