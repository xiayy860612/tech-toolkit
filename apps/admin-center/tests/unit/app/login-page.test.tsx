import { render, screen } from "@testing-library/react";
import { redirect } from "next/navigation";
import LoginPage from "@/app/(auth)/login/page";
import ShellIndexPage from "@/app/(shell)/page";
import { getSession } from "@/lib/session/get-session";

jest.mock("next/navigation", () => ({
  redirect: jest.fn((to: string) => {
    throw new Error(`NEXT_REDIRECT:${to}`);
  }),
}));
jest.mock("@/lib/session/get-session", () => ({ getSession: jest.fn() }));
jest.mock("@/app/(auth)/login/actions", () => ({ login: jest.fn() }));

const props = (search: { next?: string | string[]; reason?: string } = {}) => ({ searchParams: Promise.resolve(search) });

afterEach(() => jest.clearAllMocks());

describe("/login 页面", () => {
  it("已登录访问：重定向 /users（不管 next）", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "authenticated", session: {} as never });
    await expect(LoginPage(props({ next: "/sessions" }))).rejects.toThrow("NEXT_REDIRECT:/users");
  });

  it("匿名：渲染登录表单，next 缺省为 /users", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: false });
    const { container } = render(await LoginPage(props()));
    expect(screen.getByRole("button", { name: "登录" })).toBeInTheDocument();
    expect(container.querySelector('input[name="next"]')).toHaveValue("/users");
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(redirect).not.toHaveBeenCalled();
  });

  it("next 是安全的站内路径：带进表单；站外路径被忽略", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: false });
    const ok = render(await LoginPage(props({ next: "/users/abc?x=1" })));
    expect(ok.container.querySelector('input[name="next"]')).toHaveValue("/users/abc?x=1");
    ok.unmount();
    const bad = render(await LoginPage(props({ next: "//evil.example" })));
    expect(bad.container.querySelector('input[name="next"]')).toHaveValue("/users");
  });

  it("reason=session_ended：提示「会话已结束，请重新登录。」", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: true });
    render(await LoginPage(props({ reason: "session_ended" })));
    expect(screen.getByRole("status")).toHaveTextContent("会话已结束，请重新登录。");
  });

  it("其它 reason 不产生提示", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: false });
    render(await LoginPage(props({ reason: "whatever" })));
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("UAM 暂不可用（unverified）：仍显示登录表单", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "unverified" });
    render(await LoginPage(props()));
    expect(screen.getByRole("button", { name: "登录" })).toBeInTheDocument();
  });
});

describe("(shell)/page：默认页", () => {
  it("重定向到 /users", () => {
    expect(() => ShellIndexPage()).toThrow("NEXT_REDIRECT:/users");
  });
});
