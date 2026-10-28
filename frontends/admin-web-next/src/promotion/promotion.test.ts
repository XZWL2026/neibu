import AdminSearchSelect from "../components/AdminSearchSelect.vue";
import type { VueWrapper } from "@vue/test-utils";
import { fixtureSession } from "../fixtureSession.test-support";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { readFileSync } from "node:fs";
import { afterEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import * as matchers from "vitest-axe/matchers";
import { createMemoryHistory, createRouter } from "vue-router";
import { nextTick } from "vue";
import App from "../App.vue";
import type { AdminAttributionAdapter, AttributionHistory } from "./httpPromotionAdminAdapters";
import { adminRoutes } from "../router";

expect.extend(matchers);
enableAutoUnmount(afterEach);
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

function field(wrapper: VueWrapper, label: string) {
  return wrapper.findAllComponents(AdminSearchSelect).find(component => component.props('label') === label)!;
}

async function show(path = "/admin/login") {
  const router = createRouter({ history: createMemoryHistory(), routes: adminRoutes });
  await router.push(path); await router.isReady();
  const history = new Map<string, AttributionHistory[]>();
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({items:[],total:0}),{headers:{'Content-Type':'application/json'}})));
  const attribution: AdminAttributionAdapter = {
    records:async()=>({items:[...history].flatMap(([user,items])=>items.map(item=>({id:user+item.version,userId:user,user,previousInviter:'无',inviter:item.directInviterId,operator:item.operator||'—',reason:item.reason||'—',time:item.effectiveAt}))),total:[...history.values()].flat().length}),
    search: async () => ({ items: [{ value: 'test-user', label: '受邀用户' }, { value: 'test-other', label: '其他用户' }, { value: 'test-inviter', label: '已核实邀请人' }], total: 3 }),
    history: async user => ({ items: history.get(user) ?? [], total: (history.get(user) ?? []).length, currentVersion: (history.get(user) ?? []).length }),
    save: async (user, inviter, version, reason) => { history.set(user, [...history.get(user) ?? [], { version: version + 1, directInviterId: inviter, pointBeneficiaryId: inviter, cashPromoterId: null, cashTier: 'NONE', reason, operator: 'admin-test', effectiveAt: '2026-09-15 09:00' }]); }
  };
  const wrapper = mount(App, { props: { session: fixtureSession(), adminAttributionAdapter: attribution }, attachTo: document.body, global: { plugins: [router] } });
  await nextTick();
  return { router, wrapper };
}

async function loginAs(wrapper: Awaited<ReturnType<typeof show>>["wrapper"], role: "ADMIN" | "NORMAL_ADMIN") {
  await wrapper.get('input[autocomplete="username"]').setValue(role === "ADMIN" ? "primary.admin" : "content.ops");
  await wrapper.get('input[autocomplete="current-password"]').setValue('fixture-password');
  await wrapper.get("form").trigger("submit");
  await vi.dynamicImportSettled(); await flushPromises();
}

describe("local promotion administration", () => {
  it("shows promotion navigation only to the primary ADMIN and blocks direct access otherwise", async () => {
    const normal = await show();
    await loginAs(normal.wrapper, "NORMAL_ADMIN");
    expect(normal.wrapper.find('a[href="/admin/promotions/applications"]').exists()).toBe(false);
    expect(normal.wrapper.find('a[href="/admin/promotions/attributions"]').exists()).toBe(false);
    await normal.router.push("/admin/promotions/applications"); await nextTick();
    expect(normal.wrapper.get("h1").text()).toBe("无权限");
    expect(normal.wrapper.text()).not.toContain("通过申请");

    const primary = await show();
    await loginAs(primary.wrapper, "ADMIN");
    expect(primary.wrapper.find('a[href="/admin/promotions/applications"]').exists()).toBe(false);
    expect(primary.wrapper.find('a[href="/admin/promotions/attributions"]').exists()).toBe(true);
    await primary.router.push("/admin/promotions/applications"); await nextTick();
    expect(primary.wrapper.get("h1").text()).toBe("推荐关系管理");

    const signedOut = await show("/admin/promotions/attributions");
    expect(signedOut.wrapper.get("h1").text()).toBe("无权限");
    expect(signedOut.wrapper.text()).not.toContain("保存本地归属版本");
  });

  it("reloads server history after an explicitly injected relation save", async () => {
    const { router, wrapper } = await show(); await loginAs(wrapper, "ADMIN");
    await router.push("/admin/promotions/attributions"); await vi.dynamicImportSettled(); await flushPromises();
    const user = field(wrapper, "选择被邀请用户"), target = field(wrapper, "选择直接邀请人");
    expect(target.props('options').some((option:{label:string})=>option.label==='已核实邀请人')).toBe(true);
    await user.setValue('test-user'); await vi.dynamicImportSettled(); await flushPromises();
    await target.setValue('test-inviter');
    await wrapper.get('textarea').setValue('核实归属');
    await wrapper.get('form').trigger('submit'); await vi.dynamicImportSettled(); await flushPromises();
    expect(wrapper.findAll('[data-testid="attribution-version-test-user"]')).toHaveLength(1);
    expect(wrapper.text()).toContain('admin-test'); expect(wrapper.text()).toContain('核实归属');
    await target.setValue('test-other'); await wrapper.get('textarea').setValue('再次核实');
    await wrapper.get('form').trigger('submit'); await vi.dynamicImportSettled(); await flushPromises();
    expect(wrapper.findAll('[data-testid="attribution-version-test-user"]')).toHaveLength(2);
  });

  it("keeps the inviter and clears reason and errors when switching the selected user", async () => {
    const { router, wrapper } = await show(); await loginAs(wrapper, "ADMIN");
    await router.push("/admin/promotions/attributions"); await vi.dynamicImportSettled(); await flushPromises();
    const user = field(wrapper, "选择被邀请用户"), target = field(wrapper, "选择直接邀请人");
    await user.setValue('test-user'); await vi.dynamicImportSettled(); await flushPromises(); await target.setValue('test-inviter');
    await wrapper.get('form').trigger('submit');
    expect(wrapper.get('[role="alert"]').text()).toContain('必须填写');
    await wrapper.get('textarea').setValue('未提交草稿');
    await user.setValue('test-other'); await vi.dynamicImportSettled(); await flushPromises();
    expect(target.props('modelValue')).toBe('test-inviter'); expect(wrapper.get('textarea').element).toHaveProperty('value', '');
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
  });

  it("keeps promotion review actions at a 44px target with at least 8px spacing", () => {
    const styles = readFileSync("src/styles.css", "utf8");
    expect(styles).toMatch(/\.promotion-review-actions\s*\{[^}]*gap:\s*(?:[89]|[1-9]\d+)px/s);
    expect(styles).toMatch(/\.promotion-review-actions \.table-action\s*\{[^}]*min-height:\s*44px/s);
  });

  it("keeps the local application table accessible", async () => {
    const { router, wrapper } = await show(); await loginAs(wrapper, "ADMIN");
    await router.push("/admin/promotions/applications"); await nextTick();
    (expect(await axe(wrapper.element, { rules: { "color-contrast": { enabled: false } } })) as unknown as { toHaveNoViolations(): void }).toHaveNoViolations();
  });
});
