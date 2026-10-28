import { t, formatMessage, useLanguage } from './i18n';
import { useEffect, useRef, useState, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { ProductionVideoComposer } from "./creator/ProductionVideoComposer";
import { ImageResultPreview } from "./creator/VideoTaskResults";
import { ProductionImageComposer } from "./creator/ProductionImageComposer";
import { CreatorMediaProvider, useCreatorMedia, useOptionalCreatorMedia } from "./creator/CreatorMediaProvider";
import { creatorTaskStateLabel } from "./creator/types";
import { useTheme } from "./theme";
import { YomioHome } from "./home/YomioHome";
import "./home/home.css";
import { AccountProfile } from "./account/AccountProfile";
import { RechargePanel } from "./account/RechargePanel";
import { TransactionHistory } from "./account/TransactionHistory";
import { UserFrame } from "./UserFrame";
import { LoginSpace } from "./auth/LoginSpace";
import brandLogo from "./assets/brand.webp";
import { ApiError, loginWithOtp, loginWithPhone, sendOtp, refreshSession, promotionBindInvitationPostApiV1PromotionsInvitationsBind } from "./api";

import { clearPendingInvitation, usePendingInvitation } from "./promotion/pendingInvitation";

export function AuthPage() {
  useLanguage();
  const { selectTheme } = useTheme();
  const inviteCode=usePendingInvitation();
  const [signedIn,setSignedIn]=useState(false);
  const [invitationOwner,setInvitationOwner]=useState<string|null>(null);
  const phoneOnly = import.meta.env.VITE_PHONE_ONLY_LOGIN_ENABLED === 'true';
  const testOtp = (import.meta.env.MODE === 'development' || import.meta.env.MODE === 'test')
    && import.meta.env.VITE_LOCAL_OTP_ENABLED === 'true';
  const [notice, setNotice] = useState("");
  const [agreement, setAgreement] = useState(true);
  const [phone, setPhone] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [resendAt, setResendAt] = useState(0);
  const [resendSeconds, setResendSeconds] = useState(0);
  useEffect(() => {
    if (!resendAt) return;
    const timer = window.setInterval(() => {
      const remaining = Math.max(0, Math.ceil((resendAt - Date.now()) / 1000));
      setResendSeconds(remaining);
      if (!remaining) setResendAt(0);
    }, 1000);
    return () => window.clearInterval(timer);
  }, [resendAt]);
  const navigate = useNavigate();
  const dialog = useRef<HTMLDialogElement>(null);
  const close = () => navigate('/home', { replace: true });
  useEffect(() => {
    const element = dialog.current;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    if (element?.showModal) element.showModal(); else element?.setAttribute('open', '');
    return () => {
      element?.close?.();
      document.body.style.overflow = previousOverflow;
    };
  }, []);
  const failure = (error: unknown) => {
    if (error instanceof ApiError && error.code === 'PHONE_ONLY_LOGIN_DISABLED') return '手机号直接登录已关闭，请刷新页面使用验证码登录';
    if (error instanceof ApiError && error.code === 'SMS_PROVIDER_UNAVAILABLE') return '短信服务暂时不可用，请稍后重试';
    if (error instanceof ApiError && error.code === 'OTP_INVALID_OR_EXPIRED') return '验证码无效或已过期';
    return error instanceof Error ? error.message : '请求失败，请稍后重试';
  };
  const requestCode = async () => {
    if (busy || signedIn || resendAt > Date.now() || !phone.trim()) return;
    setBusy(true);
    setNotice('');
    try {
      await sendOtp({ phone: phone.trim() });
      setResendAt(Date.now() + 60000);
      setResendSeconds(60);
      setNotice(testOtp ? '测试验证码已就绪，无需短信；请在 5 分钟内使用，60 秒后可重新获取'
        : '验证码已发送，请在 5 分钟内使用，60 秒后可重新获取');
    } catch (error) { setNotice(failure(error)); }
    finally { setBusy(false); }
  };
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!agreement) {
      setNotice("请先同意服务条款");
      return;
    }
    if (busy || !phone.trim() || (!phoneOnly && !/^[0-9]{6}$/.test(code))) return;
    setBusy(true);
    setNotice('');
    try {
      if (!signedIn) {
        if (phoneOnly) await loginWithPhone({ phone: phone.trim() });
        else await loginWithOtp({ phone: phone.trim(), code });
        selectTheme("dark");
        window.dispatchEvent(new Event("xzwl-account-changed"));
      }
      if(inviteCode){
        const session=await refreshSession();
        const current=session.user?.id;
        if(!session.authenticated||!current||(invitationOwner&&invitationOwner!==current)){
          setSignedIn(false);setInvitationOwner(null);setCode('');
          throw new Error('登录账户已变化，请重新验证手机号后绑定邀请。');
        }
        setSignedIn(true);setInvitationOwner(current);
        await promotionBindInvitationPostApiV1PromotionsInvitationsBind({code:inviteCode});
        clearPendingInvitation(inviteCode);
      }
      navigate("/home");
    } catch (error) { setNotice(failure(error)); }
    finally { setBusy(false); }
  };
  return (
    <dialog ref={dialog} className="auth-dialog" aria-labelledby="auth-title" aria-modal="true"
      onCancel={event => { event.preventDefault(); close(); }}>
      <button className="auth-close" type="button" aria-label={t("关闭登录窗口")} onClick={close}>
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg>
      </button>
      <LoginSpace />
      <div className="auth-layout">
      <div className="auth-main">
        <form className="auth-card" onSubmit={submit}>
          <h1 id="auth-title">{t("登录/注册")}</h1>
          <Link className="auth-brand" to="/home" aria-label={t("YOMIO 首页")}><img src={brandLogo} decoding="async" fetchPriority="high" width={600} height={221} alt="" /></Link>
          <p className="auth-tagline">{t("AI 创意平台")}</p>
          {!phoneOnly && testOtp && <p className="notice">{t("测试验证码模式：点击“获取验证码”后，输入配置的测试验证码，无需接收短信。")}</p>}
          <label><span className="sr-only">{t("手机号")}</span><input placeholder={t("请输入手机号")} inputMode="tel" autoComplete="tel" required disabled={signedIn} value={phone} onChange={event => setPhone(event.target.value)} /></label>
          {!phoneOnly && <label><span className="sr-only">{t("验证码")}</span><span className="input-action"><input placeholder={t("请输入验证码")} inputMode="numeric" autoComplete="one-time-code" required disabled={signedIn} pattern="[0-9]{6}" value={code} onChange={event => setCode(event.target.value)} /><button type="button" disabled={busy || signedIn || resendSeconds > 0 || !phone.trim()} onClick={() => void requestCode()}>{t(resendSeconds > 0 ? formatMessage("{0} 秒后重试", resendSeconds) : "获取验证码")}</button></span></label>}
          <label className="check-line"><input type="checkbox" checked={agreement} onChange={(event) => setAgreement(event.target.checked)} />{t("我已阅读并同意服务条款")}</label>
          <button className="btn-primary" type="submit" disabled={busy}>{t(signedIn ? "重试绑定邀请" : "登录/注册")}</button>
          {notice && <p role="status" className="notice">{t(notice)}</p>}{signedIn&&notice&&<Link to="/home">{t("进入首页")}</Link>}
        </form>
      </div>
      </div>
    </dialog>
  );
}

