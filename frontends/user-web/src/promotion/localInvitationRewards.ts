export type InvitationRewardAction =
  | "POINT_REWARD_GRANTED"
  | "POINT_REWARD_REVERSED"
  | "POINT_DEBT_CREATED"
  | "POINT_DEBT_OFFSET";

export type InvitationRewardEntry = {
  readonly id: string;
  readonly action: InvitationRewardAction;
  readonly maskedInvitee: string;
  readonly amount4: string;
  readonly balanceBefore4: string;
  readonly balanceAfter4: string;
  readonly taskSettlementRef: string;
  readonly occurredAt: string;
};

export type InvitationRewardSnapshot = {
  readonly summary: {
    readonly cumulativeReward4: string;
    readonly availableReward4: string;
    readonly pointDebt4: string;
  };
  readonly entries: readonly InvitationRewardEntry[];
};

export const localInvitationRewardSnapshot: InvitationRewardSnapshot = {
  summary: {
    cumulativeReward4: "81.2399",
    availableReward4: "34.5678",
    pointDebt4: "2.3499"
  },
  entries: [
    { id: "PR-LOCAL-001", action: "POINT_REWARD_GRANTED", maskedInvitee: "林＊ · 138****8031", amount4: "12.3499", balanceBefore4: "0.0000", balanceAfter4: "12.3499", taskSettlementRef: "TASK-LOCAL-101 · SET-LOCAL-101", occurredAt: "2026-09-04 10:00" },
    { id: "PR-LOCAL-002", action: "POINT_REWARD_REVERSED", maskedInvitee: "星＊工作室 · api***@example.com", amount4: "-2.1299", balanceBefore4: "12.3499", balanceAfter4: "10.2200", taskSettlementRef: "TASK-LOCAL-102 · SET-LOCAL-102", occurredAt: "2026-09-04 11:00" },
    { id: "PR-LOCAL-003", action: "POINT_DEBT_CREATED", maskedInvitee: "陈＊ · 186****2954", amount4: "2.3499", balanceBefore4: "0.0000", balanceAfter4: "2.3499", taskSettlementRef: "TASK-LOCAL-103 · SET-LOCAL-103", occurredAt: "2026-09-04 12:00" },
    { id: "PR-LOCAL-004", action: "POINT_DEBT_OFFSET", maskedInvitee: "云＊科技 · ops***@example.com", amount4: "-1.1199", balanceBefore4: "2.3499", balanceAfter4: "1.2300", taskSettlementRef: "TASK-LOCAL-104 · SET-LOCAL-104", occurredAt: "2026-09-04 13:00" }
  ]
};

export function copyInvitationRewardSnapshot(snapshot: InvitationRewardSnapshot): InvitationRewardSnapshot {
  const summary = Object.freeze({ ...snapshot.summary });
  const entries = Object.freeze(snapshot.entries.map((entry) => Object.freeze({ ...entry })));
  return Object.freeze({ summary, entries });
}

export function truncateRewardDecimal2(value: string) {
  if (!/^-?(?:0|[1-9]\d*)(?:\.\d+)?$/.test(value)) {
    throw new Error("invalid reward decimal value");
  }
  const [whole, fraction = ""] = value.split(".");
  return `${whole}.${(fraction + "00").slice(0, 2)}`;
}
