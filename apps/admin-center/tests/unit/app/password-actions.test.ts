/**
 * @jest-environment node
 */
import { headers } from "next/headers";
import { changePassword } from "@/app/(shell)/account/password/actions";
import { getSession } from "@/lib/session/get-session";
import {
  createUamStub,
  FIXTURE_IDS,
  problemBody,
  UAM_STUB_BASE_URL,
  UAM_STUB_SERVICE_KEY,
  verifyResponseBody,
} from "../../support/uam-stub";

jest.mock("next/headers", () => ({ headers: jest.fn() }));
jest.mock("next/navigation", () => ({
  redirect: jest.fn((to: string) => {
    throw new Error(`NEXT_REDIRECT:${to}`);
  }),
}));
jest.mock("@/lib/session/get-session", () => ({ getSession: jest.fn() }));

const stub = createUamStub();
const saved = { ...process.env };
const CURRENT = "Old-Secret-pass1";
const NEW = "New-Secret-pass2";
const SECRETS = [CURRENT, NEW];

const SAME_ORIGIN = {
  host: "admin.test",
  origin: "http://admin.test",
  "sec-fetch-site": "same-origin",
  "x-forwarded-for": "203.0.113.7",
};

function requestHeaders(overrides: Record<string, string | null> = {}) {
  const h = new Headers(SAME_ORIGIN);
  for (const [k, v] of Object.entries(overrides)) {
    if (v === null) h.delete(k);
    else h.set(k, v);
  }
  jest.mocked(headers).mockResolvedValue(h as never);
}

function form(values: { currentPassword?: string; newPassword?: string; confirmPassword?: string; accountId?: string } = {}) {
  const fd = new FormData();
  fd.set("currentPassword", values.currentPassword ?? CURRENT);
  fd.set("newPassword", values.newPassword ?? NEW);
  fd.set("confirmPassword", values.confirmPassword ?? values.newPassword ?? NEW);
  if (values.accountId !== undefined) fd.set("accountId", values.accountId);
  return fd;
}

const changed = () => stub.respond("changeOwnPassword", { status: 204 });
const rejected = (status: number, code: string, extra = {}, headers: Record<string, string> = {}) =>
  stub.respond("changeOwnPassword", { status, body: problemBody(status, code, extra), headers });
const noPasswordIn = (value: unknown) => {
  const json = JSON.stringify(value);
  for (const secret of SECRETS) expect(json).not.toContain(secret);
};

const logs: jest.SpyInstance[] = [];

beforeAll(() => stub.listen());
afterAll(() => stub.close());
beforeEach(() => {
  process.env.UAM_BASE_URL = UAM_STUB_BASE_URL;
  process.env.UAM_SERVICE_KEY = UAM_STUB_SERVICE_KEY;
  process.env.TRUSTED_PROXY_HOPS = "1";
  requestHeaders();
  jest.mocked(getSession).mockResolvedValue({ status: "authenticated", session: verifyResponseBody() as never });
  logs.push(
    ...(["log", "info", "warn", "error"] as const).map((m) => jest.spyOn(console, m).mockImplementation(() => undefined)),
  );
});
afterEach(() => {
  expect(stub.violations).toEqual([]);
  // 任何日志都不能带出密码或服务密钥
  const logged = JSON.stringify(logs.flatMap((l) => l.mock.calls));
  noPasswordIn(logged);
  expect(logged).not.toContain(UAM_STUB_SERVICE_KEY);
  logs.splice(0).forEach((l) => l.mockRestore());
  stub.reset();
  jest.clearAllMocks();
  process.env = { ...saved };
});

describe("changePassword 动作：同源与会话", () => {
  it("跨站请求：拒绝，不调用 UAM", async () => {
    requestHeaders({ "sec-fetch-site": "cross-site" });
    const state = await changePassword({}, form());
    expect(state.formError).toMatch(/刷新页面/);
    expect(state.success).toBeUndefined();
    expect(stub.calls).toHaveLength(0);
  });

  it("会话已失效（带过 cookie）：先经 /session/expire，登录后回到改密页，不调用 UAM", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: true });
    await expect(changePassword({}, form())).rejects.toThrow("NEXT_REDIRECT:/session/expire?next=%2Faccount%2Fpassword");
    expect(stub.calls).toHaveLength(0);
  });

  it("没有会话：去登录页，登录后回到改密页", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: false });
    await expect(changePassword({}, form())).rejects.toThrow("NEXT_REDIRECT:/login?next=%2Faccount%2Fpassword");
    expect(stub.calls).toHaveLength(0);
  });

  it("无法确认会话（UAM 暂不可用）：「操作未生效」，不调用 UAM", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "unverified" });
    const state = await changePassword({}, form());
    expect(state.formError).toBe("操作未生效");
    expect(stub.calls).toHaveLength(0);
  });
});

