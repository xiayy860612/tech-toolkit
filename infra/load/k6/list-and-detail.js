// SC-009：10 万用户、1 万个同时有效会话的数据量下，User Manager 与 Session Manager 的列表和详情页 95% 在 2 秒内展示完成。
// 前置：seed-data.sh 已造好数据，且会话仍有效（空闲 30 分钟内）。经 Admin Center 入口（BASE_URL）访问真实页面。
//
// 运行（在仓库根目录）：
//   docker compose -f infra/local/docker-compose.scale.yml --env-file infra/local/e2e.env \
//     --profile load run --rm k6 run /scripts/list-and-detail.js
// 可调参数（-e 传入）：RATE（每秒迭代数，默认 20）、DURATION（默认 60s）、WARMUP（默认 15s）、
//   MIN_SESSIONS（开跑前要求的在线会话数，默认 10000）、MIN_USERS（默认 100000）、KINDS（只压指定页面类型，逗号分隔，
//   可选 users_list / users_search / user_detail / sessions_list / session_detail，默认全部）。
//
// 度量口径：
//   - 先预热 WARMUP（同样的请求，不计入判定），再测 DURATION；判定只看 phase:measure 的请求。
//   - 每次迭代访问一种页面：用户列表（随机页）、用户搜索、用户详情、在线用户列表（随机页）、某在线用户的会话页。
//   - 响应时间 = k6 的 http_req_duration（服务端渲染完成并收到完整响应），不含浏览器渲染。
//   - 成功 = 200 且页面不是「服务暂不可用」；未跟随重定向，被判未登录的 3xx 算失败。
import { check, fail } from "k6";
import exec from "k6/execution";
import http from "k6/http";
import { adminSessionId, getPage, onlineTotal, pad, pageShown, uamHeaders, UAM_URL } from "./lib/common.js";

const RATE = Number(__ENV.RATE || 20);
const DURATION = __ENV.DURATION || "60s";
const WARMUP = __ENV.WARMUP || "15s";
const MIN_SESSIONS = Number(__ENV.MIN_SESSIONS || 10000);
const MIN_USERS = Number(__ENV.MIN_USERS || 100000);
const PAGE_SIZE = 20; // Admin Center 列表每页条数
// KINDS（逗号分隔）可只压其中几类页面，用于定位瓶颈；默认五类轮流各占 1/5
const KINDS = (__ENV.KINDS || "users_list,users_search,user_detail,sessions_list,session_detail").split(",");

function scenario(startTime, duration, phase) {
  return {
    executor: "constant-arrival-rate",
    rate: RATE,
    timeUnit: "1s",
    duration,
    startTime,
    preAllocatedVUs: 40,
    maxVUs: 200,
    exec: "iteration",
    env: { PHASE: phase },
    tags: { phase },
  };
}

const thresholds = {
  // SC-009：95% ≤ 2 秒（总体与每类页面）
  "http_req_duration{phase:measure}": ["p(95)<2000"],
  // 页面正常展示（含 200 且不是降级页）的比例
  "checks{phase:measure}": ["rate>0.99"],
  "http_req_failed{phase:measure}": ["rate<0.01"],
};
for (const kind of KINDS) thresholds[`http_req_duration{phase:measure,kind:${kind}}`] = ["p(95)<2000"];

export const options = {
  scenarios: {
    warmup: scenario("0s", WARMUP, "warmup"),
    measure: scenario(WARMUP, DURATION, "measure"),
  },
  thresholds,
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

export function setup() {
  const sessionId = adminSessionId();
  const online = onlineTotal();
  if (online < MIN_SESSIONS) {
    fail(`在线会话只有 ${online}，少于 ${MIN_SESSIONS}（会话 30 分钟空闲过期？重新运行 seed-data.sh）`);
  }
  // 经 UAM 采样用户标识：用户列表分散取若干页，在线用户取若干页
  const first = http.get(`${UAM_URL}/v1/users?page=1&size=1`, { headers: uamHeaders() });
  const totalUsers = first.json("total");
  if (totalUsers < MIN_USERS) fail(`用户只有 ${totalUsers}，少于 ${MIN_USERS}（先运行 seed-data.sh）`);
  const userIds = [];
  const lastUserPage = Math.ceil(totalUsers / 100);
  for (let k = 0; k < 40; k++) {
    const page = 1 + Math.floor((k * lastUserPage) / 40);
    const res = http.get(`${UAM_URL}/v1/users?page=${page}&size=100`, { headers: uamHeaders() });
    for (const item of res.json("items")) userIds.push(item.id);
  }
  const onlineIds = [];
  const lastOnlinePage = Math.ceil(online / 100);
  for (let k = 0; k < 40; k++) {
    const page = 1 + Math.floor((k * lastOnlinePage) / 40);
    const res = http.get(`${UAM_URL}/v1/online-users?page=${page}&size=100`, { headers: uamHeaders() });
    for (const item of res.json("items")) onlineIds.push(item.userId);
  }
  console.log(`数据量：用户 ${totalUsers}，在线用户（有效会话）${online}；采样 ${userIds.length} 个用户标识、${onlineIds.length} 个在线用户标识`);
  return { sessionId, totalUsers, online, userIds, onlineIds };
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

export function iteration(data) {
  const phase = __ENV.PHASE;
  const kind = KINDS[exec.scenario.iterationInTest % KINDS.length];
  let path;
  switch (kind) {
    case "users_list":
      path = `/users?page=${1 + Math.floor(Math.random() * Math.ceil(data.totalUsers / PAGE_SIZE))}`;
      break;
    case "users_search":
      path = `/users?q=${encodeURIComponent("load" + pad(1 + Math.floor(Math.random() * 100000)))}`;
      break;
    case "user_detail":
      path = `/users/${pick(data.userIds)}`;
      break;
    case "sessions_list":
      path = `/sessions?page=${1 + Math.floor(Math.random() * Math.ceil(data.online / PAGE_SIZE))}`;
      break;
    default:
      path = `/sessions/${pick(data.onlineIds)}`;
  }
  const res = getPage(path, data.sessionId, kind, { phase });
  check(res, { "页面正常展示（200 且非降级页）": (r) => pageShown(r) }, { phase, kind });
}
