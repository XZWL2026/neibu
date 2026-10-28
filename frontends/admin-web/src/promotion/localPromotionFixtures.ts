export type LocalPromotionApplicationStatus = "待审核" | "已通过" | "已拒绝";

export type LocalPromotionApplicationView = {
  id: string;
  applicant: string;
  accountType: "个人" | "企业" | "API";
  channel: string;
  submittedAt: string;
  status: LocalPromotionApplicationStatus;
  rejectionReason?: string;
};

export const localPromotionApplications: LocalPromotionApplicationView[] = [
  { id: "APP-LOCAL-001", applicant: "Nova · USR-1388", accountType: "个人", channel: "创作者社群", submittedAt: "2026-08-29 10:20", status: "待审核" },
  { id: "APP-LOCAL-002", applicant: "星河企业 · USR-2046", accountType: "企业", channel: "企业客户活动", submittedAt: "2026-08-29 11:45", status: "待审核" },
  { id: "APP-LOCAL-003", applicant: "API Studio · USR-3021", accountType: "API", channel: "开发者社区", submittedAt: "2026-08-28 16:10", status: "已通过" },
  { id: "APP-LOCAL-004", applicant: "微光 · USR-1088", accountType: "个人", channel: "未提供", submittedAt: "2026-08-27 09:15", status: "已拒绝", rejectionReason: "推广渠道说明不完整" }
];

export type LocalReferralAccountView = {
  id: string;
  name: string;
  label: string;
  accountType: "PERSONAL" | "ENTERPRISE" | "API" | "UNSET";
  enabled: boolean;
  approvedPromoter: boolean;
};

export const localReferralAccounts: LocalReferralAccountView[] = [
  { id: "USR-LOCAL-900", name: "林＊", label: "林＊ · 138****8031", accountType: "PERSONAL", enabled: true, approvedPromoter: false },
  { id: "USR-LOCAL-901", name: "星＊", label: "星＊ · 139****2640", accountType: "ENTERPRISE", enabled: true, approvedPromoter: false },
  { id: "USR-INVITER-PERSONAL", name: "Nova", label: "Nova · 138****1388", accountType: "PERSONAL", enabled: true, approvedPromoter: true },
  { id: "USR-INVITER-ENTERPRISE", name: "星河企业", label: "星河企业 · 139****2046", accountType: "ENTERPRISE", enabled: true, approvedPromoter: false },
  { id: "USR-INVITER-API", name: "API Studio", label: "API Studio · api***@example.com", accountType: "API", enabled: true, approvedPromoter: false },
  { id: "USR-UNSET", name: "未设置账户", label: "未设置账户", accountType: "UNSET", enabled: true, approvedPromoter: false },
  { id: "USR-DISABLED", name: "已禁用账户", label: "已禁用账户", accountType: "PERSONAL", enabled: false, approvedPromoter: false }
];

export const localAttributionUsers = localReferralAccounts.filter((account) => account.enabled && account.accountType !== "UNSET");

export type LocalAttributionVersionView = {
  userId: string;
  version: number;
  directInviterId: string;
  directInviterName: string;
  pointBeneficiaryName: string;
  cashRootName: string;
  cashRootId?: string;
  cashTier: "LEVEL_1" | "LEVEL_2" | "NONE";
  reason: string;
  changedAt: string;
};

export const localAttributionVersions: LocalAttributionVersionView[] = [
  { userId: "USR-LOCAL-901", version: 1, directInviterId: "USR-INVITER-PERSONAL", directInviterName: "Nova", pointBeneficiaryName: "无", cashRootId: "USR-INVITER-PERSONAL", cashRootName: "Nova", cashTier: "LEVEL_1", reason: "注册推广码绑定", changedAt: "2026-08-20 09:12" },
  { userId: "USR-INVITER-ENTERPRISE", version: 1, directInviterId: "USR-INVITER-PERSONAL", directInviterName: "Nova", pointBeneficiaryName: "无", cashRootId: "USR-INVITER-PERSONAL", cashRootName: "Nova", cashTier: "LEVEL_1", reason: "注册推广码绑定", changedAt: "2026-08-21 11:30" }
];
