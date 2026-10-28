import { fixtureSession } from "./fixtureSession.test-support";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import * as matchers from "vitest-axe/matchers";
import { createMemoryHistory, createRouter } from "vue-router";
import { nextTick, ref } from "vue";
import App from "./App.vue";
import { adminRoutes } from "./router";

expect.extend(matchers);
enableAutoUnmount(afterEach);

it.each(['primary.admin', 'content.ops'])('keeps compact management navigation role-aware for %s', async username => {
  const session = fixtureSession(); session.authenticate(username);
  const placeholder = { template: '<h1>管理页面</h1>' };
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/admin/users', name: 'admin-users', component: placeholder },
    { path: '/admin/agents/:agentRef', name: 'admin-agent-detail', component: placeholder },
    { path: '/admin/admins', name: 'admin-admins', component: placeholder }
  ] });
  await router.push('/admin/agents/agent-example'); await router.isReady();
  const scroll = vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  const wrapper = mount(App, { props: { session }, attachTo: document.body, global: { plugins: [router] } });
  await flushPromises();
  const select = wrapper.get<HTMLSelectElement>('.management-mobile-nav select');
  expect(select.element.value).toBe('/admin/agents');
  expect(wrapper.get('.primary-nav a[aria-current="page"]').text()).toBe('代理管理');
  expect(select.find('option[value="/admin/admins"]').exists()).toBe(username === 'primary.admin');
  expect(select.find('option[value="/admin/pricing"]').exists()).toBe(username === 'primary.admin');
  await select.setValue('/admin/users'); await flushPromises();
  expect(router.currentRoute.value.path).toBe('/admin/users');
  expect(document.activeElement).toBe(wrapper.get('main').element);
  expect(scroll).toHaveBeenCalledWith(0, 0);
  if (username === 'primary.admin') {
    await select.setValue('/admin/admins'); await flushPromises();
    expect(wrapper.classes()).toContain('overview-theme');
  }
  scroll.mockRestore();
});
afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = ""; localStorage.clear(); });
async function app(path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: adminRoutes });
  await router.push(path); await router.isReady();
  const wrapper = mount(App, { props: { session: fixtureSession() }, attachTo: document.body, global: { plugins: [router] } });
  await nextTick(); return { router, wrapper };
}
const audit = async (node: Element) => (expect(await axe(node, { rules: { "color-contrast": { enabled: false } } })) as unknown as { toHaveNoViolations(): void }).toHaveNoViolations();

