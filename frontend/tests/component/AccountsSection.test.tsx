import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/users/u1",
}));

import { AccountsSection } from "@/features/accounts/AccountsSection";
import { formatTime } from "@/lib/format";
import { jsonRes, mockApi, noContent, problemRes } from "@tests/helpers/fetch-mock";

const account = (over: Record<string, unknown> = {}) => ({
  id: "a1",
  userId: "u1",
  type: "USERNAME_PASSWORD",
  identifier: "zhangsan",
  status: "ACTIVE",
  locked: false,
  lockedUntil: null,
  mustChangePassword: false,
  lastLoginAt: null,
  version: 0,
  createdAt: "2030-01-01T00:00:00Z",
  ...over,
});

const LOCKED_UNTIL = new Date(2030, 0, 1, 10, 30).toISOString();
const both = () => [
  account(),
  account({
    id: "a2",
    identifier: "zs.work",
    locked: true,
    lockedUntil: LOCKED_UNTIL,
    lastLoginAt: "2030-01-02T03:04:00Z",
  }),
];

afterEach(() => vi.unstubAllGlobals());

const rowOf = (identifier: string) => screen.getByText(identifier).closest("tr")!;

describe("AccountsSection", () => {
  it("renders the table columns and rows", async () => {
    mockApi({ "GET /users/u1/accounts": () => jsonRes(200, both()) });
    render(<AccountsSection userId="u1" />);
    await screen.findByText("zhangsan");
    for (const h of ["类型", "登录标识", "状态", "最近登录", "操作"]) {
      expect(screen.getByRole("columnheader", { name: h })).toBeInTheDocument();
    }
    expect(within(rowOf("zhangsan")).getByText("用户名密码")).toBeInTheDocument();
    expect(within(rowOf("zhangsan")).getByText("从未登录")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "新增 Account" })).toBeInTheDocument();
  });

  it("shows a locked pill with the unlock time and offers unlock only for locked accounts", async () => {
    mockApi({ "GET /users/u1/accounts": () => jsonRes(200, both()) });
    render(<AccountsSection userId="u1" />);
    await screen.findByText("zhangsan");
    expect(
      within(rowOf("zs.work")).getByText(`已锁定至 ${formatTime(LOCKED_UNTIL)}`),
    ).toBeInTheDocument();
    expect(within(rowOf("zs.work")).getByRole("button", { name: "解锁" })).toBeInTheDocument();
    expect(within(rowOf("zhangsan")).queryByRole("button", { name: "解锁" })).toBeNull();
  });

  it("offers 停用·启用, 重置密码 and 删除", async () => {
    mockApi({
      "GET /users/u1/accounts": () =>
        jsonRes(200, [account(), account({ id: "a2", identifier: "off", status: "DISABLED" })]),
    });
    render(<AccountsSection userId="u1" />);
    await screen.findByText("zhangsan");
    const active = within(rowOf("zhangsan"));
    expect(active.getByRole("button", { name: "停用" })).toBeInTheDocument();
    expect(active.getByRole("button", { name: "重置密码" })).toBeInTheDocument();
    expect(active.getByRole("button", { name: "删除" })).toBeInTheDocument();
    expect(within(rowOf("off")).getByRole("button", { name: "启用" })).toBeInTheDocument();
  });

  it("disables a non-last active account directly, without confirm", async () => {
    const api = mockApi({
      "GET /users/u1/accounts": () =>
        jsonRes(200, [account(), account({ id: "a2", identifier: "second" })]),
      "POST /users/u1/accounts/a1/disable": () => jsonRes(200, account({ status: "DISABLED" })),
    });
    render(<AccountsSection userId="u1" />);
    await screen.findByText("zhangsan");
    await userEvent.click(within(rowOf("zhangsan")).getByRole("button", { name: "停用" }));
    await waitFor(() => expect(api.callsTo("POST /users/u1/accounts/a1/disable")).toHaveLength(1));
    expect(api.callsTo("POST /users/u1/accounts/a1/disable")[0]!.query.get("confirm")).toBeNull();
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("asks for confirmation before disabling the last active account and sends confirm=true", async () => {
    const api = mockApi({
      "GET /users/u1/accounts": () =>
        jsonRes(200, [account(), account({ id: "a2", identifier: "off", status: "DISABLED" })]),
      "POST /users/u1/accounts/a1/disable": () => jsonRes(200, account({ status: "DISABLED" })),
    });
    render(<AccountsSection userId="u1" />);
    await screen.findByText("zhangsan");
    await userEvent.click(within(rowOf("zhangsan")).getByRole("button", { name: "停用" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/最后一个可用/)).toBeInTheDocument();
    expect(api.callsTo("POST /users/u1/accounts/a1/disable")).toHaveLength(0);
    await userEvent.click(within(dialog).getByRole("button", { name: "确认停用" }));
    await waitFor(() => expect(api.callsTo("POST /users/u1/accounts/a1/disable")).toHaveLength(1));
    expect(api.callsTo("POST /users/u1/accounts/a1/disable")[0]!.query.get("confirm")).toBe("true");
  });

  it("falls back to the confirm flow when the backend demands confirmation", async () => {
    let first = true;
    const api = mockApi({
      "GET /users/u1/accounts": () =>
        jsonRes(200, [account(), account({ id: "a2", identifier: "second" })]),
      "POST /users/u1/accounts/a1/disable": (c) => {
        if (first && c.query.get("confirm") !== "true") {
          first = false;
          return problemRes(409, "LAST_ACTIVE_ACCOUNT_CONFIRM_REQUIRED");
        }
        return jsonRes(200, account({ status: "DISABLED" }));
      },
    });
    render(<AccountsSection userId="u1" />);
    await screen.findByText("zhangsan");
    await userEvent.click(within(rowOf("zhangsan")).getByRole("button", { name: "停用" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: "确认停用" }));
    await waitFor(() => expect(api.callsTo("POST /users/u1/accounts/a1/disable")).toHaveLength(2));
    expect(api.callsTo("POST /users/u1/accounts/a1/disable")[1]!.query.get("confirm")).toBe("true");
  });

  it("deletes after confirmation; confirm=true only for the last active account", async () => {
    const api = mockApi({
      "GET /users/u1/accounts": () =>
        jsonRes(200, [account(), account({ id: "a2", identifier: "second" })]),
      "DELETE /users/u1/accounts/a1": () => noContent(),
    });
    render(<AccountsSection userId="u1" />);
    await screen.findByText("zhangsan");
    await userEvent.click(within(rowOf("zhangsan")).getByRole("button", { name: "删除" }));
    await userEvent.click(
      within(await screen.findByRole("dialog")).getByRole("button", { name: "确认删除" }),
    );
    await waitFor(() => expect(api.callsTo("DELETE /users/u1/accounts/a1")).toHaveLength(1));
    expect(api.callsTo("DELETE /users/u1/accounts/a1")[0]!.query.get("confirm")).toBeNull();
  });

  it("deleting the last active account warns and sends confirm=true", async () => {
    const api = mockApi({
      "GET /users/u1/accounts": () => jsonRes(200, [account()]),
      "DELETE /users/u1/accounts/a1": () => noContent(),
    });
    render(<AccountsSection userId="u1" />);
    await screen.findByText("zhangsan");
    await userEvent.click(within(rowOf("zhangsan")).getByRole("button", { name: "删除" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/最后一个可用/)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "确认删除" }));
    await waitFor(() =>
      expect(api.callsTo("DELETE /users/u1/accounts/a1")[0]!.query.get("confirm")).toBe("true"),
    );
  });

  it("enables a disabled account", async () => {
    const api = mockApi({
      "GET /users/u1/accounts": () => jsonRes(200, [account({ status: "DISABLED" })]),
      "POST /users/u1/accounts/a1/enable": () => jsonRes(200, account()),
    });
    render(<AccountsSection userId="u1" />);
    await screen.findByText("zhangsan");
    await userEvent.click(screen.getByRole("button", { name: "启用" }));
    await waitFor(() => expect(api.callsTo("POST /users/u1/accounts/a1/enable")).toHaveLength(1));
  });

  it("unlocks a locked account", async () => {
    const api = mockApi({
      "GET /users/u1/accounts": () => jsonRes(200, both()),
      "POST /users/u1/accounts/a2/unlock": () =>
        jsonRes(200, account({ id: "a2", identifier: "zs.work" })),
    });
    render(<AccountsSection userId="u1" />);
    await screen.findByText("zhangsan");
    await userEvent.click(within(rowOf("zs.work")).getByRole("button", { name: "解锁" }));
    await waitFor(() => expect(api.callsTo("POST /users/u1/accounts/a2/unlock")).toHaveLength(1));
  });

  it("resets a password after confirmation and shows the one-time temporary password", async () => {
    const api = mockApi({
      "GET /users/u1/accounts": () => jsonRes(200, [account()]),
      "POST /users/u1/accounts/a1/reset-password": () =>
        jsonRes(200, {
          ...account({ mustChangePassword: true }),
          temporaryPassword: "Reset-Tmp-Pw-123!",
        }),
    });
    render(<AccountsSection userId="u1" />);
    await screen.findByText("zhangsan");
    await userEvent.click(screen.getByRole("button", { name: "重置密码" }));
    const confirm = await screen.findByRole("dialog");
    expect(within(confirm).getByText(/立即失效/)).toBeInTheDocument();
    await userEvent.click(within(confirm).getByRole("button", { name: "确认重置" }));
    const shown = await screen.findByRole("dialog", { name: /临时密码/ });
    expect(within(shown).getByText("Reset-Tmp-Pw-123!")).toBeInTheDocument();
    expect(api.callsTo("POST /users/u1/accounts/a1/reset-password")).toHaveLength(1);
    await userEvent.click(within(shown).getByRole("button", { name: "我已记下，关闭" }));
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("adds a second account and shows its temporary password once", async () => {
    const api = mockApi({
      "GET /users/u1/accounts": () => jsonRes(200, [account()]),
      "POST /users/u1/accounts": () =>
        jsonRes(201, {
          ...account({ id: "a9", identifier: "new.acct", mustChangePassword: true }),
          temporaryPassword: "New-Tmp-Pw-456!",
        }),
    });
    render(<AccountsSection userId="u1" />);
    await screen.findByText("zhangsan");
    await userEvent.click(screen.getByRole("button", { name: "新增 Account" }));
    const dialog = await screen.findByRole("dialog", { name: "新增 Account" });
    await userEvent.type(within(dialog).getByLabelText(/登录用户名/), "new.acct");
    await userEvent.click(within(dialog).getByRole("button", { name: "创建" }));
    expect(await screen.findByRole("dialog", { name: /临时密码/ })).toBeInTheDocument();
    expect(screen.getByText("New-Tmp-Pw-456!")).toBeInTheDocument();
    expect(api.callsTo("POST /users/u1/accounts")[0]!.body).toEqual({
      type: "USERNAME_PASSWORD",
      username: "new.acct",
    });
  });

  it("validates the new username and shows USERNAME_TAKEN inline", async () => {
    mockApi({
      "GET /users/u1/accounts": () => jsonRes(200, [account()]),
      "POST /users/u1/accounts": () => problemRes(409, "USERNAME_TAKEN"),
    });
    render(<AccountsSection userId="u1" />);
    await screen.findByText("zhangsan");
    await userEvent.click(screen.getByRole("button", { name: "新增 Account" }));
    const dialog = await screen.findByRole("dialog", { name: "新增 Account" });
    await userEvent.click(within(dialog).getByRole("button", { name: "创建" }));
    expect(await within(dialog).findByText(/用户名需 3–32 位/)).toBeInTheDocument();
    await userEvent.type(within(dialog).getByLabelText(/登录用户名/), "zhangsan");
    await userEvent.click(within(dialog).getByRole("button", { name: "创建" }));
    expect(await within(dialog).findByText("该用户名已被使用")).toBeInTheDocument();
  });

  it("maps action errors by code", async () => {
    mockApi({
      "GET /users/u1/accounts": () =>
        jsonRes(200, [account(), account({ id: "a2", identifier: "second" })]),
      "POST /users/u1/accounts/a1/disable": () => problemRes(404, "NOT_FOUND"),
    });
    render(<AccountsSection userId="u1" />);
    await screen.findByText("zhangsan");
    await userEvent.click(within(rowOf("zhangsan")).getByRole("button", { name: "停用" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("资源不存在");
  });

  it("shows a failure state with retry", async () => {
    let fail = true;
    mockApi({
      "GET /users/u1/accounts": () =>
        fail ? problemRes(500, "INTERNAL") : jsonRes(200, [account()]),
    });
    render(<AccountsSection userId="u1" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("加载失败");
    fail = false;
    await userEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByText("zhangsan")).toBeInTheDocument();
  });
});
