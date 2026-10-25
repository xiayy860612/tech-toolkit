import { render, screen } from "@testing-library/react";
import { UserSearchBox } from "@/components/users/user-search-box";

describe("UserSearchBox", () => {
  it("是 GET 表单（不依赖 JS）：提交到 /users，字段名 q，不带 page", () => {
    const { container } = render(<UserSearchBox />);
    const form = screen.getByRole("search");
    expect(form.tagName).toBe("FORM");
    expect(form).toHaveAttribute("method", "get");
    expect(form).toHaveAttribute("action", "/users");
    expect(container.querySelector('input[name="page"]')).toBeNull();
    expect(screen.getByRole("searchbox", { name: "搜索用户名或账号名" })).toHaveAttribute("name", "q");
  });

  it("回填当前搜索词；有长度上限 64（与 UAM 契约一致）", () => {
    render(<UserSearchBox defaultValue="lisi" />);
    const input = screen.getByRole("searchbox", { name: "搜索用户名或账号名" });
    expect(input).toHaveValue("lisi");
    expect(input).toHaveAttribute("maxlength", "64");
    expect(input).toHaveAttribute("placeholder", "搜索用户名或账号名");
  });

  it("默认为空；有「搜索」提交按钮；输入框与按钮触控目标不小于 44px", () => {
    render(<UserSearchBox />);
    expect(screen.getByRole("searchbox", { name: "搜索用户名或账号名" })).toHaveValue("");
    const button = screen.getByRole("button", { name: "搜索" });
    expect(button).toHaveAttribute("type", "submit");
    expect(button).toHaveClass("h-11");
    expect(screen.getByRole("searchbox")).toHaveClass("h-11");
  });
});
