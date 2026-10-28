import {agentSessionBase, isApiConsoleSession} from './portalSession';
const cachePrefix=()=>{const base=agentSessionBase();return base ? `xzwl.agent.${base.split('/')[2]}.` : isApiConsoleSession() ? 'xzwl.api-console.' : '';};
const memory = new Map<string, unknown>();

function storage() {
  try {
    return typeof localStorage === "undefined" ? null : localStorage;
  } catch {
    return null;
  }
}

export function readLocal<T>(key: string, fallback: T): T {
  key=cachePrefix()+key;
  const target = storage();
  const raw = target?.getItem(key);
  if (raw !== null && raw !== undefined) {
    try { return JSON.parse(raw) as T; } catch { return fallback; }
  }
  return (memory.get(key) as T | undefined) ?? fallback;
}

export function writeLocal<T>(key: string, value: T): void {
  key=cachePrefix()+key;
  memory.set(key, value);
  storage()?.setItem(key, JSON.stringify(value));
}

export function clearAdminPageCache(): void {
  memory.clear();
  const prefix=cachePrefix();
  // Clear only caches owned by this application, never unrelated user-web data.
  for (const name of ['localStorage', 'sessionStorage'] as const) {
    try {
      const target = globalThis[name];
      for (let index = target.length - 1; index >= 0; index--) {
        const key = target.key(index);
        if (key && (prefix ? key.startsWith(prefix) : key.startsWith('xzwl.admin.') || key.startsWith('xzwl.promoter.'))) target.removeItem(key);
      }
    } catch { /* Storage may be disabled; in-memory pages are still discarded. */ }
  }
}
