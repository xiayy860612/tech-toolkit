/**
 * @jest-environment node
 */
import { revalidatePath } from "next/cache";
import { headers } from "next/headers";
import { terminateSession } from "@/app/(shell)/sessions/[userId]/actions";
import { getSession } from "@/lib/session/get-session";
import {
  createUamStub,
  FIXTURE_IDS,
  problemBody,
  UAM_STUB_BASE_URL,
  UAM_STUB_SERVICE_KEY,
  verifyResponseBody,
} from "../../support/uam-stub";
import { SESSION_REFS } from "../../support/sessions-fixtures";
import { USER_IDS } from "../../support/users-fixtures";

jest.mock("next/headers", () => ({ headers: jest.fn() }));
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));
jest.mock("next/navigation", () => ({
  redirect: jest.fn((to: string) => {
    throw new Error(`NEXT_REDIRECT:${to}`);
  }),
}));
jest.mock("@/lib/session/get-session", () => ({ getSession: jest.fn() }));

const stub = createUamStub();
const saved = { ...process.env };
const USER = USER_IDS.li;
const TARGET = SESSION_REFS.two;
const PAGE = `/sessions/${USER}`;
const SAME_ORIGIN = { host: "admin.test", origin: "http://admin.test", "sec-fetch-site": "same-origin" };

function requestHeaders(overrides: Record<string, string | null> = {}) {
  const h = new Headers(SAME_ORIGIN);
  for (const [k, v] of Object.entries(overrides)) {
    if (v === null) h.delete(k);
    else h.set(k, v);
  }
  jest.mocked(headers).mockResolvedValue(h as never);
}

const logs: jest.SpyInstance[] = [];

beforeAll(() => stub.listen());
afterAll(() => stub.close());
beforeEach(() => {
  process.env.UAM_BASE_URL = UAM_STUB_BASE_URL;
  process.env.UAM_SERVICE_KEY = UAM_STUB_SERVICE_KEY;
  requestHeaders();
  jest.mocked(getSession).mockResolvedValue({ status: "authenticated", session: verifyResponseBody() as never });
  logs.push(
    ...(["log", "info", "warn", "error"] as const).map((m) => jest.spyOn(console, m).mockImplementation(() => undefined)),
  );
});
afterEach(() => {
  expect(stub.violations).toEqual([]);
  const logged = JSON.stringify(logs.flatMap((l) => l.mock.calls));
  expect(logged).not.toContain(UAM_STUB_SERVICE_KEY);
  logs.splice(0).forEach((l) => l.mockRestore());
  stub.reset();
  jest.clearAllMocks();
  process.env = { ...saved };
});

describe("terminateSession 动作：同源、参数与会话", () => {
  it("跨站请求：拒绝，不调用 UAM，不显示成功", async () => {
    requestHeaders({ "sec-fetch-site": "cross-site" });
    expect(await terminateSession(USER, TARGET)).toEqual({ ok: false, message: expect.stringMatching(/来源校验失败/) });
    expect(stub.calls).toHaveLength(0);
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it.each([
    ["userId 为空", "", TARGET],
    ["userId 不是 UUID", "not-a-uuid", TARGET],
    ["sessionRef 为空", USER, ""],
    ["sessionRef 不是 UUID", USER, "not-a-uuid"],
    ["sessionRef 想改请求路径", USER, "../users"],
  ])("%s：友好提示，不调用 UAM", async (_name, userId, sessionRef) => {
    expect(await terminateSession(userId, sessionRef)).toEqual({ ok: false, message: "会话不存在，请刷新页面后重试" });
    expect(stub.calls).toHaveLength(0);
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("会话已失效（带过 cookie）：先经 /session/expire 再回到该用户的会话页，不调用 UAM", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: true });
    await expect(terminateSession(USER, TARGET)).rejects.toThrow(`NEXT_REDIRECT:/session/expire?next=${encodeURIComponent(PAGE)}`);
    expect(stub.calls).toHaveLength(0);
  });

  it("没有会话：去登录页，登录后回到该用户的会话页", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: false });
    await expect(terminateSession(USER, TARGET)).rejects.toThrow(`NEXT_REDIRECT:/login?next=${encodeURIComponent(PAGE)}`);
    expect(stub.calls).toHaveLength(0);
  });

  it("无法确认会话（UAM 暂不可用）：「操作未生效」，不调用 UAM，不显示成功", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "unverified" });
    expect(await terminateSession(USER, TARGET)).toEqual({ ok: false, message: "操作未生效" });
    expect(stub.calls).toHaveLength(0);
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("非 UAM 错误是程序问题：原样抛出，不伪装成失败提示", async () => {
    process.env.UAM_BASE_URL = "not-a-url";
    await expect(terminateSession(USER, TARGET)).rejects.toThrow();
  });
});

