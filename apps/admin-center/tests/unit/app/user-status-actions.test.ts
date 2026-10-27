/**
 * @jest-environment node
 */
import { revalidatePath } from "next/cache";
import { headers } from "next/headers";
import { disableUser, enableUser } from "@/app/(shell)/users/[userId]/actions";
import { getSession } from "@/lib/session/get-session";
import {
  createUamStub,
  FIXTURE_IDS,
  problemBody,
  UAM_STUB_BASE_URL,
  verifyResponseBody,
} from "../../support/uam-stub";
import { makeDetail, USER_IDS } from "../../support/users-fixtures";

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
const TARGET = USER_IDS.li;
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
  requestHeaders();
  jest.mocked(getSession).mockResolvedValue({ status: "authenticated", session: verifyResponseBody() as never });
  logs.push(
    ...(["log", "info", "warn", "error"] as const).map((m) => jest.spyOn(console, m).mockImplementation(() => undefined)),
  );
});
afterEach(() => {
  expect(stub.violations).toEqual([]);
  logs.splice(0).forEach((l) => l.mockRestore());
  stub.reset();
  jest.clearAllMocks();
  process.env = { ...saved };
});

const ACTIONS = [
  { name: "disableUser", run: disableUser, operationId: "disableUser", suffix: "disable" },
  { name: "enableUser", run: enableUser, operationId: "enableUser", suffix: "enable" },
] as const;

describe.each(ACTIONS)("$name 动作：同源、参数与会话", ({ run, operationId }) => {
  it("跨站请求：拒绝，不调用 UAM，不显示成功", async () => {
    requestHeaders({ "sec-fetch-site": "cross-site" });
    const result = await run(TARGET);
    expect(result).toEqual({ ok: false, message: expect.stringMatching(/来源校验失败/) });
    expect(stub.calls).toHaveLength(0);
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it.each([[""], ["not-a-uuid"], ["../sessions"]])("userId %j 不是合法标识：「用户不存在」，不调用 UAM", async (id) => {
    expect(await run(id)).toEqual({ ok: false, message: "用户不存在" });
    expect(stub.calls).toHaveLength(0);
  });

  it("会话已失效（带过 cookie）：先经 /session/expire 再回到该用户详情页，不调用 UAM", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: true });
    await expect(run(TARGET)).rejects.toThrow(`NEXT_REDIRECT:/session/expire?next=${encodeURIComponent(`/users/${TARGET}`)}`);
    expect(stub.calls).toHaveLength(0);
  });

  it("没有会话：去登录页，登录后回到该用户详情页", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: false });
    await expect(run(TARGET)).rejects.toThrow(`NEXT_REDIRECT:/login?next=${encodeURIComponent(`/users/${TARGET}`)}`);
    expect(stub.calls).toHaveLength(0);
  });

  it("无法确认会话（UAM 暂不可用）：「操作未生效」，不调用 UAM，不显示成功", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "unverified" });
    expect(await run(TARGET)).toEqual({ ok: false, message: "操作未生效" });
    expect(stub.calls).toHaveLength(0);
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("非 UAM 错误是程序问题：原样抛出，不伪装成失败提示", async () => {
    process.env.UAM_BASE_URL = "not-a-url";
    await expect(run(TARGET)).rejects.toThrow();
    expect(operationId).toBeTruthy();
  });
});

