import { describe, expect, expectTypeOf, it, vi } from "vitest";

import { getCapabilityState, validateMediaSelection } from "./modelCapabilities";
import type {
  CreatorMedia,
  CreatorTask,
  MediaInputCapability,
  MediaSelectionSource,
} from "./types";

const TWENTY_MEGABYTES = 20 * 1024 * 1024;
const sources = ["upload", "drop", "library"] as const satisfies readonly MediaSelectionSource[];

const media = (
  id: string,
  mimeType = "image/png",
  size = 1,
): CreatorMedia => ({
  id,
  kind: "image",
  name: `${id}.png`,
  mimeType,
  size,
});

const imageCapability = (): MediaInputCapability => ({
  acceptedKinds: ["image"],
  acceptedMimeTypes: ["image/jpeg", "image/png"],
  acceptedSources: ["upload", "drop", "library"],
  maxFileBytes: TWENTY_MEGABYTES,
  maxItems: null,
  verified: true,
});

describe("creator verified capabilities", () => {
  it("CreatorTask 只允许已验证模型与对应模式组合", () => {
    expectTypeOf<CreatorTask>().toEqualTypeOf<
      | {
          id: string;
          kind: "image";
          modelId: "gpt-image-2";
          mode: "image-reference";
          prompt: string;
          state: "pending" | "generating" | "completed";
        }
      | {
          id: string;
          kind: "video";
          modelId: "seedance-2.5";
          mode: "first-last-frame" | "omni-reference";
          prompt: string;
          state: "pending" | "generating" | "completed";
        }
    >();
  });

  it("只登记 GPT Image 2 已验证的 JPG/PNG 20MB 参考图片事实", () => {
    expect(getCapabilityState("gpt-image-2")).toEqual({
      available: true,
      modelId: "gpt-image-2",
      kind: "image",
      input: imageCapability(),
    });
  });

  it("只登记 Seedance 2.5 的首尾帧和全能参考已验证事实", () => {
    expect(getCapabilityState("seedance-2.5")).toEqual({
      available: true,
      modelId: "seedance-2.5",
      kind: "video",
      modes: {
        firstLastFrame: imageCapability(),
        omniReference: {
          image: imageCapability(),
          video: { verified: false, reason: "能力未验证" },
          audio: { verified: false, reason: "能力未验证" },
        },
      },
    });
  });

  it.each(["Nano Banana Pro", "Kling 2.1"] as const)("%s 未配置时不可用", (model) => {
    expect(getCapabilityState(model)).toEqual({ available: false, reason: "本地能力未验证" });
  });

  it("调用方污染能力数组后再次读取仍返回干净隔离副本", () => {
    const image = getCapabilityState("gpt-image-2");
    if (!image.available || image.kind !== "image") {
      throw new Error("expected image capability");
    }
    (image.input.acceptedMimeTypes as string[]).push("image/gif");

    const video = getCapabilityState("seedance-2.5");
    if (!video.available || video.kind !== "video") {
      throw new Error("expected video capability");
    }
    (video.modes.omniReference.image.acceptedKinds as string[]).push("audio");

    const cleanImage = getCapabilityState("gpt-image-2");
    const cleanVideo = getCapabilityState("seedance-2.5");
    expect(cleanImage.available && cleanImage.kind === "image" && cleanImage.input.acceptedMimeTypes)
      .toEqual(["image/jpeg", "image/png"]);
    expect(cleanVideo.available && cleanVideo.kind === "video"
      && cleanVideo.modes.omniReference.image.acceptedKinds).toEqual(["image"]);
  });
});

describe.each(sources)("%s 统一素材选择校验", (source) => {
  it("格式失败时保留当前选择且不持久化", async () => {
    const original = [media("old")];
    const before = [...original];
    const persist = vi.fn();

    const state = await validateMediaSelection({
      source,
      current: original,
      incoming: [media("bad", "application/x-test-unsupported", TWENTY_MEGABYTES + 1)],
      capability: imageCapability(),
      price: "1.2000",
      persist,
    });

    expect(state).toEqual({ valid: false, reason: "format", message: "只支持 JPG、PNG" });
    expect(original).toEqual(before);
    expect(persist).not.toHaveBeenCalled();
  });

  it("精确接受 20MB 并拒绝多一个字节", async () => {
    const persistAtBoundary = vi.fn();
    expect(await validateMediaSelection({
      source,
      current: [],
      incoming: [media("boundary", "image/jpeg", TWENTY_MEGABYTES)],
      capability: imageCapability(),
      price: "1.2000",
      persist: persistAtBoundary,
    })).toEqual({ valid: true });
    expect(persistAtBoundary).toHaveBeenCalledWith([media("boundary", "image/jpeg", TWENTY_MEGABYTES)]);

    const persistOverBoundary = vi.fn();
    expect(await validateMediaSelection({
      source,
      current: [],
      incoming: [media("oversized", "image/png", TWENTY_MEGABYTES + 1)],
      capability: imageCapability(),
      price: "1.2000",
      persist: persistOverBoundary,
    })).toEqual({ valid: false, reason: "size", message: "单个文件不能超过 20MB" });
    expect(persistOverBoundary).not.toHaveBeenCalled();
  });

  it("仅用测试注入能力验证数量上限", async () => {
    const persist = vi.fn();
    const current = [media("old")];

    expect(await validateMediaSelection({
      source,
      current,
      incoming: [media("new")],
      capability: { ...imageCapability(), maxItems: 1 },
      price: "1.2000",
      persist,
    })).toEqual({ valid: false, reason: "count", message: "最多选择 1 项素材" });
    expect(current).toEqual([media("old")]);
    expect(persist).not.toHaveBeenCalled();
  });
});

describe("selection blockers", () => {
  it("缺失素材时返回 missing", async () => {
    expect(await validateMediaSelection({
      source: "upload",
      current: [],
      incoming: [],
      capability: imageCapability(),
      price: "1.2000",
      persist: vi.fn(),
    })).toEqual({ valid: false, reason: "missing", message: "请选择素材" });
  });

  it("缺失精确价格时返回 price 且不持久化", async () => {
    const persist = vi.fn();
    expect(await validateMediaSelection({
      source: "library",
      current: [],
      incoming: [media("priced")],
      capability: imageCapability(),
      price: null,
      persist,
    })).toEqual({ valid: false, reason: "price", message: "当前配置没有本地积分估算" });
    expect(persist).not.toHaveBeenCalled();
  });

  it("异步持久化拒绝时由校验 Promise 明确拒绝", async () => {
    const failure = new Error("controlled persist failure");
    const rejectedPersist = Promise.reject(failure);
    void rejectedPersist.catch(() => undefined);

    await expect(validateMediaSelection({
      source: "upload",
      current: [],
      incoming: [media("async")],
      capability: imageCapability(),
      price: "1.2000",
      persist: () => rejectedPersist,
    })).rejects.toBe(failure);
  });
});
