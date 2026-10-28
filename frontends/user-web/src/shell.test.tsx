import "@testing-library/jest-dom/vitest";
import { readFileSync } from "node:fs";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import * as matchers from "vitest-axe/matchers";
import { createMemoryRouter } from "react-router-dom";
import { App } from "./App";
import { userRoutes } from "./router";

const userStyles = readFileSync("src/styles.css", "utf8");
expect.extend(matchers);
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
const app = (path: string) => render(<App fixture router={createMemoryRouter(userRoutes, { initialEntries: [path] })} />);
const audit = async (node: Element) => (expect(await axe(node, { rules: { "color-contrast": { enabled: false } } })) as unknown as { toHaveNoViolations(): void }).toHaveNoViolations();

// This suite deliberately keeps the historical local image/history fixture.
// Production image generation is exercised by ProductionImageComposer.test.tsx.
vi.mock("./creator/ProductionImageComposer", async () => {
  const { CreatorComposer } = await import("./creator/CreatorComposer");
  return { ProductionImageComposer: ({ active = true }: { active?: boolean }) => active ? <CreatorComposer kind="image" /> : null };
});

describe("user product shell", () => {
  it("renders the migrated homepage offline with accessible editable sections", async () => {
    const network = vi.fn(() => { throw new Error("network"); });
    vi.stubGlobal("fetch", network);
    const view = app("/home");
    const models = within(view.getByRole("region", { name: "热门模型" }));
    expect(models.getAllByRole("link")).toHaveLength(5);
    expect(models.getByRole("link", { name: /Seedance 2.5/ })).toHaveAttribute("href", "/create/video");
    expect(view.getByRole("heading", { name: "精选作品" })).toBeVisible();
    expect(view.getByRole("region", { name: "电商创作专区" })).toBeVisible();
    expect(view.getByRole("link", { name: "我的作品" })).toHaveAttribute("href", "/create/image?tab=history");
    expect(view.container.querySelector("video")).toHaveAttribute("loop");
    expect(view.container.querySelector("video")).toHaveAttribute("autoplay");
    expect(view.queryByRole("button", { name: /暂停背景|播放背景/ })).not.toBeInTheDocument();
    expect(network).not.toHaveBeenCalled();
    await audit(view.container);
  });

  it("opens account pages from the header menu without a sidebar avatar", async () => {
    app("/home");
    expect(screen.queryByRole("complementary", { name: "用户端导航" })).not.toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: "打开用户菜单" }));
    expect(screen.getAllByRole("link", { name: "个人中心" })).toHaveLength(1);
    expect(within(document.getElementById("header-account-menu")!).getByRole("link", { name: "个人中心" })).toHaveAttribute("href", "/account/profile");
    expect(screen.queryByRole("link", { name: "个人资料" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "我的密钥" })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "消费记录" })).toBeVisible();
    expect(screen.queryByRole("link", { name: "账户充值" })).not.toBeInTheDocument();
    expect(document.querySelector(".avatar-menu-head")).not.toHaveTextContent("积分");
    expect(screen.queryByRole("link", { name: "申请成为代理" })).not.toBeInTheDocument();
  });

  it("filters the homepage gallery while preserving its twelve supplied works", async () => {
    app("/home");
    const gallery = within(screen.getByRole("region", { name: "精选作品" }));
    expect(gallery.getAllByRole("button", { name: /^查看 / })).toHaveLength(12);
    fireEvent.click(gallery.getByRole("button", { name: "专业影视" }));
    expect(gallery.getAllByRole("button", { name: /^查看 / })).toHaveLength(3);
    expect(gallery.getByText("山川纪实")).toBeVisible();
    expect(gallery.queryByText("云际之境")).not.toBeInTheDocument();
    fireEvent.click(gallery.getByRole("button", { name: "全部" }));
    expect(gallery.getAllByRole("button", { name: /^查看 / })).toHaveLength(12);
    expect(screen.queryByLabelText("搜索内容")).not.toBeInTheDocument();
  });

  it("keeps commerce tools on their production routes", () => {
    app("/home");
    const commerce = within(screen.getByRole("region", { name: "电商创作专区" }));
    expect(commerce.getAllByRole("link")).toHaveLength(5);
    expect(commerce.getByRole("link", { name: "视频复刻" })).toHaveAttribute("href", "/commerce/video-replica");
    expect(commerce.getByRole("link", { name: "商品套图" })).toHaveAttribute("href", "/commerce/product-suite");
  });

  it("opens the production video composer without fictional modes when catalog loading fails", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => { throw new TypeError("offline"); }));
    app("/create/video");
    expect(await screen.findByText("模型请求未完成，请检查网络连接后重试。")).toBeVisible();
    expect(screen.getByRole("button", { name: "文生视频" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.queryByRole("button", { name: "全能参考" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "开始生成" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "重新获取模型与报价" })).toBeVisible();
  });

  it("submits an image fixture and exposes its history inside the creator", async () => {
    app("/create/image");
    fireEvent.change(await screen.findByLabelText("图片提示词"), { target: { value: "极简香水广告，青色轮廓光" } });
    fireEvent.click(screen.getByRole("button", { name: "开始生成" }));
    const createdNotice = await screen.findByText("本地图片任务已创建");
    expect(createdNotice).toHaveAttribute("role", "status");
    fireEvent.click(screen.getByRole("link", { name: /图片历史/ }));
    expect(await screen.findByRole("heading", { name: "图片历史" })).toBeVisible();
    expect(screen.getByText("极简香水广告，青色轮廓光")).toBeVisible();
  });

  it("keeps prompts and generated history isolated between image and video creators", async () => {
    const router = createMemoryRouter(userRoutes, { initialEntries: ["/create/image"] });
    render(<App fixture router={router} />);
    fireEvent.change(await screen.findByLabelText("图片提示词"), { target: { value: "只属于图片的提示词" } });
    fireEvent.click(screen.getByRole("button", { name: "开始生成" }));
    await router.navigate("/create/video");
    expect(await screen.findByLabelText("视频提示词")).toHaveValue("");
    fireEvent.click(screen.getByRole("link", { name: /视频历史/ }));
    expect(await screen.findByRole("heading", { name: "视频历史" })).toBeVisible();
    expect(screen.queryByText("只属于图片的提示词")).not.toBeInTheDocument();
  });

  it("marks navigation and moves focus after navigation", async () => {
    app("/home");
    fireEvent.click(screen.getByRole("button", { name: "展开创作导航" }));
    fireEvent.click(await screen.findByRole("link", { name: "图片" }));
    await screen.findByRole("heading", { name: "点亮图片创作" });
    await waitFor(() => expect(screen.getByRole("main")).toHaveFocus());
  });

  it("requires phone and OTP instead of entering the home with fixture credentials", async () => {
    app("/user/login");
    expect(screen.getByLabelText("手机号")).toHaveValue("");
    expect(screen.getByLabelText(/验证码/)).toHaveValue("");
    fireEvent.click(await screen.findByRole("button", { name: "登录/注册" }));
    expect(screen.getByRole("heading", { name: "登录/注册" })).toBeVisible();
  });

  it.each(["/user/login", "/user/register"])("requires the agreement before entering the home from %s", async path => {
    const network = vi.fn();
    vi.stubGlobal("fetch", network);
    const router = createMemoryRouter(userRoutes, { initialEntries: [path] });
    render(<App fixture router={router} />);
    fireEvent.change(screen.getByLabelText("手机号"), { target: { value: "13800138000" } });
    fireEvent.change(screen.getByLabelText(/验证码/), { target: { value: "123456" } });
    const agreement = await screen.findByRole("checkbox");
    fireEvent.click(agreement);
    fireEvent.click(screen.getByRole("button", { name: "登录/注册" }));
    expect(await screen.findByRole("status")).toHaveTextContent("请先同意服务条款");
    expect(network).not.toHaveBeenCalled();
    expect(router.state.location.pathname).toBe(path);
  });

  it("shows the read-only profile above keys without tabs", async () => {
    const network = vi.fn(() => { throw new Error("network"); });
    vi.stubGlobal("fetch", network);
    const view = app("/account/profile");
    expect(await screen.findByRole("heading", { name: "个人中心", level: 1 })).toBeVisible();
    const profile = screen.getByRole("region", { name: "个人资料" });
    const keys = screen.getByRole("region", { name: "平台密钥" });
    expect(profile.compareDocumentPosition(keys) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(within(profile).getByText("Nova")).toBeVisible();
    expect(within(keys).getByText("Nova 媒体 Key")).toBeVisible();
    expect(within(profile).queryByRole("textbox")).not.toBeInTheDocument();
    expect(within(keys).queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("tab")).not.toBeInTheDocument();
    expect(network).not.toHaveBeenCalled();
    await audit(view.container);
  });
});
