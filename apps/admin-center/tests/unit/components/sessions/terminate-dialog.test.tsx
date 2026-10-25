import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { terminateSession, type TerminateResult } from "@/app/(shell)/sessions/[userId]/actions";
import { TerminateDialog, TerminateFeedback } from "@/components/sessions/terminate-dialog";
import { SESSION_REFS } from "../../../support/sessions-fixtures";
import { USER_IDS } from "../../../support/users-fixtures";

jest.mock("@/app/(shell)/sessions/[userId]/actions", () => ({ terminateSession: jest.fn() }));

const terminate = jest.mocked(terminateSession);

const props = { userId: USER_IDS.li, sessionRef: SESSION_REFS.two, accountName: "lisi.mobile", current: false };

function setup(overrides: Partial<typeof props> = {}) {
  return render(
    <TerminateFeedback>
      <TerminateDialog {...props} {...overrides} />
    </TerminateFeedback>,
  );
}

const open = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(screen.getByRole("button", { name: "强制下线" }));
  return screen.findByRole("alertdialog", { name: "强制下线该会话？" });
};

afterEach(() => jest.resetAllMocks());

describe("TerminateDialog：二次确认", () => {
  it("入口「强制下线」可用，触控目标 h-11", () => {
    setup();
    const button = screen.getByRole("button", { name: "强制下线" });
    expect(button).toBeEnabled();
    expect(button).toHaveClass("h-11");
  });

  it("点击入口先弹出二次确认，说明所属账号与后果；此时不调用动作", async () => {
    const user = userEvent.setup();
    setup();
    const dialog = await open(user);
    expect(dialog).toHaveAccessibleDescription(
      "账号「lisi.mobile」的这个会话将立即结束；该用户的其他会话不受影响，账号与用户状态也不会改变。对方下一次操作会被带到登录页。",
    );
    expect(within(dialog).getByRole("button", { name: "强制下线" })).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "取消" })).toBeInTheDocument();
    expect(terminate).not.toHaveBeenCalled();
  });

  it("账号名按文本渲染", async () => {
    const user = userEvent.setup();
    setup({ accountName: "<b>x</b>" });
    await user.click(screen.getByRole("button", { name: "强制下线" }));
    const dialog = await screen.findByRole("alertdialog");
    expect(dialog).toHaveAccessibleDescription(expect.stringContaining("账号「<b>x</b>」"));
    expect(dialog.querySelector("b")).toBeNull();
  });

  it("取消：关闭对话框、不调用动作、焦点回到触发它的入口", async () => {
    const user = userEvent.setup();
    setup();
    const trigger = screen.getByRole("button", { name: "强制下线" });
    await open(user);
    await user.click(screen.getByRole("button", { name: "取消" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(terminate).not.toHaveBeenCalled();
    expect(trigger).toHaveFocus();
  });

  it("Esc 同样取消，不调用动作", async () => {
    const user = userEvent.setup();
    setup();
    await open(user);
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(terminate).not.toHaveBeenCalled();
  });

  it("确认：以用户标识与会话引用调用动作一次，关闭对话框，显示成功提示（status）并把焦点移到提示", async () => {
    terminate.mockResolvedValue({ ok: true, message: "会话已强制下线。" });
    const user = userEvent.setup();
    setup();
    const dialog = await open(user);
    await user.click(within(dialog).getByRole("button", { name: "强制下线" }));

    const status = await screen.findByRole("status");
    expect(status).toHaveTextContent("会话已强制下线。");
    expect(terminate).toHaveBeenCalledTimes(1);
    expect(terminate).toHaveBeenCalledWith(USER_IDS.li, SESSION_REFS.two);
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    // 成功后该会话卡片会消失，入口不能再接焦点：焦点落在提示上
    expect(status).toHaveFocus();
  });

  it("处理中：确认按钮禁用并提示进行中，Esc 与取消都不能中途关闭（防重复提交）", async () => {
    let finish: (value: TerminateResult) => void = () => undefined;
    terminate.mockReturnValue(new Promise((resolve) => (finish = resolve)));
    const user = userEvent.setup();
    setup();
    const dialog = await open(user);
    await user.click(within(dialog).getByRole("button", { name: "强制下线" }));

    const pending = await screen.findByRole("button", { name: "处理中…" });
    expect(pending).toBeDisabled();
    expect(screen.getByRole("button", { name: "取消" })).toBeDisabled();
    await user.click(pending);
    await user.keyboard("{Escape}");
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
    expect(terminate).toHaveBeenCalledTimes(1);

    finish({ ok: true, message: "完成" });
    expect(await screen.findByRole("status")).toHaveTextContent("完成");
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
  });
});

describe("TerminateDialog：结果", () => {
  it("会话已结束（ended）：作为普通提示（status）显示「该会话已结束」，不是错误", async () => {
    terminate.mockResolvedValue({ ok: false, ended: true, message: "该会话已结束，已刷新为最新状态" });
    const user = userEvent.setup();
    setup();
    const dialog = await open(user);
    await user.click(within(dialog).getByRole("button", { name: "强制下线" }));

    expect(await screen.findByRole("status")).toHaveTextContent("该会话已结束");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
  });

  it.each([
    ["UAM 不可用", "操作未生效"],
    ["UAM 拒绝自己的会话", "这是你当前使用的会话，请改用「退出登录」"],
  ])("失败（%s）：以 alert 显示原因，不显示成功；入口仍可再次使用，焦点回到入口", async (_name, message) => {
    terminate.mockResolvedValue({ ok: false, message });
    const user = userEvent.setup();
    setup();
    const trigger = screen.getByRole("button", { name: "强制下线" });
    const dialog = await open(user);
    await user.click(within(dialog).getByRole("button", { name: "强制下线" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(trigger).toBeEnabled();
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it("再次发起操作时清掉上一次的结果提示", async () => {
    terminate.mockResolvedValueOnce({ ok: false, message: "操作未生效" }).mockResolvedValueOnce({ ok: true, message: "好了" });
    const user = userEvent.setup();
    setup();
    for (let i = 0; i < 2; i++) {
      const dialog = await open(user);
      await user.click(within(dialog).getByRole("button", { name: "强制下线" }));
      await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    }
    expect(await screen.findByRole("status")).toHaveTextContent("好了");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("没有结果时不显示任何提示", () => {
    setup();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("TerminateDialog：操作人自己的当前会话", () => {
  it("入口禁用，并用可见文字提示改用「退出登录」（aria-describedby 关联）；点击不会弹出对话框", async () => {
    const user = userEvent.setup();
    setup({ current: true });
    const button = screen.getByRole("button", { name: "强制下线" });
    expect(button).toBeDisabled();
    const hint = "这是你当前使用的会话，请改用顶栏用户菜单中的「退出登录」。";
    expect(button).toHaveAccessibleDescription(hint);
    expect(screen.getByText(hint)).toBeVisible();
    await user.click(button);
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(terminate).not.toHaveBeenCalled();
  });

  it("不是当前会话：没有禁用提示", () => {
    setup();
    expect(screen.queryByText(/退出登录/)).not.toBeInTheDocument();
  });

  it("同一页有多个会话时，每个入口的提示各自关联（id 不冲突）", () => {
    render(
      <TerminateFeedback>
        <TerminateDialog {...props} current sessionRef={SESSION_REFS.one} />
        <TerminateDialog {...props} current sessionRef={SESSION_REFS.two} />
      </TerminateFeedback>,
    );
    for (const button of screen.getAllByRole("button", { name: "强制下线" })) {
      expect(button).toHaveAccessibleDescription(expect.stringContaining("退出登录"));
    }
  });
});
