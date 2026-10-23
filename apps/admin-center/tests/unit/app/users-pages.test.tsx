import { render, screen, within } from "@testing-library/react";
import { redirect } from "next/navigation";
import UserDetailPage from "@/app/(shell)/users/[userId]/page";
import UsersPage from "@/app/(shell)/users/page";
import { getUser, listUsers } from "@/lib/uam/client";
import { UamError } from "@/lib/uam/errors";
import { makeAccount, makeDetail, makeSummary, USER_IDS } from "../../support/users-fixtures";

jest.mock("next/navigation", () => ({
  redirect: jest.fn((to: string) => {
    throw new Error(`NEXT_REDIRECT:${to}`);
  }),
}));
jest.mock("@/lib/uam/client", () => ({ listUsers: jest.fn(), getUser: jest.fn() }));

type Param = string | string[] | undefined;
const listProps = (search: { q?: Param; page?: Param } = {}) => ({ searchParams: Promise.resolve(search) });
const detailProps = (userId: string, search: { created?: Param } = {}) => ({
  params: Promise.resolve({ userId }),
  searchParams: Promise.resolve(search),
});
const page = (items = [makeSummary()], total = items.length, p = 1) => ({ items, page: p, size: 20, total });

afterEach(() => jest.clearAllMocks());

describe("/users 列表页", () => {
  it("取第 1 页：h1「用户管理」、面包屑当前页、搜索框、列表", async () => {
    jest.mocked(listUsers).mockResolvedValue(page([makeSummary({ name: "张三" }), makeSummary({ id: USER_IDS.li, name: "李四" })]));
    render(await UsersPage(listProps()));
    expect(listUsers).toHaveBeenCalledWith({ query: "", page: 1 });
    expect(screen.getByRole("heading", { level: 1, name: "用户管理" })).toBeInTheDocument();
    expect(within(screen.getByRole("navigation", { name: "面包屑" })).getByText("用户管理")).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("searchbox")).toHaveValue("");
    expect(screen.getByRole("table", { name: "用户列表" })).toBeInTheDocument();
  });

  it("页头有「新建用户」入口，指向 /users/new（UAM 不可用时也在）", async () => {
    jest.mocked(listUsers).mockResolvedValue(page());
    const ok = render(await UsersPage(listProps()));
    expect(screen.getByRole("link", { name: "新建用户" })).toHaveAttribute("href", "/users/new");
    ok.unmount();

    jest.mocked(listUsers).mockRejectedValue(new UamError("unavailable", "x"));
    render(await UsersPage(listProps()));
    expect(screen.getByRole("link", { name: "新建用户" })).toHaveAttribute("href", "/users/new");
  });

  it("q 与 page 传给 UAM，搜索框回填；q 去首尾空白", async () => {
    jest.mocked(listUsers).mockResolvedValue(page([makeSummary()], 45, 2));
    render(await UsersPage(listProps({ q: "  LiSi ", page: "2" })));
    expect(listUsers).toHaveBeenCalledWith({ query: "LiSi", page: 2 });
    expect(screen.getByRole("searchbox")).toHaveValue("LiSi");
    expect(screen.getByRole("navigation", { name: "分页" })).toHaveTextContent("第 2 / 3 页");
  });

  it.each([["abc"], ["0"], ["-3"], ["1.5"], [""], ["99999999999"], [["2", "3"]]])("非法 page=%p 回到第 1 页（数组取第一项仅在合法时生效）", async (raw) => {
    jest.mocked(listUsers).mockResolvedValue(page());
    render(await UsersPage(listProps({ page: raw })));
    const expected = Array.isArray(raw) ? 2 : 1;
    expect(listUsers).toHaveBeenCalledWith({ query: "", page: expected });
  });

  it("q 过长截断到 64 字符、去掉控制字符；q 为数组取第一项", async () => {
    jest.mocked(listUsers).mockResolvedValue(page());
    await UsersPage(listProps({ q: "a\u0000b\nc" + "x".repeat(100) }));
    const first = jest.mocked(listUsers).mock.calls[0][0]!;
    expect(first.query).toHaveLength(64);
    expect(first.query).toMatch(/^abcx+$/);

    await UsersPage(listProps({ q: ["first", "second"] }));
    expect(jest.mocked(listUsers).mock.calls[1][0]).toEqual({ query: "first", page: 1 });
  });

  it("页码超过最后一页（如删除后）：重定向到最后一页并保留搜索词", async () => {
    jest.mocked(listUsers).mockResolvedValue({ items: [], page: 9, size: 20, total: 45 });
    await expect(UsersPage(listProps({ q: "li", page: "9" }))).rejects.toThrow("NEXT_REDIRECT:/users?q=li&page=3");
    expect(redirect).toHaveBeenCalledWith("/users?q=li&page=3");
  });

  it("没有任何用户：空态；搜索无结果：无结果态（不重定向）", async () => {
    jest.mocked(listUsers).mockResolvedValue(page([], 0));
    const empty = render(await UsersPage(listProps()));
    expect(screen.getByText("还没有用户")).toBeInTheDocument();
    empty.unmount();

    jest.mocked(listUsers).mockResolvedValue(page([], 0));
    render(await UsersPage(listProps({ q: "zzz", page: "3" })));
    expect(screen.getByText("没有找到匹配「zzz」的用户")).toBeInTheDocument();
    expect(redirect).not.toHaveBeenCalled();
  });

  it.each(["unavailable", "misconfigured"] as const)("UAM %s：读错误态「服务暂不可用，请稍后重试」，保留页头与重试链接（带原搜索词与页码）", async (kind) => {
    jest.mocked(listUsers).mockRejectedValue(new UamError(kind, "x"));
    render(await UsersPage(listProps({ q: "li", page: "2" })));
    expect(screen.getByText("服务暂不可用，请稍后重试")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: "用户管理" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "重试" })).toHaveAttribute("href", "/users?q=li&page=2");
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("UAM 返回其它业务问题码：按 §5 文案显示", async () => {
    jest.mocked(listUsers).mockRejectedValue(new UamError("problem", "x", { code: "brand_new_code" }));
    render(await UsersPage(listProps()));
    expect(screen.getByText("服务暂不可用，请稍后重试")).toBeInTheDocument();
  });

  it("非 UAM 错误是程序问题：原样抛出", async () => {
    jest.mocked(listUsers).mockRejectedValue(new TypeError("boom"));
    await expect(UsersPage(listProps())).rejects.toThrow("boom");
  });
});

