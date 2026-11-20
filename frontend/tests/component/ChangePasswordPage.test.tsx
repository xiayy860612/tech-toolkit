import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const replace = vi.fn();
const refresh = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, refresh, push: vi.fn() }),
  usePathname: () => "/change-password",
}));

import ChangePasswordPage from "@/app/(console)/change-password/page";

let fetchMock: ReturnType<typeof vi.fn>;
beforeEach(() => {
  replace.mockReset();
  refresh.mockReset();
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

const problem = (status: number, code: string) =>
  new Response(JSON.stringify({ type: "about:blank", title: code, status, code }), {
    status,
    headers: { "content-type": "application/problem+json" },
  });

async function fill(current: string, next: string, confirm: string) {
  await userEvent.type(screen.getByLabelText("当前密码"), current);
  await userEvent.type(screen.getByLabelText("新密码"), next);
  await userEvent.type(screen.getByLabelText("确认新密码"), confirm);
}

describe("ChangePasswordPage", () => {
  it("ticks the complexity rules live as the user types", async () => {
    render(<ChangePasswordPage />);
    const rule = (name: RegExp) => screen.getByText(name).closest("li")!;
    expect(rule(/长度至少 10 位/)).toHaveAttribute("data-passed", "false");
    await userEvent.type(screen.getByLabelText("新密码"), "Abcdefg1!x");
    expect(rule(/长度至少 10 位/)).toHaveAttribute("data-passed", "true");
    expect(rule(/至少 3 类/)).toHaveAttribute("data-passed", "true");
  });

  it("flags a mismatching confirmation", async () => {
    render(<ChangePasswordPage />);
    await fill("Old-Passw0rd!", "New-Passw0rd!x", "different");
    await userEvent.click(screen.getByRole("button", { name: "修改密码" }));
    expect(await screen.findByText("两次输入的新密码不一致")).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("rejects weak new passwords client-side", async () => {
    render(<ChangePasswordPage />);
    await fill("Old-Passw0rd!", "weak", "weak");
    await userEvent.click(screen.getByRole("button", { name: "修改密码" }));
    expect(await screen.findByText(/密码需至少 10 位/)).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("shows 当前密码不正确 on 422", async () => {
    fetchMock.mockResolvedValue(problem(422, "UNPROCESSABLE"));
    render(<ChangePasswordPage />);
    await fill("Wrong-Passw0rd!", "New-Passw0rd!x", "New-Passw0rd!x");
    await userEvent.click(screen.getByRole("button", { name: "修改密码" }));
    const msg = await screen.findByText("当前密码不正确");
    expect(screen.getByLabelText("当前密码")).toHaveAttribute("aria-invalid", "true");
    expect(msg).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });

  it("shows server validation errors generically", async () => {
    fetchMock.mockResolvedValue(problem(400, "VALIDATION_FAILED"));
    render(<ChangePasswordPage />);
    await fill("Old-Passw0rd!", "New-Passw0rd!x", "New-Passw0rd!x");
    await userEvent.click(screen.getByRole("button", { name: "修改密码" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("提交的内容有误");
  });

  it("posts to the BFF and enters the user list on success", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    render(<ChangePasswordPage />);
    await fill("Old-Passw0rd!", "New-Passw0rd!x", "New-Passw0rd!x");
    await userEvent.click(screen.getByRole("button", { name: "修改密码" }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/users"));
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe("/api/v1/auth/change-password");
    expect(JSON.parse(init.body)).toEqual({
      currentPassword: "Old-Passw0rd!",
      newPassword: "New-Passw0rd!x",
    });
    expect(refresh).toHaveBeenCalled();
  });
});
