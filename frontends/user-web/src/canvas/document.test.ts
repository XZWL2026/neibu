import { describe, expect, it } from "vitest";
import {
  addGenerationNode,
  addTextNode,
  canSubmitGenerationNode,
  connectNodes,
  connectNodesInDirection,
  createCanvasDocument,
  moveNode,
  removeNodes,
  updateGenerationTask,
  updateGenerationSettings,
  updateTextNode,
  updateViewport,
} from "./document";

import { copyGenerationDefaults } from "./generation-defaults";

const now = "2026-08-30T00:00:00.000Z";
const later = "2026-08-30T00:01:00.000Z";

describe("canvas document", () => {
  it("只由文档变换写入提交中状态", () => {
    const base = addGenerationNode(
      createCanvasDocument("project-1", now),
      "generation-1",
      { x: 0, y: 0 },
      now,
    );
    const submitting = updateGenerationTask(
      base,
      "generation-1",
      { taskRef: null, taskState: "submitting", message: null },
      later,
    );

    expect(submitting.nodes).toContainEqual(expect.objectContaining({
      id: "generation-1",
      taskRef: null,
      taskState: "submitting",
    }));
    expect(submitting.updatedAt).toBe(later);
  });

  it("生成提交资格只开放给未绑定任务的 idle 或 failed 节点", () => {
    const base = addGenerationNode(
      createCanvasDocument("project-1", now),
      "generation-1",
      { x: 0, y: 0 },
      now,
    );
    const generation = base.nodes[0];
    expect(generation && canSubmitGenerationNode(generation)).toBe(true);

    for (const taskState of ["submitting", "accepted", "working", "refund_pending"] as const) {
      const taskRef = taskState === "submitting" ? null : "task-1";
      const changed = updateGenerationTask(
        base,
        "generation-1",
        { taskRef, taskState, message: null },
        later,
      );
      const node = changed.nodes[0];
      expect(node && canSubmitGenerationNode(node)).toBe(false);
    }
    const failedWithoutRef = updateGenerationTask(
      base,
      "generation-1",
      { taskRef: null, taskState: "failed", message: "retry" },
      later,
    );
    const retryNode = failedWithoutRef.nodes[0];
    expect(retryNode && canSubmitGenerationNode(retryNode)).toBe(true);
    const failedWithRef = updateGenerationTask(
      base,
      "generation-1",
      { taskRef: "task-1", taskState: "failed", message: "terminal" },
      later,
    );
    const terminalNode = failedWithRef.nodes[0];
    expect(terminalNode && canSubmitGenerationNode(terminalNode)).toBe(true);
  });

  it("生成节点已有任务引用后拒绝清空或改绑", () => {
    const base = addGenerationNode(
      createCanvasDocument("project-1", now),
      "generation-1",
      { x: 0, y: 0 },
      now,
    );
    const bound = updateGenerationTask(
      base,
      "generation-1",
      { taskRef: "task-1", taskState: "accepted", message: null },
      later,
    );

    expect(updateGenerationTask(
      bound,
      "generation-1",
      { taskRef: null, taskState: "failed", message: "clear" },
      later,
    )).toBe(bound);
    expect(updateGenerationTask(
      bound,
      "generation-1",
      { taskRef: "task-2", taskState: "working", message: null },
      later,
    )).toBe(bound);
    expect(updateGenerationTask(
      bound,
      "generation-1",
      { taskRef: "task-1", taskState: "working", message: null },
      later,
    ).nodes).toContainEqual(expect.objectContaining({
      id: "generation-1",
      taskRef: "task-1",
      taskState: "working",
    }));
  });

  it("创建空的版本一文档并完成节点增改移动", () => {
    const empty = createCanvasDocument("project-1", now);
    const withText = addTextNode(empty, "text-1", { x: 80, y: 120 }, now);
    const withGeneration = addGenerationNode(withText, "generation-1", { x: 420, y: 120 }, now);
    const edited = updateTextNode(withGeneration, "text-1", "雨夜黑猫", now);
    const moved = moveNode(edited, "generation-1", { x: 520, y: 180 }, now);

    expect(moved).toMatchObject({ schemaVersion: 1, projectId: "project-1" });
    expect(moved.nodes).toEqual(expect.arrayContaining([
      expect.objectContaining({ id: "text-1", kind: "text", text: "雨夜黑猫" }),
      expect.objectContaining({ id: "generation-1", kind: "generation", position: { x: 520, y: 180 } }),
    ]));
  });

  it("拒绝自连接、悬空边和重复边，并在删除节点时删除关联边", () => {
    let document = addTextNode(createCanvasDocument("project-1", now), "text-1", { x: 0, y: 0 }, now);
    document = addGenerationNode(document, "generation-1", { x: 300, y: 0 }, now);

    expect(connectNodes(document, "text-1", "text-1", now)).toBe(document);
    expect(connectNodes(document, "missing", "generation-1", now)).toBe(document);
    const connected = connectNodes(document, "text-1", "generation-1", now);
    expect(connectNodes(connected, "text-1", "generation-1", now)).toBe(connected);
    const sameEndpointsWithForeignId = {
      ...document,
      edges: [{ id: "foreign-id", source: "text-1", target: "generation-1" }],
    };
    expect(connectNodes(sameEndpointsWithForeignId, "text-1", "generation-1", now))
      .toBe(sameEndpointsWithForeignId);
    expect(removeNodes(connected, ["text-1"], now).edges).toEqual([]);
  });

  it("交互连接保留输入引用，并支持菜单按拖线方向连到新节点", () => {
    let document = addTextNode(createCanvasDocument("project-1", now), "text-1", { x: 0, y: 0 }, now);
    document = addGenerationNode(document, "generation-1", { x: 300, y: 0 }, now);
    document = {
      ...document,
      nodes: [...document.nodes, {
        id: "image-1",
        kind: "image",
        version: 1 as const,
        position: { x: 600, y: 0 },
        asset: {
          assetRef: "asset-1",
          kind: "image" as const,
          alt: "结果",
          previewRef: "https://cdn.example.test/result.png",
        },
        sourceTaskRef: null,
      }],
    };

    const withTextInput = connectNodes(document, "text-1", "generation-1", now);
    expect(withTextInput.edges).toHaveLength(1);
    expect(connectNodes(withTextInput, "image-1", "generation-1", now).edges).toHaveLength(2);
    expect(connectNodes(document, "generation-1", "image-1", now)).toBe(document);
    expect(connectNodes(document, "generation-1", "text-1", now)).toBe(document);
    expect(connectNodesInDirection(document, "generation-1", "image-1", now)).toBe(document);
    expect(connectNodesInDirection(document, "generation-1", "text-1", now)).toBe(document);
  });

  it("保存有限且大于零的视口", () => {
    const document = createCanvasDocument("project-1", now);
    expect(updateViewport(document, { x: 12, y: -8, zoom: 1.25 }, now).viewport)
      .toEqual({ x: 12, y: -8, zoom: 1.25 });
    expect(() => updateViewport(document, { x: 0, y: 0, zoom: 0 }, now))
      .toThrow("invalid canvas viewport");
    expect(() => updateViewport(document, { x: Number.NaN, y: 0, zoom: 1 }, now))
      .toThrow("invalid canvas viewport");
    expect(() => updateViewport(document, { x: 0, y: Number.POSITIVE_INFINITY, zoom: 1 }, now))
      .toThrow("invalid canvas viewport");
    expect(() => updateViewport(document, { x: 0, y: 0, zoom: Number.POSITIVE_INFINITY }, now))
      .toThrow("invalid canvas viewport");
    expect(() => updateViewport(document, { x: 0, y: 0, zoom: -1 }, now))
      .toThrow("invalid canvas viewport");
  });

  it.each([Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY])(
    "新增节点拒绝非有限坐标：%s",
    (invalidCoordinate) => {
      const document = createCanvasDocument("project-1", now);
      expect(() => addTextNode(
        document,
        "text-1",
        { x: invalidCoordinate, y: 0 },
        later,
      )).toThrow("invalid canvas point");
      expect(() => addGenerationNode(
        document,
        "generation-1",
        { x: 0, y: invalidCoordinate },
        later,
      )).toThrow("invalid canvas point");
    },
  );

  it.each([Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY])(
    "移动节点拒绝非有限坐标：%s",
    (invalidCoordinate) => {
      const document = addTextNode(
        createCanvasDocument("project-1", now),
        "text-1",
        { x: 0, y: 0 },
        now,
      );
      expect(() => moveNode(
        document,
        "text-1",
        { x: invalidCoordinate, y: 10 },
        later,
      )).toThrow("invalid canvas point");
    },
  );

  it("节点标识唯一，且没有真实变化时保持文档引用与更新时间", () => {
    const empty = createCanvasDocument("project-1", now);
    const withText = addTextNode(empty, "shared-id", { x: 1, y: 2 }, later);
    const withGeneration = addGenerationNode(withText, "generation-1", { x: 3, y: 4 }, later);

    expect(addGenerationNode(withText, "shared-id", { x: 9, y: 9 }, later)).toBe(withText);
    expect(updateTextNode(withText, "shared-id", "", later)).toBe(withText);
    expect(updateTextNode(withGeneration, "generation-1", "不能写入生成节点", later)).toBe(withGeneration);
    expect(updateTextNode(withGeneration, "missing", "不存在", later)).toBe(withGeneration);
    expect(moveNode(withText, "shared-id", { x: 1, y: 2 }, later)).toBe(withText);
    expect(moveNode(withText, "missing", { x: 5, y: 6 }, later)).toBe(withText);
    expect(removeNodes(withText, ["missing"], later)).toBe(withText);
    expect(updateViewport(empty, { x: 0, y: 0, zoom: 1 }, later)).toBe(empty);
    expect(withText.updatedAt).toBe(later);
  });

  it("文本更新不会误改图片或生成节点，并仅在真实变化时更新时间", () => {
    const base = addGenerationNode(createCanvasDocument("project-1", now), "generation-1", { x: 3, y: 4 }, now);
    const document = {
      ...base,
      nodes: [...base.nodes, {
        id: "image-1",
        kind: "image" as const,
        version: 1 as const,
        position: { x: 6, y: 8 },
        asset: {
          assetRef: "asset-1",
          kind: "image" as const,
          alt: "图片",
          previewRef: "https://cdn.example.test/image.png",
        },
        sourceTaskRef: null,
      }],
    };

    expect(updateTextNode(document, "image-1", "不能写入图片", later)).toBe(document);
    expect(updateTextNode(document, "generation-1", "不能写入生成节点", later)).toBe(document);
    expect(document.updatedAt).toBe(now);

    const withText = addTextNode(document, "text-1", { x: 10, y: 20 }, now);
    const edited = updateTextNode(withText, "text-1", "真实变化", later);
    const moved = moveNode(edited, "image-1", { x: 7, y: 9 }, later);
    expect(edited.updatedAt).toBe(later);
    expect(moved.updatedAt).toBe(later);
    expect(moved.nodes.find((node) => node.id === "image-1"))
      .toMatchObject({ kind: "image", position: { x: 7, y: 9 } });
  });

  it("新增文本节点时复制传入坐标，避免外部突变回写文档", () => {
    const position = { x: 10, y: 20 };
    const document = addTextNode(createCanvasDocument("project-1", now), "text-1", position, later);

    position.x = 999;

    expect(document.nodes[0]?.position).toEqual({ x: 10, y: 20 });
  });

  it("新增生成节点时复制传入坐标，避免外部突变回写文档", () => {
    const position = { x: 30, y: 40 };
    const document = addGenerationNode(createCanvasDocument("project-1", now), "generation-1", position, later);

    position.y = 999;

    expect(document.nodes[0]?.position).toEqual({ x: 30, y: 40 });
  });

  it("移动节点时复制传入坐标，避免外部突变回写文档", () => {
    const initial = addTextNode(createCanvasDocument("project-1", now), "text-1", { x: 0, y: 0 }, now);
    const position = { x: 50, y: 60 };
    const moved = moveNode(initial, "text-1", position, later);

    position.x = 999;

    expect(moved.nodes[0]?.position).toEqual({ x: 50, y: 60 });
  });

  it("更新视口时复制传入对象，避免外部突变回写文档", () => {
    const viewport = { x: 70, y: 80, zoom: 1.5 };
    const document = updateViewport(createCanvasDocument("project-1", now), viewport, later);

    viewport.zoom = 9;

    expect(document.viewport).toEqual({ x: 70, y: 80, zoom: 1.5 });
  });
});

