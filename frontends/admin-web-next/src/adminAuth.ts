import { ref } from 'vue';
import { adminRequestCache } from './data/requestCache';
import { isApiConsoleSession } from './portalSession';
import type { Router } from 'vue-router';
import { configureAdminGateway, clearSession, refreshSession, loginAdmin, getSession, logout } from './api';
import { clearAdminPageCache } from './localPersistence';
import { canAccessAdminPath, homePathForRole, type AdminIdentity, type AdminSession } from './adminSession';

export function createAdminAuth(router: Router): AdminSession {
  const currentAdmin = ref<AdminIdentity | null>(null);
  const phase = ref<'checking' | 'ready' | 'anonymous' | 'error'>('checking');
  let revision = 0;
  let stopped = false;
  let pending: Promise<void> | undefined;
  const apiConsole = isApiConsoleSession();
  const entryRoutes = ['admin-login', 'portal-entry', 'api-login', 'api-portal-entry'];
  const loginPath = apiConsole ? '/api/login' : '/admin/login';
  const loginLocation = (target = router.currentRoute.value.fullPath) => ({
    path: loginPath, query: target === loginPath || target.startsWith(loginPath + '?') ? {} : { redirect: target }, replace: true
  });
  function invalidate() {
    revision++;
    pending = undefined;
    currentAdmin.value = null;
    phase.value = 'anonymous';
    clearSession();
    clearAdminPageCache();
  }
  function readIdentity() {
    const identity = getSession();
    const knownRoles = { admin: 'ADMIN', normal_admin: 'NORMAL_ADMIN' } as const;
    const role = identity?.roles?.length === 1 ? knownRoles[identity.roles[0] as keyof typeof knownRoles] : null;
    const next = identity?.authenticated && role ? { username: identity.user?.username ?? '', role, primary: identity.primary, userId: identity.user?.id ?? undefined } : null;
    adminRequestCache.setScope(next ? JSON.stringify([next.userId, next.username, next.role, next.primary]) : 'anonymous');
    if (JSON.stringify(currentAdmin.value) !== JSON.stringify(next)) currentAdmin.value = next;
    if (identity?.authenticated) phase.value = 'ready';
    else invalidate();
  }
  configureAdminGateway(() => {
    invalidate();
    void router.replace(loginLocation());
  });
  async function check() {
    if (stopped) return;
    if (!pending) {
      const started = revision;
      if (phase.value !== 'ready') phase.value = 'checking';
      const request = refreshSession().then(() => {
        if (started === revision) readIdentity();
      }).catch(() => {
        if (started === revision) { currentAdmin.value = null; phase.value = 'error'; }
      }).finally(() => { if (pending === request) pending = undefined; });
      pending = request;
    }
    await pending;
  }
  const removeGuard = router.beforeEach(async to => {
    if (entryRoutes.includes(String(to.name))) {
      // A login page never bootstraps business content, even with an existing Cookie.
      return true;
    }
    await check();
    if (phase.value === 'anonymous') return loginLocation(to.fullPath);
    return true; // Errors and permission denial render a blocking state in App.
  });
  async function verify() {
    await router.isReady();
    if (entryRoutes.includes(String(router.currentRoute.value.name))) return;
    await check();
    if (phase.value === 'anonymous') await router.replace(loginLocation());
  }
  function destination(target: unknown): string {
    const home = currentAdmin.value ? homePathForRole[currentAdmin.value.role] : '/admin/overview';
    if (typeof target !== 'string' || !target.startsWith('/') || target.startsWith('//') || /[\\\s]/.test(target)) return home;
    const resolved = router.resolve(target);
    if (!resolved.matched.length || entryRoutes.includes(String(resolved.name)) || resolved.name === 'admin-not-found'
      || !canAccessAdminPath(resolved.path, currentAdmin.value)) return home;
    return resolved.fullPath;
  }
  const refreshVisible = () => { if (document.visibilityState !== 'hidden') void verify(); };
  const hide = () => { if (router.currentRoute.value.name !== 'admin-login') phase.value = 'checking'; };
  window.addEventListener('focus', refreshVisible);
  window.addEventListener('pageshow', refreshVisible);
  window.addEventListener('pagehide', hide);
  document.addEventListener('visibilitychange', refreshVisible);
  const timer = window.setInterval(refreshVisible, 60_000);
  return {
    currentAdmin, phase, verify, destination,
    async authenticate(username, password = '') {
      try {
        stopped = false;
        await loginAdmin({ username: username.trim(), password });
        readIdentity();
        return phase.value === 'ready' && currentAdmin.value !== null;
      } catch { invalidate(); return false; }
    },
    async logout() {
      stopped = true;
      // Hide pages and drop cached data immediately; the SDK retains CSRF until the remote logout finishes.
      currentAdmin.value = null;
      phase.value = 'anonymous';
      clearAdminPageCache();
      revision++;
      let failed = false;
      try { await logout(); } catch { failed = true; }
      finally { invalidate(); }
      await router.replace({ path: loginPath, query: failed ? { logout: 'failed' } : {} });
      stopped = false;
    },
    dispose() {
      revision++;
      removeGuard();
      window.clearInterval(timer);
      window.removeEventListener('focus', refreshVisible);
      window.removeEventListener('pageshow', refreshVisible);
      window.removeEventListener('pagehide', hide);
      document.removeEventListener('visibilitychange', refreshVisible);
    }
  };
}
