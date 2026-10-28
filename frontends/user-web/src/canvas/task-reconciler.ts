import type { CanvasMediaTaskSnapshot } from "./media-task-port";
import type {
  CanvasAssetReference,
  CanvasDocument,
  CanvasEdge,
  CanvasNode,
  GenerationCanvasNode,
  ImageCanvasNode,
} from "./model";
import { isSafeCanvasPreviewRef } from "./preview-ref";

const sameAsset = (
  left: CanvasAssetReference,
  right: CanvasAssetReference,
): boolean => (
  left.assetRef === right.assetRef
  && left.kind === right.kind
  && left.alt === right.alt
  && left.previewRef === right.previewRef
  && left.durationSeconds === right.durationSeconds
);

const sameOutputNode = (
  node: CanvasNode,
  expected: ImageCanvasNode,
): boolean => (
  (node.kind === "image" || node.kind === "video")
  && node.id === expected.id
  && node.version === expected.version
  && node.position.x === expected.position.x
  && node.position.y === expected.position.y
  && node.sourceTaskRef === expected.sourceTaskRef
  && sameAsset(node.asset, expected.asset)
);

const sameEdge = (left: CanvasEdge, right: CanvasEdge): boolean => (
  left.id === right.id
  && left.source === right.source
  && left.target === right.target
);

const updateGeneration = (
  document: CanvasDocument,
  generationNodeId: string,
  patch: Pick<GenerationCanvasNode, "taskRef" | "taskState" | "message">,
  updatedAt: string,
): CanvasDocument => {
  const generation = document.nodes.find((node): node is GenerationCanvasNode => (
    node.id === generationNodeId && node.kind === "generation"
  ));
  if (
    !generation
    || (
      generation.taskRef === patch.taskRef
      && generation.taskState === patch.taskState
      && generation.message === patch.message
    )
  ) {
    return document;
  }

  return {
    ...document,
    nodes: document.nodes.map((node) => (
      node.id === generationNodeId && node.kind === "generation"
        ? { ...node, ...patch }
        : node
    )),
    updatedAt,
  };
};

const buildOutputs = (
  generation: GenerationCanvasNode,
  taskRef: string,
  assets: readonly CanvasAssetReference[],
): { nodes: ImageCanvasNode[]; edges: CanvasEdge[] } | null => {
  const seenAssetRefs = new Set<string>();
  const nodes: ImageCanvasNode[] = [];

  for (const [index, asset] of assets.entries()) {
    const alt = typeof asset?.alt === "string" ? asset.alt.trim() : "";
    if (
      !asset
      || (asset.kind !== "image" && asset.kind !== "video")
      || typeof asset.assetRef !== "string"
      || !asset.assetRef.trim()
      || seenAssetRefs.has(asset.assetRef)
      || !alt
    ) {
      return null;
    }
    seenAssetRefs.add(asset.assetRef);
    nodes.push({
      id: `${taskRef}-output-${index}`,
      kind: asset.kind,
      version: 1,
      position: {
        x: generation.position.x + 360,
        y: generation.position.y + index * 240,
      },
      asset: {
        assetRef: asset.assetRef,
        kind: asset.kind,
        alt,
        previewRef: asset.previewRef,
        ...(typeof asset.durationSeconds === "number" && Number.isFinite(asset.durationSeconds) && asset.durationSeconds > 0 ? { durationSeconds: asset.durationSeconds } : {}),
      },
      sourceTaskRef: taskRef,
    });
  }

  return {
    nodes,
    edges: nodes.map((node) => ({
      id: `${generation.id}->${node.id}`,
      source: generation.id,
      target: node.id,
    })),
  };
};

export const reconcileMediaTask = (
  document: CanvasDocument,
  generationNodeId: string,
  snapshot: CanvasMediaTaskSnapshot,
  updatedAt: string,
): CanvasDocument => {
  const generation = document.nodes.find((node): node is GenerationCanvasNode => (
    node.id === generationNodeId && node.kind === "generation"
  ));
  if (!generation || !snapshot.taskRef.trim()) {
    return document;
  }

  const firstBinding = snapshot.state === "accepted"
    && generation.taskState === "submitting"
    && generation.taskRef === null;
  if (firstBinding && document.nodes.some((node) => node.kind === "generation" && node.id !== generationNodeId && node.taskRef === snapshot.taskRef)) return document;
  if (!firstBinding && generation.taskRef !== snapshot.taskRef) {
    return document;
  }

  if ((["succeeded", "failed", "partial_success", "timed_out"].includes(generation.taskState)) && generation.taskState !== snapshot.state) return document;

  const transitions: Partial<Record<
    typeof generation.taskState,
    ReadonlySet<typeof snapshot.state>
  >> = {
    accepted: new Set(["accepted", "working", "succeeded", "failed", "partial_success", "timed_out", "refund_pending"]),
    working: new Set(["working", "succeeded", "failed", "partial_success", "timed_out", "refund_pending"]),
    refund_pending: new Set(["refund_pending", "failed", "timed_out", "partial_success"]),
    partial_success: new Set(),
    timed_out: new Set(),
    succeeded: new Set(),
    failed: new Set(),
  };
  if (!firstBinding && !transitions[generation.taskState]?.has(snapshot.state)) {
    return document;
  }

  const taskState = snapshot.state;
  const message = snapshot.message ?? null;

  if (snapshot.state !== "succeeded" && snapshot.state !== "partial_success") {
    return updateGeneration(
      document,
      generationNodeId,
      { taskRef: snapshot.taskRef, taskState, message },
      updatedAt,
    );
  }

  if (snapshot.outputs.some((asset) => asset.previewRef !== "" && !isSafeCanvasPreviewRef(asset.previewRef))) {
    return document;
  }

  const outputs = buildOutputs(generation, snapshot.taskRef, snapshot.outputs);
  if (!outputs) {
    return document;
  }

  const referenceOnlyMatch = (node: CanvasNode, output: ImageCanvasNode) => (
    (node.kind === "image" || node.kind === "video") && node.referenceOnly === true
    && node.sourceTaskRef === null && node.kind === output.kind && node.asset.assetRef === output.asset.assetRef
  );
  for (const output of outputs.nodes) {
    const collision = document.nodes.find((node) => node.id === output.id);
    if (collision && !sameOutputNode(collision, output) && !referenceOnlyMatch(collision, output)) {
      return document;
    }
  }
  for (const outputEdge of outputs.edges) {
    const collision = document.edges.find((edge) => (
      edge.id === outputEdge.id
      || (edge.source === outputEdge.source && edge.target === outputEdge.target)
    ));
    if (collision && !sameEdge(collision, outputEdge)) {
      return document;
    }
  }

  const nodes: CanvasNode[] = [
    ...document.nodes.map((node) => (
      node.id === generationNodeId && node.kind === "generation"
        ? { ...node, taskRef: snapshot.taskRef, taskState, message }
        : outputs.nodes.find(output => output.id === node.id && referenceOnlyMatch(node, output)) ?? node
    )),
    ...outputs.nodes.filter((node) => !document.nodes.some((candidate) => (
      candidate.id === node.id
    ))),
  ];
  const edges = [
    ...document.edges,
    ...outputs.edges.filter((edge) => !document.edges.some((candidate) => (
      candidate.id === edge.id
    ))),
  ];

  return { ...document, nodes, edges, updatedAt };
};
