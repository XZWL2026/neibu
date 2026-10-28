import { restoreLanguage } from './i18n';
import {LivePromotionProvider} from './promotion/LivePromotion';
import { useLayoutEffect, type ComponentProps } from "react";
import { RouterProvider } from "react-router-dom";
import "./stage-zero-contract-marker";
import { CreatorMediaProvider } from "./creator/CreatorMediaProvider";
import { publicCreatorMedia } from "./creator/mediaFixtures";
import type { AccountType } from "./creator/types";
import { LocalInvitationProvider, type InvitationClipboardAdapter, type LocalInvitationProfile } from "./promotion/localInvitationProfile";
import type { InvitationRewardSnapshot } from "./promotion/localInvitationRewards";
import { LocalPromotionProvider, type LocalPromotionViewState } from "./promotion/localPromotionViewState";
import { userRouter } from "./router";
import { UserAccountProvider, UserFixtureProvider } from "./userSession";
import { ThemeProvider } from './theme';
import { GlobalPixelBackground } from './GlobalPixelBackground';
import './light-theme.css';

type AppProps = Pick<ComponentProps<typeof RouterProvider>, "router"> & {
  fixture?: boolean;
  creatorAccountType?: AccountType;
  initialPromotionViewState?: LocalPromotionViewState;
  initialInvitationProfile?: LocalInvitationProfile;
  invitationClipboard?: InvitationClipboardAdapter;
  initialInvitationRewardSnapshot?: InvitationRewardSnapshot;
};

export function App({ router = userRouter, fixture = false, creatorAccountType, initialPromotionViewState, initialInvitationProfile, invitationClipboard, initialInvitationRewardSnapshot }: Partial<AppProps>) {
  useLayoutEffect(restoreLanguage, []);
  const AccountProvider = fixture ? UserFixtureProvider : UserAccountProvider;
  const invitationProfile = !fixture ? { eligibility: "UNSET" as const } : initialInvitationProfile ?? (
    initialPromotionViewState?.kind === "ineligible"
      ? initialPromotionViewState.reason === "unset"
        ? { eligibility: "UNSET" as const }
        : { eligibility: "DISABLED" as const, accountType: "PERSONAL" as const }
      : undefined
  );

  return <ThemeProvider><GlobalPixelBackground /><AccountProvider><LivePromotionProvider><LocalPromotionProvider initialViewState={fixture ? initialPromotionViewState : { kind: "ineligible", reason: "unset" }}><LocalInvitationProvider profile={invitationProfile} clipboard={invitationClipboard} rewardSnapshot={initialInvitationRewardSnapshot}><CreatorMediaProvider accountType={creatorAccountType} publicMedia={fixture ? publicCreatorMedia : []}><RouterProvider router={router} /></CreatorMediaProvider></LocalInvitationProvider></LocalPromotionProvider></LivePromotionProvider></AccountProvider></ThemeProvider>;
}
