import type { InjectionKey } from "vue";

export type WithdrawalReportStatus = "ALL" | "WITHDRAWING" | "PENDING_PAYOUT" | "PAID" | "REJECTED";
export type WithdrawalReportFilters = {
  readonly status: WithdrawalReportStatus;
  readonly promoterKeyword: string;
  readonly startDate: string;
  readonly endDate: string;
  readonly month?: string;
  readonly page?: number;
};
export type WithdrawalReportBucket = { readonly amount4: string; readonly count: number };
export type WithdrawalReportEntry = {
  readonly id: string;
  readonly promoter: string;
  readonly amount4: string;
  readonly status: Exclude<WithdrawalReportStatus, "ALL">;
  readonly requestedDate: string;
  readonly settlementMonth?: string;
  readonly transferReference?: string;
  readonly paidAt?: string;
};
export type WithdrawalReportSnapshot = {
  readonly page?: number;
  readonly size?: number;
  readonly total?: number;
  readonly summary: {
    readonly total: WithdrawalReportBucket;
    readonly withdrawing: WithdrawalReportBucket;
    readonly pendingPayout: WithdrawalReportBucket;
    readonly paid: WithdrawalReportBucket;
    readonly rejected: WithdrawalReportBucket;
  };
  readonly entries: readonly WithdrawalReportEntry[];
};
export type AdminWithdrawalReportAdapter = {
  live?: boolean;
  getSnapshot?: () => WithdrawalReportSnapshot;
  load?: () => Promise<WithdrawalReportSnapshot>;
  query: (filters: WithdrawalReportFilters) => Promise<WithdrawalReportSnapshot>;
};

export class InvalidWithdrawalReportDecimalError extends Error {
  constructor() { super("invalid withdrawal report decimal"); }
}

export function truncateWithdrawalReportDecimal2(value: string) {
  if (!/^-?(?:0|[1-9]\d*)(?:\.\d+)?$/.test(value)) throw new InvalidWithdrawalReportDecimalError();
  const [whole, fraction = ""] = value.split(".");
  return `${whole}.${(fraction + "00").slice(0, 2)}`;
}

export function copyValidWithdrawalReportSnapshot(snapshot: WithdrawalReportSnapshot): WithdrawalReportSnapshot {
  const buckets = Object.values(snapshot.summary);
  for (const bucket of buckets) truncateWithdrawalReportDecimal2(bucket.amount4);
  for (const entry of snapshot.entries) truncateWithdrawalReportDecimal2(entry.amount4);
  return {
    page: snapshot.page, size: snapshot.size, total: snapshot.total,
    summary: {
      total: { ...snapshot.summary.total },
      withdrawing: { ...snapshot.summary.withdrawing },
      pendingPayout: { ...snapshot.summary.pendingPayout },
      paid: { ...snapshot.summary.paid },
      rejected: { ...snapshot.summary.rejected }
    },
    entries: snapshot.entries.map((entry) => ({ ...entry }))
  };
}

export const localWithdrawalReportSnapshot: WithdrawalReportSnapshot = {
  summary: {
    total: { amount4: "1372.0000", count: 4 },
    withdrawing: { amount4: "128.0000", count: 1 },
    pendingPayout: { amount4: "860.0000", count: 1 },
    paid: { amount4: "320.0000", count: 1 },
    rejected: { amount4: "64.0000", count: 1 }
  },
  entries: [
    { id: "WD-LOCAL-001", promoter: "Nova · USR-1388", amount4: "128.0000", status: "WITHDRAWING", requestedDate: "2026-09-04" },
    { id: "WD-LOCAL-002", promoter: "星河企业 · USR-2046", amount4: "860.0000", status: "PENDING_PAYOUT", requestedDate: "2026-09-03" },
    { id: "WD-LOCAL-003", promoter: "API Studio · USR-3021", amount4: "320.0000", status: "PAID", requestedDate: "2026-09-02" },
    { id: "WD-LOCAL-004", promoter: "微光 · USR-1088", amount4: "64.0000", status: "REJECTED", requestedDate: "2026-09-01" }
  ]
};

function totalAmount4(entries: readonly WithdrawalReportEntry[]) {
  const units = entries.reduce((total, entry) => {
    truncateWithdrawalReportDecimal2(entry.amount4);
    const negative = entry.amount4.startsWith("-");
    const [whole, fraction = ""] = entry.amount4.replace("-", "").split(".");
    const value = BigInt(whole) * 10000n + BigInt((fraction + "0000").slice(0, 4));
    return total + (negative ? -value : value);
  }, 0n);
  const negative = units < 0n;
  const absolute = negative ? -units : units;
  return `${negative ? "-" : ""}${absolute / 10000n}.${String(absolute % 10000n).padStart(4, "0")}`;
}

function reportBucket(entries: readonly WithdrawalReportEntry[]): WithdrawalReportBucket {
  return { amount4: totalAmount4(entries), count: entries.length };
}

function filteredSnapshot(entries: readonly WithdrawalReportEntry[]): WithdrawalReportSnapshot {
  return {
    summary: {
      total: reportBucket(entries),
      withdrawing: reportBucket(entries.filter((entry) => entry.status === "WITHDRAWING")),
      pendingPayout: reportBucket(entries.filter((entry) => entry.status === "PENDING_PAYOUT")),
      paid: reportBucket(entries.filter((entry) => entry.status === "PAID")),
      rejected: reportBucket(entries.filter((entry) => entry.status === "REJECTED"))
    },
    entries: entries.map((entry) => ({ ...entry }))
  };
}

export function createLocalAdminWithdrawalReportAdapter(
  initial: WithdrawalReportSnapshot = localWithdrawalReportSnapshot
): AdminWithdrawalReportAdapter & { getSnapshot: () => WithdrawalReportSnapshot } {
  return {
    getSnapshot: () => copyValidWithdrawalReportSnapshot(initial),
    query: async (filters) => {
      const keyword = filters.promoterKeyword.trim().toLocaleLowerCase();
      const entries = copyValidWithdrawalReportSnapshot(initial).entries.filter((entry) =>
        (filters.status === "ALL" || entry.status === filters.status)
        && (!keyword || entry.promoter.toLocaleLowerCase().includes(keyword))
        && (!filters.startDate || entry.requestedDate >= filters.startDate)
        && (!filters.endDate || entry.requestedDate <= filters.endDate));
      return filteredSnapshot(entries);
    }
  };
}

export const adminWithdrawalReportAdapterKey: InjectionKey<AdminWithdrawalReportAdapter> = Symbol("admin-withdrawal-report-adapter");
