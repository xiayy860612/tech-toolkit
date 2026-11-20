import { defineConfig } from "@playwright/test";

// E2E 需要真实后端（dev profile）、PostgreSQL、Redis 与已启动的前端，见 AGENTS.md。
const baseURL = process.env.E2E_BASE_URL ?? "http://localhost:3000";

export default defineConfig({
  testDir: "./tests/e2e",
  timeout: 60_000,
  fullyParallel: false,
  workers: 1,
  reporter: [["list"]],
  // E2E_CHANNEL=chrome 可改用本机已安装的 Google Chrome，免下载 Playwright 自带浏览器
  use: { baseURL, trace: "retain-on-failure", channel: process.env.E2E_CHANNEL || undefined },
});
