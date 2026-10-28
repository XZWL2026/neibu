import { t, useLanguage, languages, selectLanguage } from './i18n';
import './api-portal/api-portal.css';
import { usePendingInvitation } from "./promotion/pendingInvitation";
import {PortalLoginLinks} from "./PortalLoginLinks";
import {LivePromotionLinks} from './promotion/LivePromotion';
import { formatAmount } from '../../shared/formatAmount';
import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { accountStatusLabels, accountTypeLabels, useUserSession } from "./userSession";
import { useLocalPromotionViewState } from "./promotion/localPromotionViewState";
import { useLocalInvitationProfile } from "./promotion/localInvitationProfile";
import brandLogo from "./assets/brand.webp";
import { ThemeToggle } from './theme';
import { CreationNavigation } from './CreationNavigation';

export function UserHeaderActions() {
  const language = useLanguage();
  const { userId, nickname, avatarUrl, avatarFileId, currentKey, accountType, status, logout, error, fixture, authenticated } = useUserSession();
  const navigate = useNavigate();
  const [loggingOut, setLoggingOut] = useState(false);
  async function signOut() {
    setLoggingOut(true);
    try { await logout(); navigate("/user/login"); }
    catch { /* The shared account state displays the failure. */ }
    finally { setLoggingOut(false); }
  }
  const { viewState: promotionViewState } = useLocalPromotionViewState();
  const { profile: invitationProfile } = useLocalInvitationProfile();
  const location = useLocation();
  const profilePage = location.pathname === "/account/profile";
  const [menuOpen, setMenuOpen] = useState(false);
  const [languageOpen, setLanguageOpen] = useState(false);
  const [invitationTipOpen, setInvitationTipOpen] = useState(false);
  const avatarRef = useRef<HTMLButtonElement>(null);
  const languageRef = useRef<HTMLButtonElement>(null);


  useEffect(() => {
    if (!menuOpen && !languageOpen) return;
    function closeOutside(event: PointerEvent) {
      const active = menuOpen ? avatarRef.current : languageRef.current;
      if (!active?.parentElement?.contains(event.target as Node)) {
        setMenuOpen(false); setLanguageOpen(false);
      }
    }
    function closeOnEscape(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      (menuOpen ? avatarRef : languageRef).current?.focus();
      setMenuOpen(false); setLanguageOpen(false);
    }
    document.addEventListener("pointerdown", closeOutside);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("pointerdown", closeOutside);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [menuOpen, languageOpen]);

  useEffect(() => {
    setMenuOpen(false);
    setLanguageOpen(false);
    setInvitationTipOpen(false);
  }, [location.pathname]);

  return (
        <div className="header-actions">
          <div className="language-wrap">
            <button ref={languageRef} className="header-icon-button" type="button" aria-label={t("语言切换")} title={t("语言切换")} aria-expanded={languageOpen} aria-controls="header-language" onClick={() => { setLanguageOpen(!languageOpen); setMenuOpen(false); }}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true"><circle cx="12" cy="12" r="9"/><ellipse cx="12" cy="12" rx="4" ry="9"/><path d="M3 12h18M5 6h14M5 18h14"/></svg>
            </button>
            {languageOpen && <div className="language-menu" id="header-language">{languages.map(item => <button key={item.code} type="button" lang={item.code} aria-pressed={language === item.code} onClick={() => { selectLanguage(item.code); setLanguageOpen(false); languageRef.current?.focus(); }}>{item.name}{language === item.code && <span aria-hidden="true">✓</span>}</button>)}</div>}
          </div>
          <ThemeToggle />
          <span className="header-divider" aria-hidden="true"/>
          <Link className="header-icon-button invitation-link" to="/account/referrals" aria-label={t("邀请")} aria-describedby={invitationTipOpen ? "invitation-tip" : undefined}
            onMouseEnter={() => setInvitationTipOpen(true)} onMouseLeave={() => setInvitationTipOpen(false)}
            onFocus={() => setInvitationTipOpen(true)} onBlur={() => setInvitationTipOpen(false)}
            onKeyDown={event => { if (event.key === "Escape") setInvitationTipOpen(false); }}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M3 8h18v4H3zM5 12v9h14v-9M12 8v13M12 8H8a3 3 0 1 1 3-3l1 3Zm0 0h4a3 3 0 1 0-3-3l-1 3Z"/><path className="gift-tag" d="m18 15 4 3-3 4-4-3z"/></svg>
            {invitationTipOpen && <span className="invitation-tip" id="invitation-tip" role="tooltip"><span aria-hidden="true">🎁</span>{t("邀请好友，获取积分")}</span>}
          </Link>
          <span className="header-divider" aria-hidden="true"/>
          <div className="header-wallet">
            <span className="header-points" aria-label={t("积分余额")} title={t(currentKey ? formatAmount(currentKey.balance) : "积分暂不可用")}>
              <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="1" y="1" width="22" height="22" rx="8" fill="currentColor"/><path d="m12 5 2 5 5 2-5 2-2 5-2-5-5-2 5-2z" fill="#102b30"/></svg>
              <span>{t(currentKey ? formatAmount(currentKey.balance).replace(/\s*积分$/, "") : "—")}</span>
            </span>
            <Link className="recharge-link" to="/account/recharge">{t("充值")}</Link>
          </div>
          {!authenticated && !(status === "loading" && avatarUrl) ? (
            status === "loading" ? <span className={`${profilePage ? "profile-header-avatar" : "avatar-button"} avatar-placeholder`} role="status" aria-label={t("正在读取账户")} />
              : <Link className="auth-entry-link" to="/user/login">{t("登录/注册")}</Link>
          ) : <div className="avatar-wrap">
            <button ref={avatarRef} className="avatar-button" type="button" aria-label={t("打开用户菜单")} aria-expanded={menuOpen} aria-controls="header-account-menu" onClick={() => { setMenuOpen(!menuOpen); setLanguageOpen(false); }}>{profilePage ? <><span className="profile-header-avatar" aria-hidden="true">{avatarUrl ? <img src={avatarUrl} alt="" /> : <svg viewBox="0 0 32 32" fill="currentColor"><circle cx="16" cy="10" r="6"/><path d="M5 28v-3c0-10 22-10 22 0v3Z"/></svg>}</span><span>{nickname || t("账户")}</span><svg className="profile-menu-chevron" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><path d="m4 7 6 6 6-6"/></svg></> : avatarUrl ? <img className="account-menu-avatar" src={avatarUrl} alt="" /> : avatarFileId ? null : nickname.slice(0, 1).toUpperCase() || t("账户")}</button>
            {menuOpen && (
              <div className="avatar-menu" id="header-account-menu">
                <div className="avatar-menu-head"><strong>{nickname || t("账户")}</strong><span>{t(accountType ? accountTypeLabels[accountType] : accountStatusLabels[status])}</span></div>
                <Link to="/account/profile">{t("个人中心")}</Link>
                <Link to="/account/transactions">{t("消费记录")}</Link>
                <PortalLoginLinks key={userId} enabled={authenticated && !fixture}/>
                {!fixture && <LivePromotionLinks/>}
                {fixture && invitationProfile.eligibility === "ENABLED" && <Link to="/account/referrals">{t("邀请有礼")}</Link>}
                {fixture && promotionViewState.kind === "approved" && <a href="/promotion/overview">{t("推广登录")}</a>}
                <button type="button" className="btn-secondary" disabled={loggingOut} onClick={() => void signOut()}>{t("退出登录")}</button>{error && <p role="alert">{t(error)}</p>}
              </div>
            )}
          </div>}
        </div>
  );
}

export function UserFrame({ children, headerContent }: { children: ReactNode; headerContent?: ReactNode }) {
  useLanguage();
  usePendingInvitation();
  const location = useLocation();
  const profilePage = location.pathname === "/account/profile";
  const referralPage = location.pathname === '/account/referrals';
  const transactionPage = location.pathname === '/account/transactions';
  const creationPage = ['/create/image', '/create/video', '/commerce'].includes(location.pathname) || location.pathname.startsWith('/commerce/');
  const mainRef = useRef<HTMLElement>(null);
  useLayoutEffect(() => {
    const mediaHistory = ['/create/image', '/create/video'].includes(location.pathname)
      && new URLSearchParams(location.search).get('tab') === 'history';
    if ((creationPage || ['/home', '/account/profile'].includes(location.pathname) || mediaHistory) && !location.hash) window.scrollTo({ top: 0, left: 0, behavior: 'instant' });
  }, [creationPage, location.key, location.pathname, location.search, location.hash]);
  useEffect(() => {
    mainRef.current?.focus({ preventScroll: creationPage || ["/home", "/account/profile"].includes(location.pathname) });
  }, [creationPage, location.pathname]);

  return (
    <div className={`app-frame circle-navigation-frame${profilePage ? " profile-frame" : ""}${transactionPage ? " transaction-frame" : ""}${referralPage ? ' referral-frame' : location.pathname === '/home' ? ' home-frame' : ['/create/image', '/create/video'].includes(location.pathname) ? ' creator-frame' : ''}`}>
      <a className="skip-link" href="#main-content">{t("跳到主要内容")}</a>
      <header className="app-header">
        <Link className="brand-block" to="/home" aria-label={t("XZWLab 首页")}>
          <img className="brand-logo" src={brandLogo} decoding="async" fetchPriority="high" width={600} height={221} alt="" />
        </Link>
        {profilePage && <span className="profile-brand-tagline">{t("让想象 成为影像")}</span>}
        {headerContent}
        <UserHeaderActions />
      </header>
      <CreationNavigation />
      <main id="main-content" className="main-content" ref={mainRef} tabIndex={-1}>{children}</main>
      {!creationPage && !referralPage && !profilePage && !transactionPage && <footer className="navigation-page-footer">
        {location.pathname === '/account/recharge' && <><strong>YOMIO</strong><span>© {new Date().getFullYear()} YOMIO</span><span>All rights reserved.</span></>}
        <a href="https://beian.miit.gov.cn" target="_blank" rel="noopener noreferrer">粤ICP备2026023603号</a>
      </footer>}
    </div>
  );
}
