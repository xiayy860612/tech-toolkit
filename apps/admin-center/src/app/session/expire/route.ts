import { NextResponse } from "next/server";
import { clearSessionCookie } from "@/lib/session/cookie";
import { getSession } from "@/lib/session/get-session";

/**
 * 页面渲染期间不能写 cookie，所以失效 cookie 的清除放在这里：重新 verify，**仍无效**才清并回登录页。
 * UAM 不可用（unverified）时不清——保留登录状态（FR-009）。
 * Location 用相对地址：容器里 `request.url` 的主机可能是 0.0.0.0，不能拿来拼绝对地址。
 */
function redirectTo(location: string): NextResponse {
  return new NextResponse(null, { status: 303, headers: { Location: location, "Cache-Control": "no-store" } });
}

export async function GET(): Promise<NextResponse> {
  const state = await getSession();
  if (state.status === "anonymous") {
    const response = redirectTo(state.hadCookie ? "/login?reason=session_ended" : "/login");
    if (state.hadCookie) await clearSessionCookie(response);
    return response;
  }
  // 会话又有效了，或 UAM 暂不可用：都回受保护页面（后者会看到「服务暂不可用」），不清 cookie
  return redirectTo("/users");
}
