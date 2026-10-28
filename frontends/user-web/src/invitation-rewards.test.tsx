import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createMemoryRouter } from "react-router-dom";
import { App } from "./App";
import type { LocalInvitationProfile } from "./promotion/localInvitationProfile";
import {
  copyInvitationRewardSnapshot,
  localInvitationRewardSnapshot,
  truncateRewardDecimal2,
  type InvitationRewardSnapshot
} from "./promotion/localInvitationRewards";
import { userRoutes } from "./router";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

const rewardSnapshot = {
  summary: { cumulativeReward4: "81.2399", availableReward4: "34.5678", pointDebt4: "2.3499" },
  entries: [
    { id: "PR-1", action: "POINT_REWARD_GRANTED", maskedInvitee: "林＊ · 138****8031", amount4: "12.3499", balanceBefore4: "0.0000", balanceAfter4: "12.3499", taskSettlementRef: "TASK-LOCAL-101 · SET-LOCAL-101", occurredAt: "2026-09-04 10:00" },
    { id: "PR-2", action: "POINT_REWARD_REVERSED", maskedInvitee: "林＊ · 138****8031", amount4: "-2.1299", balanceBefore4: "12.3499", balanceAfter4: "10.2200", taskSettlementRef: "TASK-LOCAL-102 · SET-LOCAL-102", occurredAt: "2026-09-04 11:00" },
    { id: "PR-3", action: "POINT_DEBT_CREATED", maskedInvitee: "星＊工作室 · api***@example.com", amount4: "2.3499", balanceBefore4: "0.0000", balanceAfter4: "2.3499", taskSettlementRef: "TASK-LOCAL-103 · SET-LOCAL-103", occurredAt: "2026-09-04 12:00" },
    { id: "PR-4", action: "POINT_DEBT_OFFSET", maskedInvitee: "星＊工作室 · api***@example.com", amount4: "-1.1199", balanceBefore4: "2.3499", balanceAfter4: "1.2300", taskSettlementRef: "TASK-LOCAL-104 · SET-LOCAL-104", occurredAt: "2026-09-04 13:00" }
  ]
} as const;

function enabledProfile(accountType: "PERSONAL" | "ENTERPRISE" | "API"): LocalInvitationProfile {
  return {
    eligibility: "ENABLED",
    accountType,
    code: `${accountType.slice(0, 3)}888`,
    link: `https://xzwlab.example/register?ref=${accountType.slice(0, 3)}888`,
    directInvites: []
  };
}

function show(profile: LocalInvitationProfile = enabledProfile("PERSONAL"), snapshot: InvitationRewardSnapshot = rewardSnapshot) {
  return render(<App
    router={createMemoryRouter(userRoutes, { initialEntries: ["/account/referrals"] })}
    initialInvitationProfile={profile}
    initialInvitationRewardSnapshot={snapshot}
  />);
}

