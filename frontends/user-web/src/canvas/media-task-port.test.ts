import { describe, expect, it } from "vitest";
import {
  addGenerationNode,
  addTextNode,
  connectNodes,
  createCanvasDocument,
} from "./document";
import { buildMediaTaskRequest, type MediaTaskPort } from "./media-task-port";

describe("MediaTaskPort", () => {
  it("自身提示词优先，完整相同文本只拼接一次且不修改原文", () => {
    let document = addGenerationNode(createCanvasDocument("own-prompt", "t0"), "g", { x: 0, y: 0 }, "t0");
    document = addTextNode(document, "t", { x: 0, y: 0 }, "t0");
    document = connectNodes(document, "t", "g", "t0");
    document.nodes = document.nodes.map(node => node.kind === "generation" ? { ...node, prompt: " 自身提示词 " } : { ...node, text: "自身提示词" });
    expect(buildMediaTaskRequest(document, "g", "r").prompt).toBe("自身提示词");
    expect(document.nodes[0]).toMatchObject({ prompt: " 自身提示词 " });
  });
  it("只暴露画布项目语义", () => {
    const port: MediaTaskPort = {
      options: async () => [],
      quote: async () => ({ points: "12.3400" }),
      submit: async () => ({ taskRef: "task-1" }),
      read: async () => ({ state: "working", taskRef: "task-1" }),
    };

    expect(Object.keys(port).sort()).toEqual(["options", "quote", "read", "submit"]);
  });

  it("只收集连接到目标生成节点的文本和图片引用", () => {
    let document = addTextNode(
      createCanvasDocument("project-1", "t0"),
      "text-1",
      { x: 0, y: 0 },
      "t0",
    );
    document = addGenerationNode(document, "generation-1", { x: 300, y: 0 }, "t0");
    document = connectNodes(document, "text-1", "generation-1", "t0");
    document = {
      ...document,
      nodes: document.nodes.map((node) => (
        node.id === "text-1" && node.kind === "text"
          ? { ...node, text: "雨夜黑猫" }
          : node
      )),
    };

    expect(buildMediaTaskRequest(document, "generation-1", "request-1")).toEqual({
      requestRef: "request-1",
      prompt: "雨夜黑猫",
      inputAssetRefs: [],
      settings: expect.objectContaining({ type: "IMAGE", model: "seedream-5.0-lite", aspectRatio: "1:1" }),
    });
  });

  it("按画布顺序合并直接连接的非空提示词和素材引用", () => {
    let document = createCanvasDocument("project-1", "t0");
    document = addTextNode(document, "text-1", { x: 0, y: 0 }, "t0");
    document = addTextNode(document, "text-2", { x: 0, y: 100 }, "t0");
    document = addTextNode(document, "text-3", { x: 0, y: 200 }, "t0");
    document = addGenerationNode(document, "generation-1", { x: 300, y: 0 }, "t0");
    document = addGenerationNode(document, "generation-2", { x: 300, y: 300 }, "t0");
    document = {
      ...document,
      nodes: [
        ...document.nodes.map((node) => node.kind === "text"
          ? {
              ...node,
              text: node.id === "text-1"
                ? "  第一段  "
                : node.id === "text-2"
                  ? "  第二段 "
                  : "   ",
            }
          : node),
        {
          id: "image-1",
          kind: "image",
          version: 1,
          position: { x: 0, y: 200 },
          asset: {
            assetRef: "asset-1",
            kind: "image",
            alt: "参考图",
            previewRef: "https://cdn.example.test/reference.png",
          },
          sourceTaskRef: null,
        },
      ],
    };
    document = connectNodes(document, "text-1", "generation-1", "t0");
    document = connectNodes(document, "text-2", "generation-1", "t0");
    document = connectNodes(document, "text-3", "generation-1", "t0");
    document = connectNodes(document, "image-1", "generation-1", "t0");
    document = connectNodes(document, "text-1", "generation-2", "t0");

    expect(buildMediaTaskRequest(document, "generation-1", "request-1")).toEqual({
      requestRef: "request-1",
      prompt: "第一段\n第二段",
      inputAssetRefs: ["asset-1"],
      settings: expect.objectContaining({ type: "IMAGE", model: "seedream-5.0-lite", aspectRatio: "1:1" }),
    });
  });
});

import { referenceIssue, type CanvasGenerationOption } from './media-task-port';
import type { CanvasAssetReference, CanvasGenerationSettings } from './model';
import { reconcileMediaTask } from './task-reconciler';

const materialOption: CanvasGenerationOption = {
  type: 'VIDEO', model: 'test-video', displayName: '参考模型', resolutions: ['480p', '720p'], aspectRatios: ['16:9'],
  maxCount: 1, minDurationSeconds: 1, maxDurationSeconds: 10, maxReferenceImages: 2,
  omniReference: { maxTotal: 12, maxImages: 9, maxVideos: 3, maxAudios: 3, maxVideoDurationSeconds: 15,
    maxAudioDurationSeconds: 15, maxVideoBytes: 10000, maxAudioBytes: 10000, audioOnly: true },
};
const materialDocument = (metadata: Partial<CanvasAssetReference>, settings: Partial<CanvasGenerationSettings> = {}) => {
  const document = addGenerationNode(createCanvasDocument('materials', 'now'), 'target', { x: 300, y: 0 }, 'now');
  document.nodes = document.nodes.map(node => node.kind === 'generation'
    ? { ...node, settings: { ...node.settings, type: 'VIDEO', durationSeconds: 5, ...settings } } : node);
  const asset: CanvasAssetReference = { assetRef: 'original-file', kind: 'image', alt: '本地素材', previewRef: '', ...metadata };
  document.nodes.push({ id: 'material', kind: asset.kind, version: 1, position: { x: 0, y: 0 }, sourceTaskRef: null, asset });
  document.edges.push({ id: 'input', source: 'material', target: 'target' });
  return document;
};

