import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './e2e', fullyParallel: false, workers: 1, timeout: 30_000,
  use: { baseURL:'http://127.0.0.1:18081', channel:'msedge', viewport:{width:1440,height:1000}, reducedMotion:'reduce', trace:'retain-on-failure' },
  webServer: { command:'node ./node_modules/vite/bin/vite.js --host 127.0.0.1 --port 18081 --strictPort', url:'http://127.0.0.1:18081', reuseExistingServer:!process.env.CI, timeout:60_000 },
});