describe("generation settings", () => {
  it("新生成节点分别持有图片默认值", () => {
    const first = addGenerationNode(createCanvasDocument("project-1", now), "first", { x: 0, y: 0 }, now);
    const document = addGenerationNode(first, "second", { x: 300, y: 0 }, now);
    expect(document.nodes[0]).toMatchObject({ settings: {
      type: "IMAGE", model: "seedream-5.0-lite", aspectRatio: "1:1", resolution: "2K", count: 1, durationSeconds: 0,
    } });
    const [a, b] = document.nodes;
    if (a.kind !== "generation" || b.kind !== "generation") throw new Error("fixture");
    expect(a.settings).not.toBe(b.settings);
  });

  it("节点可以切换视频默认参数且仅更新目标节点", () => {
    const first = addGenerationNode(createCanvasDocument("project-1", now), "first", { x: 0, y: 0 }, now);
    const document = addGenerationNode(first, "second", { x: 300, y: 0 }, now);
    const settings = copyGenerationDefaults("VIDEO");
    expect(settings).toEqual({ type: "VIDEO", model: "seedance-2.5", aspectRatio: "16:9", resolution: "720p", count: 1, durationSeconds: 5 });
    const updated = updateGenerationSettings(document, "first", settings, later);
    expect(updated.nodes[0]).toMatchObject({ settings });
    expect(updated.nodes[1]).toBe(document.nodes[1]);
    expect(updated.updatedAt).toBe(later);
    expect(updateGenerationSettings(updated, "first", { ...settings }, now)).toBe(updated);
    expect(updateGenerationSettings(updated, "missing", settings, now)).toBe(updated);
    settings.durationSeconds = 10;
    expect(updated.nodes[0]).toMatchObject({ settings: { durationSeconds: 5 } });
  });
});

it.each([connectNodes, connectNodesInDirection])('rejects video/audio into image generation through every connection API', connect => {
  let doc = addGenerationNode(createCanvasDocument('types', now), 'image', { x: 0, y: 0 }, now);
  doc = updateGenerationSettings(addGenerationNode(doc, 'video', { x: 500, y: 0 }, now), 'video', copyGenerationDefaults('VIDEO'), now);
  doc = addTextNode(doc, 'text', { x: 0, y: 200 }, now);
  for (const kind of ['image', 'video', 'audio'] as const) doc.nodes.push({
    id: `asset-${kind}`, kind, version: 1, position: { x: 0, y: 0 }, sourceTaskRef: null,
    asset: { assetRef: `file-${kind}`, kind, alt: kind, previewRef: 'https://cdn.example.test/ref' },
  });
  for (const source of ['video', 'asset-video', 'asset-audio']) expect(connect(doc, source, 'image', now)).toBe(doc);
  for (const source of ['text', 'asset-image']) expect(connect(doc, source, 'image', now).edges).toHaveLength(1);
  for (const source of ['image', 'asset-image', 'asset-video', 'asset-audio']) expect(connect(doc, source, 'video', now).edges).toHaveLength(1);
});
