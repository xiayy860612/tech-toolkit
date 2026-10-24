import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { changePassword } from "@/app/(shell)/account/password/actions";
import { ChangePasswordForm } from "@/components/auth/change-password-form";

jest.mock("@/app/(shell)/account/password/actions", () => ({ changePassword: jest.fn() }));

const action = jest.mocked(changePassword);
const CURRENT = "Old-Secret-pass1";
const NEW = "New-Secret-pass2";

async function fillAndSubmit(user: ReturnType<typeof userEvent.setup>, values: { current?: string; next?: string; confirm?: string } = {}) {
  await user.type(screen.getByLabelText("当前密码"), values.current ?? CURRENT);
  await user.type(screen.getByLabelText("新密码"), values.next ?? NEW);
  await user.type(screen.getByLabelText("确认新密码"), values.confirm ?? values.next ?? NEW);
  await user.click(screen.getByRole("button", { name: "修改密码" }));
}

afterEach(() => jest.resetAllMocks());

describe("ChangePasswordForm：结构与无障碍（与原型一致）", () => {
  it("三个字段、新密码的说明、提交按钮与取消链接", () => {
    render(<ChangePasswordForm />);
    expect(screen.getByLabelText("当前密码")).toBeInTheDocument();
    expect(screen.getByLabelText("新密码")).toHaveAccessibleDescription("至少 10 位，且同时包含字母和数字，不能与当前密码相同。");
    expect(screen.getByLabelText("确认新密码")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "修改密码" })).toHaveAttribute("type", "submit");
    expect(screen.getByRole("link", { name: "取消" })).toHaveAttribute("href", "/users");
  });

  it("autocomplete：current-password / new-password / new-password；三个都是 type=password", () => {
    render(<ChangePasswordForm />);
    expect(screen.getByLabelText("当前密码")).toHaveAttribute("autocomplete", "current-password");
    expect(screen.getByLabelText("新密码")).toHaveAttribute("autocomplete", "new-password");
    expect(screen.getByLabelText("确认新密码")).toHaveAttribute("autocomplete", "new-password");
    for (const label of ["当前密码", "新密码", "确认新密码"]) {
      expect(screen.getByLabelText(label)).toHaveAttribute("type", "password");
    }
  });

  it("触控目标不小于 44px：输入框与按钮高度 h-11", () => {
    render(<ChangePasswordForm />);
    for (const control of [
      screen.getByLabelText("当前密码"),
      screen.getByLabelText("新密码"),
      screen.getByLabelText("确认新密码"),
      screen.getByRole("button", { name: "修改密码" }),
      screen.getByRole("link", { name: "取消" }),
    ]) {
      expect(control).toHaveClass("h-11");
    }
  });

  it("表单里没有 accountId 之类的隐藏字段：账号只由服务端决定", () => {
    const { container } = render(<ChangePasswordForm />);
    expect(container.querySelectorAll('input[type="hidden"]')).toHaveLength(0);
  });

  it("初始没有任何提示", () => {
    render(<ChangePasswordForm />);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(screen.getByLabelText("当前密码")).not.toHaveAttribute("aria-invalid");
  });
});

describe("ChangePasswordForm：提交", () => {
  it("提交当前密码、新密码与确认（交给改密动作）", async () => {
    action.mockResolvedValue({});
    const user = userEvent.setup();
    render(<ChangePasswordForm />);
    await fillAndSubmit(user);
    await waitFor(() => expect(action).toHaveBeenCalledTimes(1));
    expect(Object.fromEntries(action.mock.calls[0][1].entries())).toEqual({
      currentPassword: CURRENT,
      newPassword: NEW,
      confirmPassword: NEW,
    });
  });

  it("提交期间按钮禁用并提示进行中（防重复提交）；结束后恢复", async () => {
    let finish: (value: object) => void = () => undefined;
    action.mockReturnValue(new Promise((resolve) => (finish = resolve)));
    const user = userEvent.setup();
    render(<ChangePasswordForm />);
    await user.click(screen.getByRole("button", { name: "修改密码" }));

    const pending = await screen.findByRole("button", { name: "修改中…" });
    expect(pending).toBeDisabled();
    await user.click(pending);
    expect(action).toHaveBeenCalledTimes(1);

    finish({});
    expect(await screen.findByRole("button", { name: "修改密码" })).toBeEnabled();
  });
});

