import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { createMemoryRouter } from "react-router-dom";
import { App } from "./App";
import { userNavigation } from "./navigation";
import { userRouter, userRoutes } from "./router";
import { clearPendingInvitation } from "./promotion/pendingInvitation";

beforeAll(() => {
  vi.stubGlobal("ResizeObserver", class {
    observe() {}
    unobserve() {}
    disconnect() {}
  });
});
afterAll(() => vi.unstubAllGlobals());
afterEach(() => { cleanup(); clearPendingInvitation(); sessionStorage.clear(); });
const show = (path: string) => render(<App fixture router={createMemoryRouter(userRoutes, { initialEntries: [path] })} />);

describe("user business routes", () => {
  it("keeps the confirmed page inventory and compact sidebar", () => {
    expect(userRoutes.map(({ path }) => path)).toEqual([
      "/", "/user/login", "/user/register", "/api-docs", "/home", "/create/image", "/create/video",
      "/canvas", "/commerce", "/commerce/product-suite", "/commerce/detail-page", "/commerce/image-replica", "/commerce/product-retouch", "/commerce/model-wear", "/commerce/apparel-set", "/account/profile", "/account/keys", "/account/recharge",
      "/account/transactions", "/account/referrals", "/account/promotion", "*"
    ]);
    expect(userNavigation.map((item) => item.to)).toEqual([
      "/home", "/create/image", "/create/video", "/canvas", "/commerce"
    ]);
    expect(userRouter.basename).toBe("/");
  });

  it("opens home without a login dialog for an authenticated root visit", async () => {
    const router = createMemoryRouter(userRoutes, { initialEntries: ["/"] });
    render(<App fixture router={router} />);
    expect(await screen.findByRole("heading", { name: "描述你的想法，创造你的影像" })).toBeVisible();
    expect(screen.queryByRole("dialog", { name: "登录/注册" })).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/home");
  });

  it.each([
    ["/user/register", "登录/注册"], ["/home", "描述你的想法，创造你的影像"],
    ["/create/image", "点亮图片创作"], ["/create/video", "点亮视频创作"],
    ["/canvas", "创作画布"], ["/commerce", "让好商品，拥有好画面。"], ["/commerce/product-suite", "商品套图"],
    ["/commerce/detail-page", "A+/详情页"], ["/commerce/image-replica", "图片复刻"], ["/commerce/product-retouch", "产品精修"],
    ["/commerce/model-wear", "模特穿戴"], ["/commerce/apparel-set", "服装组图"],
    ["/account/profile", "个人中心"], ["/account/keys", "个人中心"],
    ["/account/recharge", "账户充值"], ["/account/transactions", "消费记录"],
    ["/account/referrals", "邀请有礼"],
    ["/account/promotion", "申请成为代理"],
    ["/missing", "页面不存在"]
  ])("opens %s", async (path, title) => {
    show(path);
    expect(await screen.findByRole("heading", { name: title })).toBeVisible();
    if (path.startsWith("/create/")) {
      expect(screen.getByRole("img", { name: "YOMIO" })).toHaveAttribute("src", document.querySelector(".brand-logo")?.getAttribute("src"));
      expect(screen.queryByText(/描述画面并选择素材/)).not.toBeInTheDocument();
    }
  });

  it.each(["/", "/portal/"])("redirects legacy keys to the key section with replace under %s", async (basename) => {
    const router = createMemoryRouter(userRoutes, { basename, initialEntries: [`${basename}home`, `${basename}account/keys`] });
    render(<App fixture router={router} />);
    const keys = await screen.findByRole("region", { name: "平台密钥" });
    expect(router.state.location.pathname).toBe(`${basename}account/profile`);
    expect(router.state.location.hash).toBe("#keys");
    await waitFor(() => expect(keys).toHaveFocus());
    expect(router.state.historyAction).toBe("REPLACE");
    await router.navigate(-1);
    expect(await screen.findByRole("heading", { name: "描述你的想法，创造你的影像" })).toBeVisible();
  });

  it("focuses the key section when the profile hash changes without remounting", async () => {
    const router = createMemoryRouter(userRoutes, { initialEntries: ["/account/profile"] });
    render(<App fixture router={router} />);
    await screen.findByRole("heading", { name: "个人中心", level: 1 });
    await router.navigate("/account/profile#keys");
    await waitFor(() => expect(screen.getByRole("region", { name: "平台密钥" })).toHaveFocus());
  });

  it("supports creator history query deep links", async () => {
    const router = createMemoryRouter(userRoutes, { initialEntries: ["/create/video?tab=history"] });
    const view = render(<App fixture router={router} />);
    expect(await view.findByRole("heading", { name: "视频历史" })).toBeVisible();
  });

  it("生产画布替换预留页面且导航不再标记预留", async () => {
    show("/canvas");
    expect(await screen.findByRole("heading", { name: "创作画布" })).toBeVisible();
    expect(userNavigation.find((item) => item.to === "/canvas")).toMatchObject({
      label: "创作画布",
      reserved: false,
    });
  });

  it("keeps routes under a custom deployment base", async () => {
    const router = createMemoryRouter(userRoutes, { basename: "/portal/", initialEntries: ["/portal/home"] });
    render(<App fixture router={router} />);
    expect(await screen.findByRole("heading", { name: "描述你的想法，创造你的影像" })).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '展开创作导航' }));
    expect(screen.getByRole("link", { name: "图片" })).toHaveAttribute("href", "/portal/create/image");
  });
});


it.each(['/user/login', '/user/register'])('lands old referral %s links on home before explicitly opening authentication', async path => {
  const router = createMemoryRouter(userRoutes, { initialEntries: [path + '?ref=agent-code'] });
  const { container } = render(<App fixture router={router} />);
  expect(await screen.findByRole('heading', { name: '描述你的想法，创造你的影像' })).toBeVisible();
  expect(router.state.location.pathname).toBe('/home');
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  await router.navigate('/user/register');
  expect(await screen.findByRole('dialog', { name: '登录/注册' })).toBeVisible();
  expect(container.querySelector('.auth-home-background')).toHaveAttribute('inert');
  expect(screen.queryByLabelText('推广码')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '关闭登录窗口' }));
  expect(await screen.findByRole('heading', { name: '描述你的想法，创造你的影像' })).toBeVisible();
  expect(router.state.location.pathname).toBe('/home');
});
