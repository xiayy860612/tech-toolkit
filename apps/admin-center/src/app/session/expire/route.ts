import { NextResponse, type NextRequest } from "next/server";
import { clearSessionCookie } from "@/lib/session/cookie";
import { getSession } from "@/lib/session/get-session";
import { isSafeNextPath } from "@/lib/session/next-path";

/**
 * 页面渲染期间不能写 cookie，所以失效 cookie 的清除放在这里：重新 verify，**仍无效**才清并回登录页。
 * UAM 不可用（unverified）时不清——保留登录状态（FR-009）。
 * `next`（受保护页面的原路径，只接受站内路径）原样带回登录页，登录后回到原页。
 * Location 用相对地址：容器里 `request.url` 的主机可能是 0.0.0.0，不能拿来拼绝对地址。
 */
function redirectTo(location: string): NextResponse {
  return new NextResponse(null, { status: 303, headers: { Location: location, "Cache-Control": "no-store" } });
}

export async function GET(request: NextRequest): Promise<NextResponse> {
  const rawNext = request.nextUrl.searchParams.get("next");
  const next = isSafeNextPath(rawNext) ? rawNext : undefined;

  const state = await getSession();
  if (state.status === "anonymous") {
    const params = new URLSearchParams();
    // 被管理员强制下线（UAM 在 verify 的 401 上给出 reason=terminated）有专门的提示，其余结束原因保持通用提示
    if (state.hadCookie) params.set("reason", state.reason === "terminated" ? "session_terminated" : "session_ended");
    if (next) params.set("next", next);
    const query = params.toString();
    const response = redirectTo(query ? `/login?${query}` : "/login");
    if (state.hadCookie) await clearSessionCookie(response);
    return response;
  }
  // 会话又有效了，或 UAM 暂不可用：都回受保护页面（后者会看到「服务暂不可用」），不清 cookie
  return redirectTo(next ?? "/users");
}
