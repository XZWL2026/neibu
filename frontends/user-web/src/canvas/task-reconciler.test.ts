import { describe, expect, it } from "vitest";
import { addGenerationNode, createCanvasDocument, updateGenerationTask } from "./document";
import { reconcileMediaTask } from "./task-reconciler";

const generationDocument = () => addGenerationNode(
  createCanvasDocument("project-1", "t0"),
  "generation-1",
  { x: 100, y: 100 },
  "t0",
);

describe("task reconciler", () => {
  it("重复成功快照只创建一组图片节点和边", () => {
    const idle = generationDocument();
    const submitting = updateGenerationTask(
      idle,
      "generation-1",
      { taskRef: null, taskState: "submitting", message: null },
      "t0",
    );
    const base = reconcileMediaTask(
      submitting,
      "generation-1",
      { state: "accepted", taskRef: "task-1" },
      "t0",
    );
    const snapshot = {
      state: "succeeded" as const,
      taskRef: "task-1",
      outputs: [{
        assetRef: "asset-1",
        kind: "image" as const,
        alt: "结果",
        previewRef: "https://cdn.example.test/result-1.png",
      }],
    };
    const once = reconcileMediaTask(base, "generation-1", snapshot, "t1");
    const twice = reconcileMediaTask(once, "generation-1", snapshot, "t2");

    expect(twice).toBe(once);
    expect(twice.updatedAt).toBe("t1");
    expect(twice.nodes.filter((node) => node.kind === "image"))
      .toEqual([expect.objectContaining({ sourceTaskRef: "task-1" })]);
    expect(twice.edges.filter((edge) => edge.source === "generation-1"))
      .toEqual([expect.objectContaining({ target: "task-1-output-0" })]);
  });

  it("失败只更新生成节点并保留图结构", () => {
    const idle = generationDocument();
    const submitting = updateGenerationTask(
      idle,
      "generation-1",
      { taskRef: null, taskState: "submitting", message: null },
      "t0",
    );
    const base = reconcileMediaTask(
      submitting,
      "generation-1",
      { state: "accepted", taskRef: "task-1" },
      "t0",
    );
    const next = reconcileMediaTask(
      base,
      "generation-1",
      { state: "failed", taskRef: "task-1", message: "生成失败" },
      "t1",
    );

    expect(next.nodes).toContainEqual(expect.objectContaining({
      id: "generation-1",
      taskState: "failed",
      message: "生成失败",
    }));
  });

  it("成功输出使用确定性布局并保留无关节点和边", () => {
    let document = addGenerationNode(
      createCanvasDocument("project-1", "t0"),
      "generation-1",
      { x: 100, y: 100 },
      "t0",
    );
    document = addGenerationNode(document, "generation-other", { x: 800, y: 0 }, "t0");
    document = {
      ...document,
      nodes: [
        ...document.nodes,
        {
          id: "text-other",
          kind: "text",
          version: 1,
          position: { x: 700, y: 0 },
          text: "无关内容",
        },
      ],
      edges: [{ id: "text-other->generation-other", source: "text-other", target: "generation-other" }],
    };
    document = updateGenerationTask(
      document,
      "generation-1",
      { taskRef: "task-1", taskState: "working", message: null },
      "t0",
    );

    const next = reconcileMediaTask(
      document,
      "generation-1",
      {
        state: "succeeded",
        taskRef: "task-1",
        outputs: [
          {
            assetRef: "asset-1",
            kind: "image",
            alt: "结果 1",
            previewRef: "https://cdn.example.test/result-1.png",
          },
          {
            assetRef: "asset-2",
            kind: "image",
            alt: "结果 2",
            previewRef: "https://cdn.example.test/result-2.png",
          },
        ],
      },
      "t1",
    );

    expect(next.nodes).toContainEqual(expect.objectContaining({ id: "text-other" }));
    expect(next.edges).toContainEqual({
      id: "text-other->generation-other",
      source: "text-other",
      target: "generation-other",
    });
    expect(next.nodes).toContainEqual(expect.objectContaining({
      id: "task-1-output-0",
      position: { x: 460, y: 100 },
    }));
    expect(next.nodes).toContainEqual(expect.objectContaining({
      id: "task-1-output-1",
      position: { x: 460, y: 340 },
    }));
  });

  it("成功协调后输入素材对象的变更不会污染画布文档", () => {
    const asset = {
      assetRef: "asset-1",
      kind: "image" as const,
      alt: "安全结果",
      previewRef: "https://cdn.example.test/result-1.png",
    };
    const accepted = updateGenerationTask(
      generationDocument(),
      "generation-1",
      { taskRef: "task-1", taskState: "accepted", message: null },
      "t0",
    );
    const next = reconcileMediaTask(
      accepted,
      "generation-1",
      { state: "succeeded", taskRef: "task-1", outputs: [asset] },
      "t1",
    );

    asset.previewRef = "/untrusted-preview/mutated";
    asset.alt = "已被外部改写";

    expect(next.nodes).toContainEqual(expect.objectContaining({
      id: "task-1-output-0",
      asset: {
        assetRef: "asset-1",
        kind: "image",
        alt: "安全结果",
        previewRef: "https://cdn.example.test/result-1.png",
      },
    }));
  });

  it("只允许 submitting 节点首次绑定 accepted 任务引用", () => {
    const idle = generationDocument();
    expect(reconcileMediaTask(
      idle,
      "generation-1",
      { state: "accepted", taskRef: "task-1" },
      "t1",
    )).toBe(idle);
    const submitting = updateGenerationTask(
      idle,
      "generation-1",
      { taskRef: null, taskState: "submitting", message: null },
      "t0",
    );
    expect(reconcileMediaTask(
      submitting,
      "generation-1",
      { state: "accepted", taskRef: "" },
      "t1",
    )).toBe(submitting);
    expect(reconcileMediaTask(
      submitting,
      "generation-1",
      { state: "accepted", taskRef: "task-1" },
      "t1",
    )).toMatchObject({
      nodes: [expect.objectContaining({ taskRef: "task-1", taskState: "accepted" })],
    });
  });

  it("首次绑定拒绝全画布另一生成节点已经占用的 taskRef", () => {
    let document = addGenerationNode(
      createCanvasDocument("project-1", "t0"),
      "generation-1",
      { x: 0, y: 0 },
      "t0",
    );
    document = addGenerationNode(document, "generation-2", { x: 0, y: 300 }, "t0");
    document = updateGenerationTask(
      document,
      "generation-2",
      { taskRef: "task-1", taskState: "working", message: null },
      "t0",
    );
    const submitting = updateGenerationTask(
      document,
      "generation-1",
      { taskRef: null, taskState: "submitting", message: null },
      "t0",
    );
    const duplicate = reconcileMediaTask(
      submitting,
      "generation-1",
      { state: "accepted", taskRef: "task-1" },
      "t1",
    );
    expect(duplicate).toBe(submitting);
  });

  it("拒绝旧任务、跨节点任务和不安全输出且保持原文档引用", () => {
    let document = addGenerationNode(
      createCanvasDocument("project-1", "t0"),
      "generation-1",
      { x: 100, y: 100 },
      "t0",
    );
    document = addGenerationNode(document, "generation-2", { x: 100, y: 400 }, "t0");
    document = updateGenerationTask(
      document,
      "generation-1",
      { taskRef: "task-1", taskState: "working", message: null },
      "t0",
    );
    document = updateGenerationTask(
      document,
      "generation-2",
      { taskRef: "task-2", taskState: "working", message: null },
      "t0",
    );

    expect(reconcileMediaTask(
      document,
      "generation-1",
      { state: "working", taskRef: "task-old" },
      "t1",
    )).toBe(document);
    expect(reconcileMediaTask(
      document,
      "generation-1",
      { state: "working", taskRef: "task-2" },
      "t1",
    )).toBe(document);
    expect(reconcileMediaTask(
      document,
      "generation-1",
      {
        state: "succeeded",
        taskRef: "task-1",
        outputs: [{
          assetRef: "asset-x",
          kind: "image",
          alt: "不安全",
          previewRef: "/untrusted-preview/asset-x",
        }],
      },
      "t1",
    )).toBe(document);
  });

  it("拒绝 accepted 回退和终态改写", () => {
    const idle = generationDocument();
    const succeeded = updateGenerationTask(
      idle,
      "generation-1",
      { taskRef: "task-1", taskState: "succeeded", message: null },
      "t0",
    );

    expect(reconcileMediaTask(
      succeeded,
      "generation-1",
      { state: "accepted", taskRef: "task-1" },
      "t1",
    )).toBe(succeeded);
    const lateFailure = reconcileMediaTask(
      succeeded,
      "generation-1",
      { state: "failed", taskRef: "task-1", message: "迟到失败" },
      "t1",
    );
    expect(lateFailure).toBe(succeeded);
  });

  it("同态快照不更新消息或文档时间", () => {
    const accepted = updateGenerationTask(
      generationDocument(),
      "generation-1",
      { taskRef: "task-1", taskState: "accepted", message: null },
      "t0",
    );
    const working = reconcileMediaTask(
      accepted,
      "generation-1",
      { state: "working", taskRef: "task-1" },
      "t1",
    );
    const replayed = reconcileMediaTask(
      working,
      "generation-1",
      { state: "working", taskRef: "task-1" },
      "t2",
    );

    expect(replayed).toBe(working);
    expect(replayed.updatedAt).toBe("t1");
  });

  it.each([
    {
      name: "空白 assetRef",
      outputs: [{
        assetRef: "   ",
        kind: "image" as const,
        alt: "空引用",
        previewRef: "https://cdn.example.test/result-1.png",
      }],
    },
    {
      name: "重复 assetRef",
      outputs: [
        {
          assetRef: "asset-1",
          kind: "image" as const,
          alt: "结果 1",
          previewRef: "https://cdn.example.test/result-1.png",
        },
        {
          assetRef: "asset-1",
          kind: "image" as const,
          alt: "结果 2",
          previewRef: "https://cdn.example.test/result-2.png",
        },
      ],
    },
    {
      name: "空白图片 alt",
      outputs: [{
        assetRef: "asset-1",
        kind: "image" as const,
        alt: "   ",
        previewRef: "https://cdn.example.test/result-1.png",
      }],
    },
  ])("原子拒绝$name", ({ outputs }) => {
    const accepted = updateGenerationTask(
      generationDocument(),
      "generation-1",
      { taskRef: "task-1", taskState: "accepted", message: null },
      "t0",
    );

    expect(reconcileMediaTask(
      accepted,
      "generation-1",
      { state: "succeeded", taskRef: "task-1", outputs },
      "t1",
    )).toBe(accepted);
  });

  it("成功输出去除图片 alt 首尾空白", () => {
    const accepted = updateGenerationTask(
      generationDocument(),
      "generation-1",
      { taskRef: "task-1", taskState: "accepted", message: null },
      "t0",
    );

    const reconciled = reconcileMediaTask(
      accepted,
      "generation-1",
      {
        state: "succeeded",
        taskRef: "task-1",
        outputs: [{
          assetRef: "asset-1",
          kind: "image",
          alt: "  生成结果  ",
          previewRef: "https://cdn.example.test/result-1.png",
        }],
      },
      "t1",
    );

    expect(reconciled.nodes).toContainEqual(expect.objectContaining({
      kind: "image",
      asset: expect.objectContaining({ alt: "生成结果" }),
    }));
  });

  it("成功快照混入不安全预览时原子拒绝全部输出", () => {
    const accepted = updateGenerationTask(
      generationDocument(),
      "generation-1",
      { taskRef: "task-1", taskState: "accepted", message: null },
      "t0",
    );

    expect(reconcileMediaTask(
      accepted,
      "generation-1",
      {
        state: "succeeded",
        taskRef: "task-1",
        outputs: [
          {
            assetRef: "asset-safe",
            kind: "image",
            alt: "安全结果",
            previewRef: "https://cdn.example.test/result-1.png",
          },
          {
            assetRef: "asset-unsafe",
            kind: "image",
            alt: "不安全结果",
            previewRef: "/untrusted-preview/asset-unsafe",
          },
        ],
      },
      "t1",
    )).toBe(accepted);
  });

  it("工作中任务拒绝不安全预览且保留原文档", () => {
    const working = updateGenerationTask(
      generationDocument(),
      "generation-1",
      { taskRef: "task-1", taskState: "working", message: null },
      "t0",
    );
    const unsafe = reconcileMediaTask(
      working,
      "generation-1",
      {
        state: "succeeded",
        taskRef: "task-1",
        outputs: [{
          assetRef: "asset-unsafe",
          kind: "image",
          alt: "不安全结果",
          previewRef: "/untrusted-preview/asset-unsafe",
        }],
      },
      "t1",
    );

    expect(unsafe).toBe(working);
  });

  it("输出节点 ID 与无关节点冲突时原子拒绝", () => {
    let document = generationDocument();
    document = addGenerationNode(
      document,
      "task-1-output-0",
      { x: 500, y: 0 },
      "t0",
    );
    document = updateGenerationTask(
      document,
      "generation-1",
      { taskRef: "task-1", taskState: "accepted", message: null },
      "t0",
    );

    expect(reconcileMediaTask(
      document,
      "generation-1",
      {
        state: "succeeded",
        taskRef: "task-1",
        outputs: [{
          assetRef: "asset-1",
          kind: "image",
          alt: "结果",
          previewRef: "https://cdn.example.test/result-1.png",
        }],
      },
      "t1",
    )).toBe(document);
  });

  it("终态失败重放不改写消息", () => {
    const failed = updateGenerationTask(
      generationDocument(),
      "generation-1",
      { taskRef: "task-1", taskState: "failed", message: "原始失败" },
      "t0",
    );

    expect(reconcileMediaTask(
      failed,
      "generation-1",
      { state: "failed", taskRef: "task-1", message: "迟到消息" },
      "t1",
    )).toBe(failed);
  });
});
