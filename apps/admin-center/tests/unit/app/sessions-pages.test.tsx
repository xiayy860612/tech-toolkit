import { render, screen, within } from "@testing-library/react";
import { redirect } from "next/navigation";
import SessionsPage from "@/app/(shell)/sessions/page";
import UserSessionsPage from "@/app/(shell)/sessions/[userId]/page";
import { getOperator } from "@/lib/session/get-session";
import { getUser, listOnlineUsers, listUserSessions } from "@/lib/uam/client";
import { UamError } from "@/lib/uam/errors";
import { makeOnlineUser, makeSession, SESSION_REFS } from "../../support/sessions-fixtures";
import { makeDetail, USER_IDS } from "../../support/users-fixtures";

jest.mock("next/navigation", () => ({
  redirect: jest.fn((to: string) => {
    throw new Error(`NEXT_REDIRECT:${to}`);
  }),
}));
jest.mock("@/lib/uam/client", () => ({ listOnlineUsers: jest.fn(), listUserSessions: jest.fn(), getUser: jest.fn() }));
jest.mock("@/lib/session/get-session", () => ({ getOperator: jest.fn() }));
jest.mock("@/app/(shell)/users/[userId]/actions", () => ({ disableUser: jest.fn(), enableUser: jest.fn() }));

const operator = { userId: USER_IDS.zhang, accountId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", sessionRef: SESSION_REFS.one };

type Param = string | string[] | undefined;
const listProps = (search: { page?: Param } = {}) => ({ searchParams: Promise.resolve(search) });
const detailProps = (userId: string) => ({ params: Promise.resolve({ userId }) });
const onlinePage = (items = [makeOnlineUser()], total = items.length, p = 1) => ({ items, page: p, size: 20, total });

beforeEach(() => {
  jest.mocked(getOperator).mockResolvedValue(operator);
});
afterEach(() => jest.clearAllMocks());

describe("/sessions 在线用户列表页", () => {
  it("取第 1 页：h1「会话管理」、面包屑当前页、在线用户表格", async () => {
    jest.mocked(listOnlineUsers).mockResolvedValue(onlinePage([makeOnlineUser(), makeOnlineUser({ userId: USER_IDS.li, userName: "李四" })]));
    render(await SessionsPage(listProps()));
    expect(listOnlineUsers).toHaveBeenCalledWith({ page: 1 });
    expect(screen.getByRole("heading", { level: 1, name: "会话管理" })).toBeInTheDocument();
    expect(within(screen.getByRole("navigation", { name: "面包屑" })).getByText("会话管理")).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("table", { name: "在线用户列表" })).toBeInTheDocument();
  });

  it("运维人员自己所在的用户行带「你」", async () => {
    jest.mocked(listOnlineUsers).mockResolvedValue(onlinePage([makeOnlineUser({ userId: USER_IDS.zhang })]));
    render(await SessionsPage(listProps()));
    expect(screen.getAllByText("你").length).toBeGreaterThan(0);
  });

  it("取不到操作人（会话状态由外壳处理）时仍渲染列表，只是没有「你」", async () => {
    jest.mocked(getOperator).mockResolvedValue(null);
    jest.mocked(listOnlineUsers).mockResolvedValue(onlinePage([makeOnlineUser({ userId: USER_IDS.zhang })]));
    render(await SessionsPage(listProps()));
    expect(screen.getByRole("table", { name: "在线用户列表" })).toBeInTheDocument();
    expect(screen.queryByText("你")).not.toBeInTheDocument();
  });

  it("page 传给 UAM；分页按 UAM 返回的 size 计算", async () => {
    jest.mocked(listOnlineUsers).mockResolvedValue(onlinePage([makeOnlineUser()], 45, 2));
    render(await SessionsPage(listProps({ page: "2" })));
    expect(listOnlineUsers).toHaveBeenCalledWith({ page: 2 });
    expect(screen.getByRole("navigation", { name: "分页" })).toHaveTextContent("第 2 / 3 页");
  });

  it.each([["abc"], ["0"], ["-3"], ["1.5"], [""], ["99999999999"]])("非法 page=%p 回到第 1 页", async (raw) => {
    jest.mocked(listOnlineUsers).mockResolvedValue(onlinePage());
    await SessionsPage(listProps({ page: raw }));
    expect(listOnlineUsers).toHaveBeenCalledWith({ page: 1 });
  });

  it("page 为数组时取第一项", async () => {
    jest.mocked(listOnlineUsers).mockResolvedValue(onlinePage());
    await SessionsPage(listProps({ page: ["2", "3"] }));
    expect(listOnlineUsers).toHaveBeenCalledWith({ page: 2 });
  });

  it("页码超过最后一页（如用户下线后）：重定向到最后一页", async () => {
    jest.mocked(listOnlineUsers).mockResolvedValue({ items: [], page: 9, size: 20, total: 45 });
    await expect(SessionsPage(listProps({ page: "9" }))).rejects.toThrow("NEXT_REDIRECT:/sessions?page=3");
    expect(redirect).toHaveBeenCalledWith("/sessions?page=3");
  });

  it("没有在线用户：空态「当前没有在线用户」（不重定向）", async () => {
    jest.mocked(listOnlineUsers).mockResolvedValue(onlinePage([], 0));
    render(await SessionsPage(listProps({ page: "3" })));
    expect(screen.getByText("当前没有在线用户")).toBeInTheDocument();
    expect(redirect).not.toHaveBeenCalled();
  });

  it.each(["unavailable", "misconfigured"] as const)("UAM %s：读错误态，保留页头，重试链接带原页码", async (kind) => {
    jest.mocked(listOnlineUsers).mockRejectedValue(new UamError(kind, "x"));
    render(await SessionsPage(listProps({ page: "2" })));
    expect(screen.getByText("服务暂不可用，请稍后重试")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: "会话管理" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "重试" })).toHaveAttribute("href", "/sessions?page=2");
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("非 UAM 错误是程序问题：原样抛出", async () => {
    jest.mocked(listOnlineUsers).mockRejectedValue(new TypeError("boom"));
    await expect(SessionsPage(listProps())).rejects.toThrow("boom");
  });
});

