import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { login } from "@/app/(auth)/login/actions";
import { LoginForm } from "@/components/auth/login-form";

jest.mock("@/app/(auth)/login/actions", () => ({ login: jest.fn() }));

const action = jest.mocked(login);

afterEach(() => jest.resetAllMocks());

describe("LoginForm：结构与无障碍", () => {
  it("标题、说明、两个字段与登录按钮（与原型一致）", () => {
    render(<LoginForm next="/users" />);
    expect(screen.getByRole("heading", { level: 1, name: "Admin Center" })).toBeInTheDocument();
    expect(screen.getByText("请使用账号登录")).toBeInTheDocument();
    expect(screen.getByLabelText("账号名")).toBeInTheDocument();
    expect(screen.getByLabelText("密码")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "登录" })).toHaveAttribute("type", "submit");
  });

  it("autocomplete 为 username / current-password，密码框是 type=password", () => {
    render(<LoginForm next="/users" />);
    expect(screen.getByLabelText("账号名")).toHaveAttribute("autocomplete", "username");
    const password = screen.getByLabelText("密码");
    expect(password).toHaveAttribute("autocomplete", "current-password");
    expect(password).toHaveAttribute("type", "password");
  });

  it("next 作为隐藏字段随表单提交", () => {
    const { container } = render(<LoginForm next="/users/abc" />);
    expect(container.querySelector('input[type="hidden"][name="next"]')).toHaveValue("/users/abc");
  });

  it("有提示（如「会话已结束」）时以 status 展示", () => {
    render(<LoginForm next="/users" notice="会话已结束，请重新登录。" />);
    expect(screen.getByRole("status")).toHaveTextContent("会话已结束，请重新登录。");
  });

  it("初始没有任何错误提示", () => {
    render(<LoginForm next="/users" />);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByLabelText("账号名")).not.toHaveAttribute("aria-invalid");
  });
});

describe("LoginForm：提交与错误", () => {
  it("提交账号名、密码与 next（交给登录动作）", async () => {
    action.mockResolvedValue({});
    const user = userEvent.setup();
    render(<LoginForm next="/users/abc" />);
    await user.type(screen.getByLabelText("账号名"), "zhangsan");
    await user.type(screen.getByLabelText("密码"), "pw-123");
    await user.click(screen.getByRole("button", { name: "登录" }));
    await waitFor(() => expect(action).toHaveBeenCalledTimes(1));
    const data = action.mock.calls[0][1];
    expect(Object.fromEntries(data.entries())).toEqual({ accountName: "zhangsan", password: "pw-123", next: "/users/abc" });
  });

  it("字段错误显示在字段旁，并通过 aria-invalid / aria-describedby 关联", async () => {
    action.mockResolvedValue({ fieldErrors: { accountName: "请填写账号名", password: "请填写密码" } });
    const user = userEvent.setup();
    render(<LoginForm next="/users" />);
    await user.click(screen.getByRole("button", { name: "登录" }));

    const account = await screen.findByLabelText("账号名");
    await waitFor(() => expect(account).toHaveAttribute("aria-invalid", "true"));
    expect(account).toHaveAccessibleDescription("请填写账号名");
    expect(screen.getByLabelText("密码")).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByLabelText("密码")).toHaveAccessibleDescription("请填写密码");
  });

  it("统一失败提示以 alert 展示，保留账号名、绝不回填密码", async () => {
    action.mockResolvedValue({ formError: "账号名或密码错误", accountName: "zhangsan" });
    const user = userEvent.setup();
    render(<LoginForm next="/users" />);
    await user.type(screen.getByLabelText("账号名"), "zhangsan");
    await user.type(screen.getByLabelText("密码"), "wrong-pass");
    await user.click(screen.getByRole("button", { name: "登录" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("账号名或密码错误");
    expect(screen.getByLabelText("账号名")).toHaveValue("zhangsan");
    expect(screen.getByLabelText("密码")).toHaveValue("");
    expect(document.body.innerHTML).not.toContain("wrong-pass");
  });

  it("提交期间按钮禁用并提示进行中，防止重复提交", async () => {
    let finish: (value: object) => void = () => undefined;
    action.mockReturnValue(new Promise((resolve) => (finish = resolve)));
    const user = userEvent.setup();
    render(<LoginForm next="/users" />);
    await user.click(screen.getByRole("button", { name: "登录" }));

    const pending = await screen.findByRole("button", { name: "登录中…" });
    expect(pending).toBeDisabled();
    finish({});
    expect(await screen.findByRole("button", { name: "登录" })).toBeEnabled();
  });
});