describe("disableUser 动作：成功与 UAM 的结论", () => {
  it("带服务密钥与操作人请求头 POST /disable；成功后刷新详情页，提示含用户名", async () => {
    stub.respond("disableUser", { status: 200, body: makeDetail({ status: "DISABLED" }) });
    expect(await disableUser(TARGET)).toEqual({ ok: true, message: "用户「李四」已停用，其会话已结束。" });

    expect(stub.calls).toHaveLength(1);
    const [call] = stub.calls;
    expect(call.operationId).toBe("disableUser");
    expect(call.method).toBe("POST");
    expect(call.path).toBe(`/v1/users/${TARGET}/disable`);
    expect(call.headers.get("authorization")).toBeNull();
    expect(call.headers.get("x-operator-user-id")).toBe(FIXTURE_IDS.userId);
    expect(call.headers.get("x-operator-account-id")).toBe(FIXTURE_IDS.accountId);
    expect(call.headers.get("x-operator-session-ref")).toBe(FIXTURE_IDS.sessionRef);
    expect(call.body).toBeUndefined();
    expect(revalidatePath).toHaveBeenCalledWith(`/users/${TARGET}`);
  });

  it("409 cannot_disable_self：「不能停用当前登录的用户」，状态没变所以不刷新", async () => {
    stub.respond("disableUser", { status: 409, body: problemBody(409, "cannot_disable_self") });
    expect(await disableUser(FIXTURE_IDS.userId)).toEqual({ ok: false, message: "不能停用当前登录的用户" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("409 cannot_disable_last_active_user：「不能停用最后一个正常用户」", async () => {
    stub.respond("disableUser", { status: 409, body: problemBody(409, "cannot_disable_last_active_user") });
    expect(await disableUser(TARGET)).toEqual({ ok: false, message: "不能停用最后一个正常用户" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("404 user_not_found：「用户不存在」，并刷新详情页让它显示不存在状态", async () => {
    stub.respond("disableUser", { status: 404, body: problemBody(404, "user_not_found") });
    expect(await disableUser(TARGET)).toEqual({ ok: false, message: "用户不存在" });
    expect(revalidatePath).toHaveBeenCalledWith(`/users/${TARGET}`);
  });

  it.each([
    ["连接失败", { networkError: true } as const],
    ["超时", { status: 200, body: makeDetail(), delayMs: 400 } as const],
  ])("UAM 不可用（%s）：「操作未生效」，不显示成功，不刷新", async (_name, reply) => {
    process.env.UAM_TIMEOUT_MS = "40";
    stub.respond("disableUser", reply);
    expect(await disableUser(TARGET)).toEqual({ ok: false, message: "操作未生效" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("其它业务问题码（operator_required、bad_request 或未知）：「操作未生效」，不刷新", async () => {
    for (const code of ["operator_required", "bad_request", "brand_new_code"]) {
      stub.respond("disableUser", { status: 400, body: problemBody(400, code) });
      expect(await disableUser(TARGET)).toEqual({ ok: false, message: "操作未生效" });
    }
    expect(revalidatePath).not.toHaveBeenCalled();
  });
});

describe("enableUser 动作：成功与 UAM 的结论", () => {
  it("POST /enable（同样带操作人请求头）；成功后刷新详情页，提示含用户名", async () => {
    stub.respond("enableUser", { status: 200, body: makeDetail({ status: "ACTIVE" }) });
    expect(await enableUser(TARGET)).toEqual({ ok: true, message: "用户「李四」已启用。" });

    const [call] = stub.calls;
    expect(call.operationId).toBe("enableUser");
    expect(call.path).toBe(`/v1/users/${TARGET}/enable`);
    expect(call.headers.get("x-operator-user-id")).toBe(FIXTURE_IDS.userId);
    expect(revalidatePath).toHaveBeenCalledWith(`/users/${TARGET}`);
  });

  it("404 user_not_found：「用户不存在」并刷新", async () => {
    stub.respond("enableUser", { status: 404, body: problemBody(404, "user_not_found") });
    expect(await enableUser(TARGET)).toEqual({ ok: false, message: "用户不存在" });
    expect(revalidatePath).toHaveBeenCalledWith(`/users/${TARGET}`);
  });

  it("UAM 不可用：「操作未生效」，不显示成功，不刷新", async () => {
    stub.respond("enableUser", { networkError: true });
    expect(await enableUser(TARGET)).toEqual({ ok: false, message: "操作未生效" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });
});
