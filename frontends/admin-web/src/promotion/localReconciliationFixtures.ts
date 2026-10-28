import type { InjectionKey } from "vue";

export type ReconciliationAnomalyType = "ALL" | "PENDING_DEPENDENCY" | "CASH_DEBT" | "POINT_DEBT";
export type ReconciliationFilters = { readonly type: ReconciliationAnomalyType; readonly keyword: string };
export type ReconciliationEntry = {
  readonly id: string;
  readonly type: Exclude<ReconciliationAnomalyType, "ALL">;
  readonly subjectRef: string;
  readonly maskedSubject: string;
  readonly sourceId: string;
  readonly orderId: string | null;
  readonly lotId: string | null;
  readonly allocationId: string | null;
  readonly expected4: string;
  readonly actual4: string;
  readonly difference4: string;
  readonly detectedAt: string;
  readonly status: "OPEN" | "REVIEWING";
};
export type ReconciliationSnapshot = {
  readonly summary: {
    readonly total: number;
    readonly pendingDependency: number;
    readonly cashDebt: number;
    readonly pointDebt: number;
  };
  readonly entries: readonly ReconciliationEntry[];
};
export type ReconciliationDryRunPreview = {
  readonly dryRun: true;
  readonly executable: false;
  readonly anomalyId: string;
  readonly generatedAt: string;
  readonly changes: readonly {
    readonly label: string;
    readonly before4: string;
    readonly after4: string;
    readonly difference4: string;
  }[];
};
export type ReconciledBalances = { differences: { userId: string; dimension: string; expected4: string; actual4: string; difference4: string }[]; debts: { userId: string; unit: string; amount4: string }[]; pendingRewardDeliveries: number; generatedAt: string; dryRun: true; executable: false };
export type AdminReconciliationAdapter = {
  balances?: () => Promise<ReconciledBalances>;
  previewBalances?: (reason: string) => Promise<ReconciledBalances>;
  load?: () => Promise<ReconciliationSnapshot>;
  query?: (filters: ReconciliationFilters) => Promise<ReconciliationSnapshot>;
  repairDryRun?: (anomalyId: string, reason: string) => Promise<ReconciliationDryRunPreview>;
};

export class InvalidReconciliationDecimalError extends Error {
  constructor() { super("invalid reconciliation Decimal4"); }
}

export function isReconciliationDecimal4(value: string) {
  return /^-?(?:0|[1-9]\d*)\.\d{4}$/.test(value);
}

function subtractReconciliationDecimal4(after4: string, before4: string) {
  const units = (value: string) => {
    if (!isReconciliationDecimal4(value)) throw new InvalidReconciliationDecimalError();
    const negative = value.startsWith("-");
    const [whole, fraction] = value.replace("-", "").split(".");
    const parsed = BigInt(whole) * 10000n + BigInt(fraction);
    return negative ? -parsed : parsed;
  };
  const difference = units(after4) - units(before4);
  const absolute = difference < 0n ? -difference : difference;
  return `${difference < 0n ? "-" : ""}${absolute / 10000n}.${String(absolute % 10000n).padStart(4, "0")}`;
}

export function copyValidReconciliationSnapshot(snapshot: ReconciliationSnapshot): ReconciliationSnapshot {
  if (snapshot.entries.some((entry) => ![entry.expected4, entry.actual4, entry.difference4].every(isReconciliationDecimal4))) {
    throw new InvalidReconciliationDecimalError();
  }
  const counts = Object.values(snapshot.summary);
  if (counts.some((count) => !Number.isSafeInteger(count) || count < 0)) throw new Error("invalid reconciliation count");
  return {
    summary: { ...snapshot.summary },
    entries: snapshot.entries.map((entry) => ({ ...entry }))
  };
}

export function copyValidReconciliationDryRunPreview(preview: ReconciliationDryRunPreview): ReconciliationDryRunPreview {
  if (preview.dryRun !== true || preview.executable !== false) throw new Error("invalid reconciliation dry-run marker");
  if (preview.changes.some((change) => ![change.before4, change.after4, change.difference4].every(isReconciliationDecimal4))) {
    throw new InvalidReconciliationDecimalError();
  }
  return { ...preview, changes: preview.changes.map((change) => ({ ...change })) };
}

