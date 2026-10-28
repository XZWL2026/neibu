import { adminRequestCache } from './data/requestCache';
// Local fixture/preferences have their own namespace, never shared with the original application.
const prefix = 'xzwl.admin-next.';
const memory = new Map<string, unknown>();
function storage(): Storage | null { try { return globalThis.localStorage ?? null; } catch { return null; } }
export function readLocal<T>(key: string, fallback: T): T {
  const fullKey = prefix + key;
  try {
    const raw = storage()?.getItem(fullKey);
    if (raw != null) return JSON.parse(raw) as T;
  } catch { /* Invalid JSON or denied storage falls back to memory. */ }
  return (memory.get(fullKey) as T | undefined) ?? fallback;
}
export function writeLocal<T>(key: string, value: T): void {
  const fullKey = prefix + key;
  memory.set(fullKey, value);
  try { storage()?.setItem(fullKey, JSON.stringify(value)); } catch { /* Quota/permissions must not break an operation. */ }
}
export function clearAdminPageCache(): void {
  adminRequestCache.clear(); memory.clear();
  for (const name of ['localStorage', 'sessionStorage'] as const) {
    try {
      const target = globalThis[name];
      for (let i = target.length - 1; i >= 0; i--) {
        const key = target.key(i);
        if (key?.startsWith(prefix)) target.removeItem(key);
      }
    } catch { /* Memory was already cleared. */ }
  }
}
