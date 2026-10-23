import { render, screen, within } from "@testing-library/react";
import { UserList } from "@/components/users/user-list";
import { makeSummary, USER_IDS } from "../../../support/users-fixtures";

const users = [
  makeSummary({ id: USER_IDS.zhang, name: "张三", status: "ACTIVE", accountCount: 2, createdAt: "2026-10-01T08:30:00Z" }),
  makeSummary({ id: USER_IDS.li, name: "李四", status: "DISABLED", accountCount: 0, createdAt: "2026-09-01T08:30:00Z" }),
];

const base = { users, total: 2, page: 1, size: 20, query: "" };
const DATE_SHAPE = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/;

describe("UserList：表格（≥ 768 宽）", () => {
  it("列：用户名、状态、关联账号数、创建时间", () => {
    render(<UserList {...base} />);
    const table = screen.getByRole("table", { name: "用户列表" });
    const headers = within(table).getAllByRole("columnheader").map((h) => h.textContent);
    expect(headers).toEqual(["用户名", "状态", "关联账号数", "创建时间"]);
  });

  it("每行：用户名链接到详情、状态徽标（文字）、账号数、创建时间", () => {
    render(<UserList {...base} />);
    const table = screen.getByRole("table", { name: "用户列表" });
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(2);

    const first = within(rows[0]);
    expect(first.getByRole("link", { name: "张三" })).toHaveAttribute("href", `/users/${USER_IDS.zhang}`);
    expect(first.getByText("正常")).toBeInTheDocument();
    expect(first.getByText("2")).toBeInTheDocument();
    const time = rows[0].querySelector("time")!;
    expect(time).toHaveAttribute("datetime", "2026-10-01T08:30:00Z");
    expect(time.textContent).toMatch(DATE_SHAPE);

    const second = within(rows[1]);
    expect(second.getByRole("link", { name: "李四" })).toHaveAttribute("href", `/users/${USER_IDS.li}`);
    expect(second.getByText("已停用")).toBeInTheDocument();
    expect(second.getByText("0")).toBeInTheDocument();
  });

  it("用户名按文本渲染（不解析 HTML）", () => {
    render(<UserList {...base} users={[makeSummary({ name: "<img src=x onerror=alert(1)>" })]} total={1} />);
    expect(screen.getAllByText("<img src=x onerror=alert(1)>").length).toBeGreaterThan(0);
    expect(document.querySelector("img")).toBeNull();
  });
});

describe("UserList：卡片（手机 < 768 宽）", () => {
  it("同一批用户另有一份卡片列表：表格在手机上隐藏、卡片在 md 及以上隐藏", () => {
    render(<UserList {...base} />);
    const table = screen.getByRole("table", { name: "用户列表" });
    expect(table.closest("[data-slot='table-container']")?.parentElement).toHaveClass("hidden", "md:block");

    const cards = screen.getByRole("list", { name: "用户卡片" });
    expect(cards).toHaveClass("md:hidden");
    expect(within(cards).getAllByRole("listitem")).toHaveLength(2);
  });

  it("卡片含全部关键信息：用户名（链接）、状态、关联账号数、创建时间", () => {
    render(<UserList {...base} />);
    const [first, second] = within(screen.getByRole("list", { name: "用户卡片" })).getAllByRole("listitem");
    const link = within(first).getByRole("link", { name: "张三" });
    expect(link).toHaveAttribute("href", `/users/${USER_IDS.zhang}`);
    expect(link).toHaveClass("min-h-11");
    expect(within(first).getByText("正常")).toBeInTheDocument();
    expect(first).toHaveTextContent("关联账号数");
    expect(first).toHaveTextContent("2");
    expect(first).toHaveTextContent("创建时间");
    expect(first.querySelector("time")!.textContent).toMatch(DATE_SHAPE);

    expect(within(second).getByText("已停用")).toBeInTheDocument();
  });
});

describe("UserList：分页", () => {
  const paged = { ...base, total: 45, size: 20 };

  it("显示总数与页码；上一页 / 下一页是链接，保留搜索词", () => {
    render(<UserList {...paged} page={2} query="Li" />);
    const nav = screen.getByRole("navigation", { name: "分页" });
    expect(nav).toHaveTextContent("共 45 位用户 · 第 2 / 3 页");
    expect(within(nav).getByRole("link", { name: "上一页" })).toHaveAttribute("href", "/users?q=Li");
    expect(within(nav).getByRole("link", { name: "下一页" })).toHaveAttribute("href", "/users?q=Li&page=3");
  });

  it("搜索词做 URL 编码；没有搜索词时不带 q", () => {
    render(<UserList {...paged} page={1} query="张 三&x" />);
    expect(screen.getByRole("link", { name: "下一页" })).toHaveAttribute(
      "href",
      `/users?q=${encodeURIComponent("张 三&x")}&page=2`,
    );
  });

  it("第一页：上一页不可用（不是链接，aria-disabled）；最后一页：下一页不可用", () => {
    const { rerender } = render(<UserList {...paged} page={1} />);
    expect(screen.queryByRole("link", { name: "上一页" })).not.toBeInTheDocument();
    expect(screen.getByText("上一页")).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByRole("link", { name: "下一页" })).toHaveAttribute("href", "/users?page=2");

    rerender(<UserList {...paged} page={3} />);
    expect(screen.queryByRole("link", { name: "下一页" })).not.toBeInTheDocument();
    expect(screen.getByText("下一页")).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByRole("link", { name: "上一页" })).toHaveAttribute("href", "/users?page=2");
  });

  it("只有一页：两个按钮都不可用，页码为 1 / 1", () => {
    render(<UserList {...base} />);
    expect(screen.getByRole("navigation", { name: "分页" })).toHaveTextContent("共 2 位用户 · 第 1 / 1 页");
    expect(screen.getByText("上一页")).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByText("下一页")).toHaveAttribute("aria-disabled", "true");
  });

  it("分页按钮触控目标不小于 44px", () => {
    render(<UserList {...paged} page={2} />);
    expect(screen.getByRole("link", { name: "上一页" })).toHaveClass("h-11");
    expect(screen.getByRole("link", { name: "下一页" })).toHaveClass("h-11");
  });
});

describe("UserList：空态", () => {
  it("没有任何用户：「还没有用户」，无表格与分页", () => {
    render(<UserList users={[]} total={0} page={1} size={20} query="" />);
    expect(screen.getByText("还没有用户")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "分页" })).not.toBeInTheDocument();
  });

  it("搜索无结果：显示搜索词并给出清除搜索的链接", () => {
    render(<UserList users={[]} total={0} page={1} size={20} query="nobody" />);
    expect(screen.getByText("没有找到匹配「nobody」的用户")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "清除搜索" })).toHaveAttribute("href", "/users");
    expect(screen.queryByText("还没有用户")).not.toBeInTheDocument();
  });
});
