import type { InjectionKey } from "vue";

export const benefitAuditActionLabels = {
  CASH_COMMISSION_FROZEN: "现金佣金冻结",
  CASH_COMMISSION_RELEASED: "现金佣金释放",
  CASH_COMMISSION_REVERSED: "现金佣金冲回",
  CASH_REFUND_RECOVERY: "充值退款追偿",
  CASH_DEBT_RECORDED: "现金 debt",
  POINT_GRANTED: "邀请积分发放",
  POINT_REVERSED: "邀请积分冲回",
  POINT_DEBT_RECORDED: "point debt",
  POINT_OFFSET: "邀请积分抵扣"
} as const;

export const benefitAuditReasonLabels = {
  ORDER_PAID: "订单支付完成",
  WITHDRAWAL_CONFIRMED: "提现结算确认",
  ORDER_REFUNDED: "订单退款",
  RECHARGE_REFUNDED: "充值退款",
  RECOVERY_SHORTFALL: "追偿余额不足",
  INVITEE_FIRST_RECHARGE: "被邀请人首次充值",
  POINT_REWARD_REVERSED: "积分奖励冲回",
  POINT_DEBT_OFFSET: "积分欠账抵扣"
} as const;

export type BenefitAuditUnit = "ALL" | "CASH" | "POINT";
export type CashBenefitAuditAction = "CASH_COMMISSION_FROZEN" | "CASH_COMMISSION_RELEASED" | "CASH_COMMISSION_REVERSED" | "CASH_REFUND_RECOVERY" | "CASH_DEBT_RECORDED";
export type PointBenefitAuditAction = "POINT_GRANTED" | "POINT_REVERSED" | "POINT_DEBT_RECORDED" | "POINT_OFFSET";
export type BenefitAuditAction = CashBenefitAuditAction | PointBenefitAuditAction;
export type BenefitAuditActionFilter = "ALL" | BenefitAuditAction;
export type BenefitAuditReason = keyof typeof benefitAuditReasonLabels;
export type BenefitAuditCashTier = "DIRECT" | "INDIRECT";
export type BenefitAuditFilters = {
  readonly unit: BenefitAuditUnit;
  readonly action: BenefitAuditActionFilter;
  readonly userKeyword: string;
  readonly referenceKeyword: string;
  readonly startDate: string;
  readonly endDate: string;
};
type BenefitAuditEntryFields = {
  readonly id: string;
  readonly maskedBeneficiary: string;
  readonly sourceEventId: string;
  readonly orderId: string | null;
  readonly lotId: string | null;
  readonly allocationId: string | null;
  readonly withdrawalId: string | null;
  readonly before4: string;
  readonly after4: string;
  readonly delta4: string;
  readonly reason: BenefitAuditReason;
  readonly occurredAt: string;
};
export type BenefitAuditEntry = BenefitAuditEntryFields & (
  | { readonly unit: "CASH"; readonly action: CashBenefitAuditAction; readonly cashTier: BenefitAuditCashTier }
  | { readonly unit: "POINT"; readonly action: PointBenefitAuditAction; readonly cashTier: null }
);
export type BenefitAuditSnapshot = {
  readonly summary: {
    readonly cashCount: number;
    readonly pointCount: number;
    readonly cashDebtCount: number;
    readonly pointDebtCount: number;
  };
  readonly entries: readonly BenefitAuditEntry[];
};
export type AuditRecord = { id: string; unit: string; beneficiaryUserId: string; action: string; amount4: string; sourceEventId: string | null; orderId: string | null; fundingLotId: string | null; allocationId: string | null; withdrawalId: string | null; status: string | null; occurredAt: string };
export type AuditRecords = { items: AuditRecord[]; page: number; size: number; total: number };
export type AdminBenefitAuditAdapter = {
  records?: (filters: { unit: string; userId: string; reference: string; page: number }) => Promise<AuditRecords>;
  load?: () => Promise<BenefitAuditSnapshot>;
  query?: (filters: BenefitAuditFilters) => Promise<BenefitAuditSnapshot>;
};

export class InvalidBenefitAuditDecimalError extends Error {
  constructor() { super("invalid benefit audit Decimal4"); }
}

