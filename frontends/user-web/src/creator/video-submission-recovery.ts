import type { VideoSubmission } from './VideoTaskResults';

const key = (owner: string, kind: string, commerce: boolean) => `xzwl-${commerce ? 'commerce-' : ''}${kind.toLowerCase()}-submission:${owner}`;
export function readVideoSubmission(owner: string | null, kind: 'VIDEO' | 'IMAGE' = 'VIDEO', commerce = false): VideoSubmission | null {
  if (!owner) return null;
  try {
    const entry = JSON.parse(sessionStorage.getItem(key(owner, kind, commerce)) || 'null') as VideoSubmission | null;
    if (!entry || typeof entry.key !== 'string' || !entry.snapshot || !entry.request
      || entry.key.startsWith('commerce-') !== commerce || entry.request.requestRef !== entry.key || entry.request.settings?.type !== kind
      || typeof entry.request.prompt !== 'string' || !Array.isArray(entry.request.inputAssetRefs)) return null;
    return { ...entry, state: entry.taskRef ? undefined : entry.state === 'PREPARED' ? 'PREPARED' : 'UNCERTAIN' };
  } catch { return null; }
}
export function saveVideoSubmission(owner: string | null, entry: VideoSubmission | null, kind: 'VIDEO' | 'IMAGE' = 'VIDEO', commerce = entry?.key.startsWith('commerce-') ?? false): boolean {
  if (!owner) return true;
  try {
    if (entry) sessionStorage.setItem(key(owner, kind, commerce), JSON.stringify(entry));
    else sessionStorage.removeItem(key(owner, kind, commerce));
    return true;
  } catch { return false; }
}
