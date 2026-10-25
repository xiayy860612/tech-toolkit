import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { disableUser, enableUser } from "@/app/(shell)/users/[userId]/actions";
import { StatusActions } from "@/components/users/status-actions";
import { USER_IDS } from "../../../support/users-fixtures";

jest.mock("@/app/(shell)/users/[userId]/actions", () => ({ disableUser: jest.fn(), enableUser: jest.fn() }));

const disable = jest.mocked(disableUser);
const enable = jest.mocked(enableUser);

const props = { userId: USER_IDS.li, userName: "李四", status: "ACTIVE" as const, isSelf: false };

afterEach(() => jest.resetAllMocks());

describe("StatusActions：停用（正常用户）", () => {
  it("区块标题「状态管理」；入口「停用用户」可用，触控目标 h-11", () => {
    render(<StatusActions {...props} />);
    expect(screen.getByRole("heading", { level: 2, name: "状态管理" })).toBeInTheDocument();
    const button = screen.getByRole("button", { name: "停用用户" });
    expect(button).toBeEnabled();
    expect(button).toHaveClass("h-11");
    expect(screen.queryByRole("button", { name: "启用用户" })).not.toBeInTheDocument();
  });

  it("点击入口先弹出二次确认，说明后果；此时不调用动作", async () => {
    const user = userEvent.setup();
    render(<StatusActions {...props} />);
    await user.click(screen.getByRole("button", { name: "停用用户" }));

    const dialog = await screen.findByRole("alertdialog", { name: "停用用户「李四」？" });
    expect(dialog).toHaveAccessibleDescription(
      "停用后，该用户名下的所有账号都无法登录，其所有在线会话会立即结束。之后可以重新启用，但已结束的会话不会恢复。",
    );
    expect(within(dialog).getByRole("button", { name: "停用" })).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "取消" })).toBeInTheDocument();
    expect(disable).not.toHaveBeenCalled();
  });

  it("取消：关闭对话框、不调用动作、焦点回到触发它的入口", async () => {
    const user = userEvent.setup();
    render(<StatusActions {...props} />);
    const trigger = screen.getByRole("button", { name: "停用用户" });
    await user.click(trigger);
    await user.click(await screen.findByRole("button", { name: "取消" }));

    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(disable).not.toHaveBeenCalled();
    expect(trigger).toHaveFocus();
  });

  it("Esc 同样取消，不调用动作", async () => {
    const user = userEvent.setup();
    render(<StatusActions {...props} />);
    await user.click(screen.getByRole("button", { name: "停用用户" }));
    await screen.findByRole("alertdialog");
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(disable).not.toHaveBeenCalled();
  });

  it("确认：以用户标识调用停用动作一次，关闭对话框，显示动作返回的成功提示（status）", async () => {
    disable.mockResolvedValue({ ok: true, message: "用户「李四」已停用，其会话已结束。" });
    const user = userEvent.setup();
    render(<StatusActions {...props} />);
    await user.click(screen.getByRole("button", { name: "停用用户" }));
    await user.click(await screen.findByRole("button", { name: "停用" }));

    expect(await screen.findByRole("status")).toHaveTextContent("用户「李四」已停用，其会话已结束。");
    expect(disable).toHaveBeenCalledTimes(1);
    expect(disable).toHaveBeenCalledWith(USER_IDS.li);
    expect(enable).not.toHaveBeenCalled();
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("处理中：确认按钮禁用并提示进行中，Esc 与取消都不能中途关闭（防重复提交）", async () => {
    let finish: (value: { ok: true; message: string }) => void = () => undefined;
    disable.mockReturnValue(new Promise((resolve) => (finish = resolve)));
    const user = userEvent.setup();
    render(<StatusActions {...props} />);
    await user.click(screen.getByRole("button", { name: "停用用户" }));
    await user.click(await screen.findByRole("button", { name: "停用" }));

    const pending = await screen.findByRole("button", { name: "处理中…" });
    expect(pending).toBeDisabled();
    expect(screen.getByRole("button", { name: "取消" })).toBeDisabled();
    await user.click(pending);
    await user.keyboard("{Escape}");
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
    expect(disable).toHaveBeenCalledTimes(1);

    finish({ ok: true, message: "完成" });
    expect(await screen.findByRole("status")).toHaveTextContent("完成");
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it.each([
    ["UAM 不可用", "操作未生效"],
    ["最后一个正常用户", "不能停用最后一个正常用户"],
    ["用户不存在", "用户不存在"],
  ])("失败（%s）：以 alert 显示原因，不显示成功", async (_name, message) => {
    disable.mockResolvedValue({ ok: false, message });
    const user = userEvent.setup();
    render(<StatusActions {...props} />);
    await user.click(screen.getByRole("button", { name: "停用用户" }));
    await user.click(await screen.findByRole("button", { name: "停用" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    // 失败后入口仍可再次使用
    expect(screen.getByRole("button", { name: "停用用户" })).toBeEnabled();
  });

  it("再次发起操作时清掉上一次的结果提示", async () => {
    disable.mockResolvedValueOnce({ ok: false, message: "操作未生效" }).mockResolvedValueOnce({ ok: true, message: "好了" });
    const user = userEvent.setup();
    render(<StatusActions {...props} />);
    await user.click(screen.getByRole("button", { name: "停用用户" }));
    await user.click(await screen.findByRole("button", { name: "停用" }));
    await screen.findByRole("alert");

    await user.click(screen.getByRole("button", { name: "停用用户" }));
    await user.click(await screen.findByRole("button", { name: "停用" }));
    expect(await screen.findByRole("status")).toHaveTextContent("好了");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("StatusActions：停用自己", () => {
  it("入口禁用，并用可见文字说明原因（aria-describedby 关联）；点击不会弹出对话框", async () => {
    const user = userEvent.setup();
    render(<StatusActions {...props} isSelf />);
    const button = screen.getByRole("button", { name: "停用用户" });
    expect(button).toBeDisabled();
    expect(button).toHaveAccessibleDescription("不能停用当前登录的用户。");
    expect(screen.getByText("不能停用当前登录的用户。")).toBeVisible();
    await user.click(button);
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(disable).not.toHaveBeenCalled();
  });

  it("不是自己：没有禁用说明", () => {
    render(<StatusActions {...props} />);
    expect(screen.queryByText(/不能停用/)).not.toBeInTheDocument();
  });
});

describe("StatusActions：启用（已停用用户）", () => {
  const disabled = { ...props, status: "DISABLED" as const };

  it("入口「启用用户」；确认框说明「不恢复旧会话」", async () => {
    const user = userEvent.setup();
    render(<StatusActions {...disabled} />);
    expect(screen.queryByRole("button", { name: "停用用户" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "启用用户" }));

    const dialog = await screen.findByRole("alertdialog", { name: "启用用户「李四」？" });
    expect(dialog).toHaveAccessibleDescription("启用后，该用户的账号可以重新登录。此前已结束的会话不会恢复。");
    expect(within(dialog).getByRole("button", { name: "启用" })).toBeInTheDocument();
    expect(enable).not.toHaveBeenCalled();
  });

  it("确认：以用户标识调用启用动作，显示成功提示", async () => {
    enable.mockResolvedValue({ ok: true, message: "用户「李四」已启用。" });
    const user = userEvent.setup();
    render(<StatusActions {...disabled} />);
    await user.click(screen.getByRole("button", { name: "启用用户" }));
    await user.click(await screen.findByRole("button", { name: "启用" }));

    expect(await screen.findByRole("status")).toHaveTextContent("用户「李四」已启用。");
    expect(enable).toHaveBeenCalledWith(USER_IDS.li);
    expect(disable).not.toHaveBeenCalled();
  });

  it("UAM 不可用：「操作未生效」，不显示成功", async () => {
    enable.mockResolvedValue({ ok: false, message: "操作未生效" });
    const user = userEvent.setup();
    render(<StatusActions {...disabled} />);
    await user.click(screen.getByRole("button", { name: "启用用户" }));
    await user.click(await screen.findByRole("button", { name: "启用" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("操作未生效");
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("已停用的用户即使是自己也可启用（没有禁用说明）", () => {
    render(<StatusActions {...disabled} isSelf />);
    expect(screen.getByRole("button", { name: "启用用户" })).toBeEnabled();
    expect(screen.queryByText(/不能停用/)).not.toBeInTheDocument();
  });
});

describe("StatusActions：用户名按文本渲染", () => {
  it("对话框标题里的用户名不会被当作标记", async () => {
    const user = userEvent.setup();
    render(<StatusActions {...props} userName="<b>x</b>" />);
    await user.click(screen.getByRole("button", { name: "停用用户" }));
    expect(await screen.findByRole("alertdialog", { name: "停用用户「<b>x</b>」？" })).toBeInTheDocument();
  });
});