describe("/users/[userId] 详情页", () => {
  it("渲染详情；面包屑「用户管理 / 用户名」", async () => {
    jest.mocked(getUser).mockResolvedValue(makeDetail());
    render(await UserDetailPage(detailProps(USER_IDS.li)));
    expect(getUser).toHaveBeenCalledWith(USER_IDS.li);
    expect(screen.getByRole("heading", { level: 1, name: "李四" })).toBeInTheDocument();
    const crumbs = screen.getByRole("navigation", { name: "面包屑" });
    expect(within(crumbs).getByRole("link", { name: "用户管理" })).toHaveAttribute("href", "/users");
    expect(within(crumbs).getByText("李四")).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("heading", { level: 2, name: "关联账号（2）" })).toBeInTheDocument();
  });

  it("?created=1（新建成功后的跳转）：显示一次性提示「用户已创建」；其它取值不显示", async () => {
    jest.mocked(getUser).mockResolvedValue(makeDetail());
    const shown = render(await UserDetailPage(detailProps(USER_IDS.li, { created: "1" })));
    expect(screen.getByRole("status")).toHaveTextContent("用户已创建");
    shown.unmount();

    for (const created of [undefined, "0", "true", ["1", "1"]]) {
      const view = render(await UserDetailPage(detailProps(USER_IDS.li, { created })));
      expect(screen.queryByRole("status")).not.toBeInTheDocument();
      view.unmount();
    }
  });

  it("无账号用户：空态", async () => {
    jest.mocked(getUser).mockResolvedValue(makeDetail({ accounts: [] }));
    render(await UserDetailPage(detailProps(USER_IDS.li)));
    expect(screen.getByText("该用户暂无关联账号")).toBeInTheDocument();
  });

  it("已停用用户：提示", async () => {
    jest.mocked(getUser).mockResolvedValue(makeDetail({ status: "DISABLED", accounts: [makeAccount()] }));
    render(await UserDetailPage(detailProps(USER_IDS.li)));
    expect(screen.getByRole("status")).toHaveTextContent("该用户已停用");
  });

  it("UAM 404 user_not_found：「用户不存在」与返回列表的链接，面包屑当前页为「用户不存在」", async () => {
    jest.mocked(getUser).mockRejectedValue(new UamError("problem", "x", { status: 404, code: "user_not_found" }));
    render(await UserDetailPage(detailProps(USER_IDS.li)));
    expect(screen.getByText("该用户可能已被移除，或链接有误。")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "返回用户列表" })).toHaveAttribute("href", "/users");
    expect(within(screen.getByRole("navigation", { name: "面包屑" })).getByText("用户不存在")).toHaveAttribute("aria-current", "page");
  });

  it("格式非法的 userId：同样显示「用户不存在」，且不请求 UAM", async () => {
    render(await UserDetailPage(detailProps("not-a-uuid")));
    expect(screen.getByRole("link", { name: "返回用户列表" })).toBeInTheDocument();
    expect(getUser).not.toHaveBeenCalled();
  });

  it("UAM 不可用：读错误态，带返回列表链接，保留登录状态说明", async () => {
    jest.mocked(getUser).mockRejectedValue(new UamError("unavailable", "x"));
    render(await UserDetailPage(detailProps(USER_IDS.li)));
    expect(screen.getByText("服务暂不可用，请稍后重试")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "重试" })).toHaveAttribute("href", `/users/${USER_IDS.li}`);
    expect(screen.queryByText("用户不存在")).not.toBeInTheDocument();
  });

  it("非 UAM 错误：原样抛出", async () => {
    jest.mocked(getUser).mockRejectedValue(new TypeError("boom"));
    await expect(UserDetailPage(detailProps(USER_IDS.li))).rejects.toThrow("boom");
  });
});
