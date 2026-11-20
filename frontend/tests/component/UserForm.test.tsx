import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const push = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push, refresh: vi.fn() }),
  usePathname: () => "/users/new",
}));

import { UserForm } from "@/features/users/UserForm";
import { jsonRes, mockApi, problemRes } from "@tests/helpers/fetch-mock";

const created = (temporaryPassword: string | null = "Tmp-Pa55w0rd-xyz") => ({
  user: {
    id: 7,
    displayName: "王五",
    username: "wangwu",
    role: "MEMBER",
    status: "ACTIVE",
    locked: false,
    mustChangePassword: true,
    version: 0,
    createdAt: "2030-01-01T00:00:00Z",
  },
  temporaryPassword,
});

beforeEach(() => push.mockReset());
afterEach(() => vi.unstubAllGlobals());

const existing = {
  id: 1,
  displayName: "张三",
  role: "MEMBER" as const,
  status: "ACTIVE" as const,
  username: "zhangsan",
  locked: false,
  lockedUntil: null,
  mustChangePassword: false,
  lastLoginAt: null,
  version: 3,
  createdAt: "2030-01-01T00:00:00Z",
};

describe("UserForm (create)", () => {
  it("has the two grouped cards and only the fields in scope", () => {
    render(<UserForm mode="create" />);
    expect(screen.getByRole("heading", { name: "基本信息" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "账号与角色" })).toBeInTheDocument();
    expect(screen.getByLabelText(/姓名/)).toBeInTheDocument();
    expect(screen.queryByLabelText("手机号")).toBeNull();
    expect(screen.queryByLabelText("邮箱")).toBeNull();
    expect(screen.getByLabelText(/登录用户名/)).toBeInTheDocument();
    expect(screen.getByLabelText("临时密码")).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: /租户管理员/ })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: /普通用户/ })).toBeChecked();
    for (const gone of ["所属部门", "数据权限范围", "备注", "发送激活通知", "发送账号激活"]) {
      expect(document.body.textContent).not.toContain(gone);
    }
  });

  it("summarizes failing validations in the sticky bar and flags the fields", async () => {
    mockApi({});
    render(<UserForm mode="create" />);
    await userEvent.click(screen.getByRole("button", { name: "创建用户" }));
    expect(await screen.findByText("有 2 项校验未通过")).toBeInTheDocument();
    expect(screen.getByLabelText(/姓名/)).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByLabelText(/登录用户名/)).toHaveAttribute("aria-invalid", "true");
  });

  it("validates username format and password complexity", async () => {
    mockApi({});
    render(<UserForm mode="create" />);
    await userEvent.type(screen.getByLabelText(/姓名/), "王五");
    await userEvent.type(screen.getByLabelText(/登录用户名/), "a!");
    await userEvent.type(screen.getByLabelText("临时密码"), "weak");
    await userEvent.click(screen.getByRole("button", { name: "创建用户" }));
    expect(await screen.findByText("有 2 项校验未通过")).toBeInTheDocument();
  });

  it("creates the user with a system-generated password and shows it once", async () => {
    const api = mockApi({ "POST /users": () => jsonRes(201, created()) });
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });

    render(<UserForm mode="create" />);
    await userEvent.type(screen.getByLabelText(/姓名/), "王五");
    await userEvent.type(screen.getByLabelText(/登录用户名/), "wangwu");
    await userEvent.click(screen.getByRole("radio", { name: /租户管理员/ }));
    await userEvent.click(screen.getByRole("button", { name: "创建用户" }));

    expect(api.callsTo("POST /users")[0]!.body).toEqual({
      displayName: "王五",
      role: "TENANT_ADMIN",
      username: "wangwu",
    });

    const dialog = await screen.findByRole("dialog", { name: /临时密码/ });
    expect(within(dialog).getByText("Tmp-Pa55w0rd-xyz")).toBeInTheDocument();
    expect(within(dialog).getByText(/关闭后不可再次查看/)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "复制" }));
    expect(writeText).toHaveBeenCalledWith("Tmp-Pa55w0rd-xyz");
    expect(await within(dialog).findByText("已复制")).toBeInTheDocument();

    await userEvent.click(within(dialog).getByRole("button", { name: "我已记下，关闭" }));
    expect(push).toHaveBeenCalledWith("/users/7");
  });

  it("sends a supplied temporary password and skips the dialog when none comes back", async () => {
    const api = mockApi({ "POST /users": () => jsonRes(201, created(null)) });
    render(<UserForm mode="create" />);
    await userEvent.type(screen.getByLabelText(/姓名/), "王五");
    await userEvent.type(screen.getByLabelText(/登录用户名/), "wangwu");
    await userEvent.type(screen.getByLabelText("临时密码"), "Abcdefg1!xyz");
    await userEvent.click(screen.getByRole("button", { name: "创建用户" }));
    await waitFor(() => expect(push).toHaveBeenCalledWith("/users/7"));
    expect(
      (api.callsTo("POST /users")[0]!.body as { temporaryPassword: string }).temporaryPassword,
    ).toBe("Abcdefg1!xyz");
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("shows USERNAME_TAKEN inline on the username field", async () => {
    mockApi({ "POST /users": () => problemRes(409, "USERNAME_TAKEN") });
    render(<UserForm mode="create" />);
    await userEvent.type(screen.getByLabelText(/姓名/), "王五");
    await userEvent.type(screen.getByLabelText(/登录用户名/), "wangwu");
    await userEvent.click(screen.getByRole("button", { name: "创建用户" }));
    expect(await screen.findByText("该用户名已被使用")).toBeInTheDocument();
    expect(screen.getByLabelText(/登录用户名/)).toHaveAttribute("aria-invalid", "true");
    expect(push).not.toHaveBeenCalled();
  });

  it("maps server-side field errors onto fields", async () => {
    mockApi({
      "POST /users": () =>
        problemRes(400, "VALIDATION_FAILED", [{ field: "displayName", message: "姓名不合法" }]),
    });
    render(<UserForm mode="create" />);
    await userEvent.type(screen.getByLabelText(/姓名/), "王五");
    await userEvent.type(screen.getByLabelText(/登录用户名/), "wangwu");
    await userEvent.click(screen.getByRole("button", { name: "创建用户" }));
    expect(await screen.findByText("姓名不合法")).toBeInTheDocument();
  });

  it("shows a generic alert for other failures", async () => {
    mockApi({ "POST /users": () => problemRes(500, "INTERNAL") });
    render(<UserForm mode="create" />);
    await userEvent.type(screen.getByLabelText(/姓名/), "王五");
    await userEvent.type(screen.getByLabelText(/登录用户名/), "wangwu");
    await userEvent.click(screen.getByRole("button", { name: "创建用户" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("操作失败");
  });

  it("cancels back to the list", async () => {
    render(<UserForm mode="create" />);
    expect(screen.getByRole("link", { name: "取消" })).toHaveAttribute("href", "/users");
  });
});

describe("UserForm (edit)", () => {
  it("has no username or password fields and is prefilled", () => {
    render(<UserForm mode="edit" user={existing} />);
    expect(screen.queryByLabelText(/登录用户名/)).toBeNull();
    expect(screen.queryByLabelText("临时密码")).toBeNull();
    expect(screen.getByLabelText(/姓名/)).toHaveValue("张三");
    expect(screen.queryByLabelText("手机号")).toBeNull();
    expect(screen.queryByLabelText("邮箱")).toBeNull();
  });

  it("locks the role choice when editing yourself and still saves other fields", async () => {
    const api = mockApi({
      "PATCH /users/1": () => jsonRes(200, { ...existing, displayName: "张三丰", version: 4 }),
    });
    render(<UserForm mode="edit" user={{ ...existing, role: "TENANT_ADMIN" }} currentUserId={1} />);
    for (const radio of screen.getAllByRole("radio")) expect(radio).toBeDisabled();
    expect(screen.getByText("不能修改自己的角色")).toBeInTheDocument();
    const name = screen.getByLabelText(/姓名/);
    await userEvent.clear(name);
    await userEvent.type(name, "张三丰");
    await userEvent.click(screen.getByRole("button", { name: "保存" }));
    await waitFor(() => expect(api.callsTo("PATCH /users/1")).toHaveLength(1));
    expect(api.callsTo("PATCH /users/1")[0]!.body).toMatchObject({ role: "TENANT_ADMIN" });
  });

  it("keeps the role choice open when editing someone else", () => {
    render(<UserForm mode="edit" user={existing} currentUserId={2} />);
    for (const radio of screen.getAllByRole("radio")) expect(radio).toBeEnabled();
    expect(screen.queryByText("不能修改自己的角色")).toBeNull();
  });

  it("maps CANNOT_CHANGE_OWN_ROLE from the server", async () => {
    mockApi({ "PATCH /users/1": () => problemRes(409, "CANNOT_CHANGE_OWN_ROLE") });
    render(<UserForm mode="edit" user={existing} />);
    await userEvent.click(screen.getByRole("button", { name: "保存" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("不能修改自己的角色");
  });

  it("patches with the version for optimistic locking", async () => {
    const api = mockApi({
      "PATCH /users/1": () => jsonRes(200, { ...existing, displayName: "张三丰", version: 4 }),
    });
    render(<UserForm mode="edit" user={existing} />);
    const name = screen.getByLabelText(/姓名/);
    await userEvent.clear(name);
    await userEvent.type(name, "张三丰");
    await userEvent.click(screen.getByRole("button", { name: "保存" }));
    await waitFor(() => expect(push).toHaveBeenCalledWith("/users/1"));
    expect(api.callsTo("PATCH /users/1")[0]!.body).toEqual({
      displayName: "张三丰",
      role: "MEMBER",
      version: 3,
    });
  });

  it("on VERSION_CONFLICT tells the user to refresh and offers a reload", async () => {
    mockApi({ "PATCH /users/1": () => problemRes(409, "VERSION_CONFLICT") });
    const onReload = vi.fn();
    render(<UserForm mode="edit" user={existing} onReload={onReload} />);
    await userEvent.click(screen.getByRole("button", { name: "保存" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("数据已被他人修改，请刷新后重试");
    await userEvent.click(screen.getByRole("button", { name: "刷新" }));
    expect(onReload).toHaveBeenCalled();
  });

  it("maps LAST_ADMIN when demoting the last administrator", async () => {
    mockApi({ "PATCH /users/1": () => problemRes(409, "LAST_ADMIN") });
    render(<UserForm mode="edit" user={{ ...existing, role: "TENANT_ADMIN" }} />);
    await userEvent.click(screen.getByRole("radio", { name: /普通用户/ }));
    await userEvent.click(screen.getByRole("button", { name: "保存" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("不能停用或降级最后一名管理员");
  });
});