export function isBenefitAuditDecimal4(value: string) {
  return /^-?(?:0|[1-9]\d*)\.\d{4}$/.test(value);
}

export function copyValidBenefitAuditSnapshot(snapshot: BenefitAuditSnapshot): BenefitAuditSnapshot {
  if (snapshot.entries.some((entry) => ![entry.before4, entry.after4, entry.delta4].every(isBenefitAuditDecimal4))) {
    throw new InvalidBenefitAuditDecimalError();
  }
  if (Object.values(snapshot.summary).some((count) => !Number.isSafeInteger(count) || count < 0)) {
    throw new Error("invalid benefit audit count");
  }
  if (snapshot.entries.some((entry) => !Object.hasOwn(benefitAuditActionLabels, entry.action) || !Object.hasOwn(benefitAuditReasonLabels, entry.reason))) {
    throw new Error("invalid benefit audit action or reason");
  }
  if (snapshot.entries.some((entry) => entry.unit === "CASH"
    ? !entry.action.startsWith("CASH_") || !["DIRECT", "INDIRECT"].includes(entry.cashTier)
    : entry.unit !== "POINT" || !entry.action.startsWith("POINT_") || entry.cashTier !== null)) {
    throw new Error("inconsistent benefit audit unit, action, or cash tier");
  }
  return { summary: { ...snapshot.summary }, entries: snapshot.entries.map((entry) => ({ ...entry })) };
}

export const localBenefitAuditSnapshot: BenefitAuditSnapshot = {
  summary: { cashCount: 5, pointCount: 4, cashDebtCount: 1, pointDebtCount: 1 },
  entries: [
    { id: "AUD-LOCAL-001", unit: "CASH", action: "CASH_COMMISSION_FROZEN", maskedBeneficiary: "N***a · U***388", sourceEventId: "EVT-LOCAL-101", orderId: "ORD-LOCAL-201", lotId: "LOT-LOCAL-301", allocationId: "ALLOC-LOCAL-401", withdrawalId: null, before4: "0.0000", after4: "28.3456", delta4: "28.3456", cashTier: "DIRECT", reason: "ORDER_PAID", occurredAt: "2026-09-01 09:12:30" },
    { id: "AUD-LOCAL-002", unit: "CASH", action: "CASH_COMMISSION_RELEASED", maskedBeneficiary: "N***a · U***388", sourceEventId: "EVT-LOCAL-102", orderId: "ORD-LOCAL-201", lotId: "LOT-LOCAL-301", allocationId: "ALLOC-LOCAL-401", withdrawalId: "WD-LOCAL-501", before4: "28.3456", after4: "0.0000", delta4: "-28.3456", cashTier: "DIRECT", reason: "WITHDRAWAL_CONFIRMED", occurredAt: "2026-09-02 10:20:10" },
    { id: "AUD-LOCAL-003", unit: "CASH", action: "CASH_COMMISSION_REVERSED", maskedBeneficiary: "星***业 · U***046", sourceEventId: "EVT-LOCAL-103", orderId: "ORD-LOCAL-202", lotId: "LOT-LOCAL-302", allocationId: "ALLOC-LOCAL-402", withdrawalId: null, before4: "16.0000", after4: "4.0000", delta4: "-12.0000", cashTier: "INDIRECT", reason: "ORDER_REFUNDED", occurredAt: "2026-09-03 11:08:45" },
    { id: "AUD-LOCAL-004", unit: "CASH", action: "CASH_REFUND_RECOVERY", maskedBeneficiary: "A***o · U***021", sourceEventId: "EVT-LOCAL-104", orderId: "ORD-LOCAL-203", lotId: "LOT-LOCAL-303", allocationId: "ALLOC-LOCAL-403", withdrawalId: null, before4: "20.0000", after4: "8.5000", delta4: "-11.5000", cashTier: "DIRECT", reason: "RECHARGE_REFUNDED", occurredAt: "2026-09-04 08:15:00" },
    { id: "AUD-LOCAL-005", unit: "CASH", action: "CASH_DEBT_RECORDED", maskedBeneficiary: "A***o · U***021", sourceEventId: "EVT-LOCAL-105", orderId: "ORD-LOCAL-203", lotId: "LOT-LOCAL-303", allocationId: null, withdrawalId: null, before4: "0.0000", after4: "3.0000", delta4: "3.0000", cashTier: "DIRECT", reason: "RECOVERY_SHORTFALL", occurredAt: "2026-09-04 08:15:01" },
    { id: "AUD-LOCAL-006", unit: "POINT", action: "POINT_GRANTED", maskedBeneficiary: "微***光 · U***088", sourceEventId: "EVT-LOCAL-106", orderId: "ORD-LOCAL-204", lotId: null, allocationId: "ALLOC-LOCAL-406", withdrawalId: null, before4: "10.0000", after4: "40.0000", delta4: "30.0000", cashTier: null, reason: "INVITEE_FIRST_RECHARGE", occurredAt: "2026-09-01 12:00:00" },
    { id: "AUD-LOCAL-007", unit: "POINT", action: "POINT_REVERSED", maskedBeneficiary: "微***光 · U***088", sourceEventId: "EVT-LOCAL-107", orderId: "ORD-LOCAL-204", lotId: null, allocationId: "ALLOC-LOCAL-406", withdrawalId: null, before4: "40.0000", after4: "15.0000", delta4: "-25.0000", cashTier: null, reason: "POINT_REWARD_REVERSED", occurredAt: "2026-09-03 14:20:00" },
    { id: "AUD-LOCAL-008", unit: "POINT", action: "POINT_DEBT_RECORDED", maskedBeneficiary: "微***光 · U***088", sourceEventId: "EVT-LOCAL-108", orderId: "ORD-LOCAL-204", lotId: null, allocationId: null, withdrawalId: null, before4: "0.0000", after4: "10.0000", delta4: "10.0000", cashTier: null, reason: "POINT_REWARD_REVERSED", occurredAt: "2026-09-03 14:20:01" },
    { id: "AUD-LOCAL-009", unit: "POINT", action: "POINT_OFFSET", maskedBeneficiary: "微***光 · U***088", sourceEventId: "EVT-LOCAL-109", orderId: "ORD-LOCAL-205", lotId: null, allocationId: "ALLOC-LOCAL-409", withdrawalId: null, before4: "10.0000", after4: "4.0000", delta4: "-6.0000", cashTier: null, reason: "POINT_DEBT_OFFSET", occurredAt: "2026-09-04 16:30:00" }
  ]
};

