import {
  getIamSessionGetApiV1AuthSessionUrl,
  getIamLoginPostApiV1AuthAdminLoginUrl,
  getIamLoginPostApiV1AuthOtpLoginUrl,
  getIamLoginPostApiV1AuthPhoneLoginUrl,
  getIamLogoutPostApiV1AuthLogoutUrl,
  iamSessionGetApiV1AuthSession,
  type ResponseIamApiSessionDTO
} from './generated-new-system/new-system';

import { adminRequestCache, cacheLifetime } from '../data/requestCache';

export type GatewayConfig = { baseUrl?: string; onUnauthorized?: () => void };
let config: GatewayConfig = {};
let session: ResponseIamApiSessionDTO | null = null;
let sessionRequest: Promise<ResponseIamApiSessionDTO> | undefined;
let revision = 0;
let redirected = false;

/** One configuration per browser application. Tokens are never persisted. */
export function configureGateway(next: GatewayConfig) {
  const baseUrl = (next.baseUrl ?? '').trim().replace(/\/+$/, '');
  if (baseUrl) {
    const url = new URL(baseUrl);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
      throw new Error('Gateway base URL must be an HTTP(S) URL without credentials, query or fragment');
    }
  }
  config = { ...next, baseUrl };
  clearSession();
  redirected = false;
}

export function getSession(): Readonly<ResponseIamApiSessionDTO> | null { return session; }

export function clearSession() {
  adminRequestCache.clear();
  session = null;
  sessionRequest = undefined;
  revision++;
}

function expireSession() {
  clearSession();
  if (!redirected) {
    redirected = true;
    config.onUnauthorized?.();
  }
}

/** Coalesces CSRF bootstrap; a later response cannot restore an expired identity. */
export function refreshSession(): Promise<ResponseIamApiSessionDTO> {
  if (!sessionRequest) {
    const pending = iamSessionGetApiV1AuthSession().then(result => result.data).finally(() => {
      if (sessionRequest === pending) sessionRequest = undefined;
    });
    sessionRequest = pending;
  }
  return sessionRequest;
}

const messages: Record<number, string> = {
  401: '登录已失效，请重新登录', 403: '请求被拒绝', 409: '操作冲突，请刷新后重试',
  422: '提交内容无法处理', 502: '服务暂时不可用'
};

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly requestId?: string;
  readonly details: unknown;
  readonly body: unknown;
  readonly headers: Headers;

  constructor(response: Response, body: unknown) {
    const fields = body && typeof body === 'object' ? body as Record<string, unknown> : {};
    super(typeof fields.message === 'string' ? fields.message
      : typeof fields.error === 'string' ? fields.error
      : messages[response.status] ?? `请求失败（${response.status}）`);
    this.name = 'ApiError';
    this.status = response.status;
    this.code = typeof fields.code === 'string' ? fields.code : undefined;
    this.requestId = response.headers.get('X-Request-Id')
      ?? (typeof fields.requestId === 'string' ? fields.requestId : undefined);
    this.details = fields.details;
    this.body = body;
    this.headers = response.headers;
  }
}

/** Orval fetch mutator: callers use generated operations, which serialize bodies. */
async function networkRequest<T>(path: string, options: RequestInit = {}): Promise<T> {
  if (!path.startsWith('/') || path.startsWith('//') || path.includes('\\')) {
    throw new Error('SDK requests must use a relative Gateway path');
  }
  const method = (options.method ?? 'GET').toUpperCase();
  const headers = new Headers(options.headers);
  const isSession = path === getIamSessionGetApiV1AuthSessionUrl() && method === 'GET';
  const isLogin = method === 'POST' && [getIamLoginPostApiV1AuthAdminLoginUrl(), getIamLoginPostApiV1AuthOtpLoginUrl(), getIamLoginPostApiV1AuthPhoneLoginUrl()].includes(path);
  const isLogout = method === 'POST' && path === getIamLogoutPostApiV1AuthLogoutUrl();
  const startedAt = revision;
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    if (!session?.csrfToken) await refreshSession();
    if (startedAt !== revision || !session?.csrfToken) throw new Error('Session CSRF token is unavailable');
    headers.set('X-CSRF-TOKEN', session.csrfToken);
  }
  if (options.body instanceof FormData) headers.delete('Content-Type');
  const response = await fetch(`${config.baseUrl ?? ''}${path}`, {
    ...options, method, headers, credentials: 'include', cache: 'no-store', redirect: 'error'
  });
  // Clear identity before parsing: malformed upstream error JSON must not bypass 401 handling.
  if (response.status === 401 && startedAt === revision) {
    if (isLogin) clearSession(); else expireSession();
  }
  // IAM uses the same 403 shape for role and CSRF rejection. Keep identity, but
  // bootstrap a fresh token on the next write; never replay a mutation automatically.
  if (response.status === 403 && startedAt === revision && session && !['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    session = { ...session, csrfToken: null };
  }
  const text = response.status === 204 ? '' : await response.text();
  let body: unknown = text || undefined;
  if (text && response.headers.get('Content-Type')?.toLowerCase().includes('json')) {
    try { body = JSON.parse(text); }
    catch (error) { if (response.ok) throw error; }
  }
  if (!response.ok) throw new ApiError(response, body);
  if (isSession && startedAt !== revision) throw new Error('Session changed while refreshing');
  if (isSession) {
    const next = body as ResponseIamApiSessionDTO;
    if (!next || typeof next.authenticated !== 'boolean' || !(typeof next.csrfToken === 'string' || next.csrfToken === null)) {
      throw new Error('Invalid IAM session response');
    }
    if (session?.authenticated && !next.authenticated) expireSession();
    else { session = next; if (next.authenticated) redirected = false; }
  }
  if ((isLogin || isLogout) && startedAt === revision) {
    clearSession();
    await refreshSession();
  }
  return { data: body, status: response.status, headers: response.headers } as T;
}

/** Retains contract serialization, Cookie/CSRF handling and error semantics. */
export async function gatewayRequest<T>(path: string, options: RequestInit = {}): Promise<T> {
  const method = (options.method ?? 'GET').toUpperCase();
  const ttl = cacheLifetime(path);
  if (method !== 'GET' || ttl === null || !session?.authenticated || options.cache === 'no-store' || options.headers) {
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) adminRequestCache.invalidate();
    try { return await networkRequest<T>(path, options); }
    finally { if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) adminRequestCache.invalidate(); }
  }
  const query = new URL(path, 'http://local');
  query.searchParams.sort();
  const key = `${config.baseUrl ?? ''}${query.pathname}${query.search}`;
  const result = await adminRequestCache.read<T>(key, signal => networkRequest<T>(path, { ...options, signal }), ttl,
    options.signal ?? undefined, options.cache === 'reload');
  // Return an independent result; a consumer cannot mutate another page's cached report.
  const response = result as { data: unknown; status: number; headers: Headers };
  return { ...response, data: structuredClone(response.data), headers: new Headers(response.headers) } as T;
}
