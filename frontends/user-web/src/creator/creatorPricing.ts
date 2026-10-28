import type { AccountType, CreatorModelId } from "./types";

export type CreatorPriceKey = `${AccountType}:${CreatorModelId}:${string}`;

// 本地积分估算 fixture，不代表正式结算价格。
const prices: Readonly<Partial<Record<CreatorPriceKey, string | null>>> = {
  "PERSONAL:gpt-image-2:fixture-1k": "1.2000",
  "ENTERPRISE:gpt-image-2:fixture-1k": "1.0000",
};

export const getCreatorPrice = (
  account: AccountType,
  model: CreatorModelId,
  resolution: string,
): string | null => prices[`${account}:${model}:${resolution}`] ?? null;

export const estimateImagePoints = getCreatorPrice;

const parseFixed4 = (value: string): bigint => {
  const match = /^(\d+)(?:\.(\d{1,4}))?$/.exec(value);
  if (!match) {
    throw new RangeError("unitPoints must be a non-negative fixed-point value with at most four decimals");
  }

  const fraction = (match[2] ?? "").padEnd(4, "0");
  return BigInt(match[1]) * 10_000n + BigInt(fraction || "0");
};

const formatFixed4 = (value: bigint): string => {
  const integer = value / 10_000n;
  const fraction = (value % 10_000n).toString().padStart(4, "0");
  return `${integer}.${fraction}`;
};

export const estimateVideoPoints = ({
  unitPoints,
  durationSeconds,
}: {
  unitPoints: string;
  durationSeconds: number;
}): string => {
  if (!Number.isSafeInteger(durationSeconds) || durationSeconds < 0) {
    throw new RangeError("durationSeconds must be a non-negative safe integer");
  }

  return formatFixed4(parseFixed4(unitPoints) * BigInt(durationSeconds));
};
