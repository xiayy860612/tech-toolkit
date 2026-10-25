import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AppShell } from "@/components/shell/app-shell";

let pathname = "/users";
jest.mock("next/link", () => ({
  __esModule: true,
  default: ({ href, children, onClick, ...rest }: React.ComponentProps<"a">) => (
    <a
      href={href}
      {...rest}
      onClick={(event) => {
        onClick?.(event);
        event.preventDefault(); // jsdom 没有真正的导航
      }}
    >
      {children}
    </a>
  ),
}));
jest.mock("next/navigation", () => ({ usePathname: () => pathname }));

function setViewport(width: number) {
  Object.defineProperty(window, "innerWidth", { configurable: true, writable: true, value: width });
}

function renderShell(props: Partial<React.ComponentProps<typeof AppShell>> = {}) {
  return render(
    <AppShell status="authenticated" userName="张三" {...props}>
      <p>页面内容</p>
    </AppShell>,
  );
}

beforeEach(() => {
  pathname = "/users";
  setViewport(1280);
  document.cookie = "sidebar_state=; max-age=0; path=/";
});

describe("AppShell：登录状态决定外壳", () => {
  it("已登录：顶栏 + 侧边栏 + 内容", () => {
    renderShell();
    expect(screen.getByRole("banner")).toHaveTextContent("Admin Center");
    expect(screen.getByRole("navigation", { name: "主导航" })).toBeInTheDocument();
    expect(screen.getByRole("main")).toHaveTextContent("页面内容");
    expect(screen.getByRole("button", { name: /张三/ })).toBeInTheDocument();
  });

  it("匿名：只有顶栏，没有侧边栏与用户菜单", () => {
    renderShell({ status: "anonymous", userName: undefined });
    expect(screen.getByRole("banner")).toHaveTextContent("Admin Center");
    expect(screen.queryByRole("navigation", { name: "主导航" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /用户菜单|张三/ })).not.toBeInTheDocument();
    expect(screen.getByRole("main")).toHaveTextContent("页面内容");
  });

  it("unverified：只有顶栏；显示「服务暂不可用」横幅并说明登录状态已保留，不渲染需要会话的页面内容", () => {
    renderShell({ status: "unverified", userName: undefined });
    expect(screen.queryByRole("navigation", { name: "主导航" })).not.toBeInTheDocument();
    const banner = screen.getByRole("status");
    expect(banner).toHaveTextContent("服务暂不可用，请稍后重试");
    expect(banner).toHaveTextContent("登录状态已保留");
    expect(screen.queryByText("页面内容")).not.toBeInTheDocument();
  });

  it("已登录时没有「服务暂不可用」横幅", () => {
    renderShell();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("提供跳到主要内容的链接（键盘用户）", () => {
    renderShell();
    expect(screen.getByRole("link", { name: "跳到主要内容" })).toHaveAttribute("href", "#main");
    expect(screen.getByRole("main")).toHaveAttribute("id", "main");
  });
});

describe("AppShell：导航项", () => {
  it("含「用户管理」「会话管理」，当前项 aria-current=page", () => {
    pathname = "/sessions";
    renderShell();
    const nav = screen.getByRole("navigation", { name: "主导航" });
    const users = within(nav).getByRole("link", { name: "用户管理" });
    const sessions = within(nav).getByRole("link", { name: "会话管理" });
    expect(users).toHaveAttribute("href", "/users");
    expect(sessions).toHaveAttribute("href", "/sessions");
    expect(sessions).toHaveAttribute("aria-current", "page");
    expect(users).not.toHaveAttribute("aria-current");
  });

  it("子路径（用户详情）仍高亮「用户管理」", () => {
    pathname = "/users/11111111-1111-4111-8111-111111111111";
    renderShell();
    expect(screen.getByRole("link", { name: "用户管理" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "会话管理" })).not.toHaveAttribute("aria-current");
  });

  it("不在导航里的页面（修改密码）没有高亮项", () => {
    pathname = "/account/password";
    renderShell();
    expect(document.querySelector("[aria-current]")).toBeNull();
  });
});

describe("AppShell：响应式（桌面 / 平板 / 手机）", () => {
  const sidebarState = () => document.querySelector("[data-slot=sidebar]")?.getAttribute("data-state");

  it("桌面 ≥ 1024：侧边栏默认展开", () => {
    setViewport(1280);
    renderShell();
    expect(sidebarState()).toBe("expanded");
  });

  it("桌面：展开偏好来自 sidebar_state cookie（defaultSidebarOpen=false → 收起）", () => {
    renderShell({ defaultSidebarOpen: false });
    expect(sidebarState()).toBe("collapsed");
  });

  it("桌面：切换按钮收起 / 展开，并把偏好写入非敏感 UI cookie sidebar_state", async () => {
    const user = userEvent.setup();
    renderShell();
    await user.click(screen.getByRole("button", { name: "收起侧边栏" }));
    expect(sidebarState()).toBe("collapsed");
    expect(document.cookie).toContain("sidebar_state=false");
    await user.click(screen.getByRole("button", { name: "展开侧边栏" }));
    expect(sidebarState()).toBe("expanded");
    expect(document.cookie).toContain("sidebar_state=true");
  });

  it("平板 768–1023：固定为图标条，且没有切换按钮；导航项仍有可读名称", () => {
    setViewport(820);
    renderShell();
    expect(sidebarState()).toBe("collapsed");
    expect(screen.queryByRole("button", { name: /收起侧边栏|展开侧边栏/ })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "用户管理" })).toBeInTheDocument();
  });

  it("手机 < 768：侧边栏收为抽屉，顶栏有菜单按钮", async () => {
    setViewport(390);
    renderShell();
    const menu = await screen.findByRole("button", { name: "打开导航菜单" });
    expect(menu).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("手机：打开抽屉后焦点被限制在抽屉内，Esc 关闭并回到菜单按钮", async () => {
    setViewport(390);
    const user = userEvent.setup();
    renderShell();
    const menu = await screen.findByRole("button", { name: "打开导航菜单" });

    await user.click(menu);
    const drawer = await screen.findByRole("dialog", { name: "导航菜单" });
    expect(within(drawer).getByRole("link", { name: "用户管理" })).toBeInTheDocument();
    expect(within(drawer).getByRole("link", { name: "会话管理" })).toBeInTheDocument();

    // 反复 Tab / Shift+Tab，焦点始终留在抽屉内
    for (let i = 0; i < 6; i++) {
      await user.tab();
      expect(drawer).toContainElement(document.activeElement as HTMLElement);
    }
    await user.tab({ shift: true });
    expect(drawer).toContainElement(document.activeElement as HTMLElement);

    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await waitFor(() => expect(menu).toHaveFocus());
  });

  it("手机：点导航项后抽屉关闭", async () => {
    setViewport(390);
    const user = userEvent.setup();
    renderShell();
    await user.click(await screen.findByRole("button", { name: "打开导航菜单" }));
    const drawer = await screen.findByRole("dialog");
    await user.click(within(drawer).getByRole("link", { name: "会话管理" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });
});