describe("terminateSession 动作：成功与 UAM 的结论", () => {
  it("DELETE /v1/sessions/{ref}，带服务密钥与操作人请求头、没有请求体；成功后刷新该用户的会话页与在线用户列表", async () => {
    stub.respond("terminateSession", { status: 204 });
    expect(await terminateSession(USER, TARGET)).toEqual({ ok: true, message: "会话已强制下线。" });

    expect(stub.calls).toHaveLength(1);
    const [call] = stub.calls;
    expect(call.operationId).toBe("terminateSession");
    expect(call.method).toBe("DELETE");
    expect(call.path).toBe(`/v1/sessions/${TARGET}`);
    expect(call.headers.get("authorization")).toBe(`Bearer ${UAM_STUB_SERVICE_KEY}`);
    expect(call.headers.get("x-operator-user-id")).toBe(FIXTURE_IDS.userId);
    expect(call.headers.get("x-operator-account-id")).toBe(FIXTURE_IDS.accountId);
    expect(call.headers.get("x-operator-session-ref")).toBe(FIXTURE_IDS.sessionRef);
    expect(call.body).toBeUndefined();
    expect(revalidatePath).toHaveBeenCalledWith(PAGE);
    expect(revalidatePath).toHaveBeenCalledWith("/sessions");
  });

  it("409 cannot_terminate_own_session（操作人的当前会话）：提示改用「退出登录」，状态没变所以不刷新", async () => {
    stub.respond("terminateSession", { status: 409, body: problemBody(409, "cannot_terminate_own_session") });
    expect(await terminateSession(USER, FIXTURE_IDS.sessionRef)).toEqual({
      ok: false,
      message: "这是你当前使用的会话，请改用「退出登录」",
    });
    // 操作人头里带的就是目标会话引用：由 UAM 裁决，Admin Center 不自行判断
    expect(stub.calls[0].headers.get("x-operator-session-ref")).toBe(FIXTURE_IDS.sessionRef);
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("404 session_not_found：「该会话已结束」，标记为 ended（界面不当作错误），并刷新为最新状态", async () => {
    stub.respond("terminateSession", { status: 404, body: problemBody(404, "session_not_found") });
    expect(await terminateSession(USER, TARGET)).toEqual({ ok: false, ended: true, message: "该会话已结束，已刷新为最新状态" });
    expect(revalidatePath).toHaveBeenCalledWith(PAGE);
    expect(revalidatePath).toHaveBeenCalledWith("/sessions");
  });

  it.each([
    ["连接失败", { networkError: true } as const],
    ["超时", { status: 204, delayMs: 400 } as const],
  ])("UAM 不可用（%s）：「操作未生效」，不显示成功，不刷新", async (_name, reply) => {
    process.env.UAM_TIMEOUT_MS = "40";
    stub.respond("terminateSession", reply);
    expect(await terminateSession(USER, TARGET)).toEqual({ ok: false, message: "操作未生效" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("UAM 拒绝了 Admin Center（服务密钥错误）：对用户只显示「操作未生效」，运维告警一条（不含秘密）", async () => {
    stub.respond("terminateSession", { status: 403, body: problemBody(403, "client_forbidden") });
    expect(await terminateSession(USER, TARGET)).toEqual({ ok: false, message: "操作未生效" });
    expect(console.error).toHaveBeenCalledTimes(1);
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("其它业务问题码（operator_required 或未知）：「操作未生效」", async () => {
    for (const code of ["operator_required", "brand_new_code"]) {
      stub.respond("terminateSession", { status: 400, body: problemBody(400, code) });
      expect(await terminateSession(USER, TARGET)).toEqual({ ok: false, message: "操作未生效" });
    }
  });
});
