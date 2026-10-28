import { ApiError, storageMatchImagePostApiV1FilesFileIdMatch, mediaPortraitGetGetApiV1MediaPortraitsPortraitId, mediaPortraitListGetApiV1MediaPortraits } from '../api';
import { resolvePreview } from '../canvas/production-api';
import type { ComposerMaterial } from './ComposerMaterials';
import type { CreatorMedia } from './types';

// The library keeps original storage IDs, including IDs saved by older canvas uploads.
export async function reviewedLibraryMedia(media: CreatorMedia): Promise<ComposerMaterial | null> {
  const fileIds = new Set([media.id, ...(media.canvasFileIds ?? [])]);
  const kind = media.kind === 'video' ? 'VIDEO' : media.kind === 'audio' ? 'AUDIO' : 'IMAGE';
  let beforeId: string | undefined;
  type Receipt = Awaited<ReturnType<typeof mediaPortraitListGetApiV1MediaPortraits>>['data'][number];
  const candidates: Receipt[] = [];
  const reuse = async (row: Receipt): Promise<ComposerMaterial> => {
    // Model names may differ: the server quote/submission checks provider compatibility.
    const { data: current } = await mediaPortraitGetGetApiV1MediaPortraitsPortraitId(row.id);
    if (current.id !== row.id || current.fileId !== row.fileId || current.model !== row.model
      || (current.kind ?? 'IMAGE') !== kind || current.status !== 'ACTIVE') {
      throw new Error(current.errorMessage || '素材审核尚未通过或已失效，请选择已审核素材');
    }
    let preview = media.previewUrl ?? '';
    try { preview = await resolvePreview(current.fileId); } catch { /* Keep the local preview if signing fails. */ }
    return { fileId: current.fileId, name: media.name, preview, kind,
      reviewId: current.id, reviewStatus: 'ACTIVE', reviewPending: false,
      durationSeconds: current.durationSeconds, sizeBytes: media.content?.size ?? media.size };
  };
  let linkedReceiptError: unknown;
  const cursors = new Set<string>();
  do {
    const { data } = await mediaPortraitListGetApiV1MediaPortraits({ limit: 20, ...(beforeId ? { beforeId } : {}) });
    for (const row of data) {
      if (fileIds.has(row.fileId) && (row.kind ?? 'IMAGE') === kind) {
        try { return await reuse(row); }
        catch (cause) {
          if (cause instanceof ApiError && ![403, 404, 502].includes(cause.status)) throw cause;
          linkedReceiptError = cause;
        }
      }
      if ((row.kind ?? 'IMAGE') === 'IMAGE') candidates.push(row);
    }
    beforeId = data.length === 20 ? data.at(-1)?.id : undefined;
    if (beforeId && cursors.has(beforeId)) throw new Error('素材审核记录读取失败，请重试');
    if (beforeId) cursors.add(beforeId);
  } while (beforeId);
  if (linkedReceiptError) throw linkedReceiptError;
  // Legacy local UUIDs have no storage link. Compare originals on the server, avoiding TOS CORS.
  if (kind === 'IMAGE' && media.content && media.content.size > 0 && media.content.size <= 20 * 1024 * 1024 && candidates.length) {
    const bytes = await new Promise<ArrayBuffer>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result as ArrayBuffer);
      reader.onerror = () => reject(reader.error);
      reader.readAsArrayBuffer(media.content!);
    });
    const sha256 = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), byte => byte.toString(16).padStart(2, '0')).join('');
    const matches = new Map<string, boolean>();
    let staleReceipt: unknown;
    for (const row of candidates.sort((a, b) => Number(b.status === 'ACTIVE') - Number(a.status === 'ACTIVE'))) {
      try {
        if (!matches.has(row.fileId)) {
          const { data } = await storageMatchImagePostApiV1FilesFileIdMatch(row.fileId, { sizeBytes: media.content.size, sha256 });
          matches.set(row.fileId, data.matches);
        }
        if (matches.get(row.fileId)) {
          try { return await reuse(row); }
          catch (cause) {
            if (cause instanceof ApiError && ![403, 404, 502].includes(cause.status)) throw cause;
            staleReceipt = cause;
          }
        }
      } catch (cause) {
        if (!(cause instanceof ApiError) || ![403, 404, 502].includes(cause.status)) throw cause;
        matches.set(row.fileId, false);
      }
    }
    if (staleReceipt) throw staleReceipt;
  }
  if (media.canvasFileIds?.length || media.kind === 'image') throw new Error('此素材缺少可复用的原审核记录');
  return null;
}
