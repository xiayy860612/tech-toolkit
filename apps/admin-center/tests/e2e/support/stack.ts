import fs from "node:fs";
import { execFile, execFileSync, spawn } from "node:child_process";
import path from "node:path";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

const root = path.resolve(__dirname, "../../../../..");
const bootstrap = path.join(root, "infra/local/scripts/bootstrap.sh");

/** E2E 栈的入口地址（bootstrap.sh 把 Admin Center 映射到宿主机 3000 端口）。 */
export const BASE_URL = process.env.E2E_BASE_URL ?? "http://127.0.0.1:3000";

/** 构建并启动栈（等待所有服务健康）。 */
export function startStack(): void {
  execFileSync(bootstrap, ["up"], { stdio: "inherit" });
}

/** 停止栈并清掉数据卷。 */
export function stopStack(): void {
  execFileSync(bootstrap, ["down"], { stdio: "inherit" });
}

/** 在 UAM 镜像里执行一次性命令（造数用）；stdin 可传密码，避免出现在命令行。 */
export function uamCli(args: string[], stdin?: string): string {
  const compose = [
    "compose",
    "-f",
    path.join(root, "infra/local/docker-compose.stack.yml"),
    "--env-file",
    path.join(root, "infra/local/e2e.env"),
    "run",
    "--rm",
    "-T",
    "-e",
    "SPRING_PROFILES_ACTIVE=cli",
    "uam",
    "java",
    "-jar",
    "/app/uam.jar",
    ...args,
  ];
  return execFileSync("docker", compose, { input: stdin, encoding: "utf8" });
}

/**
 * 在栈里的 PostgreSQL 执行 SQL（只用于造 UAM 接口还没提供的数据，如无账号的用户、已停用的用户，以及读取实际账号数）。
 * SQL 经标准输入传入；`-At` 输出无表头、无对齐的纯文本，按行返回。
 */
export function psql(sql: string): string[] {
  const args = [
    "compose",
    "-f",
    path.join(root, "infra/local/docker-compose.stack.yml"),
    "--env-file",
    path.join(root, "infra/local/e2e.env"),
    "exec",
    "-T",
    "postgres",
    "sh",
    "-c",
    'psql -v ON_ERROR_STOP=1 -At -U "$POSTGRES_USER" -d "$POSTGRES_DB"',
  ];
  const out = execFileSync("docker", args, { input: sql, encoding: "utf8" });
  return out.split("\n").filter((line) => line.length > 0);
}

/** 在栈里的 Redis 执行 redis-cli（只读核对 UAM 的会话索引，如 SC-003 的在线用户集合）；按行返回输出。 */
export function redisCli(args: string[]): string[] {
  const compose = [
    "compose",
    "-f",
    path.join(root, "infra/local/docker-compose.stack.yml"),
    "--env-file",
    path.join(root, "infra/local/e2e.env"),
    "exec",
    "-T",
    "redis",
    "redis-cli",
    ...args,
  ];
  return execFileSync("docker", compose, { encoding: "utf8" })
    .split("\n")
    .filter((line) => line.length > 0);
}

/** docker compose 的公共参数（栈文件与 E2E 环境文件）。 */
const composeBase = () => ["compose", "-f", path.join(root, "infra/local/docker-compose.stack.yml"), "--env-file", path.join(root, "infra/local/e2e.env")];

/**
 * 停掉栈里的一个服务（不删容器与数据卷；US11 用它模拟「UAM 暂不可用」）。
 * 调用方必须在 finally 里 `startService` 把它拉起来，否则后续用例都会失败。
 */
export function stopService(service: "uam"): void {
  execFileSync("docker", [...composeBase(), "stop", service], { stdio: "inherit" });
}

/** 重新启动被停掉的服务，并等到它的健康检查通过（不重建、不重新构建镜像、不碰依赖服务）。 */
export function startService(service: "uam"): void {
  execFileSync("docker", [...composeBase(), "up", "-d", "--wait", "--no-build", "--no-recreate", "--no-deps", service], { stdio: "inherit" });
}

// ---------------------------------------------------------------------------------------------------------------------
// US12：服务密钥轮换与配置错误（quickstart S19）
// ---------------------------------------------------------------------------------------------------------------------

const rotateKeyScript = path.join(root, "infra/local/scripts/rotate-key.sh");
const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * 跑轮换脚本的一个动作。必须是异步的：脚本要几十秒，同步等待会卡住测试进程里并行的「持续访问」循环。
 * 返回脚本的标准输出 / 标准错误（失败时把它们带进异常信息）。
 */
async function runRotateKey(action: "rotate" | "restore"): Promise<string> {
  try {
    const { stdout, stderr } = await execFileAsync(rotateKeyScript, [action], { timeout: 15 * 60_000, maxBuffer: 32 * 1024 * 1024 });
    return `${stdout}${stderr}`;
  } catch (error) {
    const e = error as { stdout?: string; stderr?: string; message: string };
    throw new Error(`rotate-key.sh ${action} 失败：${e.message}\n${e.stdout ?? ""}${e.stderr ?? ""}`);
  }
}

