import { render, screen, within } from "@testing-library/react";
import { OnlineUserList } from "@/components/sessions/online-user-list";
import { makeOnlineUser, NOW } from "../../../support/sessions-fixtures";
import { USER_IDS } from "../../../support/users-fixtures";

const users = [
  makeOnlineUser({ userId: USER_IDS.zhang, userName: "张三", sessionCount: 1, lastActiveAt: "2026-10-24T11:55:00Z" }),
  makeOnlineUser({ userId: USER_IDS.li, userName: "李四", sessionCount: 2, lastActiveAt: "2026-10-24T09:00:00Z" }),
];

const base = { users, total: 2, page: 1, size: 20, now: NOW };

describe("OnlineUserList：表格（≥ 768 宽）", () => {
  it("列：用户名、有效会话数、最近活动", () => {
    render(<OnlineUserList {...base} />);
    const table = screen.getByRole("table", { name: "在线用户列表" });
    expect(within(table).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["用户名", "有效会话数", "最近活动"]);
  });

  it("每行：用户名链接到该用户的会话详情、有效会话数、最近活动（相对时间，机器可读值与绝对时间在 time 上）", () => {
    render(<OnlineUserList {...base} />);
    const rows = within(screen.getByRole("table", { name: "在线用户列表" })).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(2);

    const first = within(rows[0]);
    expect(first.getByRole("link", { name: "张三" })).toHaveAttribute("href", `/sessions/${USER_IDS.zhang}`);
    expect(first.getByText("1")).toBeInTheDocument();
    const time = rows[0].querySelector("time")!;
    expect(time).toHaveAttribute("datetime", "2026-10-24T11:55:00Z");
    expect(time).toHaveTextContent("5 分钟前");
    expect(time.getAttribute("title")).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);

    const second = within(rows[1]);
    expect(second.getByRole("link", { name: "李四" })).toHaveAttribute("href", `/sessions/${USER_IDS.li}`);
    expect(second.getByText("2")).toBeInTheDocument();
    expect(rows[1].querySelector("time")).toHaveTextContent("3 小时前");
  });

  it("运维人员自己所在的用户行带「你」标注（文字，不只靠颜色）；其他行没有", () => {
    render(<OnlineUserList {...base} currentUserId={USER_IDS.li} />);
    const rows = within(screen.getByRole("table", { name: "在线用户列表" })).getAllByRole("row").slice(1);
    expect(within(rows[0]).queryByText("你")).not.toBeInTheDocument();
    expect(within(rows[1]).getByText("你")).toBeInTheDocument();
  });

  it("用户名按文本渲染（不解析 HTML）", () => {
    render(<OnlineUserList {...base} users={[makeOnlineUser({ userName: "<img src=x onerror=alert(1)>" })]} total={1} />);
    expect(screen.getAllByText("<img src=x onerror=alert(1)>").length).toBeGreaterThan(0);
    expect(document.querySelector("img")).toBeNull();
  });

  it("不传 now 时按当前时间计算（刚刚活动的显示「刚刚」）", () => {
    render(<OnlineUserList {...base} now={undefined} users={[makeOnlineUser({ lastActiveAt: new Date().toISOString() })]} total={1} />);
    expect(screen.getAllByText("刚刚").length).toBeGreaterThan(0);
  });
});

describe("OnlineUserList：卡片（手机 < 768 宽）", () => {
  it("同一批用户另有一份卡片列表：表格在手机上隐藏、卡片在 md 及以上隐藏", () => {
    render(<OnlineUserList {...base} />);
    const table = screen.getByRole("table", { name: "在线用户列表" });
    expect(table.closest("[data-slot='table-container']")?.parentElement).toHaveClass("hidden", "md:block");
    const cards = screen.getByRole("list", { name: "在线用户卡片" });
    expect(cards).toHaveClass("md:hidden");
    expect(within(cards).getAllByRole("listitem")).toHaveLength(2);
  });

  it("卡片含全部关键信息：用户名（链接，触控目标足够大）、有效会话数、最近活动；自己带「你」", () => {
    render(<OnlineUserList {...base} currentUserId={USER_IDS.zhang} />);
    const [first, second] = within(screen.getByRole("list", { name: "在线用户卡片" })).getAllByRole("listitem");
    const link = within(first).getByRole("link", { name: "张三" });
    expect(link).toHaveAttribute("href", `/sessions/${USER_IDS.zhang}`);
    expect(link).toHaveClass("min-h-11");
    expect(first).toHaveTextContent("有效会话数");
    expect(first).toHaveTextContent("最近活动");
    expect(first).toHaveTextContent("5 分钟前");
    expect(within(first).getByText("你")).toBeInTheDocument();
    expect(within(second).queryByText("你")).not.toBeInTheDocument();
    expect(second).toHaveTextContent("2");
  });
});

describe("OnlineUserList：空态", () => {
  it("没有在线用户：「当前没有在线用户」，没有表格与分页", () => {
    render(<OnlineUserList {...base} users={[]} total={0} />);
    expect(screen.getByText("当前没有在线用户")).toBeInTheDocument();
    expect(screen.getByText("有用户登录后会显示在这里。")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "分页" })).not.toBeInTheDocument();
  });
});

describe("OnlineUserList：分页", () => {
  const paged = { ...base, total: 45, size: 20 };

  it("显示在线用户总数与页码；上一页 / 下一页是链接", () => {
    render(<OnlineUserList {...paged} page={2} />);
    const nav = screen.getByRole("navigation", { name: "分页" });
    expect(nav).toHaveTextContent("共 45 位在线用户 · 第 2 / 3 页");
    expect(within(nav).getByRole("link", { name: "上一页" })).toHaveAttribute("href", "/sessions");
    expect(within(nav).getByRole("link", { name: "下一页" })).toHaveAttribute("href", "/sessions?page=3");
  });

  it("第一页没有可用的上一页，最后一页没有可用的下一页", () => {
    const { rerender } = render(<OnlineUserList {...paged} page={1} />);
    let nav = screen.getByRole("navigation", { name: "分页" });
    expect(within(nav).queryByRole("link", { name: "上一页" })).not.toBeInTheDocument();
    expect(within(nav).getByText("上一页")).toHaveAttribute("aria-disabled", "true");
    expect(within(nav).getByRole("link", { name: "下一页" })).toHaveAttribute("href", "/sessions?page=2");

    rerender(<OnlineUserList {...paged} page={3} />);
    nav = screen.getByRole("navigation", { name: "分页" });
    expect(within(nav).getByText("下一页")).toHaveAttribute("aria-disabled", "true");
  });
});
