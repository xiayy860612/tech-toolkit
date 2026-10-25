// 造会话：用「真实的登录接口」让 N 个造数账号各登录一次，得到 N 个同时有效的会话（由 seed-data.sh 调用）。
// 经 uam-lb 调 UAM 的 POST /v1/auth/login（Argon2id 校验照常进行），所以会话结构与线上完全一致（含 uam:online 索引）。
// 会话的空闲时限是 30 分钟：造完后应尽快压测，或重新运行本脚本。
import { check, fail } from "k6";
import exec from "k6/execution";
import { LOAD_PASSWORD, clientIp, onlineTotal, pad, uamLogin } from "./lib/common.js";

const SESSIONS = Number(__ENV.SESSIONS || 10000);
const VUS = Number(__ENV.SEED_VUS || 48);

export const options = {
  scenarios: {
    seed: { executor: "shared-iterations", vus: VUS, iterations: SESSIONS, maxDuration: "40m" },
  },
  thresholds: { checks: ["rate==1"] },
  summaryTrendStats: ["avg", "med", "p(95)", "max"],
};

export default function () {
  const i = exec.scenario.iterationInTest + 1;
  const res = uamLogin(`load${pad(i)}`, LOAD_PASSWORD, clientIp(i));
  const ok = check(res, { "登录成功 200": (r) => r.status === 200 });
  // 只打印第一条失败的状态与错误码（不含任何会话标识或密码），便于定位
  if (!ok && i <= VUS) console.error(`登录失败：account=load${pad(i)} HTTP ${res.status} ${String(res.body).slice(0, 160)}`);
}

export function teardown() {
  const total = onlineTotal();
  console.log(`造会话完成：UAM 在线用户总数 = ${total}`);
  if (total < SESSIONS) fail(`在线用户数 ${total} 少于目标 ${SESSIONS}`);
}
