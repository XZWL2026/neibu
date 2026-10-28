export type PromotionFinanceLevel = "LEVEL_1" | "LEVEL_2";

export type CommissionAction =
  | "FROZEN"
  | "RELEASED"
  | "REVERSED"
  | "WITHDRAWAL_HELD"
  | "WITHDRAWAL_REJECTED"
  | "PAID"
  | "CASH_DEBT"
  | "DEBT_OFFSET";

export type CommissionEntryView = {
  id: string;
  occurredAt: string;
  action: CommissionAction;
  amount4: string;
  balanceBefore4: string;
  balanceAfter4: string;
  reference: string;
  level: PromotionFinanceLevel;
};

export type PromotionTransactionView = {
  id: string;
  maskedUser: string;
  level: PromotionFinanceLevel;
  taskSettlementRef: string;
  successfulPoints4: string;
  cashCommission4: string;
  fundingType: "REAL_RECHARGE" | "REFERRAL_REWARD";
  status: "SUCCESS";
  occurredAt: string;
};

export type PromotionFinanceSnapshot = {
  balance: {
    frozen4: string;
    available4: string;
    withdrawing4: string;
    cumulativePaid4: string;
  };
  commissionTotal4: string;
  commissionEntries: readonly CommissionEntryView[];
  transactionTotals: { successfulPoints4: string; cashCommission4: string };
  transactions: readonly PromotionTransactionView[];
};

export type PromotionFinanceAdapter = { getSnapshot: () => PromotionFinanceSnapshot };

const commissionEntries: CommissionEntryView[] = [
  { id: "CE-LOCAL-001", occurredAt: "2026-09-04 10:08", action: "FROZEN", amount4: "12.3499", balanceBefore4: "0.0000", balanceAfter4: "12.3499", reference: "ORD-LOCAL-001 · TASK-LOCAL-001", level: "LEVEL_1" },
  { id: "CE-LOCAL-002", occurredAt: "2026-09-04 10:18", action: "RELEASED", amount4: "12.3499", balanceBefore4: "150.0000", balanceAfter4: "162.3499", reference: "ORD-LOCAL-001 · TASK-LOCAL-001", level: "LEVEL_1" },
  { id: "CE-LOCAL-003", occurredAt: "2026-09-04 11:03", action: "REVERSED", amount4: "-3.4567", balanceBefore4: "162.3499", balanceAfter4: "158.8932", reference: "ORD-LOCAL-002 · TASK-LOCAL-002", level: "LEVEL_2" },
  { id: "CE-LOCAL-004", occurredAt: "2026-09-04 11:26", action: "WITHDRAWAL_HELD", amount4: "-100.0000", balanceBefore4: "158.8932", balanceAfter4: "58.8932", reference: "WD-LOCAL-004", level: "LEVEL_1" },
  { id: "CE-LOCAL-005", occurredAt: "2026-09-04 12:10", action: "WITHDRAWAL_REJECTED", amount4: "88.0000", balanceBefore4: "58.8932", balanceAfter4: "146.8932", reference: "WD-LOCAL-002", level: "LEVEL_2" },
  { id: "CE-LOCAL-006", occurredAt: "2026-09-04 13:02", action: "PAID", amount4: "-25.0000", balanceBefore4: "125.0000", balanceAfter4: "100.0000", reference: "WD-LOCAL-003", level: "LEVEL_1" },
  { id: "CE-LOCAL-007", occurredAt: "2026-09-04 14:11", action: "CASH_DEBT", amount4: "-2.1256", balanceBefore4: "0.0000", balanceAfter4: "2.1256", reference: "ORD-LOCAL-003 · TASK-LOCAL-003", level: "LEVEL_2" },
  { id: "CE-LOCAL-008", occurredAt: "2026-09-04 15:20", action: "DEBT_OFFSET", amount4: "2.1256", balanceBefore4: "2.1256", balanceAfter4: "0.0000", reference: "ORD-LOCAL-004 · TASK-LOCAL-004", level: "LEVEL_2" }
];

type TransactionCandidate = Omit<PromotionTransactionView, "status"> & {
  status: "SUCCESS" | "PROCESSING" | "FAILED" | "REFUNDED";
};

