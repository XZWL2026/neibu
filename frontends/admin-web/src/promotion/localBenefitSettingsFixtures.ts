import type { InjectionKey } from "vue";

export type BenefitGlobalRateName = "invitationPointsRate" | "defaultLevel1CashRate" | "defaultLevel2CashRate";
export type BenefitCashLevel = "level1CashRate" | "level2CashRate";
export type Rate6 = string & { readonly __rate6: "Rate6" };
export type BenefitSettingsHistoryScope = "GLOBAL" | "PROMOTER_OVERRIDE" | "CUSTOMER_OVERRIDE";
export type BenefitSettingsHistoryFilters = {
  readonly scope: "ALL" | BenefitSettingsHistoryScope;
  readonly promoterKeyword: string;
  readonly startDate: string;
  readonly endDate: string;
};
export type BenefitSettingsHistoryRecord = {
  readonly field?: string | null;
  readonly beforeRate?: string | null;
  readonly afterRate?: string | null;
  readonly version: string;
  readonly scope: BenefitSettingsHistoryScope;
  readonly maskedPromoter: string | null;
  readonly point: Rate6 | null;
  readonly defaultL1: Rate6 | null;
  readonly defaultL2: Rate6 | null;
  readonly promoterL1: Rate6 | null;
  readonly promoterL2: Rate6 | null;
  readonly effectiveAt: string;
  readonly admin: string;
  readonly reason: string;
};
export type BenefitSettingsHistorySnapshot = {
  readonly count: number;
  readonly records: readonly BenefitSettingsHistoryRecord[];
};
export type BenefitCustomer = { readonly id: string; readonly name: string; readonly phone?: string | null; readonly pointsRate: string; readonly effectiveAt?: string | null; readonly enabled?: boolean; readonly cashAgent?: boolean };
export type BenefitSettingsSnapshot = {
  readonly version: string;
  readonly effectiveAt: string;
  readonly operator: string;
  readonly reason: string;
  readonly global: Readonly<Record<BenefitGlobalRateName, string>>;
  readonly customers?: readonly BenefitCustomer[];
  readonly promoters: readonly {
    readonly id: string;
    readonly name: string;
    readonly phone?: string | null;
    readonly effectiveAt?: string | null;
    readonly enabled?: boolean;
    readonly level1CashRate: string | null;
    readonly level2CashRate: string | null;
  }[];
};
export type AdminBenefitSettingsAdapter = {
  load: () => Promise<BenefitSettingsSnapshot>;
  saveGlobalRate: (name: BenefitGlobalRateName, rate: string, reason: string) => Promise<BenefitSettingsSnapshot>;
  savePromoterRate: (promoterId: string, level: BenefitCashLevel, rate: string | null, reason: string) => Promise<BenefitSettingsSnapshot>;
  saveCustomerRate: (id: string, rate: string | null, reason: string) => Promise<BenefitSettingsSnapshot>;
  searchCustomers: (query: string, offset: number) => Promise<{items: {value: string; label: string; detail?: string}[]; total: number}>;
  loadHistory: () => Promise<BenefitSettingsHistorySnapshot>;
  queryHistory: (filters: BenefitSettingsHistoryFilters) => Promise<BenefitSettingsHistorySnapshot>;
};

export const benefitRateError = "比例必须是 0 到 1 之间、最多 6 位小数的十进制字符串";

export function isBenefitRate(value: unknown) {
  return typeof value === "string" && /^(?:0(?:\.\d{1,6})?|1(?:\.0{1,6})?)$/.test(value);
}

export class InvalidBenefitSettingsHistoryRateError extends Error {
  constructor() { super("invalid benefit settings history Rate6"); }
}

function rate6(value: string): Rate6 {
  if (!isBenefitRate(value)) throw new InvalidBenefitSettingsHistoryRateError();
  return value as Rate6;
}

function isMaskedPromoter(value: unknown) {
  if (typeof value !== "string") return false;
  const parts = value.split(" · ");
  if (parts.length > 2) return false;
  const [label, stableId] = parts;
  return /^(?:\*+|\p{L}\*+)$/u.test(label)
    && (stableId === undefined || /^(?:USR|PROMOTER)-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*$/.test(stableId));
}

