import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import * as matchers from "vitest-axe/matchers";
import { createMemoryRouter, MemoryRouter } from "react-router-dom";
import { App } from "../App";
import { userRoutes } from "../router";
import { UserFrame } from "../UserFrame";
import { UserFixtureProvider } from "../userSession";
import type { LocalPromotionViewState } from "./localPromotionViewState";

expect.extend(matchers);
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

function show(path: string, state: LocalPromotionViewState) {
  return render(<App fixture
    router={createMemoryRouter(userRoutes, { initialEntries: [path] })}
    initialPromotionViewState={state}
  />);
}

describe("local promotion application view", () => {
  it.each([
    [{ kind: "eligible-unapplied", accountType: "PERSONAL" }, "申请成为代理"],
    [{ kind: "pending", submittedAt: "2026-08-30" }, "推广审核中"],
    [{ kind: "rejected", reason: "推广渠道说明不完整" }, "重新申请成为代理"],
    [{ kind: "approved" }, "推广登录"]
  ] satisfies Array<[LocalPromotionViewState, string]>)("only exposes approved promotion login for %s (%s) in the avatar menu", async (state, label) => {
    show("/home", state);
    fireEvent.click(await screen.findByRole("button", { name: "打开用户菜单" }));
    if (label === "推广登录") expect(screen.getByRole("link", { name: label })).toBeVisible();
    else {
      expect(screen.queryByText(label)).not.toBeInTheDocument();
      expect(screen.queryByText("推广登录")).not.toBeInTheDocument();
    }
    expect(screen.queryByText("推广渠道说明不完整")).not.toBeInTheDocument();
  });

  it.each([
    { kind: "ineligible", reason: "unset" },
    { kind: "ineligible", reason: "disabled" }
  ] satisfies LocalPromotionViewState[])("hides all promotion entry points for $reason accounts", async (state) => {
    show("/home", state);
    fireEvent.click(await screen.findByRole("button", { name: "打开用户菜单" }));
    expect(screen.queryByText("申请成为代理")).not.toBeInTheDocument();
    expect(screen.queryByText("推广登录")).not.toBeInTheDocument();
    expect(screen.queryByText("推广审核中")).not.toBeInTheDocument();
    expect(screen.queryByText("邀请有礼")).not.toBeInTheDocument();
  });

  it("validates the local application and transitions to pending without a request", async () => {
    const network = vi.fn();
    vi.stubGlobal("fetch", network);
    show("/account/promotion", { kind: "eligible-unapplied", accountType: "ENTERPRISE" });
    fireEvent.click(await screen.findByRole("button", { name: "提交推广申请" }));
    expect(screen.getByText("请填写申请理由")).toHaveAttribute("role", "alert");
    expect(screen.getByText("请填写计划推广渠道")).toHaveAttribute("role", "alert");
    expect(screen.getByText("请确认推广规则与佣金结算协议")).toHaveAttribute("role", "alert");
    fireEvent.change(screen.getByLabelText("申请理由"), { target: { value: "希望向企业客户推荐创作能力" } });
    fireEvent.change(screen.getByLabelText("计划推广渠道"), { target: { value: "企业社群与线下沙龙" } });
    fireEvent.click(screen.getByRole("checkbox", { name: /确认推广规则/ }));
    fireEvent.click(screen.getByRole("button", { name: "提交推广申请" }));
    expect(await screen.findByRole("status")).toHaveTextContent("本地推广申请已提交，等待审核");
    expect(screen.getByRole("heading", { name: "推广申请审核中" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "提交推广申请" })).not.toBeInTheDocument();
    expect(network).not.toHaveBeenCalled();
  });

  it("focuses invalid fields in order and associates the agreement error", async () => {
    show("/account/promotion", { kind: "eligible-unapplied", accountType: "API" });
    const submit = await screen.findByRole("button", { name: "提交推广申请" });
    fireEvent.click(submit);
    expect(screen.getByLabelText("申请理由")).toHaveFocus();
    fireEvent.change(screen.getByLabelText("申请理由"), { target: { value: "开发者社区推广" } });
    fireEvent.click(submit);
    expect(screen.getByLabelText("计划推广渠道")).toHaveFocus();
    fireEvent.change(screen.getByLabelText("计划推广渠道"), { target: { value: "技术社区" } });
    fireEvent.click(submit);
    const agreement = screen.getByRole("checkbox", { name: /确认推广规则/ });
    expect(agreement).toHaveFocus();
    expect(agreement).toHaveAttribute("aria-invalid", "true");
    expect(agreement).toHaveAttribute("aria-describedby", "promotion-agreement-error");
    expect(document.getElementById("promotion-agreement-error")).toHaveTextContent("请确认推广规则与佣金结算协议");
  });

  it("throws when UserFrame is rendered without the local promotion provider", () => {
    expect(() => render(<MemoryRouter><UserFixtureProvider><UserFrame><span>content</span></UserFrame></UserFixtureProvider></MemoryRouter>)).toThrow("useLocalPromotionViewState must be used inside LocalPromotionProvider");
  });

  it("keeps the approved promoter in the same user session and exposes the promotion center", async () => {
    const view = show("/account/promotion", { kind: "approved" });
    expect(await screen.findByRole("heading", { name: "你已成为推广人" })).toBeVisible();
    expect(screen.getByText(/Nova，你的永久邀请码为/)).toBeVisible();
    expect(screen.getByRole("link", { name: "进入推广中心" })).toHaveAttribute("href", "/promotion/overview");
    (expect(await axe(view.container, { rules: { "color-contrast": { enabled: false } } })) as unknown as { toHaveNoViolations(): void }).toHaveNoViolations();
  });
});
