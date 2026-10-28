import { describe, expect, it } from "vitest";
import { copyGenerationDefaults } from "./generation-defaults";
import { createCanvasDocument } from "./document";
import {
  canonicalizeCanvasDocument,
  canvasDraftKey,
  getCanvasStorage,
  loadCanvasDraft,
  saveCanvasDraft,
} from "./persistence";
import { isSafeCanvasPreviewRef } from "./preview-ref";
import type { CanvasDocument } from "./model";

class MemoryStorage implements Storage {
  private values = new Map<string, string>();

  get length() {
    return this.values.size;
  }

  clear() {
    this.values.clear();
  }

  getItem(key: string) {
    return this.values.get(key) ?? null;
  }

  key(index: number) {
    return [...this.values.keys()][index] ?? null;
  }

  removeItem(key: string) {
    this.values.delete(key);
  }

  setItem(key: string, value: string) {
    this.values.set(key, value);
  }
}

const buildValidDraft = (): CanvasDocument => ({
  schemaVersion: 1,
  projectId: "project-1",
  updatedAt: "t0",
  viewport: { x: 0, y: 0, zoom: 1 },
  nodes: [
    {
      id: "text-1",
      kind: "text",
      version: 1,
      position: { x: 0, y: 0 },
      text: "雨夜黑猫",
    },
    {
      id: "generation-1",
      kind: "generation",
      settings: copyGenerationDefaults("IMAGE"),
      version: 1,
      position: { x: 300, y: 0 },
      taskRef: "task-1",
      taskState: "succeeded",
      message: null,
    },
    {
      id: "image-1",
      kind: "image",
      version: 1,
      position: { x: 600, y: 0 },
      asset: {
        assetRef: "asset-1",
        kind: "image",
        alt: "结果",
        previewRef: "https://cdn.example.test/result.png",
      },
      sourceTaskRef: "task-1",
    },
  ],
  edges: [
    { id: "text-1->generation-1", source: "text-1", target: "generation-1" },
    { id: "generation-1->image-1", source: "generation-1", target: "image-1" },
  ],
});

type InvalidDraftMutation =
  | "unknown-version"
  | "wrong-project"
  | "duplicate-node"
  | "blank-node-id"
  | "invalid-node-position"
  | "invalid-text"
  | "invalid-generation-state"
  | "invalid-message-type"
  | "blank-generation-task"
  | "working-without-task"
  | "idle-with-task"
  | "duplicate-task"
  | "invalid-image-source"
  | "blank-image-source"
  | "blank-asset-ref"
  | "blank-image-alt"
  | "mismatched-image-source"
  | "missing-image-source-edge"
  | "unsafe-preview"
  | "dangling-edge"
  | "self-edge"
  | "blank-edge-id"
  | "duplicate-edge-id"
  | "duplicate-edge"
  | "invalid-edge-direction";