function snapshotFor(entries: readonly BenefitAuditEntry[]): BenefitAuditSnapshot {
  return {
    summary: {
      cashCount: entries.filter((entry) => entry.unit === "CASH").length,
      pointCount: entries.filter((entry) => entry.unit === "POINT").length,
      cashDebtCount: entries.filter((entry) => entry.action === "CASH_DEBT_RECORDED").length,
      pointDebtCount: entries.filter((entry) => entry.action === "POINT_DEBT_RECORDED").length
    },
    entries: entries.map((entry) => ({ ...entry }))
  };
}

export function createLocalAdminBenefitAuditAdapter(
  initial: BenefitAuditSnapshot = localBenefitAuditSnapshot
): AdminBenefitAuditAdapter & Required<Pick<AdminBenefitAuditAdapter, "load" | "query">> {
  const source = copyValidBenefitAuditSnapshot(initial);
  return {
    load: async () => copyValidBenefitAuditSnapshot(source),
    query: async (filters) => {
      const userKeyword = filters.userKeyword.trim().toLocaleLowerCase();
      const referenceKeyword = filters.referenceKeyword.trim().toLocaleLowerCase();
      return snapshotFor(source.entries.filter((entry) =>
        (filters.unit === "ALL" || entry.unit === filters.unit)
        && (filters.action === "ALL" || entry.action === filters.action)
        && (!userKeyword || entry.maskedBeneficiary.toLocaleLowerCase().includes(userKeyword))
        && (!referenceKeyword || [entry.orderId, entry.lotId].some((value) => value?.toLocaleLowerCase().includes(referenceKeyword)))
        && (!filters.startDate || entry.occurredAt.slice(0, 10) >= filters.startDate)
        && (!filters.endDate || entry.occurredAt.slice(0, 10) <= filters.endDate)
      ));
    }
  };
}

export const adminBenefitAuditAdapterKey: InjectionKey<AdminBenefitAuditAdapter> = Symbol("admin-benefit-audit-adapter");
