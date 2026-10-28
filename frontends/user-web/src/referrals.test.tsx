import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import * as matchers from "vitest-axe/matchers";
import { createMemoryRouter, MemoryRouter } from "react-router-dom";
import type { ComponentProps } from "react";
import { StrictMode } from "react";
import { App } from "./App";
import { userRoutes } from "./router";
import type { LocalInvitationProfile } from "./promotion/localInvitationProfile";
import * as invitationProfileModule from "./promotion/localInvitationProfile";
import type { LocalPromotionViewState } from "./promotion/localPromotionViewState";
import { LocalPromotionProvider } from "./promotion/localPromotionViewState";
import { ReferralsPage } from "./promotion/ReferralsPage";
import { UserFrame } from "./UserFrame";
import { UserFixtureProvider } from "./userSession";

expect.extend(matchers);
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

const accountTypes = ["PERSONAL", "ENTERPRISE", "API"] as const;
const eligibleProfiles = accountTypes.map((accountType) => ({
  eligibility: "ENABLED",
  accountType,
  code: `${accountType.slice(0, 3)}888`,
  link: `https://xzwlab.example/register?ref=${accountType.slice(0, 3)}888`,
  directInvites: [{ id: `USR-${accountType}`, maskedIdentity: `${accountType} 用户`, joinedAt: "2026-08-30" }]
})) satisfies Array<Extract<LocalInvitationProfile, { eligibility: "ENABLED" }>>;

function show(path: string, props: Omit<ComponentProps<typeof App>, "router"> = {}) {
  return render(<App fixture router={createMemoryRouter(userRoutes, { initialEntries: [path] })} {...props} />);
}

