/** Bounded, memory-only query cache. Never persists tokens, secrets or financial data. */
type Entry = { value: unknown; expires: number };
type Pending = { promise: Promise<unknown>; controller: AbortController; users: number; generation: number };
const aborted = () => new DOMException('请求已取消', 'AbortError');
export class RequestCache {
  private values = new Map<string, Entry>();
  private pending = new Map<string, Pending>();
  private generation = 0;
  private scope = 'anonymous';
  readonly metrics = { hits: 0, misses: 0, coalesced: 0 };
  constructor(private readonly maxEntries = 120, private readonly now = () => Date.now()) {}
  setScope(scope: string) { if (scope !== this.scope) { this.clear(); this.scope = scope; } }
  clear() {
    this.generation++; this.values.clear();
    for (const job of this.pending.values()) job.controller.abort();
    this.pending.clear();
  }
  invalidate() { this.clear(); }
  get size() { return this.values.size; }
  async read<T>(key: string, loader: (signal: AbortSignal) => Promise<T>, ttl: number, signal?: AbortSignal, bypass = false): Promise<T> {
    if (signal?.aborted) throw aborted();
    const fullKey = `${this.scope}\n${key}`;
    const hit = this.values.get(fullKey);
    if (!bypass && hit && hit.expires > this.now()) {
      this.metrics.hits++; this.values.delete(fullKey); this.values.set(fullKey, hit);
      const generation = this.generation;
      await Promise.resolve();
      if (generation !== this.generation || signal?.aborted) throw aborted();
      return hit.value as T;
    }
    if (hit) this.values.delete(fullKey);
    let job = bypass ? undefined : this.pending.get(fullKey);
    if (job) this.metrics.coalesced++;
    else {
      this.metrics.misses++;
      const controller = new AbortController(), generation = this.generation;
      const promise = loader(controller.signal).then(value => {
        if (controller.signal.aborted || generation !== this.generation) throw aborted();
        if (ttl > 0) {
          this.values.set(fullKey, { value, expires: this.now() + ttl });
          while (this.values.size > this.maxEntries) this.values.delete(this.values.keys().next().value!);
        }
        return value;
      });
      job = { promise, controller, users: 0, generation };
      const captured = job;
      this.pending.set(fullKey, job);
      void promise.finally(() => { if (this.pending.get(fullKey) === captured) this.pending.delete(fullKey); }).catch(() => {});
    }
    job.users++;
    const captured = job;
    return new Promise<T>((resolve, reject) => {
      let finished = false;
      const release = () => {
        if (finished) return false;
        finished = true; signal?.removeEventListener('abort', cancel); captured.users--;
        return true;
      };
      const cancel = () => {
        if (!release()) return;
        reject(aborted());
        if (!captured.users) {
          captured.controller.abort();
          if (this.pending.get(fullKey) === captured) this.pending.delete(fullKey);
        }
      };
      signal?.addEventListener('abort', cancel, { once: true });
      captured.promise.then(value => {
        if (release()) {
          if (captured.generation !== this.generation) reject(aborted());
          else resolve(value as T);
        }
      }, error => { if (release()) reject(error); });
    });
  }
}
export const adminRequestCache = new RequestCache();

export function cacheLifetime(path: string): number | null {
  const url = new URL(path, 'http://local');
  // Allowlist business reads only. A secret read must never be cached or deduplicated.
  if (!url.pathname.startsWith('/api/v1/admin/') || /\/(secret|(?:[a-z-]+-)?ticket|credentials|export|proof|access-url)(\/|$)/.test(url.pathname)) return null;
  const safe = /^\/api\/v1\/admin\/(users|api-users|billing|promotions|payments)(\/|$)/.test(url.pathname)
    || url.pathname === '/api/v1/admin/media/native-video-usage'
    || url.pathname === '/api/v1/admin/routing/models';
  if (!safe || /\/payouts\/[^/]+\/account$/.test(url.pathname)) return null;
  if (/\/(media-prices|models)(\/|$)/.test(url.pathname)) return 60_000;
  if (url.pathname.includes('/billing/') || url.pathname.includes('/payments/') || url.pathname.includes('/promotions/')) return 10_000;
  return 20_000;
}
