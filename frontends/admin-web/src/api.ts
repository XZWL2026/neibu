import {agentSessionBase, isApiConsoleSession} from './portalSession';
import { configureGateway } from '@xzwl/api-contracts/new-system';
import { clearAdminPageCache } from './localPersistence';

let unauthorizedHandler: (() => void) | undefined;
export function configureAdminGateway(onUnauthorized?: () => void) {
  if (onUnauthorized) unauthorizedHandler = onUnauthorized;
  const baseUrl = import.meta.env.VITE_GATEWAY_BASE_URL;
  configureGateway({
    baseUrl: agentSessionBase() ? window.location.origin + agentSessionBase()
      : isApiConsoleSession() ? window.location.origin + '/api/session'
      : baseUrl ? new URL(baseUrl, window.location.origin).href : undefined,
    onUnauthorized: () => {
      clearAdminPageCache();
      if (unauthorizedHandler) unauthorizedHandler();
      else if (isApiConsoleSession()) window.location.replace('/api/login');
      else window.location.replace(import.meta.env.VITE_LOGIN_URL || `${agentSessionBase() ? agentSessionBase() + '/' : import.meta.env.BASE_URL}admin/login`);
    }
  });
}
configureAdminGateway();
export * from '@xzwl/api-contracts/new-system';
