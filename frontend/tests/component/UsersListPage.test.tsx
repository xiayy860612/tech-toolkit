import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/users",
}));

import UsersPage from "@/app/(console)/users/page";
import { jsonRes, mockApi, noContent, problemRes } from "@tests/helpers/fetch-mock";

const user = (over: Record<string, unknown> = {}) => ({
  id: 1,
  displayName: "张三",
  role: "MEMBER",
  status: "ACTIVE",
  username: "zhangsan",
  locked: false,
  lockedUntil: null,
  mustChangePassword: false,
  lastLoginAt: null,
  version: 0,
  createdAt: "2030-01-01T00:00:00Z",
  ...over,
});
const page = (items: unknown[], total = items.length, p = 0) => ({
  items,
  page: p,
  size: 20,
  total,
});

afterEach(() => vi.unstubAllGlobals());

describe("UsersListPage", () => {
  let api: ReturnType<typeof mockApi>;
  beforeEach(() => {
    api = mockApi({
      "GET /users": () =>
        jsonRes(
          200,
          page(
            [
              user(),
              user({
                id: 2,
                displayName: "李四",
                role: "TENANT_ADMIN",
                username: "lisi",
                locked: false,
                lockedUntil: null,
                mustChangePassword: false,
                status: "DISABLED",
              }),
            ],
            45,
          ),
        ),
    });
  });

  it("renders the columns and rows", async () => {
    render(<UsersPage />);
    expect(await screen.findByText("张三")).toBeInTheDocument();
    for (const h of ["姓名", "用户名", "角色", "状态", "最近登录", "操作"]) {
      expect(screen.getByRole("columnheader", { name: h })).toBeInTheDocument();
    }
    const row = screen.getByText("李四").closest("tr")!;
    expect(within(row).getByText("lisi")).toBeInTheDocument();
    expect(document.body.textContent).not.toContain("个账号");
    expect(within(row).getByText("租户管理员")).toBeInTheDocument();
    expect(within(row).getByText("停用")).toBeInTheDocument();
    const first = screen.getByText("张三").closest("tr")!;
    expect(within(first).getByText("从未登录")).toBeInTheDocument();
    expect(within(first).getByRole("link", { name: "详情" })).toHaveAttribute("href", "/users/1");
    expect(within(first).getByRole("link", { name: "编辑" })).toHaveAttribute(
      "href",
      "/users/1/edit",
    );
  });

  it("has status tabs and the pagination summary but no batch actions or export", async () => {
    render(<UsersPage />);
    await screen.findByText("张三");
    for (const t of ["全部", "启用", "停用"])
      expect(screen.getByRole("tab", { name: t })).toBeInTheDocument();
    expect(screen.getByText("共 45 条，每页 20 条")).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/批量|导出/);
    expect(screen.getByRole("link", { name: "新建用户" })).toHaveAttribute("href", "/users/new");
  });

  it("searches by keyword and status, resetting to page 0", async () => {
    render(<UsersPage />);
    await screen.findByText("张三");
    await userEvent.type(screen.getByLabelText("关键词"), " 张 ");
    await userEvent.click(screen.getByRole("tab", { name: "启用" }));
    await userEvent.click(screen.getByRole("button", { name: "搜索" }));
    await waitFor(() => {
      const last = api.callsTo("GET /users").at(-1)!;
      expect(last.query.get("keyword")).toBe("张");
      expect(last.query.get("status")).toBe("ACTIVE");
      expect(last.query.get("page")).toBe("0");
      expect(last.query.get("size")).toBe("20");
    });
  });

  it("filters by tab immediately", async () => {
    render(<UsersPage />);
    await screen.findByText("张三");
    await userEvent.click(screen.getByRole("tab", { name: "停用" }));
    await waitFor(() =>
      expect(api.callsTo("GET /users").at(-1)!.query.get("status")).toBe("DISABLED"),
    );
  });

  it("pages through results", async () => {
    render(<UsersPage />);
    await screen.findByText("张三");
    await userEvent.click(screen.getByRole("button", { name: "下一页" }));
    await waitFor(() => expect(api.callsTo("GET /users").at(-1)!.query.get("page")).toBe("1"));
  });

  it("shows an empty state", async () => {
    api = mockApi({ "GET /users": () => jsonRes(200, page([], 0)) });
    render(<UsersPage />);
    expect(await screen.findByText("没有符合条件的用户")).toBeInTheDocument();
  });

  it("shows a loading skeleton while fetching", async () => {
    mockApi({ "GET /users": () => new Promise<Response>(() => {}) });
    render(<UsersPage />);
    expect(await screen.findByRole("status", { name: "加载中" })).toBeInTheDocument();
  });

  it("shows a failure state with retry", async () => {
    let fail = true;
    api = mockApi({
      "GET /users": () => (fail ? problemRes(500, "INTERNAL") : jsonRes(200, page([user()]))),
    });
    render(<UsersPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("加载失败");
    fail = false;
    await userEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByText("张三")).toBeInTheDocument();
  });

  it("shows no 停用 on your own row, but keeps it on other users", async () => {
    mockApi({
      "GET /auth/me": () =>
        jsonRes(200, {
          userId: 1,
          tenantId: 0,
          displayName: "张三",
          role: "TENANT_ADMIN",
          mustChangePassword: false,
        }),
      "GET /users": () => jsonRes(200, page([user(), user({ id: 2, displayName: "李四" })])),
    });
    render(<UsersPage />);
    await screen.findByText("李四");
    const mine = screen.getByText("张三").closest("tr")!;
    const other = screen.getByText("李四").closest("tr")!;
    await waitFor(() => expect(within(mine).queryByRole("button", { name: "停用" })).toBeNull());
    expect(within(mine).getByRole("link", { name: "编辑" })).toBeInTheDocument();
    expect(within(other).getByRole("button", { name: "停用" })).toBeEnabled();
  });

  it("disables a user after confirmation and refreshes the list", async () => {
    api = mockApi({
      "GET /users": () => jsonRes(200, page([user()])),
      "POST /users/1/disable": () => jsonRes(200, user({ status: "DISABLED" })),
    });
    render(<UsersPage />);
    await screen.findByText("张三");
    await userEvent.click(screen.getByRole("button", { name: "停用" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: "确认停用" }));
    await waitFor(() => expect(api.callsTo("POST /users/1/disable")).toHaveLength(1));
    await waitFor(() => expect(api.callsTo("GET /users").length).toBeGreaterThan(1));
  });

  it("enables a disabled user directly", async () => {
    api = mockApi({
      "GET /users": () => jsonRes(200, page([user({ status: "DISABLED" })])),
      "POST /users/1/enable": () => jsonRes(200, user()),
    });
    render(<UsersPage />);
    await screen.findByText("张三");
    await userEvent.click(screen.getByRole("button", { name: "启用" }));
    await waitFor(() => expect(api.callsTo("POST /users/1/enable")).toHaveLength(1));
  });

  it("maps guard errors from disabling by code", async () => {
    api = mockApi({
      "GET /users": () => jsonRes(200, page([user()])),
      "POST /users/1/disable": () => problemRes(409, "LAST_ADMIN"),
    });
    render(<UsersPage />);
    await screen.findByText("张三");
    await userEvent.click(screen.getByRole("button", { name: "停用" }));
    await userEvent.click(
      within(await screen.findByRole("dialog")).getByRole("button", { name: "确认停用" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent("不能停用或降级最后一名管理员");
  });

  it("ignores a no-content enable response shape", async () => {
    api = mockApi({
      "GET /users": () => jsonRes(200, page([user({ status: "DISABLED" })])),
      "POST /users/1/enable": () => noContent(),
    });
    render(<UsersPage />);
    await screen.findByText("张三");
    await userEvent.click(screen.getByRole("button", { name: "启用" }));
    await waitFor(() => expect(api.callsTo("GET /users").length).toBeGreaterThan(1));
  });
});
