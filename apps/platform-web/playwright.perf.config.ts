import { defineConfig } from '@playwright/test';

// Interaction / layout-shift measurements for `pnpm perf` (spec FR-029). Runs against the same stack
// as the E2E suite; E2E_BROWSER_CHANNEL=chrome selects the installed Google Chrome.
export default defineConfig({
  testDir: './tests/perf',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 120_000,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:3000',
    channel: process.env.E2E_BROWSER_CHANNEL || undefined,
  },
});