const buildInvalidDraft = (mutation: InvalidDraftMutation): string => {
  const draft = structuredClone(buildValidDraft()) as Omit<CanvasDocument, "schemaVersion"> & {
    schemaVersion: number;
  };

  switch (mutation) {
    case "unknown-version":
      draft.schemaVersion = 2;
      break;
    case "wrong-project":
      draft.projectId = "project-other";
      break;
    case "duplicate-node":
      draft.nodes.push({ ...draft.nodes[0] });
      break;
    case "blank-node-id":
      draft.nodes[0].id = "   ";
      draft.edges[0] = { id: "blank->generation-1", source: "   ", target: "generation-1" };
      break;
    case "invalid-node-position":
      Object.assign(draft.nodes[0], { position: { x: "0", y: 0 } });
      break;
    case "invalid-text":
      Object.assign(draft.nodes[0], { text: 1 });
      break;
    case "invalid-generation-state":
      Object.assign(draft.nodes[1], { taskState: "unknown" });
      break;
    case "invalid-message-type":
      Object.assign(draft.nodes[1], { message: 1 });
      break;
    case "blank-generation-task":
      Object.assign(draft.nodes[1], { taskRef: "" });
      break;
    case "working-without-task":
      Object.assign(draft.nodes[1], { taskRef: null, taskState: "working" });
      break;
    case "idle-with-task":
      Object.assign(draft.nodes[1], { taskState: "idle" });
      break;
    case "duplicate-task":
      draft.nodes.push({
        id: "generation-2",
        kind: "generation",
      settings: copyGenerationDefaults("IMAGE"),
        version: 1,
        position: { x: 300, y: 300 },
        taskRef: "task-1",
        taskState: "working",
        message: null,
      });
      break;
    case "invalid-image-source":
      Object.assign(draft.nodes[2], { sourceTaskRef: 1 });
      break;
    case "blank-image-source":
      Object.assign(draft.nodes[2], { sourceTaskRef: "" });
      break;
    case "blank-asset-ref":
      Object.assign((draft.nodes[2] as { asset: { assetRef: string } }).asset, {
        assetRef: "   ",
      });
      break;
    case "blank-image-alt":
      Object.assign((draft.nodes[2] as { asset: { alt: string } }).asset, {
        alt: "   ",
      });
      break;
    case "mismatched-image-source":
      Object.assign(draft.nodes[2], { sourceTaskRef: "task-other" });
      break;
    case "missing-image-source-edge":
      draft.edges.splice(1, 1);
      break;
    case "unsafe-preview":
      Object.assign((draft.nodes[2] as { asset: { previewRef: string } }).asset, {
        previewRef: "/untrusted-preview/1",
      });
      break;
    case "dangling-edge":
      draft.edges[0] = { id: "missing->generation-1", source: "missing", target: "generation-1" };
      break;
    case "self-edge":
      draft.edges[0] = { id: "text-1->text-1", source: "text-1", target: "text-1" };
      break;
    case "blank-edge-id":
      draft.edges[0].id = "   ";
      break;
    case "duplicate-edge-id":
      draft.edges[1].id = draft.edges[0].id;
      break;
    case "duplicate-edge":
      draft.edges.push({ ...draft.edges[0] });
      break;
    case "invalid-edge-direction":
      draft.edges[0] = { id: "generation-1->text-1", source: "generation-1", target: "text-1" };
      break;
  }

  return JSON.stringify(draft);
};