describe("local invitation profile", () => {
  it("requires an explicit invitation provider for invitation consumers", () => {
    expect(() => render(
      <MemoryRouter><UserFixtureProvider><LocalPromotionProvider><UserFrame><div>内容</div></UserFrame></LocalPromotionProvider></UserFixtureProvider></MemoryRouter>
    )).toThrow("useLocalInvitationProfile must be used within LocalInvitationProvider");
    expect(() => render(<MemoryRouter><UserFixtureProvider><ReferralsPage /></UserFixtureProvider></MemoryRouter>)).toThrow("useLocalInvitationProfile must be used within LocalInvitationProvider");
  });

  it.each(eligibleProfiles)("opens the referrals deep link for enabled $accountType accounts without network", async (profile) => {
    const network = vi.fn();
    vi.stubGlobal("fetch", network);
    show("/account/referrals", { initialInvitationProfile: profile });
    expect(await screen.findByRole("heading", { name: "邀请有礼" })).toBeVisible();
    expect(screen.getByText(profile.code)).toBeVisible();
    expect(screen.getByDisplayValue(profile.link)).toBeVisible();
    expect(screen.getByText(profile.accountType)).toBeVisible();
    expect(screen.getByText("永久有效")).toBeVisible();
    expect(screen.getByText("成为推广人后继续复用，不会生成第二个邀请码。")).toBeVisible();
    expect(network).not.toHaveBeenCalled();
  });

  it.each([
    [{ kind: "eligible-unapplied", accountType: "PERSONAL" }, "申请成为代理"],
    [{ kind: "pending", submittedAt: "2026-08-30" }, "推广审核中"],
    [{ kind: "rejected", reason: "渠道说明不足" }, "重新申请成为代理"],
    [{ kind: "approved" }, "推广登录"]
  ] satisfies Array<[LocalPromotionViewState, string]>)
  ("keeps invitations independent from promotion state %s", async (promotionState, promotionLabel) => {
    show("/home", { initialPromotionViewState: promotionState, initialInvitationProfile: eligibleProfiles[0] });
    fireEvent.click(await screen.findByRole("button", { name: "打开用户菜单" }));
    expect(screen.getByRole("link", { name: "邀请有礼" })).toHaveAttribute("href", "/account/referrals");
    if (promotionLabel === "推广登录") expect(screen.getByRole("link", { name: promotionLabel })).toBeVisible();
    else expect(screen.queryByText(promotionLabel)).not.toBeInTheDocument();
  });

  it.each([
    { eligibility: "UNSET" },
    { eligibility: "DISABLED", accountType: "PERSONAL" }
  ] satisfies LocalInvitationProfile[])("hides invitation actions and blocks direct access for $eligibility accounts", async (profile) => {
    show("/home", { initialInvitationProfile: profile, initialPromotionViewState: { kind: "ineligible", reason: profile.eligibility === "UNSET" ? "unset" : "disabled" } });
    fireEvent.click(await screen.findByRole("button", { name: "打开用户菜单" }));
    expect(screen.queryByRole("link", { name: "邀请有礼" })).not.toBeInTheDocument();
    cleanup();
    show("/account/referrals", { initialInvitationProfile: profile });
    expect(await screen.findByRole("heading", { name: "当前不可使用邀请有礼" })).toBeVisible();
    expect(screen.queryByRole("button", { name: /复制/ })).not.toBeInTheDocument();
  });

  it("locks copy in flight and announces success and recoverable failure", async () => {
    let finish!: () => void;
    const copy = vi.fn(() => new Promise<void>((resolve) => { finish = resolve; }));
    const view = show("/account/referrals", { initialInvitationProfile: eligibleProfiles[0], invitationClipboard: { copy } });
    const button = await screen.findByRole("button", { name: "复制邀请链接" });
    fireEvent.click(button);
    fireEvent.click(button);
    expect(copy).toHaveBeenCalledTimes(1);
    expect(button).toBeDisabled();
    expect(button).toHaveTextContent("复制中…");
    expect(button).toHaveAccessibleName("正在复制邀请链接");
    finish();
    expect(await screen.findByRole("status")).toHaveTextContent("邀请链接已复制");
    expect(button).not.toBeDisabled();

    view.unmount();
    show("/account/referrals", { initialInvitationProfile: eligibleProfiles[0], invitationClipboard: { copy: vi.fn().mockRejectedValue(new Error("denied")) } });
    fireEvent.click(await screen.findByRole("button", { name: "复制邀请链接" }));
    expect(await screen.findByRole("status")).toHaveTextContent("复制失败，请手动复制邀请链接");
    expect(screen.getByRole("button", { name: "复制邀请链接" })).not.toBeDisabled();
  });

  it("still completes copy after StrictMode replays the mounted effect", async () => {
    render(<StrictMode><App fixture router={createMemoryRouter(userRoutes, { initialEntries: ["/account/referrals"] })} initialInvitationProfile={eligibleProfiles[0]} invitationClipboard={{ copy: vi.fn().mockResolvedValue(undefined) }} /></StrictMode>);
    fireEvent.click(await screen.findByRole("button", { name: "复制邀请链接" }));
    expect(await screen.findByRole("status")).toHaveTextContent("邀请链接已复制");
  });

  it.each(["resolve", "reject"] as const)("does not update invitation state after pending copy %s when unmounted", async (outcome) => {
    let settle!: () => void;
    const copy = vi.fn(() => new Promise<void>((resolve, reject) => {
      settle = () => outcome === "resolve" ? resolve() : reject(new Error("denied"));
    }));
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const view = show("/account/referrals", { initialInvitationProfile: eligibleProfiles[0], invitationClipboard: { copy } });
    fireEvent.click(await screen.findByRole("button", { name: "复制邀请链接" }));
    view.unmount();
    await act(async () => { settle(); await Promise.resolve(); });
    expect(consoleError).not.toHaveBeenCalled();
  });

  it.each(["resolve", "reject"] as const)("skips every invitation continuation after disposal on %s", async (outcome) => {
    const runCopy = Reflect.get(invitationProfileModule, "copyInvitationWithLifecycle") as undefined | ((...args: unknown[]) => Promise<void>);
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

  it("shows only direct invites, an empty state, and the point reward account", async () => {
    const populated = show("/account/referrals", { initialInvitationProfile: eligibleProfiles[0] });
    expect(await screen.findByRole("heading", { name: "直接邀请" })).toBeVisible();
    expect(screen.getByText("PERSONAL 用户")).toBeVisible();
    expect(screen.getByText(/只展示你自己直接邀请的用户/)).toBeVisible();
    expect(screen.getByRole("heading", { name: "邀请积分账户" })).toBeVisible();
    expect(populated.container).not.toHaveTextContent("现金根");
    expect(populated.container).not.toHaveTextContent("二级收益");
    (expect(await axe(populated.container, { rules: { "color-contrast": { enabled: false } } })) as unknown as { toHaveNoViolations(): void }).toHaveNoViolations();
    populated.unmount();

    show("/account/referrals", { initialInvitationProfile: { ...eligibleProfiles[1], directInvites: [] } });
    expect(await screen.findByText("暂无直接邀请用户")).toBeVisible();
  });

  it("reuses one invitation code on the approved page and passes accessibility checks", async () => {
    const approved = show("/account/promotion", { initialPromotionViewState: { kind: "approved" }, initialInvitationProfile: eligibleProfiles[2] });
    expect(await screen.findByText(/API888/)).toBeVisible();
    expect(approved.container).not.toHaveTextContent("promotionCode");
    (expect(await axe(approved.container, { rules: { "color-contrast": { enabled: false } } })) as unknown as { toHaveNoViolations(): void }).toHaveNoViolations();
  });
});
