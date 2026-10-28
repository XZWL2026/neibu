import { localWithdrawalView, type LocalWithdrawalView } from "./localWithdrawalFixtures";

export type LocalPromotionProfileView = {
  avatarText: string;
  nickname: string;
  userNumber: string;
  promoterNumber: string;
  code: string;
  link: string;
};

export const localPromotionProfileView: LocalPromotionProfileView = {
  avatarText: "N",
  nickname: "Nova",
  userNumber: "USR-1388",
  promoterNumber: "PROMOTER-000301",
  code: "NOVA88",
  link: "https://xzwlab.example/register?ref=NOVA88"
};

export type LocalReferralView = {
  id: string;
  maskedIdentity: string;
  boundAt: string;
  source: string;
  level: "LEVEL_1" | "LEVEL_2";
};

export const localReferralViews: LocalReferralView[] = [
  { id: "USR-LOCAL-901", maskedIdentity: "林＊ · 138****8031", boundAt: "2026-08-28 14:20", source: "推广链接注册", level: "LEVEL_1" },
  { id: "USR-LOCAL-902", maskedIdentity: "星＊工作室 · 139****2640", boundAt: "2026-08-24 09:12", source: "一级用户直接邀请", level: "LEVEL_2" },
  { id: "USR-LOCAL-903", maskedIdentity: "A＊＊ Studio · api***@example.com", boundAt: "2026-08-19 18:06", source: "推广码注册", level: "LEVEL_1" },
  { id: "USR-LOCAL-904", maskedIdentity: "陈＊ · 136****4210", boundAt: "2026-08-17 12:30", source: "一级用户直接邀请", level: "LEVEL_2" }
];

export type LocalPromotionSessionView = {
  profile: LocalPromotionProfileView;
  referrals: readonly LocalReferralView[];
  withdrawal?: LocalWithdrawalView;
};

export const localPromotionSessionView: LocalPromotionSessionView = {
  profile: localPromotionProfileView,
  referrals: localReferralViews,
  withdrawal: localWithdrawalView
};
