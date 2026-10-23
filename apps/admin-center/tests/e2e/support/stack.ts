import { execFileSync } from "node:child_process";
import path from "node:path";

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
