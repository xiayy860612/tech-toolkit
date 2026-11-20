import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const replace = vi.fn();
const hardNavigate = vi.fn();
const refresh = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, refresh, push: vi.fn() }),
  usePathname: () => "/login",
}));
vi.mock("@/lib/navigation", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/navigation")>()),
  hardNavigate: (url: string) => hardNavigate(url),
}));

import LoginPage from "@/app/(public)/login/page";

let fetchMock: ReturnType<typeof vi.fn>;
beforeEach(() => {
  replace.mockReset();
  hardNavigate.mockReset();
  refresh.mockReset();
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

const ok = (body: unknown) =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
const problem = (status: number, code: string) =>
  new Response(JSON.stringify({ type: "about:blank", title: code, status, code }), {
    status,
    headers: { "content-type": "application/problem+json" },
  });

async function renderLogin(next?: string) {
  render(await LoginPage({ searchParams: Promise.resolve(next ? { next } : {}) }));
}
async function submit(username = "admin", password = "Passw0rd!x") {
  await userEvent.type(screen.getByLabelText("用户名"), username);
  await userEvent.type(screen.getByLabelText("密码"), password);
  await userEvent.click(screen.getByRole("button", { name: "登录" }));
}

describe("LoginPage", () => {
  it("uses 用户名 and has no tenant-related inputs", async () => {
    await renderLogin();
    expect(screen.getByLabelText("用户名")).toBeInTheDocument();
    const labels = screen.getAllByRole("textbox").concat(screen.getAllByLabelText("密码"));
    expect(labels).toHaveLength(2);
    expect(screen.queryByLabelText(/租户/)).toBeNull();
    expect(screen.queryByPlaceholderText(/手机号|邮箱/)).toBeNull();
  });

  it.each(["忘记密码", "企业 SSO", "扫码登录", "7 天内免登录"])(
    "does not contain %s",
    async (text) => {
      await renderLogin();
      expect(document.body.textContent).not.toContain(text);
    },
  );

  it("shows brand copy about isolation, multi-account login and audit", async () => {
    await renderLogin();
    expect(screen.getByText(/多租户隔离/)).toBeInTheDocument();
    expect(screen.getByText(/操作审计/)).toBeInTheDocument();
  });

  it("validates empty fields without calling the API", async () => {
    await renderLogin();
    await userEvent.click(screen.getByRole("button", { name: "登录" }));
    expect(await screen.findByText("请输入用户名")).toBeInTheDocument();
    expect(screen.getByText("请输入密码")).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("shows the unified message on failure", async () => {
    fetchMock.mockResolvedValue(problem(401, "INVALID_CREDENTIALS"));
    await renderLogin();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("用户名或密码错误");
    expect(hardNavigate).not.toHaveBeenCalled();
  });

  it("shows the rate-limit message on 429", async () => {
    fetchMock.mockResolvedValue(problem(429, "TOO_MANY_REQUESTS"));
    await renderLogin();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("操作过于频繁");
  });

  it("posts credentials to the BFF and goes to /users on success", async () => {
    fetchMock.mockResolvedValue(
      ok({ userId: "u", accountId: "a", displayName: "管理员", mustChangePassword: false }),
    );
    await renderLogin();
    await submit("admin", "Passw0rd!x");
    await waitFor(() => expect(hardNavigate).toHaveBeenCalledWith("/users"));
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe("/api/v1/auth/login");
    expect(JSON.parse(init.body)).toEqual({ username: "admin", password: "Passw0rd!x" });
  });

  it("goes to /change-password when a password change is required", async () => {
    fetchMock.mockResolvedValue(
      ok({ userId: "u", accountId: "a", displayName: "管理员", mustChangePassword: true }),
    );
    await renderLogin("/sessions");
    await submit();
    await waitFor(() => expect(hardNavigate).toHaveBeenCalledWith("/change-password"));
  });

  it("returns to the original page from ?next=", async () => {
    fetchMock.mockResolvedValue(
      ok({ userId: "u", accountId: "a", displayName: "管理员", mustChangePassword: false }),
    );
    await renderLogin("/sessions?page=1");
    await submit();
    await waitFor(() => expect(hardNavigate).toHaveBeenCalledWith("/sessions?page=1"));
  });

  it.each(["//evil.example", "https://evil.example", "javascript:alert(1)", "/\\evil.example"])(
    "ignores unsafe next=%s",
    async (next) => {
      fetchMock.mockResolvedValue(
        ok({ userId: "u", accountId: "a", displayName: "管理员", mustChangePassword: false }),
      );
      await renderLogin(next);
      await submit();
      await waitFor(() => expect(hardNavigate).toHaveBeenCalledWith("/users"));
    },
  );

  it("disables the button while submitting", async () => {
    let release!: (r: Response) => void;
    fetchMock.mockReturnValue(new Promise<Response>((r) => (release = r)));
    await renderLogin();
    await submit();
    expect(screen.getByRole("button", { name: /登录/ })).toBeDisabled();
    release(problem(401, "INVALID_CREDENTIALS"));
    await screen.findByRole("alert");
    expect(screen.getByRole("button", { name: "登录" })).toBeEnabled();
  });
});