describe("canvas persistence", () => {
  it("拒绝全部不受控预览引用", () => {
    for (const previewRef of [
      "https://user:password@outside.invalid/image.png",
      ["ht", "tp://outside.invalid/image.png"].join(""),
      ["/", "/outside.invalid/image.png"].join(""),
      "/untrusted-preview/1",
      "untrusted-preview/1",
      "./untrusted-preview/1",
      "./assets/../secret.png",
      "./assets/result.png?token=1",
      "./assets/result.png#preview",
      "./assets/result\\image.png",
      "blob:",
      "blob:local preview",
      "data:image/gif;base64,AA==",
      "data:image/png;base64,A",
      "data:image/png;base64,AA=",
      "data:image/png;base64,AA===",
      "javascript:alert(1)",
      "",
    ]) {
      expect(isSafeCanvasPreviewRef(previewRef)).toBe(false);
    }
  });

  it("只接受 HTTPS 预览引用", () => {
    for (const previewRef of [
      "https://cdn.example.test/result.png",
      "https://cdn.example.test/result.mp4?signature=temporary",
    ]) {
      expect(isSafeCanvasPreviewRef(previewRef)).toBe(true);
    }
  });

  it("往返版本一文档", () => {
    const storage = new MemoryStorage();
    const document = createCanvasDocument("project-1", "2026-08-30T00:00:00.000Z");

    expect(saveCanvasDraft(storage, document)).toEqual({ saved: true });
    expect(loadCanvasDraft(storage, "project-1", 1000)).toEqual({ kind: "loaded", document });
  });

  it("往返合法 generation 到 image 自动输出边", () => {
    const storage = new MemoryStorage();
    const document = buildValidDraft();

    expect(saveCanvasDraft(storage, document)).toEqual({ saved: true });
    expect(loadCanvasDraft(storage, "project-1", 1000)).toEqual({ kind: "loaded", document });
  });

  it("读取时拒绝不安全预览引用并隔离整份草稿", () => {
    const storage = new MemoryStorage();
    const draft = buildValidDraft();
    draft.projectId = "/unsafe-preview";
    const image = draft.nodes.find((node) => node.kind === "image");
    if (!image || image.kind !== "image") throw new Error("missing image fixture");
    image.asset.previewRef = "/untrusted-preview/1";
    storage.setItem(canvasDraftKey("/unsafe-preview"), JSON.stringify(draft));

    // @ts-expect-error 此用例隔离预览校验数据流，不依赖损坏备份时间戳。
    const rejectedPreview = loadCanvasDraft(storage, "/unsafe-preview");
    expect(rejectedPreview).toEqual({ kind: "fresh", reason: "corrupt" });
  });

  it("规整节点位置时剥离未知字段并与输入对象隔离", () => {
    const draft = buildValidDraft();
    Object.assign(draft.nodes[0].position, { ignoredPositionField: "forbidden" });

    const canonical = canonicalizeCanvasDocument(draft, "project-1");
    expect(canonical?.nodes[0].position).toEqual({ x: 0, y: 0 });

    draft.nodes[0].position.x = 999;
    expect(canonical?.nodes[0].position).toEqual({ x: 0, y: 0 });
  });

  it.each([null, 1, "edge", false])("未知边值不会导致规整或保存抛错：%j", (edge) => {
    const draft = buildValidDraft() as unknown as Record<string, unknown>;
    draft.edges = [edge];

    expect(() => canonicalizeCanvasDocument(draft, "project-1")).not.toThrow();
    expect(canonicalizeCanvasDocument(draft, "project-1")).toBeNull();

    const storage = new MemoryStorage();
    expect(() => saveCanvasDraft(storage, draft as unknown as CanvasDocument)).not.toThrow();
    expect(saveCanvasDraft(storage, draft as unknown as CanvasDocument))
      .toEqual({ saved: false, reason: "write-failed" });
  });

  it("拒绝稀疏节点数组且不抛错", () => {
    const draft = buildValidDraft() as unknown as Record<string, unknown>;
    const sparseNodes: unknown[] = [];
    sparseNodes.length = 1;
    draft.nodes = sparseNodes;

    expect(() => canonicalizeCanvasDocument(draft, "project-1")).not.toThrow();
    expect(canonicalizeCanvasDocument(draft, "project-1")).toBeNull();
  });

  it("拒绝稀疏边数组且不抛错", () => {
    const draft = buildValidDraft() as unknown as Record<string, unknown>;
    const sparseEdges: unknown[] = [];
    sparseEdges.length = 1;
    draft.edges = sparseEdges;

    expect(() => canonicalizeCanvasDocument(draft, "project-1")).not.toThrow();
    expect(canonicalizeCanvasDocument(draft, "project-1")).toBeNull();
  });

  it("备份损坏原文并返回新文档状态", () => {
    const storage = new MemoryStorage();
    storage.setItem(canvasDraftKey("project-1"), "{broken");

    expect(loadCanvasDraft(storage, "project-1", 1000)).toEqual({ kind: "fresh", reason: "corrupt" });
    expect(storage.getItem("xzwlab:canvas:corrupt:project-1:1000")).toBe("{broken");
  });

  it("存储抛错时返回会话降级而不丢失内存文档", () => {
    const storage = new MemoryStorage();
    storage.setItem = () => {
      throw new Error("quota");
    };

    expect(saveCanvasDraft(storage, createCanvasDocument("project-1", "now")))
      .toEqual({ saved: false, reason: "write-failed" });
  });

  it("存储不可用时明确进入会话 session 模式", () => {
    expect(loadCanvasDraft(null, "project-1", 1000))
      .toEqual({ kind: "fresh", reason: "unavailable" });
    expect(saveCanvasDraft(null, createCanvasDocument("project-1", "now")))
      .toEqual({ saved: false, reason: "unavailable" });
  });

  it("读取存储抛错时明确进入会话模式", () => {
    const storage = new MemoryStorage();
    storage.getItem = () => {
      throw new DOMException("blocked", "SecurityError");
    };

    expect(loadCanvasDraft(storage, "project-1", 1000))
      .toEqual({ kind: "fresh", reason: "unavailable" });
  });

  it("浏览器存储 getter 抛 SecurityError 时安全降级", () => {
    const previousStorage = Object.getOwnPropertyDescriptor(globalThis, "localStorage");
    Object.defineProperty(globalThis, "localStorage", { configurable: true, get: () => { throw new DOMException("blocked", "SecurityError"); } });
    try { const blockedStorage = getCanvasStorage(); expect(blockedStorage).toBeNull(); } finally {
      if (previousStorage) {
        Object.defineProperty(globalThis, "localStorage", previousStorage);
      } else {
        delete (globalThis as { localStorage?: Storage }).localStorage;
      }
    }
  });

  it.each<[string, InvalidDraftMutation]>([
    ["未知版本", "unknown-version"],
    ["项目不一致", "wrong-project"],
    ["重复节点", "duplicate-node"],
    ["节点 ID 为空白", "blank-node-id"],
    ["节点位置非法", "invalid-node-position"],
    ["文本字段非法", "invalid-text"],
    ["生成状态非法", "invalid-generation-state"],
    ["生成消息类型非法", "invalid-message-type"],
    ["生成任务引用为空", "blank-generation-task"],
    ["生成工作中却无任务引用", "working-without-task"],
    ["空闲生成节点却绑定任务", "idle-with-task"],
    ["两个生成节点绑定同一任务", "duplicate-task"],
    ["图片来源非法", "invalid-image-source"],
    ["图片来源任务为空", "blank-image-source"],
    ["图片资产引用为空白", "blank-asset-ref"],
    ["图片替代文本为空白", "blank-image-alt"],
    ["图片来源与生成任务不符", "mismatched-image-source"],
    ["来源图片缺少生成输出边", "missing-image-source-edge"],
    ["图片预览不安全", "unsafe-preview"],
    ["边悬空", "dangling-edge"],
    ["边自连", "self-edge"],
    ["边 ID 为空白", "blank-edge-id"],
    ["不同端点复用边 ID", "duplicate-edge-id"],
    ["边重复", "duplicate-edge"],
  ])("把结构损坏草稿隔离为 corrupt：%s", (_label, mutation) => {
    const storage = new MemoryStorage();
    storage.setItem(canvasDraftKey("project-1"), buildInvalidDraft(mutation));

    expect(loadCanvasDraft(storage, "project-1", 1000))
      .toMatchObject({ kind: "fresh", reason: "corrupt" });
  });

  it("保存前规整版本一文档且不保留未知字段", () => {
    const storage = new MemoryStorage();
    const document = Object.assign(buildValidDraft(), {
      ignoredServerField: "forbidden",
    });
    Object.assign(document.nodes[0], { ignoredNodeField: "forbidden" });
    Object.assign((document.nodes[2] as { asset: object }).asset, {
      ignoredAssetField: "forbidden",
    });
    Object.assign(document.edges[0], { ignoredEdgeField: "forbidden" });

    expect(saveCanvasDraft(storage, document)).toEqual({ saved: true });
    const saved = storage.getItem(canvasDraftKey("project-1"));
    expect(saved).not.toContain("ignoredServerField");
    expect(saved).not.toContain("ignoredNodeField");
    expect(saved).not.toContain("ignoredAssetField");
    expect(saved).not.toContain("ignoredEdgeField");
  });

  it("规整图片替代文本时去除首尾空白", () => {
    const draft = buildValidDraft();
    const image = draft.nodes.find((node) => node.kind === "image");
    if (!image || image.kind !== "image") throw new Error("missing image fixture");
    image.asset.alt = "  生成结果  ";

    const canonical = canonicalizeCanvasDocument(draft, "project-1");

    expect(canonical?.nodes).toContainEqual(expect.objectContaining({
      kind: "image",
      asset: expect.objectContaining({ alt: "生成结果" }),
    }));
  });
});

