/**
 * 服务启动钩子：缺少 UAM_SERVICE_KEY / UAM_BASE_URL 时让服务启动失败（快速失败）。
 * `next build` 阶段不校验——构建不需要运行期环境变量。
 */
export async function register(): Promise<void> {
  if (process.env.NEXT_RUNTIME !== "nodejs") return;
  if (process.env.NEXT_PHASE === "phase-production-build") return;
  const { getConfig } = await import("./lib/config");
  getConfig();
}