export function copyValidBenefitSettingsHistorySnapshot(history: BenefitSettingsHistorySnapshot): BenefitSettingsHistorySnapshot {
  if (!Number.isSafeInteger(history.count) || history.count < 0) throw new Error("invalid benefit settings history count");
  const records = history.records.map((record) => {
    const globalRates = [record.point, record.defaultL1, record.defaultL2];
    const promoterRates = [record.promoterL1, record.promoterL2];
    if (record.scope === "GLOBAL") {
      if (record.maskedPromoter !== null || globalRates.some((value) => value === null) || promoterRates.some((value) => value !== null)) {
        throw new Error("inconsistent global benefit settings history record");
      }
    } else if (record.scope === "PROMOTER_OVERRIDE") {
      if (!isMaskedPromoter(record.maskedPromoter) || globalRates.some((value) => value !== null)) {
        throw new Error("inconsistent promoter benefit settings history record");
      }
    } else if (record.scope === "CUSTOMER_OVERRIDE") {
      if (!isMaskedPromoter(record.maskedPromoter) || [record.defaultL1,record.defaultL2,...promoterRates].some(v=>v!==null)) throw new Error("inconsistent customer benefit settings history record");
    } else {
      throw new Error("invalid benefit settings history scope");
    }
    return {
      field: record.field,
      beforeRate: record.beforeRate == null ? record.beforeRate : rate6(record.beforeRate),
      afterRate: record.afterRate == null ? record.afterRate : rate6(record.afterRate),
      version: record.version,
      scope: record.scope,
      maskedPromoter: record.maskedPromoter,
      point: record.point === null ? null : rate6(record.point),
      defaultL1: record.defaultL1 === null ? null : rate6(record.defaultL1),
      defaultL2: record.defaultL2 === null ? null : rate6(record.defaultL2),
      promoterL1: record.promoterL1 === null ? null : rate6(record.promoterL1),
      promoterL2: record.promoterL2 === null ? null : rate6(record.promoterL2),
      effectiveAt: record.effectiveAt,
      admin: record.admin,
      reason: record.reason
    };
  });
  return { count: history.count, records };
}

export function copyValidBenefitSettingsSnapshot(snapshot: BenefitSettingsSnapshot): BenefitSettingsSnapshot {
  const rates = [
    ...Object.values(snapshot.global),
    ...(snapshot.customers ?? []).map(c=>c.pointsRate),
    ...snapshot.promoters.flatMap((item) => [item.level1CashRate, item.level2CashRate]).filter((item): item is string => item !== null)
  ];
  if (rates.some((rate) => !isBenefitRate(rate))) throw new Error("invalid benefit settings rate");
  return {
    version: snapshot.version,
    effectiveAt: snapshot.effectiveAt,
    operator: snapshot.operator,
    reason: snapshot.reason,
    global: { ...snapshot.global },
    promoters: snapshot.promoters.map((item) => ({ ...item })),
    customers: (snapshot.customers ?? []).map(item=>({...item}))
  };
}

export const localBenefitSettingsSnapshot: BenefitSettingsSnapshot = {
  version: "BENEFIT-V1",
  effectiveAt: "2026-09-04 09:00",
  operator: "primary.admin",
  reason: "初始化本地推广收益规则",
  global: {
    invitationPointsRate: "0.1",
    defaultLevel1CashRate: "0.08",
    defaultLevel2CashRate: "0.03"
  },
  promoters: [
    { id: "USR-1388", name: "Nova", level1CashRate: "0.12", level2CashRate: null },
    { id: "USR-2046", name: "星河企业", level1CashRate: null, level2CashRate: "0.05" }
  ]
};

export const localBenefitSettingsHistorySnapshot: BenefitSettingsHistorySnapshot = copyValidBenefitSettingsHistorySnapshot({
  count: 3,
  records: [
    {
      version: "BENEFIT-V1",
      scope: "GLOBAL",
      maskedPromoter: null,
      point: rate6("0.1"),
      defaultL1: rate6("0.08"),
      defaultL2: rate6("0.03"),
      promoterL1: null,
      promoterL2: null,
      effectiveAt: "2026-09-04 09:00",
      admin: "primary.admin",
      reason: "初始化本地推广收益规则"
    },
    {
      version: "BENEFIT-V0-P1",
      scope: "PROMOTER_OVERRIDE",
      maskedPromoter: "N*** · USR-1388",
      point: null,
      defaultL1: null,
      defaultL2: null,
      promoterL1: rate6("0.12"),
      promoterL2: null,
      effectiveAt: "2026-09-03 10:00",
      admin: "primary.admin",
      reason: "重点推广人一级覆盖"
    },
    {
      version: "BENEFIT-V0-P2",
      scope: "PROMOTER_OVERRIDE",
      maskedPromoter: "星*** · USR-2046",
      point: null,
      defaultL1: null,
      defaultL2: null,
      promoterL1: null,
      promoterL2: rate6("0.05"),
      effectiveAt: "2026-08-31 16:00",
      admin: "primary.admin",
      reason: "企业推广人二级覆盖"
    }
  ]
});

function maskedPromoter(promoter: BenefitSettingsSnapshot["promoters"][number]) {
  const characters = Array.from(promoter.name);
  const label = characters.length > 1 ? `${characters[0]}${"*".repeat(characters.length - 1)}` : "*";
  return `${label} · ${promoter.id}`;
}