/**
 * 不停机轮换服务密钥（`infra/local/scripts/rotate-key.sh rotate`）：给 UAM 加第二个哈希并重建 → Admin Center 换新密钥并重建 →
 * 去掉旧哈希并重建 UAM。结束时栈处于「新密钥」状态，要用 `restoreKey()` 回到 e2e.env。
 */
export function rotateKey(): Promise<string> {
  return runRotateKey("rotate");
}

/** 回到 `infra/local/e2e.env` 的密钥与哈希（`rotate-key.sh restore`）；任何中间状态下都应能调用。 */
export function restoreKey(): Promise<string> {
  return runRotateKey("restore");
}

/** 等 Admin Center 对外可用（登录页返回 200）。重建容器后用。 */
export async function waitForAdminCenter(timeoutMs = 120_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    try {
      const res = await fetch(`${BASE_URL}/login`, { signal: AbortSignal.timeout(3_000), headers: { "x-forwarded-for": "203.0.113.7" } });
      if (res.status === 200) return;
    } catch {
      // 容器还没起来
    }
    if (Date.now() > deadline) throw new Error(`Admin Center 在 ${timeoutMs} ms 内没有恢复`);
    await sleep(500);
  }
}

/**
 * 用指定的服务密钥重建 Admin Center（只动它自己，不重建依赖）。`undefined` 表示回到 e2e.env 里的密钥。
 * 靠 docker compose 的优先级：进程环境变量盖过 `--env-file`，所以传 `UAM_SERVICE_KEY` 即覆盖，不传即用 e2e.env。
 * 调用方必须在 finally 里 `setAdminCenterKey(undefined)`，否则后续用例都会失败。
 */
export async function setAdminCenterKey(value: string | undefined): Promise<void> {
  const env = { ...process.env };
  delete env.UAM_SERVICE_KEY;
  if (value !== undefined) env.UAM_SERVICE_KEY = value;
  await execFileAsync("docker", [...composeBase(), "up", "-d", "--wait", "--no-build", "--no-deps", "admin-center"], { env, timeout: 5 * 60_000 });
  await waitForAdminCenter();
}

/** `infra/local/e2e.env` 里的服务密钥（仅测试用的固定值；用来断言它不出现在日志里）。 */
export function e2eServiceKey(): string {
  const line = fs.readFileSync(path.join(root, "infra/local/e2e.env"), "utf8").split("\n").find((l) => l.startsWith("UAM_SERVICE_KEY="));
  if (!line) throw new Error("infra/local/e2e.env 里没有 UAM_SERVICE_KEY");
  return line.slice("UAM_SERVICE_KEY=".length).trim();
}

/** Admin Center 当前容器的日志（按行）。 */
export async function adminCenterLogs(): Promise<string[]> {
  const { stdout, stderr } = await execFileAsync("docker", [...composeBase(), "logs", "--no-color", "--no-log-prefix", "admin-center"], { maxBuffer: 32 * 1024 * 1024 });
  return `${stdout}${stderr}`.split("\n").filter((line) => line.length > 0);
}

/**
 * 持续收集 Admin Center 的日志：轮换要重建 Admin Center 容器，旧容器的日志随之消失，所以必须在重建期间一直跟着。
 * `docker compose logs -f` 在容器被删时退出，这里在它退出后重新接上（`--since` 起点固定，按整行去重）。
 * `stop()` 返回目前收集到的全部行。
 */
export function captureAdminCenterLogs(): { stop(): Promise<string[]> } {
  const since = new Date().toISOString();
  const lines = new Set<string>();
  let stopped = false;
  let current: ReturnType<typeof spawn> | undefined;

  const loop = (async () => {
    while (!stopped) {
      await new Promise<void>((resolve) => {
        const child = spawn("docker", [...composeBase(), "logs", "-f", "--no-color", "--no-log-prefix", "--since", since, "admin-center"], { stdio: ["ignore", "pipe", "pipe"] });
        current = child;
        let pending = "";
        const feed = (chunk: Buffer) => {
          pending += chunk.toString("utf8");
          const parts = pending.split("\n");
          pending = parts.pop() ?? "";
          for (const line of parts) if (line.length > 0) lines.add(line);
        };
        child.stdout?.on("data", feed);
        child.stderr?.on("data", feed);
        child.on("error", () => resolve());
        child.on("close", () => {
          if (pending.length > 0) lines.add(pending);
          resolve();
        });
      });
      if (!stopped) await sleep(300);
    }
  })();

  return {
    async stop() {
      stopped = true;
      current?.kill();
      await loop;
      return [...lines];
    },
  };
}