it.each([{ reviewPending: true }, { reviewId: 'review-one', reviewPending: false }])('preserves the original file for ordinary uses with %j', metadata => {
  for (const settings of [{ type: 'IMAGE' as const }, { referenceMode: 'FIRST_LAST_FRAME' as const }]) {
    const document = materialDocument(metadata, settings);
    const request = buildMediaTaskRequest(document, 'target', 'key');
    expect(request.inputAssetRefs).toEqual(['original-file']);
    expect(request.references).toBeUndefined();
    expect(referenceIssue(document, 'target', materialOption)).toBe('');
  }
});

it.each(['image', 'video', 'audio'] as const)('maps a %s review association only for VIDEO OMNI', kind => {
  const document = materialDocument({ kind, reviewId: 'review-one', reviewPending: true, durationSeconds: 4 }, { referenceMode: 'OMNI' });
  expect(buildMediaTaskRequest(document, 'target', 'key').references).toEqual([
    { fileId: 'original-file', portraitId: 'review-one', kind: kind.toUpperCase(), durationSeconds: 4 },
  ]);
  expect(referenceIssue(document, 'target', materialOption)).toBe('');
});

it.each([undefined, 'OMNI'] as const)('blocks an unsubmitted review in inferred or explicit OMNI (%s)', referenceMode => {
  const document = materialDocument({ reviewPending: true }, { referenceMode });
  expect(buildMediaTaskRequest(document, 'target', 'key').referenceMode).toBe('OMNI');
  expect(referenceIssue(document, 'target', materialOption)).toBe('素材尚未完成审核提交');
});

it('infers OMNI for a review-linked image and retains the strict portrait ID when both IDs exist', () => {
  const document = materialDocument({ reviewId: 'review-one' });
  expect(buildMediaTaskRequest(document, 'target', 'key')).toMatchObject({ referenceMode: 'OMNI', references: [{ portraitId: 'review-one' }] });
  const strict = materialDocument({ portraitId: 'strict-portrait', reviewId: 'review-one' }, { referenceMode: 'OMNI' });
  expect(buildMediaTaskRequest(strict, 'target', 'key').references?.[0].portraitId).toBe('strict-portrait');
  for (const settings of [{ type: 'IMAGE' as const }, { referenceMode: 'FIRST_LAST_FRAME' as const }]) {
    const restricted = materialDocument({ portraitId: 'strict-portrait', reviewId: 'review-one' }, settings);
    expect(referenceIssue(restricted, 'target', materialOption)).toContain('已审核人像仅支持视频全能参考');
    expect(buildMediaTaskRequest(restricted, 'target', 'key').references?.[0].portraitId).toBe('strict-portrait');
  }
});

it.each([{ reviewPending: true }, { reviewId: 'review-one' }])('keeps a 31-second original video available for 480p depth with %j', metadata => {
  const document = materialDocument({ ...metadata, kind: 'video', durationSeconds: 31 }, { operation: 'DEPTH', resolution: '480p' });
  expect(referenceIssue(document, 'target', materialOption)).toBe('');
  expect(buildMediaTaskRequest(document, 'target', 'key')).toMatchObject({ inputAssetRefs: ['original-file'], settings: { operation: 'DEPTH' } });
  expect(buildMediaTaskRequest(document, 'target', 'key').references).toBeUndefined();
});

it('references a generated image directly without inheriting review metadata or requiring review submission', () => {
  let document = materialDocument({ reviewId: 'review-one' }, { type: 'IMAGE' });
  document.nodes = document.nodes.map(node => node.kind === 'generation' ? { ...node, taskRef: 'image-task', taskState: 'working' } : node);
  document = reconcileMediaTask(document, 'target', { state: 'succeeded', taskRef: 'image-task', outputs: [
    { assetRef: 'generated-file', kind: 'image', alt: '生成图片', previewRef: '' },
  ] }, 'now');
  document = addGenerationNode(document, 'next', { x: 600, y: 0 }, 'now');
  document.nodes = document.nodes.map(node => node.id === 'next' && node.kind === 'generation' ? { ...node, settings: { ...node.settings, type: 'VIDEO' } } : node);
  document = connectNodes(document, 'target', 'next', 'now');
  expect(buildMediaTaskRequest(document, 'next', 'key')).toMatchObject({ inputAssetRefs: ['generated-file'], referenceMode: 'FIRST_LAST_FRAME' });
  expect(buildMediaTaskRequest(document, 'next', 'key').references).toBeUndefined();
  expect(referenceIssue(document, 'next', materialOption)).toBe('');
  const output = document.nodes.find(node => node.id === 'image-task-output-0');
  expect(output).toBeDefined();
  expect(output).not.toHaveProperty('asset.reviewId');
  expect(output).not.toHaveProperty('asset.reviewPending');
});
