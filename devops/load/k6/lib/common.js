// k6 脚本共用：登录取会话标识、采样用户标识、带 cookie 访问 Admin Center 页面。
// 只在 docker-compose.scale.yml 的 k6 服务里运行（与入口 nginx 和 uam-lb 同网）：
//   BASE_URL  Admin Center 入口（默认 http://nginx:80）
//   UAM_URL   UAM 地址（经 uam-lb，默认 http://uam-lb:8080；UAM 不对宿主机开放）
//   UAM_SERVICE_KEY  e2e.env 里的本地测试服务密钥（由 compose 注入，不入脚本）
// 测试账号 / 密码是 devops/local/scripts/bootstrap.sh 的本地假值，与 e2e.env 同属测试数据。
import http from "k6/http";
import { fail } from "k6";

export const BASE_URL = __ENV.BASE_URL || "http://nginx:80";
export const UAM_URL = __ENV.UAM_URL || "http://uam-lb:8080";
export const ADMIN_ACCOUNT = __ENV.ADMIN_ACCOUNT || "admin";
export const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || "E2e-Admin-Pass-1";
// 造数用户的统一测试密码（等于上面管理员的测试密码：造数时复用管理员凭据的 Argon2id 哈希）
export const LOAD_PASSWORD = __ENV.LOAD_PASSWORD || ADMIN_PASSWORD;

export function pad(n, width = 6) {
  return String(n).padStart(width, "0");
}

export function uamHeaders(extra = {}) {
  return Object.assign(
    { Authorization: `Bearer ${__ENV.UAM_SERVICE_KEY}`, "Content-Type": "application/json", Accept: "application/json" },
    extra,
  );
}

// 每个调用方用不同的来源地址，避免登录失败限制（按「账号 + 来源地址」计数）互相影响
export function clientIp(n) {
  const v = Math.abs(n | 0);
  return `10.${(v >> 16) & 255}.${(v >> 8) & 255}.${v & 255}`;
}

export function uamLogin(account, password, ip) {
  return http.post(`${UAM_URL}/v1/auth/login`, JSON.stringify({ accountName: account, password, clientIp: ip }), {
    headers: uamHeaders(),
    tags: { name: "uam_login" },
  });
}

export function adminSessionId() {
  const res = uamLogin(ADMIN_ACCOUNT, ADMIN_PASSWORD, "10.250.0.1");
  if (res.status !== 200) fail(`管理员登录 UAM 失败：HTTP ${res.status}`);
  return res.json("sessionId");
}

export function onlineTotal() {
  const res = http.get(`${UAM_URL}/v1/online-users?size=1`, { headers: uamHeaders(), tags: { name: "uam_online_total" } });
  if (res.status !== 200) fail(`读取在线用户总数失败：HTTP ${res.status}`);
  return res.json("total");
}

export function cookieHeaders(sessionId) {
  return { Cookie: `${__ENV.SESSION_COOKIE_NAME || "asid"}=${sessionId}` };
}

// 带会话 cookie 访问 Admin Center 页面；不跟随重定向（被判未登录会表现为 3xx，不能算成功）。
// 成功 = 200 且页面里没有「服务暂不可用」（那是 UAM 不可达时 200 的降级页）。
export function getPage(path, sessionId, kind, extraTags = {}) {
  return http.get(`${BASE_URL}${path}`, {
    headers: cookieHeaders(sessionId),
    redirects: 0,
    tags: Object.assign({ kind, name: kind }, extraTags),
  });
}

export function pageShown(res) {
  return res.status === 200 && !String(res.body).includes("服务暂不可用");
}
