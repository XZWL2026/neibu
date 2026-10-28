export type PayoutAccountType = "BANK_CARD" | "ALIPAY" | "WECHAT";
export type PayoutAccountStatus = "ACTIVE" | "DISABLED";

export type PayoutAccountInput =
  | { type: "BANK_CARD"; holderName: string; cardNumber: string; bankName: string }
  | { type: "ALIPAY"; realName: string; account: string; qrCode?: string }
  | { type: "WECHAT"; realName: string; phone: string; qrCode?: string };

export type LocalPayoutAccountView = {
  id: string;
  type: PayoutAccountType;
  ownerName: string;
  maskedAccount: string;
  qrConfigured: boolean;
  status: PayoutAccountStatus;
};

export type PayoutAccountAdapter = {
  getSnapshot: () => readonly LocalPayoutAccountView[];
  createAccount: (input: PayoutAccountInput) => Promise<LocalPayoutAccountView>;
  updateAccount: (id: string, input: PayoutAccountInput) => Promise<LocalPayoutAccountView>;
  disableAccount: (id: string) => Promise<LocalPayoutAccountView>;
};

export type PayoutAccountFieldErrors = Partial<Record<"holderName" | "cardNumber" | "bankName" | "realName" | "account" | "phone" | "qrCode", string>>;

const PHONE = /^1[3-9]\d{9}$/;

function required(value: string, label: string, maximum = 100) {
  if (!value.trim()) return `${label}不能为空`;
  if (value.trim().length > maximum) return `${label}不能超过 ${maximum} 个字符`;
  return "";
}

export function validatePayoutAccountInput(input: PayoutAccountInput): PayoutAccountFieldErrors {
  if (input.type === "BANK_CARD") {
    return {
      holderName: required(input.holderName, "持卡人"),
      cardNumber: /^\d{10,32}$/.test(input.cardNumber.trim()) ? "" : "银行卡号应为 10 至 32 位数字",
      bankName: required(input.bankName, "开户银行")
    };
  }
  if (input.type === "ALIPAY") {
    return {
      realName: required(input.realName, "支付宝实名"),
      account: required(input.account, "支付宝账号", 200),
      qrCode: input.qrCode && input.qrCode.length > 2048 ? "支付宝收款码不能超过 2048 个字符" : ""
    };
  }
  return {
    realName: required(input.realName, "微信实名"),
    phone: PHONE.test(input.phone.trim()) ? "" : "请输入有效的中国大陆手机号",
    qrCode: input.qrCode && input.qrCode.length > 2048 ? "微信收款码不能超过 2048 个字符" : ""
  };
}

function maskPhone(value: string) {
  return `${value.slice(0, 3)}****${value.slice(-4)}`;
}

function maskAlipay(value: string) {
  if (PHONE.test(value)) return maskPhone(value);
  const at = value.indexOf("@");
  if (at > 0) return `${value[0]}***${value.slice(at)}`;
  if (value.length <= 2) return `${value[0] ?? ""}***`;
  return `${value[0]}***${value.at(-1)}`;
}

function displayView(id: string, input: PayoutAccountInput, status: PayoutAccountStatus = "ACTIVE"): LocalPayoutAccountView {
  if (input.type === "BANK_CARD") {
    return { id, type: input.type, ownerName: input.holderName.trim(), maskedAccount: `尾号 ${input.cardNumber.trim().slice(-4)}`, qrConfigured: false, status };
  }
  if (input.type === "ALIPAY") {
    return { id, type: input.type, ownerName: input.realName.trim(), maskedAccount: maskAlipay(input.account.trim()), qrConfigured: Boolean(input.qrCode?.trim()), status };
  }
  return { id, type: input.type, ownerName: input.realName.trim(), maskedAccount: maskPhone(input.phone.trim()), qrConfigured: Boolean(input.qrCode?.trim()), status };
}

export function createLocalPayoutAccountAdapter(initial: readonly LocalPayoutAccountView[] = []): PayoutAccountAdapter {
  let accounts = initial.map((account) => ({ ...account }));
  let sequence = accounts.length + 1;
  const snapshot = () => accounts.map((account) => ({ ...account }));
  return {
    getSnapshot: snapshot,
    createAccount(input) {
      return Promise.resolve().then(() => {
        const account = displayView(`PA-LOCAL-${String(sequence++).padStart(3, "0")}`, input);
        accounts = [account, ...accounts];
        return { ...account };
      });
    },
    updateAccount(id, input) {
      return Promise.resolve().then(() => {
        const current = accounts.find((account) => account.id === id);
        if (!current || current.status !== "ACTIVE") throw new Error("account cannot be updated");
        if (current.type !== input.type) throw new Error("account type cannot be changed");
        const updated = displayView(id, input);
        accounts = accounts.map((account) => account.id === id ? updated : account);
        return { ...updated };
      });
    },
    disableAccount(id) {
      return Promise.resolve().then(() => {
        const current = accounts.find((account) => account.id === id);
        if (!current || current.status !== "ACTIVE") throw new Error("account cannot be disabled");
        const disabled = { ...current, status: "DISABLED" as const };
        accounts = accounts.map((account) => account.id === id ? disabled : account);
        return { ...disabled };
      });
    }
  };
}
