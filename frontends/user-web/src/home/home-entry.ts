import type { ComposerDraft } from '../creator/composer-draft';
import type { CanvasGenerationSettings } from '../canvas/model';
import { canvasReviewStatuses } from '../canvas/model';
import type { ComposerMaterial } from '../creator/ComposerMaterials';

export type HomeGenerationMode = NonNullable<ComposerDraft['mode']>;
export type HomeCreationEntry = { source: 'yomio-home'; prompt: string; modelName?: string; settings?: CanvasGenerationSettings; mode?: HomeGenerationMode;
  ownerId?: string; materials?: (ComposerMaterial | null)[] };

function entryMaterials(value: unknown): (ComposerMaterial | null)[] | undefined {
  if (!Array.isArray(value) || value.length > 50) return undefined;
  const result: (ComposerMaterial | null)[] = [];
  for (const item of value) {
    if (item === null) { result.push(null); continue; }
    if (!item || typeof item !== 'object') return undefined;
    const file = item as Partial<ComposerMaterial>;
    if (typeof file.fileId !== 'string' || !file.fileId || file.fileId.length > 2000 || typeof file.name !== 'string' || file.name.length > 1000
      || ![undefined, 'IMAGE', 'VIDEO', 'AUDIO'].includes(file.kind)
      || file.durationSeconds != null && (!Number.isFinite(file.durationSeconds) || file.durationSeconds <= 0)
      || file.sizeBytes !== undefined && (!Number.isFinite(file.sizeBytes) || file.sizeBytes < 0)
      || file.reviewStatus !== undefined && !canvasReviewStatuses.includes(file.reviewStatus)
      || [file.reviewId, file.portraitId].some(id => id !== undefined && (typeof id !== 'string' || !id || id.length > 2000))) return undefined;
    result.push({ fileId: file.fileId, name: file.name, preview: '', kind: file.kind, durationSeconds: file.durationSeconds, sizeBytes: file.sizeBytes,
      reviewId: file.reviewId, portraitId: file.portraitId, reviewStatus: file.reviewStatus, reviewPending: file.reviewStatus !== 'ACTIVE' && !file.reviewId });
  }
  return result;
}

function entrySettings(value: unknown): CanvasGenerationSettings | undefined {
  if (!value || typeof value !== 'object') return undefined;
  const settings = value as Partial<CanvasGenerationSettings>;
  if (!['IMAGE', 'VIDEO'].includes(settings.type ?? '') || typeof settings.model !== 'string' || !settings.model || settings.model.length > 120
    || typeof settings.aspectRatio !== 'string' || settings.aspectRatio.length > 40 || typeof settings.resolution !== 'string' || settings.resolution.length > 40
    || !Number.isInteger(settings.count) || settings.count! < 1 || settings.count! > 100
    || !Number.isInteger(settings.durationSeconds) || settings.durationSeconds! < 0 || settings.durationSeconds! > 86400) return undefined;
  return { type: settings.type!, model: settings.model, aspectRatio: settings.aspectRatio, resolution: settings.resolution, count: settings.count!, durationSeconds: settings.durationSeconds! };
}

export function homeCreationEntry(state: unknown): HomeCreationEntry | null {
  if (!state || typeof state !== 'object') return null;
  const value = state as Partial<HomeCreationEntry>;
  const materials = entryMaterials(value.materials);
  return value.source === 'yomio-home' && typeof value.prompt === 'string' && value.prompt.length <= 2000
    && (value.modelName === undefined || typeof value.modelName === 'string' && value.modelName.length <= 80)
    ? { source: 'yomio-home', prompt: value.prompt, modelName: value.modelName, ...(entrySettings(value.settings) ? { settings: entrySettings(value.settings) } : {}),
      ...(value.mode && ['auto', 'text', 'reference', 'omni', 'frames'].includes(value.mode) ? { mode: value.mode } : {}),
      ...(typeof value.ownerId === 'string' && value.ownerId.length > 0 && value.ownerId.length <= 200 && materials
        ? { ownerId: value.ownerId, materials } : {}) } : null;
}

// Display names are matched against the server catalog; no provider ID is invented here.
export function homeModelMatches(displayName: string, requested: string | undefined): boolean {
  if (!requested) return false;
  const normalize = (value: string) => value.toLowerCase().replace(/[\s._-]/g, '').replace(/^gptimage/, 'image');
  const actual = normalize(displayName), wanted = normalize(requested);
  return actual === wanted || wanted === 'nanobanana' && actual === 'nanobananapro';
}

export function homePromptDraft(prompt: string, previous: ComposerDraft | null, kind: 'IMAGE' | 'VIDEO', settings: CanvasGenerationSettings | null = null, mode?: HomeGenerationMode, materials?: (ComposerMaterial | null)[]): ComposerDraft {
  return { prompt, selection: settings?.type === kind ? settings : previous?.selection ?? null, mode: mode ?? previous?.mode ?? (kind === 'IMAGE' ? 'auto' : 'text'),
    materials: materials ?? previous?.materials ?? (kind === 'VIDEO' ? [null, null] : []), bindings: materials ? [] : previous?.bindings ?? [] };
}
