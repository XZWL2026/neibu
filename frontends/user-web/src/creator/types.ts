export type MediaKind = "image" | "video" | "audio";

export type MediaSelectionSource = "upload" | "drop" | "library";

export type CreatorMedia = {
  ownerId?: string | null;
  canvasFileIds?: readonly string[];
  id: string;
  kind: MediaKind;
  name: string;
  mimeType: string;
  size: number;
  content?: Blob;
  previewUrl?: string;
  origin?: "public" | "personal";
  storageMode?: "persistent" | "session";
};

export type MediaInputCapability = {
  acceptedKinds: readonly MediaKind[];
  acceptedMimeTypes: readonly string[];
  acceptedSources: readonly MediaSelectionSource[];
  maxFileBytes: number;
  maxItems: number | null;
  maxItemsByKind?: Partial<Record<MediaKind, number>>;
  maxFileBytesByKind?: Partial<Record<MediaKind, number>>;
  verified: boolean;
};

export type UnverifiedMediaCapability = {
  verified: false;
  reason: "能力未验证";
};

export type OmniReferenceCapabilities = {
  image: MediaInputCapability;
  video: UnverifiedMediaCapability;
  audio: UnverifiedMediaCapability;
};

export type AccountType = "PERSONAL" | "ENTERPRISE";

export type CreatorModelId =
  | "gpt-image-2"
  | "seedance-2.5"
  | "Nano Banana Pro"
  | "Kling 2.1";

export type CreatorModeId = "image-reference" | "first-last-frame" | "omni-reference";

export type PendingCreatorSwitch = {
  nextModel: CreatorModelId;
  nextMode: CreatorModeId;
  removedMedia: readonly CreatorMedia[];
  resetParameters: readonly string[];
};

export type CreatorTaskState = "pending" | "generating" | "completed";

export const creatorTaskStateLabel = (state: CreatorTaskState): "待提交" | "生成中" | "已完成" =>
  state === "pending" ? "待提交" : state === "generating" ? "生成中" : "已完成";

export type CreatorTask =
  | {
      id: string;
      kind: "image";
      modelId: "gpt-image-2";
      mode: "image-reference";
      prompt: string;
      state: CreatorTaskState;
    }
  | {
      id: string;
      kind: "video";
      modelId: "seedance-2.5";
      mode: "first-last-frame" | "omni-reference";
      prompt: string;
      state: CreatorTaskState;
    };

export type CreatorValidationState =
  | { valid: true }
  | {
      valid: false;
      reason: "format" | "size" | "count" | "missing" | "price";
      message: string;
    };

export type LocalMediaRepository = {
  list(): Promise<readonly CreatorMedia[]>;
  put(media: CreatorMedia): Promise<void>;
  remove(id: string): Promise<void>;
  close(): void;
};

export type LocalMediaRepositoryOpenState =
  | { storageMode: "persistent"; repository: LocalMediaRepository }
  | {
      storageMode: "session";
      repository: LocalMediaRepository;
      reason: "forced" | "unavailable" | "open-failed";
    };

export type CreatorNotice =
  | null
  | {
      kind: "session-fallback" | "write-failed" | "validation";
      message: string;
    };

export type ObjectUrlLifecycle = {
  create(value: Blob): string;
  revoke(url: string): void;
};
