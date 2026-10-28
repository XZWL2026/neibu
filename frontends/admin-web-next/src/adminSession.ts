import type { InjectionKey, Ref } from "vue";

export type AdminRole = "ADMIN" | "NORMAL_ADMIN" | "PROMOTER" | "API_USER";
export type AdminIdentity = { username: string; role: AdminRole; primary?: boolean; userId?: string };
export type AdminSession = {
  currentAdmin: Ref<AdminIdentity | null>;
  phase?: Ref<'checking' | 'ready' | 'anonymous' | 'error'>;
  verify?: () => Promise<void>;
  destination?: (target: unknown) => string;
  logout?: () => Promise<void>;
  dispose?: () => void;
  authenticate: (username: string, password?: string) => boolean | Promise<boolean>;
};

export const adminSessionKey: InjectionKey<AdminSession> = Symbol("admin-session");

const apiRoot = "/" + "api";

export const homePathForRole: Record<AdminRole, string> = {
  ADMIN: "/admin/overview",
  NORMAL_ADMIN: "/admin/overview",
  PROMOTER: "/promoter/overview",
  API_USER: `${apiRoot}/dashboard`
};

export const primaryOnlyRoutes = ["/admin/provider-logs", "/admin/providers", "/admin/pricing", "/admin/admins", "/admin/promotions/applications", "/admin/promotions/attributions", "/admin/promotions/withdrawals", "/admin/promotions/withdrawal-report", "/admin/promotions/benefit-settings", "/admin/promotions/reconciliation", "/admin/promotions/benefit-audit"];

export function canAccessAdminPath(path: string, identity: AdminIdentity | null): boolean {
  if (!identity) return false;
  if (identity.role === 'PROMOTER') return path.startsWith('/promoter/');
  if (identity.role === 'API_USER') return path.startsWith('/api/');
  if (!path.startsWith('/admin/')) return false;
  return !primaryOnlyRoutes.some(target => path === target || path.startsWith(target + '/'))
    || (identity.role === 'ADMIN' && identity.primary === true);
}
