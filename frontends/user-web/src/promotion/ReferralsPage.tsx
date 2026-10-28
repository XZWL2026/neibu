import { t, formatMessage, useLanguage } from '../i18n';
import {useUserSession} from '../userSession';
import {LiveReferralsPage} from './LivePromotion';
import { formatAmount } from '../../../shared/formatAmount';
import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { copyInvitationWithLifecycle, useLocalInvitationProfile } from "./localInvitationProfile";
import { type InvitationRewardAction } from "./localInvitationRewards";

const rewardActionLabels: Record<InvitationRewardAction, string> = {
  POINT_REWARD_GRANTED: "奖励发放",
  POINT_REWARD_REVERSED: "奖励冲回",
  POINT_DEBT_CREATED: "待追回创建",
  POINT_DEBT_OFFSET: "待追回抵扣"
};

export function ReferralsPage() {
  useLanguage(); return useUserSession().fixture ? <FixtureReferralsPage/> : <LiveReferralsPage/>; }
function FixtureReferralsPage() {
  useLanguage();
  const { profile, clipboard, rewardSnapshot } = useLocalInvitationProfile();
  const mountedRef = useRef(true);
  const copyingLock = useRef(false);
  const [copying, setCopying] = useState(false);
  const [copyStatus, setCopyStatus] = useState("");
  const [rewardAction, setRewardAction] = useState<"ALL" | InvitationRewardAction>("ALL");

  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);

  if (profile.eligibility !== "ENABLED") {
    return (
      <section className="promotion-page referral-page" aria-labelledby="referral-title">
        <p className="eyebrow">REFERRAL PROGRAM</p>
        <h1 id="referral-title">{t("当前不可使用邀请有礼")}</h1>
        <p>{t(profile.eligibility === "UNSET" ? "账户类型尚未设置，暂不具备邀请资格。" : "账号已被系统禁用，无法复制或建立新邀请。")}</p>
        <Link className="btn-secondary promotion-main-action" to="/home">{t("返回首页")}</Link>
      </section>
    );
  }
  if (!rewardSnapshot) throw new Error("enabled invitation profile requires reward snapshot");

  const visibleRewardEntries = rewardSnapshot.entries.filter((entry) =>
    rewardAction === "ALL" || entry.action === rewardAction);

  async function copyInvitationLink() {
    if (profile.eligibility !== "ENABLED" || copyingLock.current) return;
    copyingLock.current = true;
    setCopying(true);
    setCopyStatus("");
    await copyInvitationWithLifecycle(clipboard, profile.link, {
      isActive: () => mountedRef.current,
      success: () => setCopyStatus("邀请链接已复制"),
      failure: () => setCopyStatus("复制失败，请手动复制邀请链接"),
      settled: () => {
        copyingLock.current = false;
        setCopying(false);
      }
    });
  }

  return (
    <section className="promotion-page referral-page" aria-labelledby="referral-title">
      <p className="eyebrow">REFERRAL PROGRAM</p>
      <h1 id="referral-title">{t("邀请有礼")}</h1>
      <p>{t("你的邀请码与注册链接均永久有效，成为推广人后继续复用同一账号、同一邀请码与链接。")}</p>

      <article className="invitation-card" aria-labelledby="invitation-profile-title">
        <div>
          <p className="eyebrow">PERMANENT INVITATION</p>
          <h2 id="invitation-profile-title">{t("永久邀请码")}</h2>
        </div>
        <dl className="invitation-summary">
          <div><dt>{t("邀请码")}</dt><dd>{profile.code}</dd></div>
          <div><dt>{t("账户类型")}</dt><dd>{profile.accountType}</dd></div>
          <div><dt>{t("有效期")}</dt><dd>{t("永久有效")}</dd></div>
        </dl>
        <label htmlFor="invitation-link">{t("完整注册链接")}</label>
        <div className="invitation-copy-row">
          <input id="invitation-link" value={profile.link} readOnly />
          <button className="btn-primary" type="button" aria-label={t(copying ? "正在复制邀请链接" : "复制邀请链接")} disabled={copying} onClick={copyInvitationLink}>{t(copying ? "复制中…" : "复制邀请链接")}</button>
        </div>
        <p className="invitation-reuse-note">{t("成为推广人后继续复用，不会生成第二个邀请码。")}</p>
        <p className="copy-status" role="status" aria-live="polite">{t(copyStatus)}</p>
      </article>

      <section className="direct-invites" aria-labelledby="direct-invites-title">
        <div className="direct-invites-heading">
          <div><h2 id="direct-invites-title">{t("直接邀请")}</h2><p>{t("这里只展示你自己直接邀请的用户，不包含下级用户。")}</p></div>
          <span>{formatMessage("{0} 人", profile.directInvites.length)}</span>
        </div>
        {profile.directInvites.length ? (
          <div className="referral-table-wrap">
            <table>
              <caption className="sr-only">{t("直接邀请用户列表")}</caption>
              <thead><tr><th>{t("用户编号")}</th><th>{t("脱敏用户")}</th><th>{t("加入时间")}</th></tr></thead>
              <tbody>{profile.directInvites.map((invite) => <tr key={invite.id}><td>{invite.id}</td><td>{invite.maskedIdentity}</td><td>{invite.joinedAt}</td></tr>)}</tbody>
            </table>
          </div>
        ) : <div className="referral-empty-state"><strong>{t("暂无直接邀请用户")}</strong><p>{t("分享你的永久邀请链接后，直接邀请用户会显示在这里。")}</p></div>}
      </section>

      <section className="invitation-rewards" aria-labelledby="invitation-rewards-title">
        <div className="direct-invites-heading">
          <div><h2 id="invitation-rewards-title">{t("邀请积分账户")}</h2><p>{t("以下余额和明细均来自服务端快照，页面不会自行汇总或重算。")}</p></div>
          <span>{t("只读")}</span>
        </div>
        <dl className="reward-summary">
          <div data-testid="cumulative-reward"><dt>{t("累计奖励")}</dt><dd>{formatMessage("{0} 积分", formatAmount(rewardSnapshot.summary.cumulativeReward4))}</dd></div>
        </dl>
        <aside className="reward-rules" aria-label={t("邀请积分规则")}>
          <strong>{t("积分奖励只能用于消费，不可提现。")}</strong>
          <p>{t("规则示例：A 只获得现金佣金，不获得积分；B 因 C 成功消费获得积分；C 因 D 成功消费获得积分。")}</p>
          <p>{t("奖励积分再次消费不产生新的邀请奖励。")}</p>
        </aside>
        <label className="reward-filter">{t("动作筛选")}<select aria-label={t("筛选奖励动作")} value={rewardAction} onChange={(event) => setRewardAction(event.target.value as "ALL" | InvitationRewardAction)}>
            <option value="ALL">{t("全部动作")}</option>
            {Object.entries(rewardActionLabels).map(([value, label]) => <option key={value} value={value}>{t(label)}</option>)}
          </select>
        </label>
        <div className="referral-table-wrap reward-table-wrap">
          <table>
            <caption>{t("邀请积分奖励明细")}</caption>
            <thead><tr><th>{t("动作")}</th><th>{t("邀请用户")}</th><th>{t("积分变动")}</th><th>{t("变更前")}</th><th>{t("变更后")}</th><th>{t("任务 / 结算")}</th><th>{t("时间")}</th></tr></thead>
            <tbody>
              {visibleRewardEntries.map((entry) => <tr key={entry.id} className="reward-entry-row"><td>{t(rewardActionLabels[entry.action])}</td><td>{entry.maskedInvitee}</td><td>{formatAmount(entry.amount4)}</td><td>{formatAmount(entry.balanceBefore4)}</td><td>{formatAmount(entry.balanceAfter4)}</td><td>{entry.taskSettlementRef}</td><td>{entry.occurredAt}</td></tr>)}
              {!visibleRewardEntries.length && <tr><td colSpan={7}>{t("当前筛选没有邀请积分明细")}</td></tr>}
            </tbody>
          </table>
        </div>
      </section>
    </section>
  );
}