describe("admin product shell", () => {
  it("renders local data, stays offline, and is accessible", async () => {
    const network = vi.fn(() => { throw new Error("network"); });
    vi.stubGlobal("fetch", network);
    const router = createRouter({ history: createMemoryHistory(), routes: adminRoutes });
    await router.push("/admin/overview"); await router.isReady();
    const wrapper = mount(App, { props: { session: fixtureSession() }, global: { plugins: [router] } });
    expect(wrapper.text()).not.toContain("任务受理趋势");
    expect(wrapper.text()).not.toContain("需要关注");
    expect(network).not.toHaveBeenCalled();
    (expect(await axe(wrapper.element, { rules: { "color-contrast": { enabled: false } } })) as unknown as { toHaveNoViolations(): void }).toHaveNoViolations();
  });



  it("requires administrator identity for real consumption data", async () => {
    const { wrapper } = await app("/admin/consumption");
    expect(wrapper.text()).toContain("无权限");
    expect(wrapper.text()).not.toContain("CON-0829-01");
  });

  it("keeps consumption and legacy media links on the real query surface", async () => {
    const { wrapper, router } = await app("/admin/login");
    await wrapper.get("form").trigger("submit"); await flushPromises();
    expect(wrapper.find('a[href="/admin/media"]').exists()).toBe(false);
    await router.push("/admin/consumption"); await flushPromises();
    expect(wrapper.get("h1").text()).toBe("消费明细");
    expect(wrapper.find('form.consumption-filters').exists()).toBe(true);
    expect(wrapper.text()).not.toMatch(/CON-0829|Nova|sk-demo/);
    await router.push("/admin/media/old-task"); await flushPromises();
    expect(router.currentRoute.value.path).toBe("/admin/consumption/old-task");
    expect(wrapper.get("h1").text()).toBe("消费详情");
    expect(wrapper.text()).not.toContain("腾讯任务");
  });

  it("marks navigation and focuses main after routing", async () => {
    const { wrapper, router } = await app("/admin/login");
    await wrapper.get("form").trigger("submit"); await flushPromises();
    expect(wrapper.get('a[aria-current="page"]').text()).toContain("运营概览");
    await router.push("/admin/media"); await flushPromises(); await nextTick();
    expect(wrapper.get("h1").text()).toBe("消费明细");
    expect(document.activeElement).toBe(wrapper.get("main").element);
  });

  it("completes the local admin login flow", async () => {
    const { wrapper, router } = await app("/admin/login");
    await wrapper.get("form").trigger("submit"); await flushPromises();
    expect(router.currentRoute.value.path).toBe("/admin/overview");
  });

  it("hides primary-admin navigation and blocks direct privileged routes for NORMAL_ADMIN", async () => {
    const { wrapper, router } = await app("/admin/login");
    await wrapper.get('input[autocomplete="username"]').setValue("content.ops");
    await wrapper.get("form").trigger("submit"); await flushPromises();
    expect(wrapper.text()).toContain("NORMAL_ADMIN");
    expect(wrapper.find('a[href="/admin/pricing"]').exists()).toBe(false);
    expect(wrapper.find('a[href="/admin/admins"]').exists()).toBe(false);
    await router.push("/admin/pricing"); await flushPromises();
    expect(wrapper.get("h1").text()).toBe("无权限");
    expect(wrapper.text()).not.toContain("保存本地配置");
    await router.push("/admin/admins"); await nextTick();
    expect(wrapper.get("h1").text()).toBe("无权限");
    expect(wrapper.text()).not.toContain("新增管理员");
  });

  it("keeps pricing and administrator pages available to primary ADMIN", async () => {
    const { wrapper, router } = await app("/admin/login");
    await wrapper.get("form").trigger("submit"); await flushPromises();
    expect(wrapper.text()).toContain("ADMIN");
    expect(wrapper.find('a[href="/admin/pricing"]').exists()).toBe(true);
    expect(wrapper.find('a[href="/admin/admins"]').exists()).toBe(true);
    await router.push("/admin/pricing"); await flushPromises();
    expect(wrapper.get("h1").text()).toBe("模型定价");
    await router.push("/admin/admins"); await nextTick();
    expect(wrapper.get("h1").text()).toBe("管理员");
  });

  it("never reveals fixture or internal media URLs to either administrator role", async () => {
    const primary = await app("/admin/login");
    await primary.wrapper.get("form").trigger("submit"); await flushPromises();
    await primary.router.push("/admin/media/VID-0829-063"); await nextTick();
    expect(primary.wrapper.text()).not.toContain("tos://bucket-xzwl/video-0829-063.mp4");

    const normal = await app("/admin/login");
    await normal.wrapper.get('input[autocomplete="username"]').setValue("content.ops");
    await normal.wrapper.get("form").trigger("submit"); await flushPromises();
    await normal.router.push("/admin/media/VID-0829-063"); await nextTick();
    expect(normal.wrapper.text()).not.toContain("tos://bucket-xzwl/video-0829-063.mp4");
  });

  it.each(["finance.ops", "unknown.ops"])("does not grant privileged access to %s", async (username) => {
    const { wrapper, router } = await app("/admin/login");
    await wrapper.get('input[autocomplete="username"]').setValue(username);
    await wrapper.get("form").trigger("submit"); await flushPromises();
    expect(router.currentRoute.value.path).toBe("/admin/login");
    expect(wrapper.get('[role="status"]').text()).toContain("登录失败");
    await router.push("/admin/pricing"); await flushPromises();
    expect(wrapper.get("h1").text()).toBe("无权限");
  });

  it("blocks a privileged direct link before an administrator signs in", async () => {
    const { wrapper } = await app("/admin/admins");
    expect(wrapper.get("h1").text()).toBe("无权限");
  });

  it("shows an error for an unavailable real user detail", async () => {
    const { wrapper } = await app("/admin/users/UNKNOWN/status");
    await flushPromises();
    expect(wrapper.text()).toContain("无法读取用户详情");
  });

  it.each([
    ["primary.admin", "/admin/overview"],
    ["content.ops", "/admin/overview"],
    ["promoter.demo", "/promoter/overview"],
    ["api.demo", "/api/dashboard"]
  ])("redirects %s to its role home", async (username, expectedPath) => {
    const { wrapper, router } = await app("/admin/login");
    await wrapper.get('input[autocomplete="username"]').setValue(username);
    await wrapper.get("form").trigger("submit"); await flushPromises();
    expect(router.currentRoute.value.path).toBe(expectedPath);
    expect(wrapper.find(".header-tools .api-recharge-link").exists()).toBe(username === "api.demo");
    if (username === "api.demo") expect(wrapper.get(".api-recharge-link").attributes("href")).toBe("/api/billing");
  });

  it("renders only the promoter navigation and local data", async () => {
    const { wrapper } = await app("/admin/login");
    await wrapper.get('input[autocomplete="username"]').setValue("promoter.demo");
    await wrapper.get("form").trigger("submit"); await flushPromises();
    expect(wrapper.text()).toContain("推广概览");
    expect(wrapper.text()).toContain("推广用户");
    expect(wrapper.find('a[href="/admin/users"]').exists()).toBe(false);
    expect(wrapper.find('a[href="/admin/keys"]').exists()).toBe(false);
  });

  it("shows promoter financial totals alongside invitation count", async () => {
    const { wrapper } = await app("/admin/login");
    await wrapper.get('input[autocomplete="username"]').setValue("promoter.demo");
    await wrapper.get("form").trigger("submit"); await flushPromises();
    expect(wrapper.text()).toContain("邀请人数");
    expect(wrapper.text()).not.toMatch(/一级用户|二级用户/);
    expect(wrapper.text()).toContain("总充值");
    expect(wrapper.text()).toContain("登录身份缺少业务用户");
    expect(wrapper.text()).not.toContain("¥ 16,000");
    expect(wrapper.text()).toContain("总消耗");
    expect(wrapper.text()).not.toContain("¥ 12,846.4");
    expect(wrapper.text()).toContain("总佣金");
    expect(wrapper.text()).not.toContain("¥ 2,272.6");
  });

  it("renders the API console navigation without promoter content", async () => {
    const { wrapper } = await app("/admin/login");
    await wrapper.get('input[autocomplete="username"]').setValue("api.demo");
    await wrapper.get("form").trigger("submit"); await flushPromises();
    expect(wrapper.text()).toContain("API 密钥");
    expect(wrapper.find('nav a[href="/api/keys"]').exists()).toBe(false);
    expect(wrapper.text()).toContain("充值记录");
    expect(wrapper.text()).not.toContain("推广用户");
    expect(wrapper.find('a[href="/promoter/users"]').exists()).toBe(false);
  });

  it("denies cross-role deep links before mounting another role page", async () => {
    const promoter = await app("/admin/login");
    await promoter.wrapper.get('input[autocomplete="username"]').setValue("promoter.demo");
    await promoter.wrapper.get("form").trigger("submit"); await flushPromises();
    await promoter.router.push("/admin/users"); await nextTick();
    expect(promoter.wrapper.get("h1").text()).toBe("无权限");
    expect(promoter.wrapper.text()).not.toContain("用户列表");

    const api = await app("/admin/login");
    await api.wrapper.get('input[autocomplete="username"]').setValue("api.demo");
    await api.wrapper.get("form").trigger("submit"); await flushPromises();
    await api.router.push("/promoter/users"); await nextTick();
    expect(api.wrapper.get("h1").text()).toBe("无权限");
    expect(api.wrapper.text()).not.toContain("一级用户");
  });

  it("keeps the API console local and reference-inspired", async () => {
    const network = vi.fn(() => { throw new Error("network"); });
    vi.stubGlobal("fetch", network);
    const { wrapper } = await app("/admin/login");
    await wrapper.get('input[autocomplete="username"]').setValue("api.demo");
    await wrapper.get("form").trigger("submit"); await flushPromises();
    expect(wrapper.text()).toContain("账户");
    expect(wrapper.text()).toContain("剩余额度");
    expect(wrapper.text()).toContain("API 密钥");
    expect(wrapper.find('nav a[href="/api/keys"]').exists()).toBe(false);
    expect(wrapper.text()).toContain("充值记录");
    expect(network).not.toHaveBeenCalled();
  });

  it("clears the active role when logging out", async () => {
    const { wrapper, router } = await app("/admin/login");
    await wrapper.get('input[autocomplete="username"]').setValue("promoter.demo");
    await wrapper.get("form").trigger("submit"); await flushPromises();
    await wrapper.get('[aria-label="打开管理员菜单"]').trigger("click");
    await wrapper.get('[data-testid="admin-logout"]').trigger("click"); await flushPromises();
    expect(router.currentRoute.value.path).toBe("/admin/login");
    expect(wrapper.find('a[href="/promoter/users"]').exists()).toBe(false);
    expect(wrapper.text()).toContain("后台登录");
  });

  it("clears a stale role when the login route is opened directly", async () => {
    const { wrapper, router } = await app("/admin/login");
    await wrapper.get('input[autocomplete="username"]').setValue("promoter.demo");
    await wrapper.get("form").trigger("submit"); await flushPromises();
    await router.push("/admin/login"); await nextTick();
    expect(wrapper.find(".workspace-header").exists()).toBe(false);
    expect(wrapper.find('a[href="/promoter/users"]').exists()).toBe(false);
    expect(wrapper.text()).toContain("后台登录");
  });
});


