import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createUser } from "@/app/(shell)/users/new/actions";
import { UserForm } from "@/components/users/user-form";

jest.mock("@/app/(shell)/users/new/actions", () => ({ createUser: jest.fn() }));

const action = jest.mocked(createUser);
const KEY = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
const NEW_KEY = "ffffffff-bbbb-4ccc-8ddd-eeeeeeeeeeee";

const hiddenKey = (container: HTMLElement) => container.querySelector<HTMLInputElement>('input[type="hidden"][name="idempotencyKey"]');

async function fillAndSubmit(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText("用户名"), "王五");
  await user.type(screen.getByLabelText("账号名"), "wangwu");
  await user.type(screen.getByLabelText("初始密码"), "Sup3r-Secret-pass");
  await user.click(screen.getByRole("button", { name: "创建用户" }));
}

afterEach(() => jest.resetAllMocks());

describe("UserForm：结构与无障碍（与原型一致）", () => {
  it("三个字段、各自的说明、创建按钮与取消链接", () => {
    render(<UserForm idempotencyKey={KEY} />);
    expect(screen.getByLabelText("用户名")).toHaveAccessibleDescription("1–64 个字符，可以与其他用户重名。");
    expect(screen.getByLabelText("账号名")).toHaveAccessibleDescription("3–32 个字母、数字或 _ . -，不区分大小写，创建后不可修改。");
    expect(screen.getByLabelText("初始密码")).toHaveAccessibleDescription("至少 10 位，且同时包含字母和数字。");
    expect(screen.getByRole("button", { name: "创建用户" })).toHaveAttribute("type", "submit");
    expect(screen.getByRole("link", { name: "取消" })).toHaveAttribute("href", "/users");
  });

  it("autocomplete：账号名 off、密码 new-password；密码框是 type=password；用户名无多余属性", () => {
    render(<UserForm idempotencyKey={KEY} />);
    expect(screen.getByLabelText("账号名")).toHaveAttribute("autocomplete", "off");
    const password = screen.getByLabelText("初始密码");
    expect(password).toHaveAttribute("autocomplete", "new-password");
    expect(password).toHaveAttribute("type", "password");
    expect(screen.getByLabelText("用户名")).toHaveAttribute("type", "text");
  });

  it("触控目标不小于 44px：输入框与按钮高度 h-11", () => {
    render(<UserForm idempotencyKey={KEY} />);
    for (const control of [
      screen.getByLabelText("用户名"),
      screen.getByLabelText("账号名"),
      screen.getByLabelText("初始密码"),
      screen.getByRole("button", { name: "创建用户" }),
      screen.getByRole("link", { name: "取消" }),
    ]) {
      expect(control).toHaveClass("h-11");
    }
  });

  it("每次渲染的幂等键作为隐藏字段随表单提交", () => {
    const { container } = render(<UserForm idempotencyKey={KEY} />);
    expect(hiddenKey(container)).toHaveValue(KEY);
  });

  it("初始没有任何错误提示", () => {
    render(<UserForm idempotencyKey={KEY} />);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByLabelText("账号名")).not.toHaveAttribute("aria-invalid");
  });
});

describe("UserForm：提交", () => {
  it("提交用户名、账号名、密码与幂等键（交给创建动作）", async () => {
    action.mockResolvedValue({});
    const user = userEvent.setup();
    render(<UserForm idempotencyKey={KEY} />);
    await fillAndSubmit(user);
    await waitFor(() => expect(action).toHaveBeenCalledTimes(1));
    expect(Object.fromEntries(action.mock.calls[0][1].entries())).toEqual({
      userName: "王五",
      accountName: "wangwu",
      password: "Sup3r-Secret-pass",
      idempotencyKey: KEY,
    });
  });

  it("提交期间按钮禁用并提示进行中（防重复提交）；结束后恢复", async () => {
    let finish: (value: object) => void = () => undefined;
    action.mockReturnValue(new Promise((resolve) => (finish = resolve)));
    const user = userEvent.setup();
    render(<UserForm idempotencyKey={KEY} />);
    await user.click(screen.getByRole("button", { name: "创建用户" }));

    const pending = await screen.findByRole("button", { name: "创建中…" });
    expect(pending).toBeDisabled();
    // 双击：禁用后的按钮不再触发第二次提交
    await user.click(pending);
    expect(action).toHaveBeenCalledTimes(1);

    finish({});
    expect(await screen.findByRole("button", { name: "创建用户" })).toBeEnabled();
  });
});