describe("changePassword 动作：输入校验（不调用 UAM，字段旁给出原因）", () => {
  it("三项都空：三处必填提示", async () => {
    const state = await changePassword({}, form({ currentPassword: "", newPassword: "", confirmPassword: "" }));
    expect(state.fieldErrors).toEqual({
      currentPassword: "请填写当前密码",
      newPassword: "请填写新密码",
      confirmPassword: "请再次输入新密码",
    });
    expect(stub.calls).toHaveLength(0);
  });

  it("缺字段（非字符串）按空处理", async () => {
    const state = await changePassword({}, new FormData());
    expect(Object.keys(state.fieldErrors ?? {}).sort()).toEqual(["confirmPassword", "currentPassword", "newPassword"]);
    expect(stub.calls).toHaveLength(0);
  });

  it("新密码太弱：强度要求在新密码旁；返回状态不含任何密码", async () => {
    const state = await changePassword({}, form({ newPassword: "weak1" }));
    expect(state.fieldErrors).toEqual({ newPassword: "新密码至少 10 位，且同时包含字母和数字" });
    noPasswordIn(state);
    expect(JSON.stringify(state)).not.toContain("weak1");
    expect(stub.calls).toHaveLength(0);
  });

  it("两次输入的新密码不一致：不调用 UAM，确认项从不外传", async () => {
    const state = await changePassword({}, form({ confirmPassword: `${NEW}x` }));
    expect(state.fieldErrors).toEqual({ confirmPassword: "两次输入的新密码不一致" });
    noPasswordIn(state);
    expect(stub.calls).toHaveLength(0);
  });

  it("新密码与当前密码相同：不调用 UAM", async () => {
    const state = await changePassword({}, form({ currentPassword: NEW }));
    expect(state.fieldErrors).toEqual({ newPassword: "新密码不能与当前密码相同" });
    expect(stub.calls).toHaveLength(0);
  });
});

describe("changePassword 动作：来源地址", () => {
  it.each([
    ["缺失", { "x-forwarded-for": null }],
    ["不是合法 IP", { "x-forwarded-for": "not-an-ip" }],
  ])("来源地址%s：安全失败（服务暂不可用），不调用 UAM", async (_name, overrides) => {
    requestHeaders(overrides);
    const state = await changePassword({}, form());
    expect(state.formError).toBe("服务暂不可用，请稍后重试");
    expect(stub.calls).toHaveLength(0);
    expect(console.warn).toHaveBeenCalled();
  });

  it("按可信代理跳数取右数第 N 项，忽略用户自带的左侧内容", async () => {
    requestHeaders({ "x-forwarded-for": "198.51.100.9, 203.0.113.7" });
    changed();
    await changePassword({}, form());
    expect((stub.calls[0].body as { clientIp: string }).clientIp).toBe("203.0.113.7");
  });
});

describe("changePassword 动作：成功", () => {
  it("accountId 取自会话（操作人自己的账号）；带服务密钥与操作人请求头；请求体只有当前密码、新密码与来源地址", async () => {
    changed();
    const state = await changePassword({}, form());

    expect(state).toEqual({ success: true });
    expect(stub.calls).toHaveLength(1);
    const [call] = stub.calls;
    expect(call.operationId).toBe("changeOwnPassword");
    expect(call.path).toBe(`/v1/accounts/${FIXTURE_IDS.accountId}/password`);
    expect(call.headers.get("authorization")).toBe(`Bearer ${UAM_STUB_SERVICE_KEY}`);
    expect(call.headers.get("x-operator-user-id")).toBe(FIXTURE_IDS.userId);
    expect(call.headers.get("x-operator-account-id")).toBe(FIXTURE_IDS.accountId);
    expect(call.headers.get("x-operator-session-ref")).toBe(FIXTURE_IDS.sessionRef);
    expect(call.body).toEqual({ currentPassword: CURRENT, newPassword: NEW, clientIp: "203.0.113.7" });
  });

  it("表单里伪造的 accountId 被忽略：永远只改操作人自己的账号", async () => {
    changed();
    const other = "99999999-9999-4999-8999-999999999999";
    await changePassword({}, form({ accountId: other }));
    expect(stub.calls[0].path).toBe(`/v1/accounts/${FIXTURE_IDS.accountId}/password`);
    expect(JSON.stringify(stub.calls[0].body)).not.toContain(other);
  });

  it("成功不返回任何密码，也不动会话 cookie（动作不涉及 cookie）", async () => {
    changed();
    noPasswordIn(await changePassword({}, form()));
  });
});

