export type LocalWithdrawalBalanceView = {
  frozen4: string;
  available4: string;
  withdrawing4: string;
  cumulativePaid4: string;
  cashDebt4: string;
  minimumAmount2: string;
};

export type LocalWithdrawalStatus = "WITHDRAWING" | "PENDING_PAYOUT" | "PAID" | "REJECTED";

export type LocalWithdrawalItemView = {
  id: string;
  amount2: string;
  status: LocalWithdrawalStatus;
  createdAt: string;
  rejectionReason?: string;
};

export type LocalWithdrawalView = {
  balance: LocalWithdrawalBalanceView;
  items: readonly LocalWithdrawalItemView[];
};

export type WithdrawalRequest = { amount2: string; idempotencyKey: string };
export type WithdrawalResult = { balance: LocalWithdrawalBalanceView; withdrawal: LocalWithdrawalItemView };
export type WithdrawalSnapshot = LocalWithdrawalView & { unresolvedRequest: WithdrawalRequest | null };
export type WithdrawalAdapter = {
  getSnapshot: () => WithdrawalSnapshot;
  setUnresolvedRequest: (request: WithdrawalRequest | null) => void;
  submitWithdrawal: (request: WithdrawalRequest) => Promise<WithdrawalResult>;
};

export const localWithdrawalView: LocalWithdrawalView = {
  balance: {
    frozen4: "260.1256",
    available4: "150.0000",
    withdrawing4: "125.0000",
    cumulativePaid4: "430.5000",
    cashDebt4: "0.0000",
    minimumAmount2: "100.00"
  },
  items: [
    { id: "WD-LOCAL-004", amount2: "100.00", status: "WITHDRAWING", createdAt: "2026-08-30 09:00" },
    { id: "WD-LOCAL-003", amount2: "25.00", status: "PENDING_PAYOUT", createdAt: "2026-08-29 09:00" },
    { id: "WD-LOCAL-002", amount2: "88.00", status: "REJECTED", rejectionReason: "收款信息需核对", createdAt: "2026-08-28 09:00" },
    { id: "WD-LOCAL-001", amount2: "200.00", status: "PAID", createdAt: "2026-08-27 09:00" }
  ]
};

function scaled4(value: string) {
  const [whole, fraction = ""] = value.split(".");
  return BigInt(whole) * 10_000n + BigInt((fraction + "0000").slice(0, 4));
}

function decimal4(value: bigint) {
  const whole = value / 10_000n;
  const fraction = (value % 10_000n).toString().padStart(4, "0");
  return `${whole}.${fraction}`;
}

export function createLocalWithdrawalAdapter(initialView: LocalWithdrawalView = localWithdrawalView): WithdrawalAdapter {
  let snapshot: LocalWithdrawalView = {
    balance: { ...initialView.balance },
    items: initialView.items.map((item) => ({ ...item }))
  };
  let unresolvedRequest: WithdrawalRequest | null = null;
  let sequence = 5;
  const completed = new Map<string, Promise<WithdrawalResult>>();
  return {
    getSnapshot: () => ({
      balance: { ...snapshot.balance },
      items: snapshot.items.map((item) => ({ ...item })),
      unresolvedRequest: unresolvedRequest ? { ...unresolvedRequest } : null
    }),
    setUnresolvedRequest(request) {
      unresolvedRequest = request ? { ...request } : null;
    },
    submitWithdrawal(request) {
      const previous = completed.get(request.idempotencyKey);
      if (previous) return previous;
      const operation = Promise.resolve().then(() => {
        const amount4 = scaled4(request.amount2);
        const balance = {
          ...snapshot.balance,
          available4: decimal4(scaled4(snapshot.balance.available4) - amount4),
          withdrawing4: decimal4(scaled4(snapshot.balance.withdrawing4) + amount4)
        };
        const withdrawal = { id: `WD-LOCAL-${String(sequence++).padStart(3, "0")}`, amount2: request.amount2, status: "WITHDRAWING" as const, createdAt: "刚刚" };
        snapshot = { balance, items: [withdrawal, ...snapshot.items] };
        return {
          balance: { ...balance },
          withdrawal: { ...withdrawal }
        };
      });
      completed.set(request.idempotencyKey, operation);
      return operation;
    }
  };
}