describe("invitation point reward account", () => {
  it("shows cumulative rewards without the available reward or point debt summaries", async () => {
    show();

    expect(await screen.findByRole("heading", { name: "邀请积分账户" })).toBeVisible();
    expect(screen.getByTestId("cumulative-reward")).toHaveTextContent("81.23");
    expect(screen.queryByTestId("available-reward")).not.toBeInTheDocument();
    expect(screen.queryByTestId("point-debt")).not.toBeInTheDocument();
    expect(screen.getByText("积分奖励只能用于消费，不可提现。")).toBeVisible();
    expect(screen.getByText("规则示例：A 只获得现金佣金，不获得积分；B 因 C 成功消费获得积分；C 因 D 成功消费获得积分。")).toBeVisible();
    expect(screen.getByText("奖励积分再次消费不产生新的邀请奖励。")).toBeVisible();
    expect(screen.queryByText(/积分待追回/)).not.toBeInTheDocument();
  });

  it("shows four immutable actions and filters rows without changing snapshot totals", async () => {
    show();
    await screen.findByRole("heading", { name: "邀请积分账户" });

    const table = screen.getByRole("table", { name: "邀请积分奖励明细" });
    for (const label of ["奖励发放", "奖励冲回", "待追回创建", "待追回抵扣"]) {
      expect(within(table).getByText(label)).toBeVisible();
    }
    expect(within(table).getByText("TASK-LOCAL-101 · SET-LOCAL-101")).toBeVisible();
    expect(within(table).getAllByText("林＊ · 138****8031")).toHaveLength(2);
    expect(within(table).getByText("2026-09-04 10:00")).toBeVisible();
    expect(within(table).getAllByText("12.34")).toHaveLength(3);

    fireEvent.change(screen.getByLabelText("筛选奖励动作"), { target: { value: "POINT_DEBT_CREATED" } });
    expect(within(table).getAllByRole("row")).toHaveLength(2);
    expect(within(table).getByText("待追回创建")).toBeVisible();
    expect(screen.getByTestId("cumulative-reward")).toHaveTextContent("81.23");
  });

  it.each(["PERSONAL", "ENTERPRISE", "API"] as const)("shows rewards for enabled %s accounts without network", async (accountType) => {
    const network = vi.fn();
    vi.stubGlobal("fetch", network);
    show(enabledProfile(accountType));
    expect(await screen.findByRole("heading", { name: "邀请积分账户" })).toBeVisible();
    expect(network).not.toHaveBeenCalled();
  });

  it.each([
    { eligibility: "UNSET" },
    { eligibility: "DISABLED", accountType: "PERSONAL" }
  ] satisfies LocalInvitationProfile[])("does not expose reward snapshots to $eligibility accounts", async (profile) => {
    show(profile, {
      ...rewardSnapshot,
      summary: { cumulativeReward4: "987.6543", availableReward4: "876.5432", pointDebt4: "765.4321" },
      entries: [{ ...rewardSnapshot.entries[0], taskSettlementRef: "TASK-SHOULD-NOT-LEAK · SET-SHOULD-NOT-LEAK" }]
    });
    expect(await screen.findByRole("heading", { name: "当前不可使用邀请有礼" })).toBeVisible();
    expect(screen.queryByRole("heading", { name: "邀请积分账户" })).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent("987.65");
    expect(document.body).not.toHaveTextContent("TASK-SHOULD-NOT-LEAK");
  });

  it("keeps reward fixtures free of private task payloads and full identities", async () => {
    const view = show();
    await screen.findByRole("heading", { name: "邀请积分账户" });
    expect(view.container.textContent).not.toMatch(/13800138000|alice@example\.com|prompt|resultURL|secret-key|内部异常/i);
  });

  it("deep-freezes copied snapshots without changing the shared fixture", () => {
    const originalAvailable = localInvitationRewardSnapshot.summary.availableReward4;
    const originalInvitee = localInvitationRewardSnapshot.entries[0].maskedInvitee;
    const snapshot = copyInvitationRewardSnapshot(localInvitationRewardSnapshot);

    expect(Object.isFrozen(snapshot)).toBe(true);
    expect(Object.isFrozen(snapshot.summary)).toBe(true);
    expect(Object.isFrozen(snapshot.entries)).toBe(true);
    expect(snapshot.entries.every(Object.isFrozen)).toBe(true);
    expect(Reflect.set(snapshot.summary, "availableReward4", "0.0000")).toBe(false);
    expect(Reflect.set(snapshot.entries[0], "maskedInvitee", "完整用户身份")).toBe(false);
    expect(snapshot.summary.availableReward4).toBe(originalAvailable);
    expect(snapshot.entries[0].maskedInvitee).toBe(originalInvitee);
    expect(localInvitationRewardSnapshot.summary.availableReward4).toBe(originalAvailable);
    expect(localInvitationRewardSnapshot.entries[0].maskedInvitee).toBe(originalInvitee);
  });

  it("rejects malformed reward decimals without echoing the original value", () => {
    for (const value of ["", "12.34.56", "12.3oops-private", "1e3"]) {
      let thrown: unknown;
      try {
        truncateRewardDecimal2(value);
      } catch (error) {
        thrown = error;
      }
      expect(thrown).toBeInstanceOf(Error);
      expect((thrown as Error).message).toBe("invalid reward decimal value");
    }
  });
});
