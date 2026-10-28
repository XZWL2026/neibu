import type { InjectionKey } from "vue";

export type AdminWithdrawalStatus = "WITHDRAWING" | "PENDING_PAYOUT" | "PAID" | "REJECTED";

export type AdminWithdrawalView = {
  id: string;
  promoter: string;
  amount: string;
  status: AdminWithdrawalStatus;
  payoutAccountSnapshot: string;
  requestedAt: string;
  rejectionReason?: string;
  settlementMonth?: string;
  payoutAccountId?: string;
  transferReference?: string;
  paidAt?: string;
};

export type PayoutFilters = { status: string; promoterUserId: string; month: string; page: number };
export type PayoutList = { items: AdminWithdrawalView[]; page: number; size: number; total: number };
export type PayoutAccountDetails = { accountName: string; accountReference: string; bankName: string | null; qrCodeReference: string | null; snapshotAt: string };
export type AdminWithdrawalAdapter = {
  query?: (filters: PayoutFilters) => Promise<PayoutList>;
  promoters?: (query: string, page: number) => Promise<{ items: { value: string; label: string }[]; total: number }>;
  accounts?: (userId: string, page: number) => Promise<{ items: { value: string; label: string }[]; total: number }>;
  account?: (id: string) => Promise<PayoutAccountDetails>;
  create?: (userId: string, month: string, amount: string, accountId: string, existingId?: string) => Promise<AdminWithdrawalView>;
  getSnapshot?: () => readonly AdminWithdrawalView[];
  load?: () => Promise<readonly AdminWithdrawalView[]>;
  approve: (id: string, operationKey: string) => Promise<AdminWithdrawalView>;
  reject: (id: string, reason: string, operationKey: string) => Promise<AdminWithdrawalView>;
  markPaid: (id: string, operationKey: string, proof?: { transferReference: string; paidAt: string }) => Promise<AdminWithdrawalView>;
};

type CompletedCommand = {
  withdrawalId: string;
  action: "APPROVE" | "REJECT" | "MARK_PAID";
  rejectionReason?: string;
  settlementMonth?: string;
  payoutAccountId?: string;
  transferReference?: string;
  paidAt?: string;
  result: AdminWithdrawalView;
};

export const localAdminWithdrawals: AdminWithdrawalView[] = [
  { id: "WD-LOCAL-001", promoter: "Nova · USR-1388", amount: "¥ 128.00", status: "WITHDRAWING", payoutAccountSnapshot: "银行卡 · 尾号 7890", requestedAt: "2026-09-04 09:30" },
  { id: "WD-LOCAL-002", promoter: "星河企业 · USR-2046", amount: "¥ 860.00", status: "PENDING_PAYOUT", payoutAccountSnapshot: "支付宝 · x***@example.com", requestedAt: "2026-09-03 16:45" },
  { id: "WD-LOCAL-003", promoter: "API Studio · USR-3021", amount: "¥ 320.00", status: "PAID", payoutAccountSnapshot: "微信 · 138****5678", requestedAt: "2026-09-02 14:10" },
  { id: "WD-LOCAL-004", promoter: "微光 · USR-1088", amount: "¥ 64.00", status: "REJECTED", payoutAccountSnapshot: "银行卡 · 尾号 2468", requestedAt: "2026-09-01 11:25", rejectionReason: "收款信息不完整" }
];

export function createLocalAdminWithdrawalAdapter(initial: readonly AdminWithdrawalView[] = localAdminWithdrawals): AdminWithdrawalAdapter & { getSnapshot: () => readonly AdminWithdrawalView[] } {
  let withdrawals = initial.map((item) => ({ ...item }));
  const completedCommands = new Map<string, CompletedCommand>();

  function transition(
    id: string,
    operationKey: string,
    action: CompletedCommand["action"],
    expected: AdminWithdrawalStatus,
    status: AdminWithdrawalStatus,
    rejectionReason?: string
  ) {
    return Promise.resolve().then(() => {
      const key = operationKey?.trim();
      if (!key) throw new Error("operationKey must not be blank");
      const completed = completedCommands.get(key);
      if (completed) {
        if (completed.withdrawalId === id && completed.action === action && completed.rejectionReason === rejectionReason) {
          return { ...completed.result };
        }
        throw new Error("operationKey conflicts with a completed withdrawal command");
      }
      const current = withdrawals.find((item) => item.id === id);
      if (!current || current.status !== expected) throw new Error("withdrawal cannot transition");
      const updated = { ...current, status, ...(rejectionReason ? { rejectionReason } : {}) };
      withdrawals = withdrawals.map((item) => item.id === id ? updated : item);
      completedCommands.set(key, { withdrawalId: id, action, rejectionReason, result: { ...updated } });
      return { ...updated };
    });
  }

  return {
    getSnapshot: () => withdrawals.map((item) => ({ ...item })),
    approve: (id, operationKey) => transition(id, operationKey, "APPROVE", "WITHDRAWING", "PENDING_PAYOUT"),
    reject(id, reason, operationKey) {
      const normalized = reason.trim();
      if (!normalized) return Promise.reject(new Error("rejection reason is required"));
      return transition(id, operationKey, "REJECT", "WITHDRAWING", "REJECTED", normalized);
    },
    markPaid: (id, operationKey) => transition(id, operationKey, "MARK_PAID", "PENDING_PAYOUT", "PAID")
  };
}

export const adminWithdrawalAdapterKey: InjectionKey<AdminWithdrawalAdapter> = Symbol("admin-withdrawal-adapter");
