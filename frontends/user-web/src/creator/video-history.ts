import { mediaListGetApiV1MediaTasks } from '../api';
import type { VideoSubmission } from './VideoTaskResults';

// Cursors belong to the unfiltered feed: an entire page may contain images.
export async function readMediaHistory({ before, commerceOnly, known, isCurrent, mediaType = 'VIDEO' }: {
  before?: string; commerceOnly?: boolean; known?: Set<string>; isCurrent(): boolean; mediaType?: 'IMAGE' | 'VIDEO';
}) {
  const entries: VideoSubmission[] = [];
  const visited = new Set<string>();
  let cursor = before;
  let head: string | null | undefined;
  while (isCurrent()) {
    const { data } = await mediaListGetApiV1MediaTasks({ limit: 20, excludeCanvas: true,
      ...(commerceOnly ? { commerceOnly: true } : {}), ...(cursor ? { beforeId: cursor } : {}) });
    if (!isCurrent()) return null;
    if (head === undefined) head = data[0]?.id ?? null;
    entries.push(...data.filter(item => item.type === mediaType && item.id)
      .map(item => ({ key: item.id!, taskRef: item.id!, snapshot: item })));
    const next = data.length === 20 ? data.at(-1)?.id || null : null;
    if (next && (next === cursor || visited.has(next))) throw new Error('Repeated history cursor');
    // On refresh, bridge all new pages to the existing feed, without moving its older cursor.
    if (!next || (known?.size ? data.some(item => item.id && known.has(item.id)) : entries.length > 0)) {
      return { entries, cursor: next, head };
    }
    visited.add(next); cursor = next;
  }
  return null;
}

export const readVideoHistory = readMediaHistory;
