import { defineConfig } from "@playwright/test";

// 对着 k8s 部署的冒烟测试（devops/k8s）：只依赖 HTTP 入口，不碰 docker compose。
// 先 `devops/k8s/scripts/deploy.sh forward`（另开终端）和 `deploy.sh test-account`，再 `npm run e2e:k8s`。
// 入口前面有真正的反向代理（Envoy），不需要像 compose 栈那样伪造 X-Forwarded-For。
export default defineConfig({
  testDir: "tests/k8s-smoke",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: process.env.K8S_SMOKE_BASE_URL ?? "http://127.0.0.1:8088",
    trace: "retain-on-failure",
    viewport: { width: 1440, height: 900 },
  },
});