describe("ChangePasswordForm：成功", () => {
  it("提示「密码已修改」（status）；三个密码框清空；页面里没有任何密码", async () => {
    action.mockResolvedValue({ success: true });
    const user = userEvent.setup();
    render(<ChangePasswordForm />);
    await fillAndSubmit(user);

    expect(await screen.findByRole("status")).toHaveTextContent("密码已修改，当前会话保持有效。");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    for (const label of ["当前密码", "新密码", "确认新密码"]) {
      await waitFor(() => expect(screen.getByLabelText(label)).toHaveValue(""));
    }
    expect(document.body.innerHTML).not.toContain(CURRENT);
    expect(document.body.innerHTML).not.toContain(NEW);
  });

  it("成功后再次提交失败：成功提示消失", async () => {
    action.mockResolvedValueOnce({ success: true }).mockResolvedValueOnce({ fieldErrors: { currentPassword: "当前密码不正确" } });
    const user = userEvent.setup();
    render(<ChangePasswordForm />);
    await fillAndSubmit(user);
    await screen.findByRole("status");
    await fillAndSubmit(user);
    await waitFor(() => expect(screen.queryByRole("status")).not.toBeInTheDocument());
    expect(screen.getByLabelText("当前密码")).toHaveAccessibleDescription("当前密码不正确");
  });
});

describe("ChangePasswordForm：错误与不回填", () => {
  it("字段错误显示在字段旁，并通过 aria-invalid / aria-describedby 关联（新密码的原因替换说明文字）", async () => {
    action.mockResolvedValue({
      fieldErrors: {
        currentPassword: "当前密码不正确",
        newPassword: "新密码不能与当前密码相同",
        confirmPassword: "两次输入的新密码不一致",
      },
    });
    const user = userEvent.setup();
    render(<ChangePasswordForm />);
    await fillAndSubmit(user);

    const current = screen.getByLabelText("当前密码");
    await waitFor(() => expect(current).toHaveAttribute("aria-invalid", "true"));
    expect(current).toHaveAccessibleDescription("当前密码不正确");
    expect(screen.getByLabelText("新密码")).toHaveAccessibleDescription("新密码不能与当前密码相同");
    expect(screen.getByLabelText("新密码")).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByLabelText("确认新密码")).toHaveAccessibleDescription("两次输入的新密码不一致");
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("失败后三个密码框都清空，页面里没有任何密码（不回填）", async () => {
    action.mockResolvedValue({ fieldErrors: { currentPassword: "当前密码不正确" } });
    const user = userEvent.setup();
    render(<ChangePasswordForm />);
    await fillAndSubmit(user);

    await waitFor(() => expect(screen.getByLabelText("当前密码")).toHaveAttribute("aria-invalid", "true"));
    for (const label of ["当前密码", "新密码", "确认新密码"]) {
      expect(screen.getByLabelText(label)).toHaveValue("");
    }
    expect(document.body.innerHTML).not.toContain(CURRENT);
    expect(document.body.innerHTML).not.toContain(NEW);
  });

  it("表单级提示（限流、操作未生效）以 alert 展示", async () => {
    action.mockResolvedValue({ formError: "尝试过于频繁，请 900 秒后再试" });
    const user = userEvent.setup();
    render(<ChangePasswordForm />);
    await fillAndSubmit(user);

    expect(await screen.findByRole("alert")).toHaveTextContent("尝试过于频繁，请 900 秒后再试");
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(screen.getByLabelText("当前密码")).toHaveValue("");
  });
});
