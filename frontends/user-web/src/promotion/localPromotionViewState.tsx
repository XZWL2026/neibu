import { createContext, useContext, useMemo, useState, type ReactNode } from "react";

export type LocalPromotionViewState =
  | { kind: "eligible-unapplied"; accountType: "PERSONAL" | "ENTERPRISE" | "API" }
  | { kind: "pending"; submittedAt: string }
  | { kind: "rejected"; reason: string }
  | { kind: "approved" }
  | { kind: "ineligible"; reason: "unset" | "disabled" };

type LocalPromotionContextValue = {
  viewState: LocalPromotionViewState;
  submitLocalApplication: () => void;
};

const defaultLocalPromotionViewState: LocalPromotionViewState = {
  kind: "eligible-unapplied",
  accountType: "PERSONAL"
};

const LocalPromotionContext = createContext<LocalPromotionContextValue | null>(null);

export function LocalPromotionProvider({ children, initialViewState = defaultLocalPromotionViewState }: {
  children: ReactNode;
  initialViewState?: LocalPromotionViewState;
}) {
  const [viewState, setViewState] = useState(initialViewState);
  const value = useMemo<LocalPromotionContextValue>(() => ({
    viewState,
    submitLocalApplication: () => setViewState({ kind: "pending", submittedAt: "刚刚" })
  }), [viewState]);
  return <LocalPromotionContext.Provider value={value}>{children}</LocalPromotionContext.Provider>;
}

export function useLocalPromotionViewState() {
  const context = useContext(LocalPromotionContext);
  if (!context) throw new Error("useLocalPromotionViewState must be used inside LocalPromotionProvider");
  return context;
}
