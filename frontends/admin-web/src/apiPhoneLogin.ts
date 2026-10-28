import {
  configureGateway, configureAdminGateway, sendOtp, loginWithOtp, refreshSession,
  iamIssuePostApiV1AuthPortalTickets, iamRedeemPostApiV1AuthPortalLogin, ApiError
} from './api';
import { clearAdminPageCache } from './localPersistence';

// Only the public login page uses the phone session. Always restore the API
// gateway before leaving it; each namespace bootstraps its own CSRF token.
async function withPhoneSession<T>(action: () => Promise<T>): Promise<T> {
  configureGateway({ baseUrl: window.location.origin });
  try { return await action(); }
  finally { configureAdminGateway(); }
}

export function sendApiLoginCode(phone: string) {
  return withPhoneSession(() => sendOtp({ phone }));
}

export async function loginApiWithPhone(phone: string, code: string) {
  const hex = (bytes: Uint8Array) => Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  const verifier = hex(crypto.getRandomValues(new Uint8Array(32)));
  const challenge = hex(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))));
  const ticket = await withPhoneSession(async () => {
    await loginWithOtp({ phone, code });
    // Eligibility is resolved by IAM, never inferred from a successful OTP.
    const { data } = await iamIssuePostApiV1AuthPortalTickets({ role: 'API_USER', challenge });
    if (!/^[0-9a-f]{64}$/.test(data.ticket ?? '')) throw new Error('Invalid login ticket');
    return data.ticket!;
  });
  const { data } = await iamRedeemPostApiV1AuthPortalLogin({ ticket, verifier });
  if (data.role !== 'API_USER') throw new Error('Invalid API identity');
  const identity = await refreshSession();
  if (!identity.authenticated || identity.roles?.length !== 1 || identity.roles[0] !== 'api_user') {
    throw new Error('Invalid API session');
  }
  clearAdminPageCache();
}

export function apiLoginError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'OTP_INVALID_OR_EXPIRED') return '验证码错误或已过期，请重新输入或获取验证码';
    if (error.code === 'SMS_PROVIDER_UNAVAILABLE') return '短信服务暂时不可用，请稍后重试';
    if (error.code === 'OTP_LOCKED') return '验证码尝试次数过多，请重新获取验证码';
    if (error.code === 'USER_DISABLED') return '账户已停用，请联系管理员';
    if (error.status === 429) return '请求过于频繁，请稍后再试';
    if (error.status === 423) return '账户暂时无法登录，请联系管理员';
    if (error.status === 403 && error.code?.includes('PORTAL')) return '该手机号暂无 API 访问权限，请联系管理员';
  }
  return '登录请求未完成，请稍后重试';
}
