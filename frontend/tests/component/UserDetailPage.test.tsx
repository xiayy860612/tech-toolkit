import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/users/1",
}));

import { UserDetail } from "@/features/users/UserDetail";
import { jsonRes, mockApi, problemRes } from "@tests/helpers/fetch-mock";

const user = (over: Record<string, unknown> = {}) => ({
  id: 1,
  displayName: "张三",
  role: "TENANT_ADMIN",
  status: "ACTIVE",
  username: "zhangsan",
  locked: false,
  lockedUntil: null,
  mustChangePassword: false,
  lastLoginAt: "2030-01-02T03:04:00Z",
  version: 1,
  createdAt: "2030-01-01T00:00:00Z",
  ...over,
});

afterEach(() => vi.unstubAllGlobals());

describe("UserDetail", () => {
  it("shows the user info card", async () => {
    mockApi({ "GET /users/1": () => jsonRes(200, user()) });
    render(<UserDetail id="1" />);
    expect(await screen.findByRole("heading", { name: "张三" })).toBeInTheDocument();
    for (const t of ["租户管理员"]) {
      expect(screen.getByText(t)).toBeInTheDocument();
    }
    expect(screen.getByRole("link", { name: "编辑" })).toHaveAttribute("href", "/users/1/edit");
    expect(screen.getByRole("switch", { name: "启用状态" })).toBeChecked();
  });

  it("does not show the enable/disable switch for yourself", async () => {
    mockApi({ "GET /users/1": () => jsonRes(200, user()) });
    render(<UserDetail id="1" currentUserId={1} />);
    await screen.findByRole("heading", { name: "张三" });
    expect(screen.queryByRole("switch", { name: "启用状态" })).toBeNull();
    expect(screen.queryByText("不能停用自己")).toBeNull();
  });

  it("still lets you disable someone else", async () => {
    mockApi({ "GET /users/1": () => jsonRes(200, user()) });
    render(<UserDetail id="1" currentUserId={2} />);
    await screen.findByRole("heading", { name: "张三" });
    expect(screen.getByRole("switch", { name: "启用状态" })).toBeEnabled();
    expect(screen.queryByText("不能停用自己")).toBeNull();
  });

  it("has no session section (sessions live on their own page)", async () => {
    mockApi({ "GET /users/1": () => jsonRes(200, user()) });
    render(<UserDetail id="1" />);
    await screen.findByRole("heading", { name: "张三" });
    expect(document.body.textContent).not.toContain("会话");
  });

  it("disables the user after confirmation", async () => {
    const api = mockApi({
      "GET /users/1": () => jsonRes(200, user()),
      "POST /users/1/disable": () => jsonRes(200, user({ status: "DISABLED", version: 2 })),
    });
    render(<UserDetail id="1" />);
    await userEvent.click(await screen.findByRole("switch", { name: "启用状态" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/立即失效/)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "确认停用" }));
    await waitFor(() => expect(api.callsTo("POST /users/1/disable")).toHaveLength(1));
    await waitFor(() => expect(screen.getByRole("switch", { name: "启用状态" })).not.toBeChecked());
  });

  it("enables a disabled user directly", async () => {
    const api = mockApi({
      "GET /users/1": () => jsonRes(200, user({ status: "DISABLED" })),
      "POST /users/1/enable": () => jsonRes(200, user()),
    });
    render(<UserDetail id="1" />);
    await userEvent.click(await screen.findByRole("switch", { name: "启用状态" }));
    await waitFor(() => expect(api.callsTo("POST /users/1/enable")).toHaveLength(1));
    await waitFor(() => expect(screen.getByRole("switch", { name: "启用状态" })).toBeChecked());
  });

  it.each([
    ["CANNOT_DISABLE_SELF", "不能停用自己"],
    ["LAST_ADMIN", "不能停用或降级最后一名管理员"],
  ])("maps %s to a readable message", async (code, text) => {
    mockApi({
      "GET /users/1": () => jsonRes(200, user()),
      "POST /users/1/disable": () => problemRes(409, code),
    });
    render(<UserDetail id="1" />);
    await userEvent.click(await screen.findByRole("switch", { name: "启用状态" }));
    await userEvent.click(
      within(await screen.findByRole("dialog")).getByRole("button", { name: "确认停用" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(text);
    expect(screen.getByRole("switch", { name: "启用状态" })).toBeChecked();
  });

  it("shows not-found for cross-tenant / missing users", async () => {
    mockApi({ "GET /users/1": () => problemRes(404, "NOT_FOUND") });
    render(<UserDetail id="1" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("资源不存在");
    expect(screen.getByRole("link", { name: "返回用户列表" })).toHaveAttribute("href", "/users");
  });

  it("offers a retry on other load failures", async () => {
    let fail = true;
    mockApi({ "GET /users/1": () => (fail ? problemRes(500, "INTERNAL") : jsonRes(200, user())) });
    render(<UserDetail id="1" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("操作失败");
    fail = false;
    await userEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByRole("heading", { name: "张三" })).toBeInTheDocument();
  });
});
