import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const replace = vi.fn();
let pathname = "/users";
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, refresh: vi.fn(), push: vi.fn() }),
  usePathname: () => pathname,
}));

import { AppShell } from "@/components/AppShell";

let fetchMock: ReturnType<typeof vi.fn>;
beforeEach(() => {
  pathname = "/users";
  replace.mockReset();
  fetchMock = vi.fn(async () => new Response(null, { status: 204 }));
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

const ME = { displayName: "管理员" };

describe("AppShell (full)", () => {
  it("shows only 用户管理 and 会话管理 in the sidebar", () => {
    render(
      <AppShell me={ME} variant="full">
        <p>页面内容</p>
      </AppShell>,
    );
    const nav = screen.getByRole("navigation", { name: "主导航" });
    const links = within(nav).getAllByRole("link");
    expect(links.map((l) => l.textContent)).toEqual(["用户管理", "会话管理"]);
    expect(links.map((l) => l.getAttribute("href"))).toEqual(["/users", "/sessions"]);
    expect(screen.getByText("页面内容")).toBeInTheDocument();
    for (const gone of ["订单管理", "商品管理", "客户管理", "系统设置", "审计日志"]) {
      expect(document.body.textContent).not.toContain(gone);
    }
  });

  it("marks the current section", () => {
    pathname = "/sessions";
    render(
      <AppShell me={ME} variant="full">
        x
      </AppShell>,
    );
    expect(screen.getByRole("link", { name: "会话管理" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "用户管理" })).not.toHaveAttribute("aria-current");
  });

  it("shows a breadcrumb for the current page", () => {
    pathname = "/users/new";
    render(
      <AppShell me={ME} variant="full">
        x
      </AppShell>,
    );
    expect(screen.getByRole("navigation", { name: "面包屑" })).toHaveTextContent("用户管理");
  });

  it("has a user menu with 修改密码 and 退出登录", async () => {
    render(
      <AppShell me={ME} variant="full">
        x
      </AppShell>,
    );
    expect(screen.queryByRole("menu")).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: /管理员/ }));
    const menu = screen.getByRole("menu");
    expect(within(menu).getByRole("menuitem", { name: "修改密码" })).toHaveAttribute(
      "href",
      "/change-password",
    );
    expect(within(menu).getByRole("menuitem", { name: "退出登录" })).toBeInTheDocument();
  });

  it("closes the menu with Escape", async () => {
    render(
      <AppShell me={ME} variant="full">
        x
      </AppShell>,
    );
    await userEvent.click(screen.getByRole("button", { name: /管理员/ }));
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).toBeNull();
  });

  it("logs out through the BFF and returns to /login", async () => {
    render(
      <AppShell me={ME} variant="full">
        x
      </AppShell>,
    );
    await userEvent.click(screen.getByRole("button", { name: /管理员/ }));
    await userEvent.click(screen.getByRole("menuitem", { name: "退出登录" }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/login"));
    expect(fetchMock.mock.calls[0]![0]).toBe("/api/v1/auth/logout");
    expect(fetchMock.mock.calls[0]![1].method).toBe("POST");
  });

  it("still returns to /login if the logout call fails", async () => {
    fetchMock.mockRejectedValue(new TypeError("offline"));
    render(
      <AppShell me={ME} variant="full">
        x
      </AppShell>,
    );
    await userEvent.click(screen.getByRole("button", { name: /管理员/ }));
    await userEvent.click(screen.getByRole("menuitem", { name: "退出登录" }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/login"));
  });
});

describe("AppShell sidebar collapse (FR-031)", () => {
  const renderFull = () =>
    render(
      <AppShell me={ME} variant="full">
        x
      </AppShell>,
    );
  const aside = () => screen.getByRole("navigation", { name: "主导航" }).closest("aside")!;

  beforeEach(() => window.localStorage.clear());
  afterEach(() => vi.restoreAllMocks());

  it("starts expanded and collapses to icons only, keeping accessible names", async () => {
    renderFull();
    const toggle = screen.getByRole("button", { name: "收起侧边栏" });
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(aside()).toHaveClass("w-sidebar");

    await userEvent.click(toggle);

    expect(screen.getByRole("button", { name: "展开侧边栏" })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
    expect(aside()).toHaveClass("w-16");
    expect(aside()).not.toHaveClass("w-sidebar");
    // 仍可通过名称找到链接（文字仅对读屏可见），并带 title
    const link = screen.getByRole("link", { name: "用户管理" });
    expect(link).toHaveAttribute("title", "用户管理");
    expect(link.querySelector("span")).toHaveClass("sr-only");
    expect(window.localStorage.getItem("sidebar-collapsed")).toBe("1");

    await userEvent.click(screen.getByRole("button", { name: "展开侧边栏" }));
    expect(aside()).toHaveClass("w-sidebar");
    expect(window.localStorage.getItem("sidebar-collapsed")).toBe("0");
  });

  it("remembers the choice across mounts", async () => {
    window.localStorage.setItem("sidebar-collapsed", "1");
    renderFull();
    await waitFor(() => expect(aside()).toHaveClass("w-16"));
    expect(screen.getByRole("button", { name: "展开侧边栏" })).toBeInTheDocument();
  });

  it("falls back to expanded when storage is unavailable", async () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    renderFull();
    expect(aside()).toHaveClass("w-sidebar");
    await userEvent.click(screen.getByRole("button", { name: "收起侧边栏" }));
    expect(aside()).toHaveClass("w-16"); // 写入失败不影响本次切换
  });

  it("opens a drawer on narrow screens and closes it by backdrop, Escape or choosing an item", async () => {
    renderFull();
    expect(screen.queryByRole("navigation", { name: "移动端导航" })).toBeNull();

    await userEvent.click(screen.getByRole("button", { name: "打开菜单" }));
    const drawer = screen.getByRole("navigation", { name: "移动端导航" });
    expect(
      within(drawer)
        .getAllByRole("link")
        .map((l) => l.textContent),
    ).toEqual(["用户管理", "会话管理"]);

    // 抽屉头部有关闭按钮（X）
    await userEvent.click(screen.getByRole("button", { name: "关闭菜单" }));
    expect(screen.queryByRole("navigation", { name: "移动端导航" })).toBeNull();

    // 点击遮罩也关闭
    await userEvent.click(screen.getByRole("button", { name: "打开菜单" }));
    await userEvent.click(screen.getByTestId("drawer-backdrop"));
    expect(screen.queryByRole("navigation", { name: "移动端导航" })).toBeNull();

    await userEvent.click(screen.getByRole("button", { name: "打开菜单" }));
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("navigation", { name: "移动端导航" })).toBeNull();

    await userEvent.click(screen.getByRole("button", { name: "打开菜单" }));
    await userEvent.click(
      within(screen.getByRole("navigation", { name: "移动端导航" })).getByRole("link", {
        name: "会话管理",
      }),
    );
    expect(screen.queryByRole("navigation", { name: "移动端导航" })).toBeNull();
  });

  it("has no menu buttons without a sidebar (non-admin)", () => {
    pathname = "/forbidden";
    render(
      <AppShell me={ME} variant="minimal">
        x
      </AppShell>,
    );
    for (const name of ["收起侧边栏", "展开侧边栏", "打开菜单"]) {
      expect(screen.queryByRole("button", { name })).toBeNull();
    }
  });
});

describe("AppShell (minimal, non-admin)", () => {
  it("has no sidebar navigation but keeps the user menu", async () => {
    pathname = "/forbidden";
    render(
      <AppShell me={ME} variant="minimal">
        无权限
      </AppShell>,
    );
    expect(screen.queryByRole("navigation", { name: "主导航" })).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: /管理员/ }));
    expect(screen.getByRole("menuitem", { name: "修改密码" })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "退出登录" })).toBeInTheDocument();
  });
});

describe("AppShell (locked, password change required)", () => {
  it("disables the other navigation entries", () => {
    pathname = "/change-password";
    render(
      <AppShell me={ME} variant="locked">
        x
      </AppShell>,
    );
    const nav = screen.getByRole("navigation", { name: "主导航" });
    expect(within(nav).queryAllByRole("link")).toHaveLength(0);
    const items = within(nav).getAllByText(/用户管理|会话管理/);
    for (const item of items) expect(item.closest("[aria-disabled='true']")).not.toBeNull();
  });

  it("hides the change-password menu entry but allows logging out", async () => {
    pathname = "/change-password";
    render(
      <AppShell me={ME} variant="locked">
        x
      </AppShell>,
    );
    await userEvent.click(screen.getByRole("button", { name: /管理员/ }));
    expect(screen.queryByRole("menuitem", { name: "修改密码" })).toBeNull();
    expect(screen.getByRole("menuitem", { name: "退出登录" })).toBeInTheDocument();
  });
});
