import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CredentialSection } from "@/features/credentials/CredentialSection";
import type { User } from "@/lib/api/types";
import { formatTime } from "@/lib/format";
import { jsonRes, mockApi, problemRes } from "@tests/helpers/fetch-mock";

const LOCKED_UNTIL = new Date(2030, 0, 1, 10, 30).toISOString();

const user = (over: Partial<User> = {}): User => ({
  id: 1,
  displayName: "张三",
  username: "zhangsan",
  role: "MEMBER",
  status: "ACTIVE",
  locked: false,
  lockedUntil: null,
  mustChangePassword: false,
  lastLoginAt: null,
  version: 0,
  createdAt: "2030-01-01T00:00:00Z",
  ...over,
});

afterEach(() => vi.unstubAllGlobals());

describe("CredentialSection", () => {
  it("shows the built-in username/password login and nothing about Accounts", () => {
    render(<CredentialSection user={user()} onChanged={() => undefined} />);
    for (const h of ["类型", "登录标识", "状态", "最近登录", "操作"]) {
      expect(screen.getByRole("columnheader", { name: h })).toBeInTheDocument();
    }
    expect(screen.getByText("用户名密码")).toBeInTheDocument();
    expect(screen.getByText("zhangsan")).toBeInTheDocument();
    expect(screen.getByText("从未登录")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "重置密码" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "解锁" })).toBeNull();
    for (const name of ["新增 Account", "停用", "删除"]) {
      expect(screen.queryByRole("button", { name })).toBeNull();
    }
  });

  it("shows a locked pill and offers 解锁 only when locked", async () => {
    const onChanged = vi.fn();
    const api = mockApi({ "POST /users/1/unlock": () => jsonRes(200, user()) });
    render(
      <CredentialSection
        user={user({ locked: true, lockedUntil: LOCKED_UNTIL })}
        onChanged={onChanged}
      />,
    );
    expect(screen.getByText(`已锁定至 ${formatTime(LOCKED_UNTIL)}`)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "解锁" }));
    await waitFor(() => expect(api.callsTo("POST /users/1/unlock")).toHaveLength(1));
    await waitFor(() => expect(onChanged).toHaveBeenCalledWith(user()));
  });

  it("resets the password after confirmation and shows the one-time temporary password", async () => {
    const onChanged = vi.fn();
    const api = mockApi({
      "POST /users/1/reset-password": () =>
        jsonRes(200, {
          user: user({ mustChangePassword: true }),
          temporaryPassword: "Reset-Tmp-Pw-123!",
        }),
    });
    render(<CredentialSection user={user()} onChanged={onChanged} />);
    await userEvent.click(screen.getByRole("button", { name: "重置密码" }));
    const confirm = await screen.findByRole("dialog");
    expect(within(confirm).getByText(/旧密码立即失效/)).toBeInTheDocument();
    await userEvent.click(within(confirm).getByRole("button", { name: "确认重置" }));

    const shown = await screen.findByRole("dialog", { name: /临时密码/ });
    expect(within(shown).getByText("Reset-Tmp-Pw-123!")).toBeInTheDocument();
    expect(api.callsTo("POST /users/1/reset-password")).toHaveLength(1);
    expect(onChanged).toHaveBeenCalledWith(user({ mustChangePassword: true }));
    await userEvent.click(within(shown).getByRole("button", { name: "我已记下，关闭" }));
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("maps action errors by code", async () => {
    mockApi({ "POST /users/1/unlock": () => problemRes(404, "NOT_FOUND") });
    render(
      <CredentialSection
        user={user({ locked: true, lockedUntil: LOCKED_UNTIL })}
        onChanged={() => undefined}
      />,
    );
    await userEvent.click(screen.getByRole("button", { name: "解锁" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("资源不存在");
  });

  it("reports a failed reset without showing a password", async () => {
    mockApi({ "POST /users/1/reset-password": () => problemRes(404, "NOT_FOUND") });
    render(<CredentialSection user={user()} onChanged={() => undefined} />);
    await userEvent.click(screen.getByRole("button", { name: "重置密码" }));
    await userEvent.click(
      within(await screen.findByRole("dialog")).getByRole("button", { name: "确认重置" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent("资源不存在");
    expect(screen.queryByRole("dialog", { name: /临时密码/ })).toBeNull();
  });
});
