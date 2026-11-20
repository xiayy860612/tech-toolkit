import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/sessions",
}));

import SessionsPage from "@/app/(console)/sessions/page";
import { jsonRes, mockApi, noContent, problemRes } from "@tests/helpers/fetch-mock";

const ME = {
  userId: 99,
  tenantId: 0,
  displayName: "管理员",
  role: "TENANT_ADMIN",
  mustChangePassword: false,
};

const session = (over: Record<string, unknown> = {}) => ({
  id: "s1",
  userId: 1,
  displayName: "张三",
  username: "zhangsan",
  loginType: "USERNAME_PASSWORD",
  device: "Chrome 130 / macOS",
  ip: "203.0.113.9",
  createdAt: "2030-01-01T08:00:00Z",
  lastSeenAt: "2030-01-01T09:00:00Z",
  expiresAt: "2030-01-01T16:00:00Z",
  current: false,
  ...over,
});
const list = (items: unknown[], total = items.length) => ({ items, page: 0, size: 20, total });

const rows = () => [
  session(),
  session({
    id: "s2",
    userId: 99,
    displayName: "管理员",
    username: "admin",
    loginType: "USERNAME_PASSWORD",
    device: "Safari 18 / macOS",
    current: true,
  }),
  session({
    id: "s3",
    userId: 99,
    displayName: "管理员",
    username: "admin",
    loginType: "USERNAME_PASSWORD",
    device: "Firefox 131 / Windows",
  }),
];

afterEach(() => vi.unstubAllGlobals());

let api: ReturnType<typeof mockApi>;
beforeEach(() => {
  api = mockApi({
    "GET /auth/me": () => jsonRes(200, ME),
    "GET /sessions": () => jsonRes(200, list(rows(), 45)),
  });
});

const rowByDevice = (device: string) => screen.getByText(device).closest("tr")!;

