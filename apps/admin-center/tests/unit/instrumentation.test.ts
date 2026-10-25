/**
 * @jest-environment node
 */
import { register } from "@/instrumentation";

describe("register()（服务启动钩子）", () => {
  const saved = { ...process.env };
  afterEach(() => {
    process.env = { ...saved };
  });

  function env(vars: Record<string, string | undefined>) {
    for (const [k, v] of Object.entries(vars)) {
      if (v === undefined) delete process.env[k];
      else process.env[k] = v;
    }
  }

  it("nodejs 运行时缺少 UAM_SERVICE_KEY 时抛错（服务启动失败）", async () => {
    env({ NEXT_RUNTIME: "nodejs", NEXT_PHASE: undefined, UAM_BASE_URL: "http://uam", UAM_SERVICE_KEY: undefined });
    await expect(register()).rejects.toThrow("UAM_SERVICE_KEY");
  });

  it("nodejs 运行时缺少 UAM_BASE_URL 时抛错", async () => {
    env({ NEXT_RUNTIME: "nodejs", NEXT_PHASE: undefined, UAM_BASE_URL: undefined, UAM_SERVICE_KEY: "k" });
    await expect(register()).rejects.toThrow("UAM_BASE_URL");
  });

  it("配置齐全时正常返回", async () => {
    env({ NEXT_RUNTIME: "nodejs", NEXT_PHASE: undefined, UAM_BASE_URL: "http://uam", UAM_SERVICE_KEY: "k" });
    await expect(register()).resolves.toBeUndefined();
  });

  it("非 nodejs 运行时（edge）不校验", async () => {
    env({ NEXT_RUNTIME: "edge", UAM_BASE_URL: undefined, UAM_SERVICE_KEY: undefined });
    await expect(register()).resolves.toBeUndefined();
  });

  it("next build 阶段不校验：构建不需要运行期环境变量", async () => {
    env({
      NEXT_RUNTIME: "nodejs",
      NEXT_PHASE: "phase-production-build",
      UAM_BASE_URL: undefined,
      UAM_SERVICE_KEY: undefined,
    });
    await expect(register()).resolves.toBeUndefined();
  });
});
