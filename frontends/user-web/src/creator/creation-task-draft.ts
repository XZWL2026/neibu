import { randomUUID } from '../../../shared/randomUUID';
import type { Dispatch, SetStateAction } from 'react';
import type { CanvasMediaTaskRequest } from '../canvas/media-task-port';
import type { CanvasGenerationSettings } from '../canvas/model';
import { resolvePreview } from '../canvas/production-api';
import type { ComposerMaterial } from './ComposerMaterials';
import type { VideoSubmission } from './VideoTaskResults';
import { mediaPortraitGetGetApiV1MediaPortraitsPortraitId } from '../api';
import { reviewedLibraryMedia } from './reviewedLibraryMedia';

function taskSettings(entry: VideoSubmission) {
  const task = entry.snapshot;
  if (!['IMAGE', 'VIDEO'].includes(task.type ?? '') || !task.model || !task.resolution || !task.aspectRatio
    || task.count == null || task.durationSeconds == null || !task.prompt) throw new Error('任务参数不可用');
  const settings: CanvasGenerationSettings = { type: task.type as 'IMAGE' | 'VIDEO', model: task.model,
    resolution: task.resolution, aspectRatio: task.aspectRatio, count: task.count, durationSeconds: task.durationSeconds };
  return settings;
}
export function regenerationEntry(source: VideoSubmission, commerce = false): VideoSubmission {
  if (!source.taskRef) throw new Error('任务不可用');
  const settings = taskSettings(source), key = `${commerce ? 'commerce-' : ''}${randomUUID()}`;
  const references = (source.snapshot.references ?? []).map(ref => ({ fileId: ref.fileId || ref.httpsUrl || '',
    kind: ref.kind ?? 'IMAGE' as const, durationSeconds: ref.durationSeconds, ...(ref.portraitId ? { portraitId: ref.portraitId } : {}) }));
  if (references.some(ref => !ref.fileId)) throw new Error('参考素材不可用');
  const request: CanvasMediaTaskRequest = { requestRef: key, settings, prompt: source.snapshot.prompt!,
    inputAssetRefs: references.map(ref => ref.fileId), references,
    ...(source.snapshot.referenceMode ? { referenceMode: source.snapshot.referenceMode } : {}) };
  return { key, replacesTaskId: source.taskRef, request, state: 'SUBMITTING', draft: source.draft,
    snapshot: { ...source.snapshot, id: undefined, status: 'ROUTING', results: [], failures: [], failureMessage: null, refundStatus: null } };
}
export async function creationTaskDraft(entry: VideoSubmission) {
  const settings = taskSettings(entry), task = entry.snapshot;
  const materials = await Promise.all((task.references ?? []).map(async (ref, index): Promise<ComposerMaterial> => {
    const fileId = ref.fileId || ref.httpsUrl;
    if (!fileId) throw new Error('参考素材不可用');
    const saved = entry.draft?.materials.find(file => file?.fileId === fileId);
    const kind = ref.kind ?? 'IMAGE';
    const name = saved?.name ?? `${kind === 'VIDEO' ? '视频' : kind === 'AUDIO' ? '音频' : '图片'}${index + 1}`;
    const reviewId = ref.portraitId || saved?.reviewId || saved?.portraitId;
    let reviewed: ComposerMaterial;
    if (reviewId) {
      // Read the existing receipt; restoring an edit must never submit a new review.
      const { data } = await mediaPortraitGetGetApiV1MediaPortraitsPortraitId(reviewId);
      if (data.id !== reviewId || data.fileId !== fileId || (data.kind ?? 'IMAGE') !== kind || data.status !== 'ACTIVE')
        throw new Error('原素材审核记录不可用');
      let preview = '';
      try { preview = await resolvePreview(fileId); } catch { /* The stored reference remains usable without a preview. */ }
      reviewed = { fileId, name, kind, preview, reviewId, reviewStatus: 'ACTIVE', reviewPending: false };
    } else {
      // Image task history may omit the receipt ID; reuse the existing library lookup.
      const existing = await reviewedLibraryMedia({ id: fileId, canvasFileIds: [fileId], name,
        kind: kind === 'VIDEO' ? 'video' : kind === 'AUDIO' ? 'audio' : 'image', mimeType: '', size: saved?.sizeBytes ?? 0 });
      if (!existing) throw new Error('原素材审核记录不可用');
      reviewed = existing;
    }
    return { ...saved, ...reviewed, name, kind,
      ...(ref.portraitId ? { portraitId: ref.portraitId } : {}), durationSeconds: ref.durationSeconds };
  }));
  const mode = task.type === 'IMAGE' ? materials.length ? 'reference' : 'text'
    : task.referenceMode === 'OMNI' ? 'omni' : materials.length ? 'frames' : 'text';
  return { settings, prompt: entry.draft?.prompt ?? task.prompt!,  materials,
    mode, bindings: entry.draft?.bindings ?? [] };
}

// Saved previews expire; restore identity immediately and obtain fresh access URLs.
export function restoreMaterialPreviews<T extends ComposerMaterial | null>(materials: T[], setMaterials: Dispatch<SetStateAction<T[]>>) {
  const restored = materials.map(file => file ? { ...file, preview: '' } as T : file);
  setMaterials(restored);
  let cancelled = false;
  void Promise.all(restored.map(async file => {
    if (!file) return file;
    try { return { ...file, preview: await resolvePreview(file.fileId) } as T; }
    catch { return file; }
  })).then(refreshed => {
    if (!cancelled) setMaterials(current => current.map(file => {
      const index = restored.indexOf(file);
      return index < 0 ? file : refreshed[index];
    }));
  });
  return () => { cancelled = true; };
}
