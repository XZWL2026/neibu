import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import * as matchers from "vitest-axe/matchers";
import { createMemoryHistory, createRouter } from "vue-router";
import { nextTick } from "vue";
import App from "./App.vue";
import { partnerRoutes } from "./router";
import { createClipboardAdapter } from "./promotion/clipboardAdapter";
import * as clipboardModule from "./promotion/clipboardAdapter";
import { localPromotionSessionView, type LocalPromotionSessionView } from "./promotion/localPromotionFixtures";

expect.extend(matchers);
enableAutoUnmount(afterEach);
afterEach(() => { vi.unstubAllGlobals(); });

async function show(path: string, clipboard = createClipboardAdapter(), session: LocalPromotionSessionView = localPromotionSessionView) {
  const router = createRouter({ history: createMemoryHistory(), routes: partnerRoutes });
  await router.push(path); await router.isReady();
  const wrapper = mount(App, { attachTo: document.body, props: { clipboard, session }, global: { plugins: [router] } });
  await nextTick();
  return { router, wrapper };
}

describe("local promotion center", () => {
  it("supports overview and referral deep links without a network request", async () => {
    const network = vi.fn(); vi.stubGlobal("fetch", network);
    const overview = await show("/promotion/overview", { copy: vi.fn().mockResolvedValue(undefined) });
    expect(overview.wrapper.get("h1").text()).toBe("推广概览");
    expect(overview.wrapper.text()).toContain("Nova");
    expect(overview.wrapper.text()).toContain("NOVA88");
    expect(overview.wrapper.find('input[aria-label="推广人 ID"]').exists()).toBe(false);
    expect(overview.wrapper.findAll(".finance-card")).toHaveLength(4);
    expect(overview.wrapper.findAll(".finance-card").map((card) => card.get("strong").text())).toEqual(["¥260.12", "¥150.00", "¥125.00", "¥430.50"]);
    expect(network).not.toHaveBeenCalled();
    (expect(await axe(overview.wrapper.element, { rules: { "color-contrast": { enabled: false } } })) as unknown as { toHaveNoViolations(): void }).toHaveNoViolations();

    await overview.router.push("/promotion/referrals"); await nextTick();
    expect(overview.wrapper.get("h1").text()).toBe("被推广用户");
    expect(overview.wrapper.text()).toContain("林＊");
    expect(overview.wrapper.text()).not.toContain("13800138000");
    expect(overview.wrapper.get('a[aria-current="page"]').text()).toContain("被推广用户");
    expect(document.activeElement).toBe(overview.wrapper.get("main").element);
  });

  it("copies through the adapter and announces success and failure", async () => {
    const copy = vi.fn().mockResolvedValue(undefined);
    const success = await show("/promotion/overview", { copy });
    await success.wrapper.get("button[aria-label='复制推广链接']").trigger("click");
    await flushPromises();
    expect(copy).toHaveBeenCalledWith("https://xzwlab.example/register?ref=NOVA88");
    expect(success.wrapper.get('[role="status"]').text()).toContain("推广链接已复制");

    const failure = await show("/promotion/overview", { copy: vi.fn().mockRejectedValue(new Error("denied")) });
    await failure.wrapper.get("button[aria-label='复制推广链接']").trigger("click");
    await flushPromises();
    expect(failure.wrapper.get('[role="status"]').text()).toContain("复制失败，请手动复制推广链接");
  });

  it("locks copying in flight so rapid clicks invoke the adapter once", async () => {
    let completeCopy!: () => void;
    const copy = vi.fn(() => new Promise<void>((resolve) => { completeCopy = resolve; }));
    const view = await show("/promotion/overview", { copy });
    const button = view.wrapper.get("button[aria-label='复制推广链接']");
    await button.trigger("click");
    await button.trigger("click");
    expect(copy).toHaveBeenCalledTimes(1);
    expect(button.attributes("disabled")).toBeDefined();
    expect(button.attributes("aria-label")).toBe("正在复制永久邀请链接");
    completeCopy();
    await flushPromises();
    expect(button.attributes("disabled")).toBeUndefined();
  });

  it.each(["resolve", "reject"] as const)("does not update overview refs after pending copy %s when unmounted", async (outcome) => {
    let settle!: () => void;
    const copy = vi.fn(() => new Promise<void>((resolve, reject) => {
      settle = () => outcome === "resolve" ? resolve() : reject(new Error("denied"));
    }));
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const view = await show("/promotion/overview", { copy });
    await view.wrapper.get("button[aria-label='复制推广链接']").trigger("click");
    view.wrapper.unmount();
    settle();
    await flushPromises();
    expect(consoleError).not.toHaveBeenCalled();
  });

  it.each(["resolve", "reject"] as const)("skips every promotion copy continuation after disposal on %s", async (outcome) => {
    const runCopy = Reflect.get(clipboardModule, "copyPromotionWithLifecycle") as undefined | ((...args: unknown[]) => Promise<void>);
    expect(runCopy).toBeTypeOf("function");
    if (!runCopy) return;
    let settle!: () => void;
    const copy = vi.fn(() => new Promise<void>((resolve, reject) => {
      settle = () => outcome === "resolve" ? resolve() : reject(new Error("denied"));
    }));
    let active = true;
    const success = vi.fn();
    const failure = vi.fn();
    const settled = vi.fn();
    const operation = runCopy({ copy }, "https://example.test/ref", { isActive: () => active, success, failure, settled });
    active = false;
    settle();
    await operation;
    expect(success).not.toHaveBeenCalled();
    expect(failure).not.toHaveBeenCalled();
    expect(settled).not.toHaveBeenCalled();
  });

  it("uses navigator.clipboard in the production adapter and renders an empty referral state", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal("navigator", { clipboard: { writeText } });
    await createClipboardAdapter().copy("https://example.test/ref");
    expect(writeText).toHaveBeenCalledWith("https://example.test/ref");
    const empty = await show("/promotion/referrals", { copy: vi.fn() }, { ...localPromotionSessionView, referrals: [] });
    expect(empty.wrapper.text()).toContain("暂无一级或二级用户");
  });

  it("redirects root and recovers from a missing route", async () => {
    const root = await show("/");
    expect(root.router.currentRoute.value.path).toBe("/promotion/overview");
    await root.router.push("/missing"); await nextTick();
    expect(root.wrapper.get("h1").text()).toBe("页面不存在");
    expect(root.wrapper.findAll("a").some((link) => link.text().includes("返回推广概览") && link.attributes("href") === "/promotion/overview")).toBe(true);
  });
});