export function createLocalAdminBenefitSettingsAdapter(
  initial: BenefitSettingsSnapshot = localBenefitSettingsSnapshot,
  initialHistory: BenefitSettingsHistorySnapshot = localBenefitSettingsHistorySnapshot
): AdminBenefitSettingsAdapter {
  let current = copyValidBenefitSettingsSnapshot(initial);
  let history = copyValidBenefitSettingsHistorySnapshot(initialHistory);
  let revision = 1;
  const replace = (
    reason: string,
    update: Partial<Pick<BenefitSettingsSnapshot, "global" | "promoters" | "customers">>,
    record: (next: BenefitSettingsSnapshot) => BenefitSettingsHistoryRecord
  ) => {
    revision += 1;
    current = copyValidBenefitSettingsSnapshot({
      ...current,
      ...update,
      version: `BENEFIT-V${revision}`,
      effectiveAt: "2026-09-04 12:00",
      operator: "primary.admin",
      reason
    });
    history = copyValidBenefitSettingsHistorySnapshot({ count: history.count + 1, records: [record(current), ...history.records] });
    return copyValidBenefitSettingsSnapshot(current);
  };
  return {
    load: async () => copyValidBenefitSettingsSnapshot(current),
    saveGlobalRate: async (name, rate, reason) => {
      if (!isBenefitRate(rate)) throw new Error("invalid benefit settings rate");
      if (!reason.trim()) throw new Error("administrator reason is required");
      return replace(reason.trim(), { global: { ...current.global, [name]: rate } }, (next) => ({
        version: next.version,
        scope: "GLOBAL",
        maskedPromoter: null,
        point: rate6(next.global.invitationPointsRate),
        defaultL1: rate6(next.global.defaultLevel1CashRate),
        defaultL2: rate6(next.global.defaultLevel2CashRate),
        promoterL1: null,
        promoterL2: null,
        effectiveAt: next.effectiveAt,
        admin: next.operator,
        reason: next.reason
      }));
    },
    savePromoterRate: async (promoterId, level, rate, reason) => {
      if (rate !== null && !isBenefitRate(rate)) throw new Error("invalid benefit settings rate");
      if (!reason.trim()) throw new Error("administrator reason is required");
      if (!current.promoters.some((item) => item.id === promoterId)) throw new Error("promoter not found");
      return replace(reason.trim(), {
        promoters: current.promoters.map((item) => item.id === promoterId ? { ...item, [level]: rate } : item)
      }, (next) => {
        const promoter = next.promoters.find((item) => item.id === promoterId)!;
        return {
          version: next.version,
          scope: "PROMOTER_OVERRIDE",
          maskedPromoter: maskedPromoter(promoter),
          point: null,
          defaultL1: null,
          defaultL2: null,
          promoterL1: promoter.level1CashRate === null ? null : rate6(promoter.level1CashRate),
          promoterL2: promoter.level2CashRate === null ? null : rate6(promoter.level2CashRate),
          effectiveAt: next.effectiveAt,
          admin: next.operator,
          reason: next.reason
        };
      });
    },
    searchCustomers: async (query, offset) => {
      const rows=(current.customers??[]).filter(c=>c.enabled!==false&&!c.cashAgent&&`${c.name} ${c.phone??''}`.includes(query));
      return {items:rows.slice(offset,offset+20).map(c=>({value:c.id,label:c.name,detail:c.phone??''})),total:rows.length};
    },
    saveCustomerRate: async (id, rate, reason) => {
      if(rate!==null&&!isBenefitRate(rate))throw new Error("invalid benefit settings rate");
      const customer=current.customers?.find(c=>c.id===id);
      if(!customer)throw new Error("customer not found");
      const before=customer.pointsRate;
      return replace(reason,{customers:rate===null?current.customers!.filter(c=>c.id!==id):current.customers!.map(c=>c.id===id?{...c,pointsRate:rate}:c)},next=>({
        version:next.version,scope:"CUSTOMER_OVERRIDE",maskedPromoter:`*** · USR-${id}`,field:"invitationPointsRate",beforeRate:before,afterRate:rate,
        point:rate===null?null:rate6(rate),defaultL1:null,defaultL2:null,promoterL1:null,promoterL2:null,effectiveAt:next.effectiveAt,admin:next.operator,reason
      }));
    },
    loadHistory: async () => copyValidBenefitSettingsHistorySnapshot(history),
    queryHistory: async (filters) => {
      const promoterKeyword = filters.promoterKeyword.trim().toLocaleLowerCase();
      const records = history.records.filter((record) =>
        (filters.scope === "ALL" || record.scope === filters.scope)
        && (!promoterKeyword || record.maskedPromoter?.toLocaleLowerCase().includes(promoterKeyword))
        && (!filters.startDate || record.effectiveAt.slice(0, 10) >= filters.startDate)
        && (!filters.endDate || record.effectiveAt.slice(0, 10) <= filters.endDate)
      );
      return copyValidBenefitSettingsHistorySnapshot({ count: records.length, records });
    }
  };
}

export const adminBenefitSettingsAdapterKey: InjectionKey<AdminBenefitSettingsAdapter> = Symbol("admin-benefit-settings-adapter");