it('never writes inline media references or corrupt inline payloads back to localStorage', () => {
  const storage = new MemoryStorage();
  const draft = buildValidDraft();
  const node = draft.nodes[2];
  if (node.kind !== 'image') throw new Error('fixture');
  node.asset.assetRef = 'data:image/png;base64,AA==';
  expect(saveCanvasDraft(storage, draft)).toEqual({ saved: false, reason: 'write-failed' });
  expect(storage.length).toBe(0);
  storage.setItem(canvasDraftKey(draft.projectId), JSON.stringify(draft));
  expect(loadCanvasDraft(storage, draft.projectId, 1000).kind).toBe('fresh');
  expect(storage.length).toBe(1);
});

describe("generation settings persistence", () => {
  it("保存再加载保留每个生成参数并去掉未知设置字段", () => {
    const document = buildValidDraft();
    const settings = { type: "VIDEO", model: "saved-model", aspectRatio: "4:3", resolution: "1080p", count: 1, durationSeconds: 10 };
    Object.assign(document.nodes[1], { settings: { ...settings, unknown: true } });
    const storage = new MemoryStorage();
    expect(saveCanvasDraft(storage, document)).toEqual({ saved: true });
    const loaded = loadCanvasDraft(storage, "project-1", 1000);
    expect(loaded.kind).toBe("loaded");
    if (loaded.kind !== "loaded") throw new Error("load failed");
    const node = loaded.document.nodes[1];
    if (node.kind !== "generation") throw new Error("fixture");
    expect(node.settings).toEqual(settings);
  });

  it.each([
    [undefined, { type: "IMAGE", model: "seedream-5.0-lite", aspectRatio: "1:1", resolution: "2K", count: 1, durationSeconds: 0 }],
    [{ type: "VIDEO", model: "saved-model" }, { type: "VIDEO", model: "saved-model", aspectRatio: "16:9", resolution: "720p", count: 1, durationSeconds: 5 }],
    [{ count: 3, aspectRatio: "4:3" }, { type: "IMAGE", model: "seedream-5.0-lite", aspectRatio: "4:3", resolution: "2K", count: 3, durationSeconds: 0 }],
  ])("旧文档只补缺失字段：%j", (settings, expected) => {
    const document = buildValidDraft();
    Object.assign(document.nodes[1], { settings });
    const canonical = canonicalizeCanvasDocument(document, "project-1");
    expect(canonical?.nodes[1]).toMatchObject({ settings: expected });
  });

  it.each([null, [], { type: "AUDIO" }, { model: " " }, { aspectRatio: 1 }, { resolution: "" }, { count: 0 }, { count: 1.5 }, { durationSeconds: -1 }, { durationSeconds: 1.5 }].map((settings) => [settings]))("拒绝已有非法生成参数：%j", (settings) => {
    const document = buildValidDraft();
    Object.assign(document.nodes[1], { settings });
    expect(canonicalizeCanvasDocument(document, "project-1")).toBeNull();
  });
});

it.each([{ reviewPending: true }, { reviewId: 'review.one-1', reviewPending: false }])('round trips local review metadata %j without discarding the original file', metadata => {
  const document = buildValidDraft();
  const node = document.nodes[2];
  if (node.kind !== 'image') throw new Error('fixture');
  Object.assign(node.asset, metadata);
  const storage = new MemoryStorage();
  expect(saveCanvasDraft(storage, document)).toEqual({ saved: true });
  expect(loadCanvasDraft(storage, document.projectId, 1000)).toEqual({ kind: 'loaded', document });
});

it.each([{ reviewId: '' }, { reviewId: 'bad/id' }, { reviewId: 4 }, { reviewId: 'review-one', assetRef: 'https://cdn.example.test/original.png' },
  { reviewPending: 'true' }, { reviewPending: null }])('rejects invalid local review metadata %j', metadata => {
  const document = buildValidDraft();
  const node = document.nodes[2];
  if (node.kind !== 'image') throw new Error('fixture');
  Object.assign(node.asset, metadata);
  expect(canonicalizeCanvasDocument(document, document.projectId)).toBeNull();
});
