import { render, screen, within } from "@testing-library/react";
import { SessionList } from "@/components/sessions/session-list";
import { makeSession, NOW, SESSION_REFS } from "../../../support/sessions-fixtures";

const sessions = [
  makeSession({ sessionRef: SESSION_REFS.one, accountName: "lisi", current: true }),
  makeSession({
    sessionRef: SESSION_REFS.two,
    accountId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
    accountName: "lisi.mobile",
    loginAt: "2026-10-23T08:00:00Z",
    lastActiveAt: "2026-10-24T09:30:00Z",
    expiresAt: "2026-10-24T20:00:00Z",
  }),
];

const cards = () => within(screen.getByRole("list", { name: "会话列表" })).getAllByRole("listitem");
const DATE_SHAPE = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/;

describe("SessionList：会话卡片", () => {
  it("每个会话一张卡片，分别标明账号名与账号种类", () => {
    render(<SessionList sessions={sessions} now={NOW} />);
    expect(cards()).toHaveLength(2);
    expect(within(cards()[0]).getByText("lisi")).toBeInTheDocument();
    expect(within(cards()[1]).getByText("lisi.mobile")).toBeInTheDocument();
    for (const card of cards()) expect(card).toHaveTextContent("账号名 / 密码");
  });

  it("含登录时间、最近活动（绝对时间 + 相对时间）、到期时间", () => {
    render(<SessionList sessions={sessions} now={NOW} />);
    const second = cards()[1];
    for (const label of ["登录时间", "最近活动", "到期时间", "会话引用"]) expect(second).toHaveTextContent(label);

    const times = Array.from(second.querySelectorAll("time"));
    expect(times.map((t) => t.getAttribute("datetime"))).toEqual([
      "2026-10-23T08:00:00Z",
      "2026-10-24T09:30:00Z",
      "2026-10-24T20:00:00Z",
    ]);
    for (const t of times) expect(t.textContent).toMatch(DATE_SHAPE);
    expect(second).toHaveTextContent("（2 小时前）");
  });

  it("只显示短会话引用（前 8 位），页面里没有完整的引用，更没有会话标识", () => {
    render(<SessionList sessions={sessions} now={NOW} />);
    expect(within(cards()[0]).getByText(SESSION_REFS.one.slice(0, 8)).tagName).toBe("CODE");
    expect(within(cards()[1]).getByText(SESSION_REFS.two.slice(0, 8))).toBeInTheDocument();
    expect(document.body.innerHTML).not.toContain(SESSION_REFS.one);
    expect(document.body.innerHTML).not.toContain(SESSION_REFS.two);
    expect(document.body.innerHTML).not.toMatch(/sessionId|session_id/i);
  });

  it("运维人员自己的会话标注「当前会话」（文字徽标），其他会话没有", () => {
    render(<SessionList sessions={sessions} now={NOW} />);
    expect(within(cards()[0]).getByText("当前会话")).toBeInTheDocument();
    expect(within(cards()[1]).queryByText("当前会话")).not.toBeInTheDocument();
    expect(screen.getAllByText("当前会话")).toHaveLength(1);
  });

  it("没有「强制下线」入口（属于后续用户故事）", () => {
    render(<SessionList sessions={sessions} now={NOW} />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(screen.queryByText("强制下线")).not.toBeInTheDocument();
  });

  it("账号名按文本渲染（不解析 HTML）", () => {
    render(<SessionList sessions={[makeSession({ accountName: "<img src=x onerror=alert(1)>" })]} now={NOW} />);
    expect(screen.getByText("<img src=x onerror=alert(1)>")).toBeInTheDocument();
    expect(document.querySelector("img")).toBeNull();
  });

  it("不传 now 时按当前时间计算", () => {
    render(<SessionList sessions={[makeSession({ lastActiveAt: new Date().toISOString() })]} />);
    expect(screen.getByText("（刚刚）")).toBeInTheDocument();
  });
});

describe("SessionList：空态", () => {
  it("没有会话：说明原因并可返回在线用户", () => {
    render(<SessionList sessions={[]} now={NOW} />);
    expect(screen.getByText("该用户当前没有在线会话")).toBeInTheDocument();
    expect(screen.getByText("可能已退出、到期或被下线。")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "返回在线用户" })).toHaveAttribute("href", "/sessions");
    expect(screen.queryByRole("list", { name: "会话列表" })).not.toBeInTheDocument();
  });
});
