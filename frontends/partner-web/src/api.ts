import { configureGateway } from '@xzwl/api-contracts/new-system';

configureGateway({
  baseUrl: import.meta.env.VITE_GATEWAY_BASE_URL,
  onUnauthorized: () => window.location.replace(
    import.meta.env.VITE_LOGIN_URL || `${'/'}user/login`
  )
});

export * from '@xzwl/api-contracts/new-system';
