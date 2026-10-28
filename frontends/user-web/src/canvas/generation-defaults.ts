import type { CanvasGenerationSettings } from "./model";

export const IMAGE_GENERATION_DEFAULTS = Object.freeze({
  type: "IMAGE" as const,
  model: "seedream-5.0-lite",
  aspectRatio: "1:1",
  resolution: "2K",
  count: 1,
  durationSeconds: 0,
});

export const VIDEO_GENERATION_DEFAULTS = Object.freeze({
  type: "VIDEO" as const,
  model: "seedance-2.5",
  aspectRatio: "16:9",
  resolution: "720p",
  count: 1,
  durationSeconds: 5,
});

export const copyGenerationDefaults = (type: CanvasGenerationSettings["type"]): CanvasGenerationSettings => ({
  ...(type === "VIDEO" ? VIDEO_GENERATION_DEFAULTS : IMAGE_GENERATION_DEFAULTS),
});
