import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { transactions as fixtureTransactions } from "./fixtures";
import { storageAvatarAccessUrlGetApiV1FilesAvatarsFileIdAccessUrl, refreshSession, userSelfGetApiV1UsersMe, accessListGetApiV1AccessKeys,
  billingBalanceGetApiV1BillingWalletsKeyId, paymentGetGetApiV1PaymentsRechargesId,
  paymentOptionsGetApiV1PaymentsRechargeOptions, logout as logoutAccount } from "./api";

export type UserAccountType = "PERSONAL" | "ENTERPRISE" | "API";
export type UserAccountStatus = "loading" | "anonymous" | "error" | "not-open" | "disabled" | "pending-activation" | "awaiting-credit" | "ready";
export function apiGenerationNotice(account: Pick<UserSession, "accountType"> | null, type: "IMAGE" | "VIDEO") {
  return account?.accountType === "API"
    ? `当前用户为api用户，可在查看媒体记录，但不能生成${type === "IMAGE" ? "图片" : "视频"}。`
    : null;
}
export const accountTypeLabels = { PERSONAL: "个人账户", ENTERPRISE: "企业账户", API: "API 账户" };
export const accountStatusLabels: Record<UserAccountStatus, string> = {
  loading: "正在读取账户", anonymous: "请先登录", error: "账户读取失败", "not-open": "账户待开通", disabled: "账户或密钥已禁用", ready: "已开通", "pending-activation": "账户身份已确认，正在开通", "awaiting-credit": "已付款，等待积分入账",
};

export type UserKey = {
  id: string;
  maskedKey?: string | null;
  name: string;
  kind: "媒体 Key" | "API Key";
  owner: string;
  balance: string;
  relationship: "Owner" | "Member";
  generated?: boolean;
  enabled?: boolean;
  accountType?: UserAccountType;
  availablePoints4?: string | null;
};

export type Transaction = {
  id: string;
  action: string;
  amount: string;
  ledgerAmount?: string;
  time: string;
  status: string;
};

export type UserSession = {
  fixture: boolean;
  userId: string | null;
  phone: string | null;
  accountType: UserAccountType | null;
  authenticated: boolean;
  status: UserAccountStatus;
  error: string | null;
  availablePoints4: string | null;
  canGenerate: boolean;
  refresh: () => Promise<void>;
  logout: () => Promise<void>;
  nickname: string;
  avatarFileId?: string | null;
  avatarUrl?: string | null;
  keys: UserKey[];
  currentKey: UserKey | null;
  transactions: Transaction[];
  saveNickname: (nickname: string) => void;
  bindKey: (rawKey: string) => UserKey | null;
  createRecharge: (rawAmount: string) => { created: boolean; displayAmount: string } | null;
  isRechargePending: (rawAmount: string) => boolean;
};

const UserSessionContext = createContext<UserSession | null>(null);

function normalizeAmount(rawAmount: string) {
  const match = rawAmount.trim().match(/^(\d+)(?:\.(\d{0,4}))?$/);
  if (!match) return null;
  const whole = match[1].replace(/^0+(?=\d)/, "") || "0";
  const fraction = match[2] ?? "";
  if (Number(`${whole}.${fraction || "0"}`) < 1) return null;
  return `${whole}.${fraction.padEnd(4, "0")}`;
}

export function formatMoneyDirect(rawAmount: string) {
  const normalized = normalizeAmount(rawAmount);
  if (!normalized) return null;
  const [whole, fraction] = normalized.split(".");
  return `${whole}.${fraction.slice(0, 2)}`;
}

