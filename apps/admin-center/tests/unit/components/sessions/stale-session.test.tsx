import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { terminateSession } from "@/app/(shell)/sessions/[userId]/actions";
import { SessionList } from "@/components/sessions/session-list";
import { TerminateFeedback } from "@/components/sessions/terminate-dialog";
import { makeSession, NOW, SESSION_REFS } from "../../../support/sessions-fixtures";
import { USER_IDS } from "../../../support/users-fixtures";

jest.mock("@/app/(shell)/sessions/[userId]/actions", () => ({ terminateSession: jest.fn() }));

const terminate = jest.mocked(terminateSession);
const ENDED = { ok: false, ended: true, message: "该会话已结束，已刷新为最新状态" } as const;

const stale = makeSession({ sessionRef: SESSION_REFS.two, accountName: "lisi.mobile" });
const other = makeSession({ sessionRef: SESSION_REFS.one, accountName: "lisi" });

function page(sessions: ReturnType<typeof makeSession>[]) {
  return (
    <TerminateFeedback>
      <SessionList userId={USER_IDS.li} sessions={sessions} now={NOW} />
    </TerminateFeedback>
  );
}

async function terminateStale(user: ReturnType<typeof userEvent.setup>) {
  const card = within(screen.getByRole("list", { name: "会话列表" }))
    .getAllByRole("listitem")
    .find((item) => within(item).queryByText("lisi.mobile"))!;
  await user.click(within(card).getByRole("button", { name: "强制下线" }));
  const dialog = await screen.findByRole("alertdialog");
  await user.click(within(dialog).getByRole("button", { name: "强制下线" }));
}

afterEach(() => jest.resetAllMocks());

describe("停留期间会话已结束（FR-011、spec US2 场景 5）", () => {
  it("对已自行结束的会话强制下线：友好提示（不是错误），刷新后的列表不再含它，其余会话保留", async () => {
    terminate.mockResolvedValue(ENDED);
    const user = userEvent.setup();
    const { rerender } = render(page([other, stale]));
    await terminateStale(user);

    const notice = await screen.findByRole("status");
    expect(notice).toHaveTextContent("该会话已结束，已刷新为最新状态");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(notice).toHaveFocus(); // 卡片即将消失，焦点不能丢给 body

    // Server Action 的 revalidatePath 让页面重新渲染：列表是最新状态，提示仍在
    rerender(page([other]));
    const cards = within(screen.getByRole("list", { name: "会话列表" })).getAllByRole("listitem");
    expect(cards).toHaveLength(1);
    expect(screen.queryByText("lisi.mobile")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("该会话已结束");
  });

  it("该用户最后一个会话已结束：刷新后显示空态，并仍保留「已结束」提示与返回在线用户的出口", async () => {
    terminate.mockResolvedValue(ENDED);
    const user = userEvent.setup();
    const { rerender } = render(page([stale]));
    await terminateStale(user);
    await screen.findByRole("status");

    rerender(page([]));
    expect(screen.getByRole("status")).toHaveTextContent("该会话已结束");
    expect(screen.getByText("该用户当前没有在线会话")).toBeInTheDocument();
    expect(screen.getByText("可能已退出、到期或被下线。")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "返回在线用户" })).toHaveAttribute("href", "/sessions");
    expect(screen.queryByRole("list", { name: "会话列表" })).not.toBeInTheDocument();
  });

  it("页面本身刷新到更新后的数据（会话已结束）：直接显示最新列表，不残留旧提示", () => {
    const { rerender } = render(page([other, stale]));
    expect(screen.getAllByRole("listitem")).toHaveLength(2);
    rerender(page([other]));
    expect(screen.getAllByRole("listitem")).toHaveLength(1);
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
