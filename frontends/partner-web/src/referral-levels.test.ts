import { enableAutoUnmount, mount } from "@vue/test-utils";
import { readFileSync } from "node:fs";
import { afterEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import * as matchers from "vitest-axe/matchers";
import { createMemoryHistory, createRouter } from "vue-router";
import { nextTick } from "vue";
import App from "./App.vue";
import { partnerRoutes } from "./router";
import { localPromotionSessionView, type LocalPromotionSessionView } from "./promotion/localPromotionFixtures";

expect.extend(matchers);
enableAutoUnmount(afterEach);
afterEach(() => { vi.unstubAllGlobals(); });

async function show(path: string, session: LocalPromotionSessionView = localPromotionSessionView) {
  const router = createRouter({ history: createMemoryHistory(), routes: partnerRoutes });
  await router.push(path); await router.isReady();
  const wrapper = mount(App, { attachTo: document.body, props: { clipboard: { copy: vi.fn().mockResolvedValue(undefined) }, session }, global: { plugins: [router] } });
  await nextTick();
  return wrapper;
}

describe("fixed two-level referral model", () => {
  it.each([
    [[], "0 人", "0 人", "暂无一级或二级用户"],
    [[
      { id: "L1-CUSTOM", maskedIdentity: "一级＊", boundAt: "2026-08-30", source: "自定义", level: "LEVEL_1" },
      { id: "L2-CUSTOM-1", maskedIdentity: "二级甲＊", boundAt: "2026-08-30", source: "自定义", level: "LEVEL_2" },
      { id: "L2-CUSTOM-2", maskedIdentity: "二级乙＊", boundAt: "2026-08-30", source: "自定义", level: "LEVEL_2" }
    ], "1 人", "2 人", "二级乙＊"]
  ] as const)("derives overview counts and referral rows from one injected session", async (referrals, level1Count, level2Count, referralText) => {
    const session = {
      profile: { avatarText: "S", nickname: "Session User", userNumber: "USR-SESSION", promoterNumber: "PROMOTER-SESSION", code: "SAME88", link: "https://xzwlab.example/register?ref=SAME88" },
      referrals
    };
    const router = createRouter({ history: createMemoryHistory(), routes: partnerRoutes });
    await router.push("/promotion/overview"); await router.isReady();
    const wrapper = mount(App, { attachTo: document.body, props: { clipboard: { copy: vi.fn() }, session }, global: { plugins: [router] } });
    expect(wrapper.get('[data-testid="level-1-count"]').text()).toContain(level1Count);
    expect(wrapper.get('[data-testid="level-2-count"]').text()).toContain(level2Count);
    await router.push("/promotion/referrals"); await nextTick();
    expect(wrapper.text()).toContain(referralText);
    if (!referrals.length) expect(wrapper.text()).not.toMatch(/林＊|星＊工作室/);
  });

  it("labels the permanent code/link, explains reuse, and shows explicit L1/L2 counts", async () => {
    const wrapper = await show("/promotion/overview");
    expect(wrapper.text()).toContain("永久邀请码");
    expect(wrapper.text()).toContain("永久邀请链接");
    expect(wrapper.text()).toContain("同一账号、同一邀请码与链接");
    expect(wrapper.get('[data-testid="level-1-count"]').text()).toContain("一级用户");
    expect(wrapper.get('[data-testid="level-2-count"]').text()).toContain("二级用户");
    expect(wrapper.text()).toContain("财务金额直接来自服务端快照");
    expect(wrapper.text()).not.toContain("累计邀请");
  });

  it("renders masked L1 and L2 fixtures with fixed ownership explanations and no third level", async () => {
    const network = vi.fn(); vi.stubGlobal("fetch", network);
    const wrapper = await show("/promotion/referrals");
    expect(wrapper.get('section[aria-labelledby="level-1-title"]').text()).toContain("一级用户");
    expect(wrapper.get('section[aria-labelledby="level-1-title"]').text()).toContain("林＊");
    expect(wrapper.get('section[aria-labelledby="level-1-title"]').text()).toContain("积分受益人无");
    expect(wrapper.get('section[aria-labelledby="level-1-title"]').text()).toContain("LEVEL_1");
    expect(wrapper.get('section[aria-labelledby="level-2-title"]').text()).toContain("二级用户");
    expect(wrapper.get('section[aria-labelledby="level-2-title"]').text()).toContain("星＊工作室");
    expect(wrapper.get('section[aria-labelledby="level-2-title"]').text()).toContain("积分归其直接邀请人");
    expect(wrapper.get('section[aria-labelledby="level-2-title"]').text()).toContain("LEVEL_2");
    expect(wrapper.text()).toContain("任务成功奖励将在下一阶段启用");
    expect(wrapper.text()).toContain("佣金将在下一阶段启用");
    expect(wrapper.text()).not.toMatch(/三级用户|LEVEL_3/);
    expect(network).not.toHaveBeenCalled();
    (expect(await axe(wrapper.element, { rules: { "color-contrast": { enabled: false } } })) as unknown as { toHaveNoViolations(): void }).toHaveNoViolations();
  });

  it("keeps level-aware empty state and rejects promoterId as a page or prop boundary", async () => {
    const wrapper = await show("/promotion/referrals", { ...localPromotionSessionView, referrals: [] });
    expect(wrapper.text()).toContain("暂无一级或二级用户");
    expect(wrapper.find('input[aria-label="推广人 ID"]').exists()).toBe(false);
    const sources = ["src/App.vue", "src/pages/PromotionOverviewPage.vue", "src/pages/PromotionReferralsPage.vue"].map((path) => readFileSync(path, "utf8")).join("\n");
    expect(sources).not.toMatch(/promoterId/);
  });

  it("keeps level tables responsive and copy controls touchable", () => {
    const styles = readFileSync("src/styles.css", "utf8");
    expect(styles).toMatch(/\.referral-table-wrap[^}]*overflow-x:\s*auto/s);
    expect(styles).toMatch(/\.promotion-link-card button[^}]*min-height:\s*44px/s);
    expect(styles).toMatch(/@media\s*\(max-width:\s*768px\)/);
  });
});