it('isolates the light reference shell to promoter identities and keeps account actions accessible', async () => {
  const scroll = vi.fn(); vi.stubGlobal('scrollTo',scroll);
  const router = createRouter({history:createMemoryHistory(),routes:adminRoutes});
  await router.push('/promoter/users'); await router.isReady();
  const identity = ref<import('./adminSession').AdminIdentity | null>({role:'PROMOTER',username:'推广测试'});
  const wrapper = mount(App,{props:{session:{currentAdmin:identity,authenticate:()=>false}},global:{plugins:[router]}});
  expect(wrapper.find('.promoter-theme').exists()).toBe(true);
  await nextTick(); expect(scroll).toHaveBeenCalledWith(0,0);
  expect(wrapper.get('nav').findAll('a')).toHaveLength(5);
  expect(wrapper.text()).not.toContain('权限边界');
  await wrapper.get('button[aria-label="打开管理员菜单"]').trigger('click');
  expect(wrapper.text()).toContain('修改密码'); expect(wrapper.text()).toContain('退出当前设备');
  identity.value={role:'ADMIN',primary:true,username:'admin'}; await nextTick();
  expect(wrapper.find('.promoter-theme').exists()).toBe(false);
  expect(wrapper.text()).toContain('权限边界');
});

it('uses the documentation theme only for API docs and retains the console navigation elsewhere', async () => {
  const session = fixtureSession(); session.authenticate('api.demo');
  const router = createRouter({ history: createMemoryHistory(), routes: adminRoutes });
  await router.push('/api/docs'); await router.isReady();
  const wrapper = mount(App, { props: { session }, global: { plugins: [router] } });
  await flushPromises();
  expect(wrapper.classes()).toContain('api-docs-theme');
  expect(wrapper.get('.workspace-header .brand-logo').attributes('src')).toContain('admin-brand');
  expect(wrapper.find('.admin-sidebar').exists()).toBe(false);
  expect(wrapper.find('aside[aria-label="请求与响应示例"]').exists()).toBe(true);
  expect(wrapper.get('.api-recharge-link').attributes('href')).toBe('/api/billing');
  const scroll = vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  await router.push('/api/docs#console-api-errors'); await flushPromises();
  expect(scroll).not.toHaveBeenCalled();
  scroll.mockRestore();
  await router.push('/api/keys'); await flushPromises();
  expect(wrapper.classes()).not.toContain('api-docs-theme');
  expect(wrapper.find('nav[aria-label="API 控制台导航"]').exists()).toBe(true);
  expect(wrapper.get('.admin-sidebar .brand-logo').attributes('src')).toContain('admin-brand');
  expect(wrapper.get('.primary-nav a[href="/api/transfer"]').text()).toBe('公户转账');
  expect(wrapper.find('aside[aria-label="请求与响应示例"]').exists()).toBe(false);
});

it('dismisses account actions with Escape, outside pointer and keyboard focus leaving the header', async () => {
  const session = fixtureSession(); session.authenticate('api.demo');
  const router = createRouter({ history: createMemoryHistory(), routes: adminRoutes });
  await router.push('/api/docs'); await router.isReady();
  const wrapper = mount(App, { props: { session }, attachTo: document.body, global: { plugins: [router] } });
  await flushPromises();
  const toggle = wrapper.get<HTMLButtonElement>('button[aria-label="打开账户菜单"]');
  await toggle.trigger('click');
  await wrapper.get('[data-testid="admin-logout"]').trigger('keydown', { key: 'Escape' });
  expect(wrapper.find('.admin-menu').exists()).toBe(false);
  expect(document.activeElement).toBe(toggle.element);
  await toggle.trigger('click');
  document.body.dispatchEvent(new Event('pointerdown', { bubbles: true })); await nextTick();
  expect(wrapper.find('.admin-menu').exists()).toBe(false);
  await toggle.trigger('click');
  await wrapper.get('.header-tools').trigger('focusout', { relatedTarget: wrapper.get('#main-content').element });
  expect(toggle.attributes('aria-expanded')).toBe('false');
});
