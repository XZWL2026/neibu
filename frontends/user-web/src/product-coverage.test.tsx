import "@testing-library/jest-dom/vitest";
import { cleanup, render } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import * as matchers from "vitest-axe/matchers";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { App } from "./App";
import { CreatorPage } from "./pages";
import { LocalPromotionProvider } from "./promotion/localPromotionViewState";
import { LocalInvitationProvider } from "./promotion/localInvitationProfile";
import { userRoutes } from "./router";
import { UserFixtureProvider } from "./userSession";

expect.extend(matchers);
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

function renderRoute(path: string) {
  return render(<App router={createMemoryRouter(userRoutes, { initialEntries: [path] })} />);
}

async function expectAccessible(path: string) {
  const view = renderRoute(path);
  expect(await axe(view.container, { rules: { "color-contrast": { enabled: false } } })).toHaveNoViolations();
  view.unmount();
}

it("renders local data on a production route", async () => {
  const router = createMemoryRouter(userRoutes, { initialEntries: ["/home"] });
  const view = render(<App router={router} />);
  expect(await view.findByRole("heading", { name: "热门模型" })).toBeVisible();
  expect(view.getByRole("heading", { name: "精选作品" })).toBeVisible();
});

it("supports a deep link to production creator history", async () => {
  const router = createMemoryRouter(
    [{ path: "/create/video", element: <CreatorPage kind="video" /> }],
    { initialEntries: ["/create/video?tab=history"] }
  );
  const view = render(<UserFixtureProvider><LocalPromotionProvider><LocalInvitationProvider><RouterProvider router={router} /></LocalInvitationProvider></LocalPromotionProvider></UserFixtureProvider>);
  expect(await view.findByRole("heading", { name: "视频历史" })).toBeVisible();
  expect(view.getByRole("region", { name: "视频历史" })).toBeVisible();
  expect(view.queryByText("两位舞者在金色剧场半空相遇")).not.toBeInTheDocument();
});

it("works offline across production login, creator, and account routes", async () => {
  const network = vi.fn(() => { throw new Error("network access is forbidden"); });
  vi.stubGlobal("fetch", network);
  const router = createMemoryRouter(userRoutes, { initialEntries: ["/user/login"] });
  const login = render(<App router={router} />);
  expect(login.getByRole("main")).toBeVisible();
  login.unmount();
  for (const path of ["/create/image", "/account/profile", "/account/transactions"]) {
    const view = renderRoute(path);
    expect(view.getByRole("main")).toBeVisible();
    view.unmount();
  }
  expect(network).not.toHaveBeenCalled();
});

it("remains accessible on production login, creator, account form, and account table routes", async () => {
  const router = createMemoryRouter(userRoutes, { initialEntries: ["/user/login"] });
  const view = render(<App router={router} />);
  expect(await axe(view.container, { rules: { "color-contrast": { enabled: false } } })).toHaveNoViolations();
  view.unmount();
  await expectAccessible("/create/video");
  await expectAccessible("/account/profile");
  await expectAccessible("/account/transactions");
});