describe("changePassword 动作：UAM 的结论", () => {
  it("422 current_password_incorrect：在当前密码字段旁，不含密码", async () => {
    rejected(422, "current_password_incorrect");
    const state = await changePassword({}, form());
    expect(state).toEqual({ fieldErrors: { currentPassword: "当前密码不正确" } });
    noPasswordIn(state);
  });

  it("422 weak_password：在新密码字段旁", async () => {
    rejected(422, "weak_password");
    expect(await changePassword({}, form())).toEqual({
      fieldErrors: { newPassword: "新密码至少 10 位，且同时包含字母和数字" },
    });
  });

  it("422 same_as_current：在新密码字段旁", async () => {
    rejected(422, "same_as_current");
    expect(await changePassword({}, form())).toEqual({ fieldErrors: { newPassword: "新密码不能与当前密码相同" } });
  });

  it("422 validation_failed：按字段给原因（未知字段忽略）", async () => {
    rejected(422, "validation_failed", {
      errors: [
        { field: "currentPassword", reason: "required" },
        { field: "newPassword", reason: "too_weak" },
        { field: "other", reason: "required" },
      ],
    });
    expect((await changePassword({}, form())).fieldErrors).toEqual({
      currentPassword: "请填写当前密码",
      newPassword: "新密码至少 10 位，且同时包含字母和数字",
    });
  });

  it("422 validation_failed 没有可显示的字段：通用提示", async () => {
    rejected(422, "validation_failed");
    expect((await changePassword({}, form())).formError).toBe("输入有误，请按提示修改后重试");
    rejected(422, "validation_failed", { errors: [{ field: "clientIp", reason: "required" }] });
    expect((await changePassword({}, form())).formError).toBe("输入有误，请按提示修改后重试");
  });

  it("429 throttled：提示稍后再试，带 Retry-After 秒数；不显示成功", async () => {
    rejected(429, "throttled", {}, { "Retry-After": "900" });
    const state = await changePassword({}, form());
    expect(state.formError).toBe("尝试过于频繁，请 900 秒后再试");
    expect(state.success).toBeUndefined();
    expect(state.fieldErrors).toBeUndefined();
  });

  it.each([
    ["连接失败", { networkError: true } as const],
    ["超时", { status: 204, delayMs: 400 } as const],
  ])("UAM 不可用（%s）：「操作未生效」，绝不显示成功", async (_name, reply) => {
    process.env.UAM_TIMEOUT_MS = "40";
    stub.respond("changeOwnPassword", reply);
    expect(await changePassword({}, form())).toEqual({ formError: "操作未生效" });
  });

  it("UAM 拒绝了 Admin Center 本身（服务密钥或权限）：「操作未生效」，并给运维告警（不含密钥与密码）", async () => {
    stub.respond("changeOwnPassword", { status: 403, body: problemBody(403, "client_forbidden") });
    expect(await changePassword({}, form())).toEqual({ formError: "操作未生效" });
    expect(console.error).toHaveBeenCalledTimes(1);
    expect(console.error).toHaveBeenCalledWith(expect.stringContaining("403"));
  });

  it("未知问题码：「操作未生效」", async () => {
    rejected(422, "brand_new_code");
    expect(await changePassword({}, form())).toEqual({ formError: "操作未生效" });
  });

  it("非 UAM 错误是程序问题：原样抛出，不伪装成提示", async () => {
    process.env.UAM_BASE_URL = "not-a-url";
    await expect(changePassword({}, form())).rejects.toThrow();
  });
});