describe("/sessions/[userId] 用户会话页", () => {
  const setup = (sessions = [makeSession({ current: true }), makeSession({ sessionRef: SESSION_REFS.two, accountName: "lisi.mobile" })]) => {
    jest.mocked(getUser).mockResolvedValue(makeDetail({ id: USER_IDS.li, name: "李四" }));
    jest.mocked(listUserSessions).mockResolvedValue(sessions);
  };

  it("带操作人读取会话；h1「{用户名} 的会话」；面包屑「会话管理 / 用户名」；会话卡片含「当前会话」", async () => {
    setup();
    render(await UserSessionsPage(detailProps(USER_IDS.li)));
    expect(listUserSessions).toHaveBeenCalledWith(USER_IDS.li, operator);
    expect(getUser).toHaveBeenCalledWith(USER_IDS.li);
    expect(screen.getByRole("heading", { level: 1, name: "李四 的会话" })).toBeInTheDocument();
    const crumbs = screen.getByRole("navigation", { name: "面包屑" });
    expect(within(crumbs).getByRole("link", { name: "会话管理" })).toHaveAttribute("href", "/sessions");
    expect(within(crumbs).getByText("李四")).toHaveAttribute("aria-current", "page");
    expect(within(screen.getByRole("list", { name: "会话列表" })).getAllByRole("listitem")).toHaveLength(2);
    expect(screen.getAllByText("当前会话")).toHaveLength(1);
  });

  it("用户没有会话：空态与返回在线用户", async () => {
    setup([]);
    render(await UserSessionsPage(detailProps(USER_IDS.li)));
    expect(screen.getByText("该用户当前没有在线会话")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "返回在线用户" })).toHaveAttribute("href", "/sessions");
  });

  it("取不到操作人（外壳会处理会话状态）：不渲染内容，也不请求 UAM", async () => {
    jest.mocked(getOperator).mockResolvedValue(null);
    expect(await UserSessionsPage(detailProps(USER_IDS.li))).toBeNull();
    expect(listUserSessions).not.toHaveBeenCalled();
    expect(getUser).not.toHaveBeenCalled();
  });

  it("格式非法的 userId：「用户不存在」，不请求 UAM", async () => {
    render(await UserSessionsPage(detailProps("not-a-uuid")));
    expect(screen.getByRole("heading", { level: 1, name: "用户不存在" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "返回用户列表" })).toBeInTheDocument();
    expect(within(screen.getByRole("navigation", { name: "面包屑" })).getByText("用户不存在")).toHaveAttribute("aria-current", "page");
    expect(listUserSessions).not.toHaveBeenCalled();
    expect(getUser).not.toHaveBeenCalled();
    expect(screen.queryByText("服务暂不可用，请稍后重试")).not.toBeInTheDocument();
  });

  it.each([
    ["会话接口", () => jest.mocked(listUserSessions)],
    ["用户接口", () => jest.mocked(getUser)],
  ])("%s 返回 404 user_not_found：「用户不存在」", async (_name, target) => {
    setup();
    target().mockRejectedValue(new UamError("problem", "x", { status: 404, code: "user_not_found" }));
    render(await UserSessionsPage(detailProps(USER_IDS.li)));
    expect(screen.getByText("该用户可能已被移除，或链接有误。")).toBeInTheDocument();
  });

  it("UAM 不可用：读错误态，重试链接指回本页，不显示「用户不存在」", async () => {
    setup();
    jest.mocked(listUserSessions).mockRejectedValue(new UamError("unavailable", "x"));
    render(await UserSessionsPage(detailProps(USER_IDS.li)));
    expect(screen.getByText("服务暂不可用，请稍后重试")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "重试" })).toHaveAttribute("href", `/sessions/${USER_IDS.li}`);
    expect(screen.queryByText("用户不存在")).not.toBeInTheDocument();
  });

  it("非 UAM 错误：原样抛出", async () => {
    setup();
    jest.mocked(listUserSessions).mockRejectedValue(new TypeError("boom"));
    await expect(UserSessionsPage(detailProps(USER_IDS.li))).rejects.toThrow("boom");
  });
});