describe("UserForm：错误与回显", () => {
  it("字段错误显示在字段旁，并通过 aria-invalid / aria-describedby 关联（替换说明文字）", async () => {
    action.mockResolvedValue({
      fieldErrors: { userName: "请填写用户名", accountName: "账号名已被使用", password: "密码至少 10 位，且同时包含字母和数字" },
      idempotencyKey: NEW_KEY,
    });
    const user = userEvent.setup();
    render(<UserForm idempotencyKey={KEY} />);
    await user.click(screen.getByRole("button", { name: "创建用户" }));

    const account = await screen.findByLabelText("账号名");
    await waitFor(() => expect(account).toHaveAttribute("aria-invalid", "true"));
    expect(account).toHaveAccessibleDescription("账号名已被使用");
    expect(screen.getByLabelText("用户名")).toHaveAccessibleDescription("请填写用户名");
    expect(screen.getByLabelText("初始密码")).toHaveAccessibleDescription("密码至少 10 位，且同时包含字母和数字");
    expect(screen.getByLabelText("初始密码")).toHaveAttribute("aria-invalid", "true");
  });

  it("账号名冲突：保留已填的用户名与账号名，密码框清空且页面里没有密码，幂等键换成动作返回的新键", async () => {
    action.mockResolvedValue({
      fieldErrors: { accountName: "账号名已被使用" },
      values: { userName: "王五", accountName: "wangwu" },
      idempotencyKey: NEW_KEY,
    });
    const user = userEvent.setup();
    const { container } = render(<UserForm idempotencyKey={KEY} />);
    await fillAndSubmit(user);

    await waitFor(() => expect(screen.getByLabelText("账号名")).toHaveAttribute("aria-invalid", "true"));
    expect(screen.getByLabelText("用户名")).toHaveValue("王五");
    expect(screen.getByLabelText("账号名")).toHaveValue("wangwu");
    expect(screen.getByLabelText("初始密码")).toHaveValue("");
    expect(document.body.innerHTML).not.toContain("Sup3r-Secret-pass");
    expect(hiddenKey(container)).toHaveValue(NEW_KEY);
  });

  it("表单级提示（如「操作未生效」）以 alert 展示；已填内容保留，幂等键保持（重试不重复创建）", async () => {
    action.mockResolvedValue({
      formError: "操作未生效",
      values: { userName: "王五", accountName: "wangwu" },
      idempotencyKey: KEY,
    });
    const user = userEvent.setup();
    const { container } = render(<UserForm idempotencyKey={KEY} />);
    await fillAndSubmit(user);

    expect(await screen.findByRole("alert")).toHaveTextContent("操作未生效");
    expect(screen.getByLabelText("用户名")).toHaveValue("王五");
    expect(screen.getByLabelText("初始密码")).toHaveValue("");
    expect(hiddenKey(container)).toHaveValue(KEY);
  });

  it("动作没有返回幂等键时沿用渲染时的键", async () => {
    action.mockResolvedValue({ formError: "x" });
    const user = userEvent.setup();
    const { container } = render(<UserForm idempotencyKey={KEY} />);
    await user.click(screen.getByRole("button", { name: "创建用户" }));
    await screen.findByRole("alert");
    expect(hiddenKey(container)).toHaveValue(KEY);
  });
});