export function UserFixtureProvider({ children }: { children: ReactNode }) {
  const initialKey: UserKey = {
    id: "KEY-1388-M",
    name: "Nova 媒体 Key",
    kind: "媒体 Key",
    owner: "Nova",
    balance: "1,280.00 积分",
    relationship: "Owner",
    generated: true
  };

  const [nickname, setNickname] = useState("Nova");
  const [keys, setKeys] = useState<UserKey[]>([initialKey]);
  const [currentKeyId, setCurrentKeyId] = useState(initialKey.id);
  const [localTransactions, setLocalTransactions] = useState<Transaction[]>([]);
  const [pendingRechargeAmounts, setPendingRechargeAmounts] = useState<string[]>([]);

  const currentKey = keys.find((key) => key.id === currentKeyId) ?? initialKey;
  const transactions = useMemo(
    () => [...localTransactions, ...fixtureTransactions.map((item) => ({ ...item }))],
    [localTransactions]
  );

  const bindKey = (rawKey: string) => {
    const kind = /^sk-_[0-9a-f]{64}$/.test(rawKey) ? "媒体 Key" : /^sk-[a-z0-9]{32}$/.test(rawKey) ? "API Key" : null;
    if (!kind) return null;
    const existing = keys.find((key) => key.id === rawKey);
    if (existing) {
      setCurrentKeyId(existing.id);
      return existing;
    }
    const key: UserKey = {
      id: rawKey,
      name: `共享创作者 ${kind}`,
      kind,
      owner: "共享创作者",
      balance: "860.00 积分",
      relationship: "Member"
    };
    setKeys((items) => [...items, key]);
    setCurrentKeyId(key.id);
    return key;
  };

  const isRechargePending = (rawAmount: string) => {
    const normalized = normalizeAmount(rawAmount);
    return normalized ? pendingRechargeAmounts.includes(normalized) : false;
  };

  const createRecharge = (rawAmount: string) => {
    const normalized = normalizeAmount(rawAmount);
    const displayAmount = formatMoneyDirect(rawAmount);
    if (!normalized || !displayAmount) return null;
    if (pendingRechargeAmounts.includes(normalized)) return { created: false, displayAmount };
    setPendingRechargeAmounts((amounts) => [...amounts, normalized]);
    setLocalTransactions((items) => [{
      id: `PAY-LOCAL-${String(items.length + 1).padStart(3, "0")}`,
      action: "充值订单",
      amount: `¥ ${displayAmount}`,
      ledgerAmount: normalized,
      time: "刚刚",
      status: "待支付"
    }, ...items]);
    return { created: true, displayAmount };
  };

  return <UserSessionContext.Provider value={{
    fixture: true, userId: "1388", phone: null, accountType: "PERSONAL", authenticated: true,
    status: "ready", error: null, availablePoints4: "1280.0000", canGenerate: true,
    refresh: async () => {}, logout: async () => {},
    nickname,
    keys,
    currentKey,
    transactions,
    saveNickname: setNickname,
    bindKey,
    createRecharge,
    isRechargePending
  }}>{children}</UserSessionContext.Provider>;
}

export function useOptionalUserSession() {
  return useContext(UserSessionContext);
}

export function useUserSession() {
  const session = useContext(UserSessionContext);
  if (!session) throw new Error("useUserSession must be used inside a user account provider");
  return session;
}

const emptyAccount = {
  userId: null, phone: null, nickname: "", avatarFileId: null as string | null, accountType: null, authenticated: false,
  status: "loading", error: null, keys: [], currentKey: null, availablePoints4: null,
} satisfies Pick<UserSession, "userId" | "phone" | "nickname" | "avatarFileId" | "accountType" | "authenticated" | "status" | "error" | "keys" | "currentKey" | "availablePoints4">;
type AccountSnapshot = Pick<UserSession, keyof typeof emptyAccount>;

function accountType(value: string | null): UserAccountType | null {
  return value === "PERSONAL" || value === "ENTERPRISE" || value === "API" ? value : null;
}

