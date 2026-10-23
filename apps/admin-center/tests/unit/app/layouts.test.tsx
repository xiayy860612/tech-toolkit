import { render, screen } from "@testing-library/react";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import AuthLayout from "@/app/(auth)/layout";
import ShellLayout from "@/app/(shell)/layout";
import RootLayout from "@/app/layout";
import { getSession } from "@/lib/session/get-session";

jest.mock("next/headers", () => ({ cookies: jest.fn() }));
jest.mock("next/navigation", () => ({
  usePathname: () => "/users",
  redirect: jest.fn((to: string) => {
    throw new Error(`NEXT_REDIRECT:${to}`);
  }),
}));
jest.mock("@/lib/session/get-session", () => ({ getSession: jest.fn() }));

function sidebarCookie(value?: string) {
  jest.mocked(cookies).mockResolvedValue({
    get: (name: string) => (name === "sidebar_state" && value ? { name, value } : undefined),
  } as never);
}

beforeEach(() => {
  window.innerWidth = 1280;
  sidebarCookie();
});
afterEach(() => jest.clearAllMocks());

describe("(shell)/layout：读会话三态", () => {
  it("authenticated：渲染带用户名的外壳，且不把会话数据传给浏览器端组件", async () => {
    jest.mocked(getSession).mockResolvedValue({
      status: "authenticated",
      session: {
        user: { id: "u", name: "张三" },
        account: { id: "a", loginName: "zhangsan", kind: "PASSWORD" },
        sessionRef: "r",
        expiresAt: "2026-10-24T12:00:00Z",
      },
    });
    const element = await ShellLayout({ children: <p>内容</p> });
    expect(element.props).toEqual(expect.objectContaining({ status: "authenticated", userName: "张三" }));
    expect(element.props).not.toHaveProperty("session");
    render(element);
    expect(screen.getByRole("navigation", { name: "主导航" })).toBeInTheDocument();
    expect(screen.getByText("内容")).toBeInTheDocument();
  });

  it("sidebar_state=false 时默认收起，其它情况默认展开", async () => {
    jest.mocked(getSession).mockResolvedValue({
      status: "authenticated",
      session: { user: { id: "u", name: "张三" } } as never,
    });
    sidebarCookie("false");
    expect((await ShellLayout({ children: null })).props.defaultSidebarOpen).toBe(false);
    sidebarCookie("true");
    expect((await ShellLayout({ children: null })).props.defaultSidebarOpen).toBe(true);
    sidebarCookie();
    expect((await ShellLayout({ children: null })).props.defaultSidebarOpen).toBe(true);
  });

  it("unverified：保留登录（不重定向），外壳显示「服务暂不可用」", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "unverified" });
    const element = await ShellLayout({ children: <p>内容</p> });
    expect(redirect).not.toHaveBeenCalled();
    render(element);
    expect(screen.getByRole("status")).toHaveTextContent("服务暂不可用");
  });

  it("anonymous 且带过 cookie：经 /session/expire 清掉失效 cookie", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: true });
    await expect(ShellLayout({ children: null })).rejects.toThrow("NEXT_REDIRECT:/session/expire");
  });

  it("anonymous 没有 cookie：直接去 /login", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: false });
    await expect(ShellLayout({ children: null })).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});

describe("(auth)/layout：极简外壳", () => {
  it("只有居中的内容区，没有导航", () => {
    render(
      <AuthLayout>
        <p>登录表单</p>
      </AuthLayout>,
    );
    expect(screen.getByRole("main")).toHaveTextContent("登录表单");
    expect(screen.queryByRole("navigation")).not.toBeInTheDocument();
  });
});

describe("根 layout", () => {
  it("语言为 zh-CN，渲染子内容", () => {
    const element = RootLayout({ children: <p>子内容</p> });
    expect(element.type).toBe("html");
    expect(element.props.lang).toBe("zh-CN");
  });
});
