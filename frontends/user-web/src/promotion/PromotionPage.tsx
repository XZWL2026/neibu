import { t, formatMessage, useLanguage } from '../i18n';
import {LiveApplicationPage} from './LivePromotion';
import { useRef, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { useUserSession } from "../userSession";
import { useLocalPromotionViewState } from "./localPromotionViewState";
import { useLocalInvitationProfile } from "./localInvitationProfile";

type LocalApplicationErrors = Partial<Record<"reason" | "channels" | "agreement", string>>;

export function PromotionPage() {
  useLanguage(); return useUserSession().fixture ? <FixturePromotionPage/> : <LiveApplicationPage/>; }
function FixturePromotionPage() {
  useLanguage();
  const { nickname } = useUserSession();
  const { viewState, submitLocalApplication } = useLocalPromotionViewState();
  const { profile: invitationProfile } = useLocalInvitationProfile();
  const [reason, setReason] = useState("");
  const [channels, setChannels] = useState("");
  const [agreement, setAgreement] = useState(false);
  const [errors, setErrors] = useState<LocalApplicationErrors>({});
  const [notice, setNotice] = useState("");
  const reasonField = useRef<HTMLTextAreaElement>(null);
  const channelsField = useRef<HTMLTextAreaElement>(null);
  const agreementField = useRef<HTMLInputElement>(null);

  function submit(event: FormEvent) {
    event.preventDefault();
    const nextErrors: LocalApplicationErrors = {};
    if (!reason.trim()) nextErrors.reason = "请填写申请理由";
    if (!channels.trim()) nextErrors.channels = "请填写计划推广渠道";
    if (!agreement) nextErrors.agreement = "请确认推广规则与佣金结算协议";
    setErrors(nextErrors);
    if (nextErrors.reason) reasonField.current?.focus();
    else if (nextErrors.channels) channelsField.current?.focus();
    else if (nextErrors.agreement) agreementField.current?.focus();
    if (Object.keys(nextErrors).length) return;
    setNotice("本地推广申请已提交，等待审核");
    submitLocalApplication();
  }

  if (viewState.kind === "pending") return <PromotionStatus title={t("推广申请审核中")} description={formatMessage("提交时间：{0}。审核完成前不可重复提交。", viewState.submittedAt)} notice={notice} />;
  if (viewState.kind === "approved") return (
    <section className="promotion-page" aria-labelledby="promotion-title">
      <p className="eyebrow">PROMOTION CENTER</p>
      <h1 id="promotion-title">{t("你已成为推广人")}</h1>
      <p>{nickname}{t("，你的永久邀请码为 ")}<strong>{t(invitationProfile.eligibility === "ENABLED" ? invitationProfile.code : "不可用")}</strong>{t("。推广后台继续沿用当前用户登录会话和邀请链接。")}</p>
      <a className="btn-primary promotion-main-action" href="/promotion/overview">{t("进入推广中心")}</a>
    </section>
  );
  if (viewState.kind === "ineligible") return <PromotionStatus title={t("当前不可申请推广人")} description={t(viewState.reason === "disabled" ? "账号已被系统禁用，暂不展示推广入口。" : "账户类型尚未设置，暂不具备申请资格。")} />;

  return (
    <section className="promotion-page" aria-labelledby="promotion-title">
      <p className="eyebrow">LOCAL PROMOTION APPLICATION</p>
      <h1 id="promotion-title">{t(viewState.kind === "rejected" ? "重新申请成为代理" : "申请成为代理")}</h1>
      <p>{t("本页使用本地 fixture 视图状态，不会发起网络请求，也不定义生产接口字段。")}</p>
      {viewState.kind === "rejected" && <div className="promotion-rejection"><strong>{t("上次申请未通过")}</strong><p>{viewState.reason}</p></div>}
      <form className="promotion-form" onSubmit={submit} noValidate>
        <label htmlFor="promotion-reason">{t("申请理由")}</label>
        <textarea ref={reasonField} id="promotion-reason" value={reason} onChange={(event) => setReason(event.target.value)} aria-describedby={errors.reason ? "promotion-reason-error" : undefined} aria-invalid={Boolean(errors.reason)} />
        {errors.reason && <p id="promotion-reason-error" className="field-error" role="alert">{t(errors.reason)}</p>}
        <label htmlFor="promotion-channels">{t("计划推广渠道")}</label>
        <textarea ref={channelsField} id="promotion-channels" value={channels} onChange={(event) => setChannels(event.target.value)} aria-describedby={errors.channels ? "promotion-channels-error" : undefined} aria-invalid={Boolean(errors.channels)} />
        {errors.channels && <p id="promotion-channels-error" className="field-error" role="alert">{t(errors.channels)}</p>}
        <label className="promotion-agreement">
          <input ref={agreementField} type="checkbox" checked={agreement} onChange={(event) => setAgreement(event.target.checked)} aria-describedby={errors.agreement ? "promotion-agreement-error" : undefined} aria-invalid={Boolean(errors.agreement)} />
          <span>{t("我确认推广规则与佣金结算协议")}</span>
        </label>
        {errors.agreement && <p id="promotion-agreement-error" className="field-error" role="alert">{t(errors.agreement)}</p>}
        <button className="btn-primary promotion-main-action" type="submit">{t("提交推广申请")}</button>
      </form>
    </section>
  );
}

function PromotionStatus({ title, description, notice }: { title: string; description: string; notice?: string }) {
  useLanguage();
  return (
    <section className="promotion-page" aria-labelledby="promotion-title">
      <p className="eyebrow">PROMOTION STATUS</p>
      <h1 id="promotion-title">{t(title)}</h1>
      <p>{t(description)}</p>
      {notice && <p className="promotion-notice" role="status">{t(notice)}</p>}
      <Link className="btn-secondary promotion-main-action" to="/home">{t("返回首页")}</Link>
    </section>
  );
}
