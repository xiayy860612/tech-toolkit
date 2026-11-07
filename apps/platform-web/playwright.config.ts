import { defineConfig } from '@playwright/test';

// E2E runs against an already running stack (tenant-core-api + platform-web + Postgres + Redis).
export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:3000',
    // E2E_BROWSER_CHANNEL=chrome runs the installed Google Chrome instead of bundled Chromium.
    channel: process.env.E2E_BROWSER_CHANNEL || undefined,
    trace: 'retain-on-failure',
  },
});
