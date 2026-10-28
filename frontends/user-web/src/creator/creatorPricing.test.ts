import { describe, expect, it } from "vitest";

import { estimateImagePoints, estimateVideoPoints, getCreatorPrice } from "./creatorPricing";

const createTestPriceTable = <
  Account extends string,
  Model extends string,
  Resolution extends string,
>(table: Readonly<Partial<Record<`${Account}:${Model}:${Resolution}`, string | null>>>) => ({
  get(account: Account, model: Model, resolution: Resolution): string | null {
    return table[`${account}:${model}:${resolution}`] ?? null;
  },
});

describe("creator local points estimate fixture", () => {
  it("按账户类型、模型、分辨率精确取价", () => {
    expect(getCreatorPrice("PERSONAL", "gpt-image-2", "fixture-1k")).toBe("1.2000");
    expect(getCreatorPrice("ENTERPRISE", "gpt-image-2", "fixture-1k")).toBe("1.0000");
    expect(estimateVideoPoints({ unitPoints: "0.4000", durationSeconds: 5 })).toBe("2.0000");
    expect(getCreatorPrice("PERSONAL", "gpt-image-2", "missing")).toBeNull();
  });

  it("不从一个分辨率推断另一个分辨率的价格", () => {
    expect(getCreatorPrice("PERSONAL", "gpt-image-2", "fixture-2k")).toBeNull();
  });

  it("允许测试局部表用 0 表示免费而不扩展生产模型", () => {
    const testOnlyFreePrices = createTestPriceTable({
      "PERSONAL:test-only-free:fixture-1k": "0",
    });

    expect(testOnlyFreePrices.get("PERSONAL", "test-only-free", "fixture-1k")).toBe("0");
  });

  it("图片按单次估算，视频按四位定点每秒单价乘时长估算", () => {
    expect(estimateImagePoints("PERSONAL", "gpt-image-2", "fixture-1k")).toBe("1.2000");
    expect(estimateVideoPoints({ unitPoints: "0.4000", durationSeconds: 5 })).toBe("2.0000");
  });

  it("缺失价格保持 null 以供提交入口禁用", () => {
    expect(estimateImagePoints("ENTERPRISE", "gpt-image-2", "unknown-resolution")).toBeNull();
  });

  it("视频 0 单价保持四位定点并支持 MAX_SAFE_INTEGER 秒边界", () => {
    expect(estimateVideoPoints({ unitPoints: "0", durationSeconds: 8 })).toBe("0.0000");
    expect(estimateVideoPoints({
      unitPoints: "0.0001",
      durationSeconds: Number.MAX_SAFE_INTEGER,
    })).toBe("900719925474.0991");
  });

  it.each(["-0.0001", "0.00001", "NaN", "Infinity", "1.", ".1"])(
    "拒绝非法或超过四位的小数 %s",
    (unitPoints) => {
      expect(() => estimateVideoPoints({ unitPoints, durationSeconds: 1 })).toThrow(RangeError);
    },
  );

  it.each([-1, 1.5, Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY,
    Number.MAX_SAFE_INTEGER + 1])("拒绝负数、非整数、非有限或越界时长 %s", (durationSeconds) => {
    expect(() => estimateVideoPoints({ unitPoints: "0.4000", durationSeconds })).toThrow(RangeError);
  });
});
