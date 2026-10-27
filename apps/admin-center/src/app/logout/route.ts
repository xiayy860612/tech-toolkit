import { NextResponse, type NextRequest } from "next/server";
import { originForbiddenResponse, isSameOriginRequest } from "@/lib/security/origin-check";
import { clearSessionCookie, readSessionId } from "@/lib/session/cookie";
import { logout } from "@/lib/uam/client";
import { UamError } from "@/lib/uam/errors";

/**
 * 退出登录（FR-010、contracts/web-routes.md §4）：同源校验（失败 403）→ 调 UAM `logout` → 清 cookie → 303 到 `/login`。
 * - 只接受 POST（顶栏用户菜单里的表单）；不导出 GET，Next 对其回 405，所以链接、图片、预取都不能触发退出。
 * - UAM 不可用或拒绝了调用时**仍清 cookie 并回登录页**：用户必须总能从浏览器侧退出。此时 UAM 里的会话
 *   要等空闲 / 绝对到期才失效，所以写一条不含会话标识的告警日志，且不向用户声称 UAM 侧已终止。
 * - Location 用相对地址：容器里 `request.url` 的主机可能是 0.0.0.0，不能拿来拼绝对地址。
 */
export async function POST(request: NextRequest): Promise<Response> {
  if (!isSameOriginRequest(request.method, request.headers)) return originForbiddenResponse();

  const sessionId = await readSessionId();
  if (sessionId) {
    try {
      await logout(sessionId);
    } catch (error) {
      if (!(error instanceof UamError)) throw error; // 非 UAM 错误是程序问题（如配置缺失），不要吞掉
      console.warn(
        `[logout] UAM 未确认会话已终止（${error.kind}${error.status ? `，HTTP ${error.status}` : ""}），已仅清除浏览器端 cookie；` +
          "UAM 侧会话将在空闲 / 绝对到期时失效",
      );
    }
  }

  const response = new NextResponse(null, { status: 303, headers: { Location: "/login", "Cache-Control": "no-store" } });
  await clearSessionCookie(response);
  return response;
}
