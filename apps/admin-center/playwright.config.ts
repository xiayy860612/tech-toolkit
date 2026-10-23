import { defineConfig } from "@playwright/test";
import { SOURCE_HEADERS } from "./tests/e2e/support/fixtures";

// 视口矩阵：每个宽度一个 project（spec SC-008）。完整旅程在每个宽度上都要跑通。
const widths = [320, 375, 768, 1024, 1440, 2560] as const;

export default defineConfig({
  testDir: "tests/e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    trace: "retain-on-failure",
    extraHTTPHeaders: SOURCE_HEADERS,
  },
  projects: widths.map((width) => ({
    name: `w${width}`,
    use: { viewport: { width, height: 900 } },
  })),
});
