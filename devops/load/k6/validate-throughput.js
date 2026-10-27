// SC-011：相同响应时间目标下，实例数 1 → 4，总吞吐 ≥ 单实例的 3.2 倍。方法说明与判定口径见 ../SC-011.md。
//
// 本脚本只度量「当前拓扑」的稳态吞吐（一次运行 = 一个实例数、一轮）：
//   - 闭环负载：固定 VUS 个虚拟用户不停地访问只读页面（用户列表、在线用户、用户详情），无思考时间，把系统压到饱和；
//   - 先预热 WARMUP，再测 DURATION；只统计 phase:measure；
//   - 输出一行机器可读结果：SC011_RESULT {"instances":N,"rps":...,"p95_ms":...,"error_rate":...}。
//   - 若给了 BASELINE_RPS（单实例各轮的中位数），则同时计算倍数并给出该轮的判定；最终判定以 SC-011.md 的多轮中位数为准。
//
// 运行示例（INSTANCES 只是写进结果里的标签，由部署平台实际调整实例数）：
//   docker compose -f devops/local/docker-compose.scale.yml --env-file devops/local/e2e.env \
//     --profile load run --rm k6 run -e INSTANCES=1 -e VUS=64 /scripts/validate-throughput.js
// 可调参数：VUS（默认 64）、DURATION（默认 120s）、WARMUP（默认 30s）、RT_TARGET_MS（p95 目标，默认 2000）、
//   BASELINE_RPS、TARGET_RATIO（默认 3.2）、SESSION_ID（给定则不经 UAM 登录；压测环境无法直连 UAM 时使用，此时用户采样退化，见 setup）。
import { check } from "k6";
import exec from "k6/execution";
import http from "k6/http";
import { adminSessionId, getPage, pageShown, uamHeaders, UAM_URL } from "./lib/common.js";

const VUS = Number(__ENV.VUS || 64);
const DURATION = __ENV.DURATION || "120s";
const WARMUP = __ENV.WARMUP || "30s";
const RT_TARGET_MS = Number(__ENV.RT_TARGET_MS || 2000);
const TARGET_RATIO = Number(__ENV.TARGET_RATIO || 3.2);
const INSTANCES = Number(__ENV.INSTANCES || 0);
const PAGE_SIZE = 20;

// 把 k6 的时长写法（120s、2m、1m30s、1h）换算成秒，用来算 rps；其他写法（如 ms、纯数字）直接报错，避免 NaN 静默进入结果
function durationSeconds(text) {
  const value = String(text);
  if (!/^(\d+(\.\d+)?[hms])+$/.test(value)) {
    throw new Error(`DURATION 只支持 s / m / h 单位（如 120s、2m、1m30s），收到：${value}`);
  }
  const unit = { h: 3600, m: 60, s: 1 };
  let total = 0;
  for (const m of value.matchAll(/(\d+(?:\.\d+)?)([hms])/g)) total += Number(m[1]) * unit[m[2]];
  return total;
}
const DURATION_SECONDS = durationSeconds(DURATION);

function scenario(startTime, duration, phase) {
  return { executor: "constant-vus", vus: VUS, duration, startTime, exec: "iteration", env: { PHASE: phase }, tags: { phase } };
}

export const options = {
  scenarios: {
    warmup: scenario("0s", WARMUP, "warmup"),
    measure: scenario(WARMUP, DURATION, "measure"),
  },
  thresholds: {
    // 「响应时间目标不变」：测量阶段 p95 不得超过目标
    "http_req_duration{phase:measure}": [`p(95)<${RT_TARGET_MS}`],
    "http_req_failed{phase:measure}": ["rate<0.01"],
    // 子指标只有在被阈值引用时才会出现在汇总里；handleSummary 要读它来算吞吐，所以挂一个恒真的阈值
    "http_reqs{phase:measure}": ["count>=0"],
  },
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

export function setup() {
  const sessionId = __ENV.SESSION_ID || adminSessionId();
  // 用户标识采样需要直连 UAM；压测环境连不上（给了 SESSION_ID 的场景）时退化：只访问用户列表第 1 页，详情页换成列表页
  const res = http.get(`${UAM_URL}/v1/users?page=1&size=100`, { headers: uamHeaders() });
  if (res.status !== 200) {
    console.warn("无法直连 UAM 采样用户：用户列表只访问第 1 页，用户详情改为访问列表页（结果里请注明）");
    return { sessionId, userIds: [], totalUsers: 1 };
  }
  return { sessionId, userIds: res.json("items").map((u) => u.id), totalUsers: res.json("total") };
}

export function iteration(data) {
  const phase = __ENV.PHASE;
  const n = exec.scenario.iterationInTest;
  const choice = n % 3;
  let kind, path;
  if (choice === 0) {
    kind = "users_list";
    path = `/users?page=${1 + (n % Math.max(1, Math.ceil(data.totalUsers / PAGE_SIZE)))}`;
  } else if (choice === 1) {
    kind = "sessions_list";
    path = `/sessions?page=1`;
  } else if (data.userIds.length > 0) {
    kind = "user_detail";
    path = `/users/${data.userIds[n % data.userIds.length]}`;
  } else {
    kind = "users_list";
    path = "/users?page=1";
  }
  const res = getPage(path, data.sessionId, kind, { phase });
  check(res, { "页面正常展示（200 且非降级页）": (r) => pageShown(r) }, { phase, kind });
}

export function handleSummary(data) {
  const reqs = data.metrics["http_reqs{phase:measure}"];
  const dur = data.metrics["http_req_duration{phase:measure}"];
  const failed = data.metrics["http_req_failed{phase:measure}"];
  const seconds = DURATION_SECONDS;
  const rps = reqs ? reqs.values.count / seconds : 0;
  const result = {
    instances: INSTANCES,
    vus: VUS,
    duration_s: seconds,
    rps: Number(rps.toFixed(2)),
    requests: reqs ? reqs.values.count : 0,
    avg_ms: dur ? Number(dur.values.avg.toFixed(1)) : null,
    med_ms: dur ? Number(dur.values.med.toFixed(1)) : null,
    p90_ms: dur ? Number(dur.values["p(90)"].toFixed(1)) : null,
    p95_ms: dur ? Number(dur.values["p(95)"].toFixed(1)) : null,
    p99_ms: dur ? Number(dur.values["p(99)"].toFixed(1)) : null,
    max_ms: dur ? Number(dur.values.max.toFixed(1)) : null,
    error_rate: failed ? Number(failed.values.rate.toFixed(4)) : null,
  };
  let text = `\nSC011_RESULT ${JSON.stringify(result)}\n`;
  if (__ENV.BASELINE_RPS) {
    const baseline = Number(__ENV.BASELINE_RPS);
    const ratio = rps / baseline;
    const ok = ratio >= TARGET_RATIO && result.p95_ms !== null && result.p95_ms < RT_TARGET_MS && result.error_rate < 0.01;
    text += `SC011_RATIO ${ratio.toFixed(2)}（目标 ≥ ${TARGET_RATIO}；本轮${ok ? "达标" : "未达标"}；最终判定见 SC-011.md 的多轮中位数）\n`;
  }
  return { stdout: text };
}
