export type CanvasPoint = {
  x: number;
  y: number;
};

export type CanvasViewport = CanvasPoint & {
  zoom: number;
};

export const canvasReviewStatuses = ["SUBMITTING", "PROCESSING", "ACTIVE", "FAILED", "UNCERTAIN", "UNAVAILABLE"] as const;
export type CanvasReviewStatus = typeof canvasReviewStatuses[number];

export type CanvasAssetReference = {
  reviewStatus?: CanvasReviewStatus;
  portraitId?: string;
  // Review association for the original file; only VIDEO OMNI submits it as portraitId.
  reviewId?: string;
  reviewPending?: boolean;
  assetRef: string;
  kind: "image" | "video" | "audio";
  alt: string;
  previewRef: string;
  durationSeconds?: number;
  sizeBytes?: number;
};

type CanvasNodeBase = {
  id: string;
  version: 1;
  position: CanvasPoint;
};

export type TextCanvasNode = CanvasNodeBase & {
  kind: "text";
  text: string;
};

export type ImageCanvasNode = CanvasNodeBase & {
  kind: "image" | "video" | "audio";
  asset: CanvasAssetReference;
  sourceTaskRef: string | null;
  referenceOnly?: boolean;
};

export type CanvasTaskState =
  | "idle"
  | "submitting"
  | "accepted"
  | "working"
  | "succeeded"
  | "failed"
  | "partial_success"
  | "timed_out"
  | "refund_pending";

export type CanvasGenerationSettings = {
  operation?: "GENERATION" | "DEPTH";
  type: "IMAGE" | "VIDEO";
  referenceMode?: "FIRST_LAST_FRAME" | "OMNI";
  model: string;
  aspectRatio: string;
  resolution: string;
  count: number;
  durationSeconds: number;
};

export type GenerationCanvasNode = CanvasNodeBase & {
  kind: "generation";
  prompt?: string;
  settings: CanvasGenerationSettings;
  taskRef: string | null;
  taskState: CanvasTaskState;
  message: string | null;
  promptBindings?: { token: string; fileId: string }[];
  selectedOutputId?: string;
  runs?: CanvasRun[];
};

// A run keeps the exact submitted inputs; editing a node never rewrites its history.
export type CanvasRun = {
  id: string;
  createdAt: string;
  taskRef: string | null;
  state: CanvasTaskState;
  request: import('./media-task-port').CanvasMediaTaskRequest | null;
};

export type CanvasNode = TextCanvasNode | ImageCanvasNode | GenerationCanvasNode;

export type CanvasEdge = {
  id: string;
  source: string;
  target: string;
  outputId?: string;
  assetRef?: string;
  role?: 'reference' | 'start' | 'end' | 'annotation';
};

export type CanvasDocument = {
  schemaVersion: 1;
  projectId: string;
  nodes: CanvasNode[];
  edges: CanvasEdge[];
  groups?: CanvasGroup[];
  viewport: CanvasViewport;
  updatedAt: string;
};

// Editor-only organization; members keep absolute coordinates and their own task inputs.
export type CanvasGroup = { id: string; name: string; nodeIds: string[] };
