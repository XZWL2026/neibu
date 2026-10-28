import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { existsSync } from "node:fs";
import { resolve } from "node:path";

export default defineConfig({
  plugins: [react(), {
    name: "cache-versioned-images",
    configurePreviewServer(server) {
      server.middlewares.use((request, response, next) => {
        const path = request.url?.split('?')[0] ?? '';
        if (/^\/assets\/[\w-]+-[\w-]{8}\.(?:webp|png|jpe?g|avif|svg)$/.test(path)
          && existsSync(resolve(server.config.root, server.config.build.outDir, path.slice(1)))) {
          response.setHeader('Cache-Control', 'public, max-age=31536000, immutable');
        }
        next();
      });
    }
  }],
  test: {
    environment: "jsdom",
    restoreMocks: true
  }
});