describe("SessionsPage", () => {
  it("renders the filter bar, columns, rows and pagination", async () => {
    render(<SessionsPage />);
    await screen.findByText("Chrome 130 / macOS");
    expect(screen.getByLabelText("用户关键字")).toBeInTheDocument();
    for (const h of ["用户", "用户名", "设备", "IP", "登录时间", "最近活跃", "到期时间", "操作"]) {
      expect(screen.getByRole("columnheader", { name: h })).toBeInTheDocument();
    }
    const row = within(rowByDevice("Chrome 130 / macOS"));
    expect(row.getByText("张三")).toBeInTheDocument();
    expect(row.getByText("zhangsan")).toBeInTheDocument();
    expect(row.getByText("203.0.113.9")).toBeInTheDocument();
    expect(screen.getByText("共 45 条，每页 20 条")).toBeInTheDocument();
  });

  it("marks the current session and offers no actions on it", async () => {
    render(<SessionsPage />);
    await screen.findByText("Chrome 130 / macOS");
    const current = screen.getAllByText("当前")[0]!.closest("tr")!;
    expect(within(current).queryByRole("button")).toBeNull();
  });

  it("offers 强制下线 on other sessions and 下线该用户全部会话 only for other users", async () => {
    render(<SessionsPage />);
    await screen.findByText("Chrome 130 / macOS");
    const others = within(rowByDevice("Chrome 130 / macOS"));
    expect(others.getByRole("button", { name: "强制下线" })).toBeInTheDocument();
    expect(others.getByRole("button", { name: "下线该用户全部会话" })).toBeInTheDocument();
    // 管理员自己的另一个会话：可强制下线，但不提供「下线该用户全部会话」
    const own = within(rowByDevice("Firefox 131 / Windows"));
    expect(own.getByRole("button", { name: "强制下线" })).toBeInTheDocument();
    expect(own.queryByRole("button", { name: "下线该用户全部会话" })).toBeNull();
  });

  it("revokes one session after confirmation, refreshes the list and shows a toast", async () => {
    api = mockApi({
      "GET /auth/me": () => jsonRes(200, ME),
      "GET /sessions": () => jsonRes(200, list(rows(), 3)),
      "DELETE /sessions/s1": () => noContent(),
    });
    render(<SessionsPage />);
    await screen.findByText("Chrome 130 / macOS");
    await userEvent.click(
      within(rowByDevice("Chrome 130 / macOS")).getByRole("button", { name: "强制下线" }),
    );
    const dialog = await screen.findByRole("dialog");
    expect(api.callsTo("DELETE /sessions/s1")).toHaveLength(0);
    await userEvent.click(within(dialog).getByRole("button", { name: "确认下线" }));
    await waitFor(() => expect(api.callsTo("DELETE /sessions/s1")).toHaveLength(1));
    await waitFor(() => expect(api.callsTo("GET /sessions").length).toBeGreaterThan(1));
    expect(await screen.findByText("已强制下线该会话")).toBeInTheDocument();
  });

  it("revokes all sessions of a user after confirmation", async () => {
    api = mockApi({
      "GET /auth/me": () => jsonRes(200, ME),
      "GET /sessions": () => jsonRes(200, list(rows(), 3)),
      "DELETE /users/1/sessions": () => noContent(),
    });
    render(<SessionsPage />);
    await screen.findByText("Chrome 130 / macOS");
    await userEvent.click(
      within(rowByDevice("Chrome 130 / macOS")).getByRole("button", { name: "下线该用户全部会话" }),
    );
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/张三/)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "确认下线" }));
    await waitFor(() => expect(api.callsTo("DELETE /users/1/sessions")).toHaveLength(1));
    expect(await screen.findByText("已下线该用户的全部会话")).toBeInTheDocument();
  });

  it.each([
    ["DELETE /sessions/s1", "CANNOT_REVOKE_CURRENT_SESSION", "不能下线当前会话", "强制下线"],
    [
      "DELETE /users/1/sessions",
      "CANNOT_REVOKE_OWN_SESSIONS",
      "不能下线自己的全部会话",
      "下线该用户全部会话",
    ],
  ])("maps %s error %s by code", async (route, code, text, button) => {
    api = mockApi({
      "GET /auth/me": () => jsonRes(200, ME),
      "GET /sessions": () => jsonRes(200, list(rows(), 3)),
      [route]: () => problemRes(409, code),
    });
    render(<SessionsPage />);
    await screen.findByText("Chrome 130 / macOS");
    await userEvent.click(
      within(rowByDevice("Chrome 130 / macOS")).getByRole("button", { name: button }),
    );
    await userEvent.click(
      within(await screen.findByRole("dialog")).getByRole("button", { name: "确认下线" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(text);
  });

  it("filters by user keyword, resetting to the first page", async () => {
    render(<SessionsPage />);
    await screen.findByText("Chrome 130 / macOS");
    await userEvent.click(screen.getByRole("button", { name: "下一页" }));
    await waitFor(() => expect(api.callsTo("GET /sessions").at(-1)!.query.get("page")).toBe("1"));
    await userEvent.type(screen.getByLabelText("用户关键字"), " 张三 ");
    await userEvent.click(screen.getByRole("button", { name: "搜索" }));
    await waitFor(() => {
      const last = api.callsTo("GET /sessions").at(-1)!;
      expect(last.query.get("keyword")).toBe("张三");
      expect(last.query.get("page")).toBe("0");
    });
  });

  it("shows the empty state", async () => {
    api = mockApi({
      "GET /auth/me": () => jsonRes(200, ME),
      "GET /sessions": () => jsonRes(200, list([], 0)),
    });
    render(<SessionsPage />);
    expect(await screen.findByText("当前没有有效的登录会话")).toBeInTheDocument();
  });

  it("shows a failure state with retry", async () => {
    let fail = true;
    api = mockApi({
      "GET /auth/me": () => jsonRes(200, ME),
      "GET /sessions": () => (fail ? problemRes(500, "INTERNAL") : jsonRes(200, list(rows(), 3))),
    });
    render(<SessionsPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("加载失败");
    fail = false;
    await userEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByText("Chrome 130 / macOS")).toBeInTheDocument();
  });

  it("recognises the own user from the current-session row when /auth/me fails", async () => {
    api = mockApi({
      "GET /auth/me": () => problemRes(500, "INTERNAL"),
      "GET /sessions": () => jsonRes(200, list(rows(), 3)),
    });
    render(<SessionsPage />);
    await screen.findByText("Chrome 130 / macOS");
    expect(
      within(rowByDevice("Firefox 131 / Windows")).queryByRole("button", {
        name: "下线该用户全部会话",
      }),
    ).toBeNull();
  });
});
