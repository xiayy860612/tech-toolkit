import { render, screen, within } from "@testing-library/react";
import NewUserPage from "@/app/(shell)/users/new/page";
import { requireOperator } from "@/lib/session/get-session";

jest.mock("@/app/(shell)/users/new/actions", () => ({ createUser: jest.fn() }));
jest.mock("@/lib/session/get-session", () => ({ requireOperator: jest.fn() }));

const keyOf = (container: HTMLElement) =>
  container.querySelector<HTMLInputElement>('input[type="hidden"][name="idempotencyKey"]')!.value;

const OPERATOR = { userId: "u", accountId: "a", sessionRef: "r" };
beforeEach(() => jest.mocked(requireOperator).mockResolvedValue(OPERATOR));
afterEach(() => jest.clearAllMocks());

describe("/users/new 新建用户页", () => {
  it("requireOperator() 是第一步（US13）：匿名访问时跳转，不渲染表单", async () => {
    jest.mocked(requireOperator).mockRejectedValue(new Error("NEXT_REDIRECT:/login?next=%2Fusers%2Fnew"));
    await expect(NewUserPage()).rejects.toThrow("NEXT_REDIRECT:/login?next=%2Fusers%2Fnew");
  });

  it("unverified（requireOperator 返回 null）：表单照常渲染（没有数据，不受影响）", async () => {
    jest.mocked(requireOperator).mockResolvedValue(null);
    render(await NewUserPage());
    expect(screen.getByRole("heading", { level: 1, name: "新建用户" })).toBeInTheDocument();
  });

  it("h1「新建用户」、说明、面包屑「用户管理 / 新建用户」、表单", async () => {
    render(await NewUserPage());
    expect(screen.getByRole("heading", { level: 1, name: "新建用户" })).toBeInTheDocument();
    expect(screen.getByText("创建一个用户及其一个「账号名 / 密码」账号。")).toBeInTheDocument();
    const crumbs = screen.getByRole("navigation", { name: "面包屑" });
    expect(within(crumbs).getByRole("link", { name: "用户管理" })).toHaveAttribute("href", "/users");
    expect(within(crumbs).getByText("新建用户")).toHaveAttribute("aria-current", "page");
    expect(screen.getByLabelText("账号名")).toBeInTheDocument();
  });

  it("每次渲染生成一个新的 Idempotency-Key（长度满足 UAM 契约 16–64）", async () => {
    const first = render(await NewUserPage());
    const a = keyOf(first.container);
    first.unmount();
    const second = render(await NewUserPage());
    const b = keyOf(second.container);

    for (const key of [a, b]) {
      expect(key.length).toBeGreaterThanOrEqual(16);
      expect(key.length).toBeLessThanOrEqual(64);
      expect(key).toMatch(/^[0-9a-f-]{36}$/);
    }
    expect(a).not.toBe(b);
  });
});