export function UserAccountProvider({ children }: { children: ReactNode }) {
  const [account, setAccount] = useState<AccountSnapshot>(emptyAccount);
  const [avatarUrl, setAvatarUrl] = useState<string | null>(null);
  const avatarCache = useRef<{ userId: string; fileId: string; url: string; expiresAt: number } | null>(null);
  useEffect(() => {
    if (account.status === "loading") return;
    if (!account.userId || !account.avatarFileId) {
      avatarCache.current = null;
      setAvatarUrl(null);
      return;
    }
    const { userId, avatarFileId: fileId } = account;
    const cached = avatarCache.current;
    if (cached?.userId === userId && cached.fileId === fileId && cached.expiresAt > Date.now() + 60_000) {
      setAvatarUrl(cached.url);
      return;
    }
    if (cached?.userId !== userId || cached.fileId !== fileId) setAvatarUrl(null);
    const controller = new AbortController();
    void storageAvatarAccessUrlGetApiV1FilesAvatarsFileIdAccessUrl(fileId, { signal: controller.signal })
      .then(({ data }) => {
        if (controller.signal.aborted || !data.url) return;
        avatarCache.current = { userId, fileId, url: data.url, expiresAt: Date.parse(data.expiresAt ?? '') };
        setAvatarUrl(data.url);
      })
      .catch(() => { /* Keep the default avatar when the image service is unavailable. */ });
    return () => controller.abort();
  }, [account.userId, account.avatarFileId, account.status]);
  const request = useRef<AbortController | null>(null);
  const loggingOut = useRef(false);
  const displayedOwner = useRef<string | null>(null);
  displayedOwner.current = account.userId;
  const refresh = useCallback(async (preserveOwner = false) => {
    if (loggingOut.current) return;
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    const { signal } = controller;
    const previousOwner = displayedOwner.current;
    // Focus revalidation keeps draft ownership stable (including native file choosers),
    // but clears keys, balance and generation eligibility until verification completes.
    setAccount(current => ({ ...emptyAccount, nickname: current.nickname,
      ...(preserveOwner ? { userId: current.userId } : {}) }));
    let next: AccountSnapshot = { ...emptyAccount };
    try {
      // Anonymous bootstrap never calls a protected endpoint (or triggers its redirect).
      const session = await refreshSession();
      if (signal.aborted) return;
      if (avatarCache.current?.userId !== session.user?.id || !session.authenticated) {
        avatarCache.current = null;
        setAvatarUrl(null);
      }
      if (preserveOwner && previousOwner !== session.user?.id) setAccount({ ...emptyAccount });
      if (!session.authenticated || !session.user?.id) {
        setAccount({ ...emptyAccount, status: "anonymous" }); return;
      }
      const { data: user } = await userSelfGetApiV1UsersMe({ signal });
      if (signal.aborted) return;
      if (user.id !== session.user.id) throw new Error("账户归属不一致，请重新登录。");
      next = { ...next, authenticated: true, userId: user.id, phone: user.phone, avatarFileId: user.avatarFileId ?? null, nickname: user.nickname || user.phone || user.id };
      if (!user.enabled) { setAccount({ ...next, status: "disabled" }); return; }
      const { data: options } = await paymentOptionsGetApiV1PaymentsRechargeOptions({ signal });
      if (signal.aborted) return;
      if (options.userId !== user.id || options.accountType !== null && !accountType(options.accountType)) {
        throw new Error("充值账户信息不一致。");
      }
      // Payment fixes identity at confirmation, before Access/Billing finish fulfillment.
      next.accountType = accountType(options.accountType);
      const { data: metadata } = await accessListGetApiV1AccessKeys({ signal });
      if (signal.aborted) return;
      // The Access list endpoint returns the unique owner primary; member keys cannot bind it.
      const owned = metadata.filter(key => key.ownerId === user.id);
      if (owned.length > 1) throw new Error("主密钥信息不一致，请刷新重试。");
      const primary = owned[0];
      next.keys = owned.flatMap(key => key.keyId && accountType(key.accountType) && ["MEDIA", "API"].includes(key.kind ?? "") ? [{
        maskedKey: typeof key.maskedKey === 'string' && /^sk-[A-Za-z0-9_-]+\*{4}$/.test(key.maskedKey) ? key.maskedKey : null,
        id: key.keyId, name: key.kind === "API" ? "平台 API Key" : "平台媒体 Key",
        kind: key.kind === "API" ? "API Key" as const : "媒体 Key" as const,
        owner: user.nickname || user.phone || user.id!, relationship: "Owner" as const,
        balance: "—", enabled: key.enabled, accountType: accountType(key.accountType)!,
      }] : []);
      if (!primary) { setAccount({ ...next, status: next.accountType ? "pending-activation" : "not-open" }); return; }
      if (!primary.enabled) { setAccount({ ...next, status: "disabled" }); return; }
      if (!next.keys.length || !primary.keyId || !next.accountType || primary.accountType !== next.accountType
        || primary.kind !== (next.accountType === "API" ? "API" : "MEDIA")) {
        throw new Error("主密钥信息不完整，请刷新重试。");
      }
      if (!primary.firstRechargeOrderId) { setAccount({ ...next, status: "pending-activation" }); return; }
      const { data: first } = await paymentGetGetApiV1PaymentsRechargesId(primary.firstRechargeOrderId, { signal });
      if (signal.aborted) return;
      if (first.userId !== user.id || first.orderId !== primary.firstRechargeOrderId) {
        throw new Error("账户开通记录不一致。");
      }
      // Key creation precedes Billing credit; it does not prove fulfillment succeeded.
      if (!first.fulfilled || !["PAID", "REFUND_PENDING", "REFUNDED"].includes(first.status ?? "")) {
        setAccount({ ...next, status: "awaiting-credit" }); return;
      }
      const { data: wallet } = await billingBalanceGetApiV1BillingWalletsKeyId(primary.keyId, { signal });
      if (signal.aborted) return;
      if (wallet.walletId !== primary.keyId || !wallet.availablePoints4 || !/^\d+(?:\.\d{1,4})?$/.test(wallet.availablePoints4)) {
        throw new Error("账户余额暂不可用，请刷新重试。");
      }
      const currentKey = { ...next.keys[0], balance: `${wallet.availablePoints4} 积分`, availablePoints4: wallet.availablePoints4 };
      setAccount({ ...next, status: "ready", currentKey, keys: [currentKey], availablePoints4: wallet.availablePoints4 });
    } catch {
      if (!signal.aborted) setAccount({ ...next, currentKey: null, availablePoints4: null, status: "error", error: "账户信息暂时无法读取，请刷新重试。" });
    }
  }, []);
  const logout = useCallback(async () => {
    if (loggingOut.current) return;
    loggingOut.current = true;
    request.current?.abort();
    avatarCache.current = null;
    setAvatarUrl(null);
    setAccount({ ...emptyAccount });
    try {
      await logoutAccount();
      setAccount({ ...emptyAccount, status: "anonymous" });
    } catch {
      setAccount({ ...emptyAccount, status: "error", error: "退出登录失败，请重试。" });
      throw new Error("退出登录失败，请重试。");
    } finally {
      loggingOut.current = false;
    }
  }, []);
  useEffect(() => {
    void refresh();
    const changed = () => { void refresh(); };
    const focused = () => { void refresh(true); };
    window.addEventListener("xzwl-account-changed", changed);
    window.addEventListener("focus", focused);
    return () => {
      request.current?.abort();
      window.removeEventListener("xzwl-account-changed", changed);
      window.removeEventListener("focus", focused);
    };
  }, [refresh]);
  return <UserSessionContext.Provider value={{ ...account, avatarUrl, fixture: false, refresh, logout,
    canGenerate: account.status === "ready" && account.currentKey?.kind === "媒体 Key" && /[1-9]/.test(account.availablePoints4 ?? ""),
    transactions: [], saveNickname: () => {}, bindKey: () => null, createRecharge: () => null, isRechargePending: () => false,
  }}>{children}</UserSessionContext.Provider>;
}