const transactionCandidates: TransactionCandidate[] = [
  { id: "TX-LOCAL-001", maskedUser: "林＊ · 138****8031", level: "LEVEL_1", taskSettlementRef: "TASK-LOCAL-001 · SET-LOCAL-001", successfulPoints4: "30.1256", cashCommission4: "12.3499", fundingType: "REAL_RECHARGE", status: "SUCCESS", occurredAt: "2026-09-04 10:08" },
  { id: "TX-LOCAL-002", maskedUser: "星＊工作室 · 139****2640", level: "LEVEL_2", taskSettlementRef: "TASK-LOCAL-002 · SET-LOCAL-002", successfulPoints4: "100.0000", cashCommission4: "7.7777", fundingType: "REAL_RECHARGE", status: "SUCCESS", occurredAt: "2026-09-04 11:03" },
  { id: "TX-LOCAL-REWARD", maskedUser: "A＊＊ Studio · api***@example.com", level: "LEVEL_2", taskSettlementRef: "TASK-LOCAL-REWARD · SET-LOCAL-REWARD", successfulPoints4: "20.0000", cashCommission4: "0.0000", fundingType: "REFERRAL_REWARD", status: "SUCCESS", occurredAt: "2026-09-04 12:18" },
  { id: "TX-PROCESSING", maskedUser: "处理中用户＊", level: "LEVEL_1", taskSettlementRef: "TASK-PROCESSING · SET-PROCESSING", successfulPoints4: "0.0000", cashCommission4: "0.0000", fundingType: "REAL_RECHARGE", status: "PROCESSING", occurredAt: "2026-09-04 13:00" },
  { id: "TX-FAILED", maskedUser: "失败用户＊", level: "LEVEL_2", taskSettlementRef: "TASK-FAILED · SET-FAILED", successfulPoints4: "0.0000", cashCommission4: "0.0000", fundingType: "REAL_RECHARGE", status: "FAILED", occurredAt: "2026-09-04 13:10" },
  { id: "TX-REFUNDED", maskedUser: "退款用户＊", level: "LEVEL_1", taskSettlementRef: "TASK-REFUNDED · SET-REFUNDED", successfulPoints4: "10.0000", cashCommission4: "0.0000", fundingType: "REAL_RECHARGE", status: "REFUNDED", occurredAt: "2026-09-04 13:20" }
];

const successfulTransactions = transactionCandidates
  .filter((item) => item.status === "SUCCESS")
  .map(({ status: _status, ...item }) => ({ ...item, status: "SUCCESS" as const }));

export const localPromotionFinanceSnapshot: PromotionFinanceSnapshot = {
  balance: { frozen4: "260.1256", available4: "150.0000", withdrawing4: "125.0000", cumulativePaid4: "430.5000" },
  commissionTotal4: "518.7654",
  commissionEntries,
  transactionTotals: { successfulPoints4: "150.1256", cashCommission4: "20.1276" },
  transactions: successfulTransactions
};

function copySnapshot(snapshot: PromotionFinanceSnapshot): PromotionFinanceSnapshot {
  return {
    balance: { ...snapshot.balance },
    commissionTotal4: snapshot.commissionTotal4,
    commissionEntries: snapshot.commissionEntries.map((item) => ({ ...item })),
    transactionTotals: { ...snapshot.transactionTotals },
    transactions: snapshot.transactions.map((item) => ({ ...item }))
  };
}

export function createLocalPromotionFinanceAdapter(
  snapshot: PromotionFinanceSnapshot = localPromotionFinanceSnapshot
): PromotionFinanceAdapter {
  return { getSnapshot: () => copySnapshot(snapshot) };
}

export function truncateDecimal2(value: string) {
  if (!/^-?(?:0|[1-9]\d*)(?:\.\d+)?$/.test(value)) {
    throw new Error("invalid decimal value");
  }
  const [whole, fraction = ""] = value.split(".");
  return `${whole}.${(fraction + "00").slice(0, 2)}`;
}

export function formatCash2(value: string) {
  return `¥${truncateDecimal2(value)}`;
}
