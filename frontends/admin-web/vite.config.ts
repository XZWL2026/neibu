import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";
import { loadEnv } from "vite";

export default defineConfig(({ mode }) => {
  const target = loadEnv(mode, process.cwd(), "ADMIN_").ADMIN_GATEWAY_PROXY_TARGET;
  const userWebUrl = loadEnv(mode, process.cwd(), "VITE_").VITE_USER_WEB_URL
    || (mode === "development" ? "http://127.0.0.1:18080" : "");
  return {
  define: { "import.meta.env.VITE_USER_WEB_URL": JSON.stringify(userWebUrl) },
  plugins: [vue()],
  // Preserve the browser-facing Host so Cookie/CSRF requests remain same-origin.
  server: { proxy: {
    '/api/session/api/': { target: 'http://127.0.0.1:18080', changeOrigin: false },
    "^/agent/[0-9a-f]{32}/api/v1/": { target: "http://127.0.0.1:18080", changeOrigin: false },
    ...(target ? { "/api/v1": { target, changeOrigin: false } } : {})
  } },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test-setup.ts"],
    restoreMocks: true
  }
  };
});
