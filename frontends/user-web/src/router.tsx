import { t, useLanguage } from './i18n';
import { createBrowserRouter, Link, Navigate, useLocation, type RouteObject } from "react-router-dom";
import { CanvasPage } from "./canvas/CanvasHome";
import { AccountPage, AuthPage, CreatorPage, HomePage, NotFoundPage } from "./pages";
import { PromotionPage } from "./promotion/PromotionPage";
import { ReferralsPage } from "./promotion/ReferralsPage";
import { ApiPortal } from "./api-portal/ApiPortal";
import { UserFrame } from "./UserFrame";
import { ProductSuitePage } from "./commerce/ProductSuitePage";
import { CommerceVideoPage } from "./commerce/CommerceVideoPage";
import { CommerceWorkbench } from "./commerce/CommerceWorkbench";
import { useUserSession } from "./userSession";

function CommerceBackLink() {
  useLanguage();
  return <Link to="/commerce">
    <span className="suite-back-icon" aria-hidden="true"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="m12 5-7 7 7 7M5 12h14" /></svg></span>
    <span>{t("返回电商创作")}</span>
  </Link>;
}

function RootEntry() {
  const account = useUserSession();
  // Loading or a failed account read does not prove the session has expired.
  if (account.status === 'loading' || account.status === 'error') return <HomePage />;
  return <Navigate to={account.status === 'anonymous' ? '/user/login' : '/home'} replace />;
}

function AuthEntry() {
  const location = useLocation();
  if (new URLSearchParams(location.search).has('ref')) return <Navigate to={`/home${location.search}`} replace />;
  return <><div className="auth-home-background" inert aria-hidden="true"><HomePage /></div><AuthPage /></>;
}

export const userRoutes: RouteObject[] = [
  { id: "user-root", path: "/", element: <RootEntry /> },
  { id: "user-login", path: "/user/login", element: <AuthEntry /> },
  { id: "user-register", path: "/user/register", element: <AuthEntry /> },
  { id: "user-api-docs", path: "/api-docs", element: <ApiPortal /> },
  { id: "user-home", path: "/home", element: <HomePage /> },
  { id: "user-create-image", path: "/create/image", element: <CreatorPage kind="image" /> },
  { id: "user-create-video", path: "/create/video", element: <CreatorPage kind="video" /> },
  { id: "user-canvas", path: "/canvas", element: <CanvasPage /> },
  { id: "user-commerce", path: "/commerce", element: <CommerceWorkbench /> },
  { id: "user-commerce-product-suite", path: "/commerce/product-suite", element: <ProductSuitePage backLink={<CommerceBackLink />} /> },
  ...(['detail-page', 'image-replica', 'product-retouch', 'model-wear', 'apparel-set'] as const).map(tool => ({
    id: `user-commerce-${tool}`, path: `/commerce/${tool}`, element: <ProductSuitePage tool={tool} backLink={<CommerceBackLink />} />,
  })),
  ...(["video-replica", "seller-video"] as const).map(tool => ({
    id: `user-commerce-${tool}`, path: `/commerce/${tool}`, element: <CommerceVideoPage tool={tool} />,
  })),
  { id: "user-profile", path: "/account/profile", element: <AccountPage page="profile" /> },
  { id: "user-keys", path: "/account/keys", element: <Navigate to="/account/profile#keys" replace /> },
  { id: "user-recharge", path: "/account/recharge", element: <AccountPage page="recharge" /> },
  { id: "user-transactions", path: "/account/transactions", element: <AccountPage page="transactions" /> },
  { id: "user-referrals", path: "/account/referrals", element: <UserFrame><ReferralsPage /></UserFrame> },
  { id: "user-promotion", path: "/account/promotion", element: <UserFrame><PromotionPage /></UserFrame> },
  { id: "user-not-found", path: "*", element: <NotFoundPage /> }
];

export const userRouter = createBrowserRouter(userRoutes, { basename: import.meta.env.BASE_URL });
