import { render, screen, within } from "@testing-library/react";
import { AccountList } from "@/components/users/account-list";
import { makeAccount } from "../../../support/users-fixtures";

const accounts = [
  makeAccount({ id: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", loginName: "lisi", status: "ACTIVE", createdAt: "2026-09-20T01:02:00Z" }),
  makeAccount({ id: "cccccccc-cccc-4ccc-8ccc-cccccccccccc", loginName: "lisi.mobile", status: "DISABLED", createdAt: "2026-09-25T10:00:00Z" }),
  makeAccount({ id: "dddddddd-dddd-4ddd-8ddd-dddddddddddd", loginName: "lisi.old", status: "DELETED", createdAt: "2026-09-26T10:00:00Z" }),
];

describe("AccountList", () => {
  it("标题含账号数：「关联账号（N）」", () => {
    render(<AccountList accounts={accounts} />);
    expect(screen.getByRole("heading", { level: 2, name: "关联账号（3）" })).toBeInTheDocument();
  });

  it("账号表：账号名、账号种类、状态、创建时间；无遗漏", () => {
    render(<AccountList accounts={accounts} />);
    const table = screen.getByRole("table", { name: "关联账号" });
    expect(within(table).getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
      "账号名",
      "账号种类",
      "状态",
      "创建时间",
    ]);
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent("lisi");
    expect(rows[0]).toHaveTextContent("账号名 / 密码");
    expect(within(rows[0]).getByText("正常")).toBeInTheDocument();
    expect(rows[0].querySelector("time")).toHaveAttribute("datetime", "2026-09-20T01:02:00Z");
    expect(rows[0].querySelector("time")!.textContent).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
    expect(rows[1]).toHaveTextContent("lisi.mobile");
    expect(within(rows[1]).getByText("已停用")).toBeInTheDocument();
    expect(within(rows[2]).getByText("已删除")).toBeInTheDocument();
  });

  it("手机上是卡片：每个账号一张，含同样的信息；表格在手机上隐藏", () => {
    render(<AccountList accounts={accounts} />);
    const table = screen.getByRole("table", { name: "关联账号" });
    expect(table.closest("[data-slot='table-container']")?.parentElement).toHaveClass("hidden", "md:block");
    const list = screen.getByRole("list", { name: "关联账号卡片" });
    expect(list).toHaveClass("md:hidden");
    const items = within(list).getAllByRole("listitem");
    expect(items).toHaveLength(3);
    expect(items[1]).toHaveTextContent("lisi.mobile");
    expect(items[1]).toHaveTextContent("账号名 / 密码");
    expect(items[1]).toHaveTextContent("已停用");
    expect(items[1]).toHaveTextContent("创建时间");
  });

  it("账号名按文本渲染；不出现任何凭据字段", () => {
    const { container } = render(<AccountList accounts={[makeAccount({ loginName: "<b>x</b>" })]} />);
    expect(screen.getAllByText("<b>x</b>").length).toBeGreaterThan(0);
    expect(container.querySelector("b")).toBeNull();
    expect(container.textContent).not.toMatch(/密码哈希|password_hash|argon/i);
  });

  it("没有账号：空态「该用户暂无关联账号」，无表格；标题计数为 0", () => {
    render(<AccountList accounts={[]} />);
    expect(screen.getByRole("heading", { level: 2, name: "关联账号（0）" })).toBeInTheDocument();
    expect(screen.getByText("该用户暂无关联账号")).toBeInTheDocument();
    expect(screen.getByText("该用户目前无法登录。")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
});
