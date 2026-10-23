import { render, screen, within } from "@testing-library/react";
import { Breadcrumbs } from "@/components/shell/breadcrumbs";
import { isNavActive, NAV_ITEMS } from "@/components/shell/nav-config";

describe("Breadcrumbs", () => {
  it("前面的项是链接，最后一项是当前页（aria-current=page，不是链接）", () => {
    render(<Breadcrumbs items={[{ label: "用户管理", href: "/users" }, { label: "张三" }]} />);
    const nav = screen.getByRole("navigation", { name: "面包屑" });
    expect(within(nav).getByRole("link", { name: "用户管理" })).toHaveAttribute("href", "/users");
    const current = within(nav).getByText("张三");
    expect(current).toHaveAttribute("aria-current", "page");
    expect(current).not.toHaveAttribute("href");
    expect(current.closest("a")).toBeNull();
  });

  it("只有一项时它就是当前页", () => {
    render(<Breadcrumbs items={[{ label: "会话管理" }]} />);
    expect(screen.getByText("会话管理")).toHaveAttribute("aria-current", "page");
  });
});

describe("nav-config", () => {
  it("只有两个入口：用户管理、会话管理", () => {
    expect(NAV_ITEMS.map((i) => [i.label, i.href])).toEqual([
      ["用户管理", "/users"],
      ["会话管理", "/sessions"],
    ]);
  });

  it.each([
    ["/users", "/users", true],
    ["/users/abc", "/users", true],
    ["/users/new", "/users", true],
    ["/usersx", "/users", false],
    ["/sessions", "/users", false],
    ["/", "/users", false],
  ])("isNavActive(%s, %s) = %s", (pathname, href, expected) => {
    expect(isNavActive(pathname, href)).toBe(expected);
  });
});
