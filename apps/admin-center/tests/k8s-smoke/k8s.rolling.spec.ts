import { execFile } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import { expect, test } from "@playwright/test";

const execFileAsync = promisify(execFile);
const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * 滚动发布期间不丢会话（对应 compose 栈的 rolling-restart.sh：意外登出必须为 0）。
 * 登录后持续访问受保护页面，同时对 uam 与 admin-center 做 `kubectl rollout restart`：
 * 会话在 UAM 的 Redis 里，换掉任何一个 Pod 都不应该让已登录的人被登出，也不应该有请求失败（PDB + 就绪探针 + preStop + 优雅停机）。
 * 会重启工作负载，默认跳过；设 K8S_SMOKE_ROLLING=1 才执行。
 */
const ACCOUNT = process.env.K8S_TEST_ACCOUNT ?? "k8s-test";
const PASSWORD_FILE =
  process.env.TEST_PASSWORD_FILE ?? path.join(process.env.XDG_CONFIG_HOME ?? path.join(os.homedir(), ".config"), "tech-toolkit", "k8s-test-account-password");
const KUBE_CONTEXT = process.env.K8S_CONTEXT ?? `kind-${process.env.KIND_CLUSTER ?? "cka"}`;
const NAMESPACE = process.env.K8S_NAMESPACE ?? "tech-toolkit";

const kubectl = (...args: string[]) => execFileAsync("kubectl", ["--context", KUBE_CONTEXT, "-n", NAMESPACE, ...args], { maxBuffer: 8 * 1024 * 1024 });
const REDIRECTS = [301, 302, 303, 307, 308];

test.describe("k8s 滚动发布", () => {
  test.skip(process.env.K8S_SMOKE_ROLLING !== "1", "会重启 uam / admin-center：设 K8S_SMOKE_ROLLING=1 才跑");

  test("滚动重启 uam 与 admin-center 期间：已登录用户不被登出，请求不失败", async ({ page }) => {
    test.setTimeout(10 * 60_000);
    await page.goto("/login");
    await page.getByLabel("账号").fill(ACCOUNT);
    await page.getByLabel("密码").fill(fs.readFileSync(PASSWORD_FILE, "utf8").trim());
    await page.getByRole("button", { name: "登录" }).click();
    await expect(page).toHaveURL(/\/users/);

    const stats = { ok: 0, loggedOut: 0, unavailable: 0, failed: [] as string[] };
    let stop = false;
    const poller = (async () => {
      while (!stop) {
        try {
          const res = await page.request.get("/users", { maxRedirects: 0, timeout: 5_000 });
          if (REDIRECTS.includes(res.status())) stats.loggedOut++;
          else if (res.status() !== 200) stats.failed.push(`HTTP ${res.status()}`);
          else if ((await res.text()).includes("服务暂不可用")) stats.unavailable++;
          else stats.ok++;
        } catch (error) {
          stats.failed.push(String(error).split("\n")[0]);
        }
        await sleep(200);
      }
    })();

    await sleep(1_000);
    await kubectl("rollout", "restart", "deployment/uam", "deployment/admin-center");
    await kubectl("rollout", "status", "deployment/uam", "--timeout=300s");
    await kubectl("rollout", "status", "deployment/admin-center", "--timeout=300s");
    await sleep(2_000);
    stop = true;
    await poller;

    console.log(`滚动期间的请求：成功 ${stats.ok}，被登出 ${stats.loggedOut}，服务暂不可用 ${stats.unavailable}，失败 ${stats.failed.length}`);
    expect(stats.ok, "应当有足够多的成功请求，否则这次观察没有意义").toBeGreaterThan(20);
    expect(stats.loggedOut, "意外登出必须为 0").toBe(0);
    expect(stats.unavailable, "不应出现「服务暂不可用」").toBe(0);
    expect(stats.failed, "不应有失败请求").toEqual([]);

    // 滚动结束后会话仍然有效
    await page.goto("/users");
    await expect(page.getByRole("heading", { level: 1, name: "用户管理" })).toBeVisible();
  });
});