export const localReconciliationSnapshot: ReconciliationSnapshot = {
  summary: { total: 3, pendingDependency: 1, cashDebt: 1, pointDebt: 1 },
  entries: [
    {
      id: "REC-LOCAL-001", type: "PENDING_DEPENDENCY", subjectRef: "USR-1388", maskedSubject: "N***a · U***388",
      sourceId: "SRC-LOCAL-101", orderId: "ORD-LOCAL-201", lotId: null, allocationId: "ALLOC-LOCAL-401",
      expected4: "1.0000", actual4: "0.0000", difference4: "-1.0000", detectedAt: "2026-09-04 09:12:30", status: "OPEN"
    },
    {
      id: "REC-LOCAL-002", type: "CASH_DEBT", subjectRef: "USR-2046", maskedSubject: "星***业 · U***046",
      sourceId: "SRC-LOCAL-102", orderId: "ORD-LOCAL-202", lotId: "LOT-LOCAL-302", allocationId: "ALLOC-LOCAL-402",
      expected4: "28.3456", actual4: "20.0000", difference4: "-8.3456", detectedAt: "2026-09-04 09:18:10", status: "REVIEWING"
    },
    {
      id: "REC-LOCAL-003", type: "POINT_DEBT", subjectRef: "USR-3021", maskedSubject: "A***o · U***021",
      sourceId: "SRC-LOCAL-103", orderId: "ORD-LOCAL-203", lotId: "LOT-LOCAL-303", allocationId: null,
      expected4: "30.1250", actual4: "25.1250", difference4: "-5.0000", detectedAt: "2026-09-04 09:25:45", status: "OPEN"
    }
  ]
};

function snapshotFor(entries: readonly ReconciliationEntry[]): ReconciliationSnapshot {
  return {
    summary: {
      total: entries.length,
      pendingDependency: entries.filter((entry) => entry.type === "PENDING_DEPENDENCY").length,
      cashDebt: entries.filter((entry) => entry.type === "CASH_DEBT").length,
      pointDebt: entries.filter((entry) => entry.type === "POINT_DEBT").length
    },
    entries: entries.map((entry) => ({ ...entry }))
  };
}

export function createLocalAdminReconciliationAdapter(
  initial: ReconciliationSnapshot = localReconciliationSnapshot
): AdminReconciliationAdapter & Required<Pick<AdminReconciliationAdapter, "load" | "query" | "repairDryRun">> {
  const source = copyValidReconciliationSnapshot(initial);
  return {
    load: async () => copyValidReconciliationSnapshot(source),
    query: async (filters) => {
      const keyword = filters.keyword.trim().toLocaleLowerCase();
      const entries = source.entries.filter((entry) =>
        (filters.type === "ALL" || entry.type === filters.type)
        && (!keyword || [entry.subjectRef, entry.maskedSubject, entry.sourceId, entry.orderId, entry.lotId, entry.allocationId]
          .some((value) => value?.toLocaleLowerCase().includes(keyword))));
      return snapshotFor(entries);
    },
    repairDryRun: async (anomalyId, reason) => {
      if (!reason.trim()) throw new Error("administrator reason is required");
      const entry = source.entries.find((item) => item.id === anomalyId);
      if (!entry) throw new Error("reconciliation anomaly not found");
      return {
        dryRun: true,
        executable: false,
        anomalyId,
        generatedAt: "2026-09-04 10:30:00",
        changes: [{
          label: "账务差异预览",
          before4: entry.actual4,
          after4: entry.expected4,
          difference4: subtractReconciliationDecimal4(entry.expected4, entry.actual4)
        }]
      };
    }
  };
}

export const adminReconciliationAdapterKey: InjectionKey<AdminReconciliationAdapter> = Symbol("admin-reconciliation-adapter");
