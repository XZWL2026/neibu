import type {
  CreatorMedia,
  CreatorModelId,
  CreatorValidationState,
  MediaInputCapability,
  MediaSelectionSource,
  OmniReferenceCapabilities,
} from "./types";

const REFERENCE_IMAGE_MAX_FILE_BYTES = 20 * 1024 * 1024;

const referenceImageCapability = (): MediaInputCapability => ({
  acceptedKinds: ["image"],
  acceptedMimeTypes: ["image/jpeg", "image/png"],
  acceptedSources: ["upload", "drop", "library"],
  maxFileBytes: REFERENCE_IMAGE_MAX_FILE_BYTES,
  maxItems: null,
  verified: true,
});

type ImageCapabilityState = {
  available: true;
  modelId: "gpt-image-2";
  kind: "image";
  input: MediaInputCapability;
};

type VideoCapabilityState = {
  available: true;
  modelId: "seedance-2.5";
  kind: "video";
  modes: {
    firstLastFrame: MediaInputCapability;
    omniReference: OmniReferenceCapabilities;
  };
};

type UnavailableCapabilityState = {
  available: false;
  reason: "本地能力未验证";
};

export type CreatorCapabilityState =
  | ImageCapabilityState
  | VideoCapabilityState
  | UnavailableCapabilityState;

const capabilities: Readonly<{
  "gpt-image-2": ImageCapabilityState;
  "seedance-2.5": VideoCapabilityState;
}> = {
  "gpt-image-2": {
    available: true,
    modelId: "gpt-image-2",
    kind: "image",
    input: referenceImageCapability(),
  },
  "seedance-2.5": {
    available: true,
    modelId: "seedance-2.5",
    kind: "video",
    modes: {
      firstLastFrame: referenceImageCapability(),
      omniReference: {
        image: referenceImageCapability(),
        video: { verified: false, reason: "能力未验证" },
        audio: { verified: false, reason: "能力未验证" },
      },
    },
  },
};

const cloneInputCapability = (capability: MediaInputCapability): MediaInputCapability => ({
  ...capability,
  acceptedKinds: [...capability.acceptedKinds],
  acceptedMimeTypes: [...capability.acceptedMimeTypes],
  acceptedSources: [...capability.acceptedSources],
});

export const getCapabilityState = (model: CreatorModelId): CreatorCapabilityState => {
  if (model === "gpt-image-2") {
    return {
      ...capabilities[model],
      input: cloneInputCapability(capabilities[model].input),
    };
  }
  if (model === "seedance-2.5") {
    const capability = capabilities[model];
    return {
      ...capability,
      modes: {
        firstLastFrame: cloneInputCapability(capability.modes.firstLastFrame),
        omniReference: {
          image: cloneInputCapability(capability.modes.omniReference.image),
          video: { ...capability.modes.omniReference.video },
          audio: { ...capability.modes.omniReference.audio },
        },
      },
    };
  }
  return { available: false, reason: "本地能力未验证" };
};

type MediaSelection = {
  source: MediaSelectionSource;
  current: readonly CreatorMedia[];
  incoming: readonly CreatorMedia[];
  capability: MediaInputCapability;
  price: string | null;
  persist(media: readonly CreatorMedia[]): void | Promise<void>;
};

const formatMessage = (capability: MediaInputCapability): string =>
  capability.acceptedKinds.length === 1 && capability.acceptedKinds[0] === "image"
    ? "只支持 JPG、PNG"
    : "素材格式不受支持";

const sizeMessage = (maxFileBytes: number): string =>
  maxFileBytes === REFERENCE_IMAGE_MAX_FILE_BYTES
    ? "单个文件不能超过 20MB"
    : `单个文件不能超过 ${maxFileBytes} 字节`;

export const validateMediaSelection = async (selection: MediaSelection): Promise<CreatorValidationState> => {
  const { source, current, incoming, capability, price, persist } = selection;
  if (current.length + incoming.length === 0) {
    return { valid: false, reason: "missing", message: "请选择素材" };
  }

  if (
    !capability.acceptedSources.includes(source)
    || incoming.some((item) => (
      !capability.acceptedKinds.includes(item.kind)
      || !capability.acceptedMimeTypes.includes(item.mimeType)
    ))
  ) {
    return { valid: false, reason: "format", message: formatMessage(capability) };
  }

  if (incoming.some((item) => item.size > capability.maxFileBytes)) {
    return { valid: false, reason: "size", message: sizeMessage(capability.maxFileBytes) };
  }

  if (capability.maxItems !== null && current.length + incoming.length > capability.maxItems) {
    return {
      valid: false,
      reason: "count",
      message: `最多选择 ${capability.maxItems} 项素材`,
    };
  }

  if (price === null) {
    return { valid: false, reason: "price", message: "当前配置没有本地积分估算" };
  }

  await persist(incoming);
  return { valid: true };
};
