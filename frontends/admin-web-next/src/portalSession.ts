// Each administrator-opened agent tab has its own cookie, API and router path.
export function agentSessionBase(path = window.location.pathname || ''): string {
  return path.match(/^\/agent\/[0-9a-f]{32}(?=\/)/)?.[0] ?? '';
}

export function isApiConsoleSession(path = window.location.pathname || ''): boolean {
  return /^\/api(?:\/|$)/.test(path) && !/^\/api\/v1(?:\/|$)/.test(path);
}
