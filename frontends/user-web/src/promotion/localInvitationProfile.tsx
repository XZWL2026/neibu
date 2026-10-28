import { createContext, useContext, type ReactNode } from "react";
import {
  copyInvitationRewardSnapshot,
  localInvitationRewardSnapshot,
  type InvitationRewardSnapshot
} from "./localInvitationRewards";

export type DirectInvitationView = {
  id: string;
  maskedIdentity: string;
  joinedAt: string;
};

export type LocalInvitationProfile =
  | { eligibility: "UNSET"; accountType?: never }
  | { eligibility: "DISABLED"; accountType: "PERSONAL" | "ENTERPRISE" | "API" }
  | {
      eligibility: "ENABLED";
      accountType: "PERSONAL" | "ENTERPRISE" | "API";
      code: string;
      link: string;
      directInvites: readonly DirectInvitationView[];
    };

export type InvitationClipboardAdapter = { copy: (text: string) => Promise<void> };
export type InvitationCopyLifecycle = {
  isActive: () => boolean;
  success: () => void;
  failure: () => void;
  settled: () => void;
};

export async function copyInvitationWithLifecycle(clipboard: InvitationClipboardAdapter, text: string, lifecycle: InvitationCopyLifecycle) {
  try {
    await clipboard.copy(text);
    if (lifecycle.isActive()) lifecycle.success();
  } catch {
    if (lifecycle.isActive()) lifecycle.failure();
  } finally {
    if (lifecycle.isActive()) lifecycle.settled();
  }
}

export const localInvitationProfile: LocalInvitationProfile = {
  eligibility: "ENABLED",
  accountType: "PERSONAL",
  code: "NOVA88",
  link: "https://xzwlab.example/register?ref=NOVA88",
  directInvites: [
    { id: "USR-LOCAL-901", maskedIdentity: "林＊ · 138****8031", joinedAt: "2026-08-28" },
    { id: "USR-LOCAL-902", maskedIdentity: "星＊工作室 · 139****2640", joinedAt: "2026-08-24" }
  ]
};

export function createInvitationClipboardAdapter(): InvitationClipboardAdapter {
  return { copy: (text) => navigator.clipboard.writeText(text) };
}

type LocalInvitationContextValue = {
  profile: LocalInvitationProfile;
  clipboard: InvitationClipboardAdapter;
  rewardSnapshot: InvitationRewardSnapshot | null;
};

const LocalInvitationContext = createContext<LocalInvitationContextValue | null>(null);

export function LocalInvitationProvider({ children, profile = localInvitationProfile, clipboard = createInvitationClipboardAdapter(), rewardSnapshot = localInvitationRewardSnapshot }: {
  children: ReactNode;
  profile?: LocalInvitationProfile;
  clipboard?: InvitationClipboardAdapter;
  rewardSnapshot?: InvitationRewardSnapshot;
}) {
  const visibleRewardSnapshot = profile.eligibility === "ENABLED"
    ? copyInvitationRewardSnapshot(rewardSnapshot)
    : null;
  return <LocalInvitationContext.Provider value={{ profile, clipboard, rewardSnapshot: visibleRewardSnapshot }}>{children}</LocalInvitationContext.Provider>;
}

export function useLocalInvitationProfile() {
  const context = useContext(LocalInvitationContext);
  if (!context) throw new Error("useLocalInvitationProfile must be used within LocalInvitationProvider");
  return context;
}