export function HomePage() { return <YomioHome />; }

export function CreatorPage({ kind }: { kind: "image" | "video" }) {
  useLanguage();
  const context = useOptionalCreatorMedia();
  if (!context) {
    return <CreatorMediaProvider><CreatorPageContent kind={kind} /></CreatorMediaProvider>;
  }
  return <CreatorPageContent kind={kind} />;
}

function CreatorPageContent({ kind }: { kind: "image" | "video" }) {
  useLanguage();
  const [query] = useSearchParams();
  const { tasks } = useCreatorMedia();
  const history = query.get("tab") === "history";
  const label = kind === "image" ? "图片" : "视频";
  const preview = kind === "image" ? query.get("preview") : null;
  if (preview) return <UserFrame><section className="creator-stage creator-image"><Link to="/create/image">{t("返回图片创作")}</Link><ImageResultPreview fileId={preview} /></section></UserFrame>;
  return <UserFrame><section className={`creator-stage creator-${kind}`}>
    <div className="creator-top"><div><p className="eyebrow">{t("CREATIVE STUDIO")}</p><strong>{formatMessage("{0}工作台", t(label))}</strong></div><nav aria-label={formatMessage("{0}工作区", t(label))}><Link to={`/create/${kind}`}>{t("探索")}</Link><Link to={`/create/${kind}?tab=history`}>{formatMessage("{0}历史", t(label))}</Link></nav></div>
    <div className="creator-canvas">{history ? null : <div className="stage-empty"><div className="stage-glow" /><img className="stage-brand-logo" src={brandLogo} decoding="async" fetchPriority="high" width={600} height={221} alt="YOMIO" /><h1>{formatMessage("点亮{0}创作", t(label))}</h1><div className="creator-task-list">{tasks[kind].map((task) => <article data-testid={`creator-task-${task.id}`} key={task.id}><strong>{task.prompt}</strong><span role="status" aria-live="polite" aria-label={formatMessage("任务 {0} 状态", task.id)}>{t(creatorTaskStateLabel(task.state))}</span></article>)}</div></div>}</div>
    {kind === "video" ? <ProductionVideoComposer historyOnly={history} /> : <ProductionImageComposer historyOnly={history} />}
  </section></UserFrame>;
}

export function ReservedPage({ kind }: { kind: "canvas" | "commerce" }) {
  useLanguage();
  const name = kind === "canvas" ? "智能画布" : "电商创作";
  return <UserFrame><section className="reserved-page"><span>◇</span><h1>{formatMessage("{0}即将开放", name)}</h1><p>{t("入口已预留，后续产品规格确认后再开放完整能力。")}</p><Link className="btn-primary" to="/home">{t("返回首页")}</Link></section></UserFrame>;
}

export function AccountPage({ page }: { page: "profile" | "recharge" | "transactions" }) {
  useLanguage();
  const titles = {profile:"个人中心",recharge:"账户充值",transactions:"消费记录"};
  return <UserFrame><section className={`account-page${page === "recharge" ? " account-recharge-page" : page === "profile" ? " account-profile-page" : ""}`}><header className={`page-head${page === "recharge" ? " recharge-page-label" : ""}`}><p className="eyebrow">{t("ACCOUNT")}</p><h1>{t(titles[page])}</h1>{page === "profile" && <p>{t("管理你的账户与平台访问凭证。")}</p>}</header>
    {page === "profile" && <AccountProfile />}
    {page === "recharge" && <RechargePanel />}
    {page === "transactions" && <TransactionHistory overview initialCategory="all" />}
  </section></UserFrame>;
}

export function NotFoundPage() {
  useLanguage(); return <UserFrame><section className="reserved-page"><span>404</span><h1>{t("页面不存在")}</h1><p>{t("这个本地页面没有收录。")}</p><Link className="btn-primary" to="/home">{t("返回首页")}</Link></section></UserFrame>; }
