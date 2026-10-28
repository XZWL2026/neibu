import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';
import { loadEnv } from 'vite';
import { fileURLToPath, URL } from 'node:url';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const gatewayTarget = env.ADMIN_GATEWAY_PROXY_TARGET || 'http://127.0.0.1:18080';
  return {
    plugins: [vue()],
    resolve: { alias: {
      '@xzwl/api-contracts/new-system': fileURLToPath(new URL('./src/contracts/new-system.ts', import.meta.url)),
      '@xzwl/api-contracts/gateway': fileURLToPath(new URL('./src/contracts/gateway.ts', import.meta.url)),
    } },
    define: { 'import.meta.env.VITE_USER_WEB_URL': JSON.stringify(env.VITE_USER_WEB_URL || (mode === 'development' ? 'http://127.0.0.1:18080' : '')) },
    server: { host: '127.0.0.1', port: 18081, proxy: {
      '/api/v1': {
        target: gatewayTarget,
        changeOrigin: true,
        // The upstream admin gateway may live under /admin with scoped cookies.
        // Keep browser requests same-origin and preserve HttpOnly/Secure/CSRF.
        cookieDomainRewrite: '',
        cookiePathRewrite: '/',
        configure(proxy) {
          proxy.on('proxyReq', (proxyReq, request) => {
            if (request.headers.origin) proxyReq.setHeader('Origin', new URL(gatewayTarget).origin);
          });
        },
      },
    } },
    build: { target: 'es2022', assetsDir: env.ADMIN_ASSETS_DIR || 'assets', rollupOptions: { output: {
      manualChunks(id) {
        if (id.includes('/node_modules/echarts/') || id.includes('/node_modules/zrender/')) return 'charts';
        if (id.includes('/node_modules/vue/') || id.includes('/node_modules/@vue/') || id.includes('/node_modules/vue-router/')) return 'vue';
      },
    } } },
    test: {
      environment: 'jsdom', setupFiles: ['./src/test-setup.ts'], restoreMocks: true,
      exclude: ['**/node_modules/**', '**/dist/**', '**/e2e/**', '**/legacy-tests/**'],
      maxWorkers: 4,
      // Cold lazy-page imports can exceed Vitest's 5s default on Windows.
      testTimeout: 15_000,
    },
  };
});
