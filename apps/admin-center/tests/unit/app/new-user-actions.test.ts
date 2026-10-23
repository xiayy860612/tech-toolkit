/**
 * @jest-environment node
 */
import { headers } from "next/headers";
import { createUser } from "@/app/(shell)/users/new/actions";
import { getSession } from "@/lib/session/get-session";
import {
  createUamStub,
  FIXTURE_IDS,
  problemBody,
  UAM_STUB_BASE_URL,
  UAM_STUB_SERVICE_KEY,
  verifyResponseBody,
} from "../../support/uam-stub";
import { makeDetail } from "../../support/users-fixtures";

jest.mock("next/headers", () => ({ headers: jest.fn() }));
jest.mock("next/navigation", () => ({
  redirect: jest.fn((to: string) => {
    throw new Error(`NEXT_REDIRECT:${to}`);
  }),
}));
jest.mock("@/lib/session/get-session", () => ({ getSession: jest.fn() }));

const stub = createUamStub();
const saved = { ...process.env };
const PASSWORD = "Sup3r-Secret-pass";
const KEY = "11111111-2222-4333-8444-555555555555";
const CREATED_ID = "44444444-4444-4444-8444-444444444444";

const SAME_ORIGIN = { host: "admin.test", origin: "http://admin.test", "sec-fetch-site": "same-origin" };

function requestHeaders(overrides: Record<string, string | null> = {}) {
  const h = new Headers(SAME_ORIGIN);
  for (const [k, v] of Object.entries(overrides)) {
    if (v === null) h.delete(k);
    else h.set(k, v);
  }
  jest.mocked(headers).mockResolvedValue(h as never);
}

function form(values: { userName?: string; accountName?: string; password?: string; key?: string | null } = {}) {
  const fd = new FormData();
  fd.set("userName", values.userName ?? "王五");
  fd.set("accountName", values.accountName ?? "wangwu");
  fd.set("password", values.password ?? PASSWORD);
  if (values.key !== null) fd.set("idempotencyKey", values.key ?? KEY);
  return fd;
}

const created = () => stub.respond("createUser", { status: 201, body: makeDetail({ id: CREATED_ID, name: "王五" }) });

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
  // 任何日志都不能带出密码或服务密钥
  const logged = JSON.stringify(logs.flatMap((l) => l.mock.calls));
  expect(logged).not.toContain(PASSWORD);
  expect(logged).not.toContain(UAM_STUB_SERVICE_KEY);
  logs.splice(0).forEach((l) => l.mockRestore());
  stub.reset();
  jest.clearAllMocks();
  process.env = { ...saved };
});

describe("createUser 动作：同源、会话与幂等键", () => {
  it("跨站请求：拒绝，不调用 UAM，保留幂等键与已填内容", async () => {
    requestHeaders({ "sec-fetch-site": "cross-site" });
    const state = await createUser({}, form());
    expect(state.formError).toBeTruthy();
    expect(state.idempotencyKey).toBe(KEY);
    expect(state.values).toEqual({ userName: "王五", accountName: "wangwu" });
    expect(stub.calls).toHaveLength(0);
  });

  it.each([[null], [""], ["short"], ["has space and is long enough!!"], ["x".repeat(65)]])(
    "幂等键缺失或格式不对（%j）：不调用 UAM，提示刷新页面",
    async (key) => {
      const state = await createUser({}, form({ key }));
      expect(state.formError).toMatch(/刷新页面/);
      expect(stub.calls).toHaveLength(0);
    },
  );

  it("会话已失效（带过 cookie）：先经 /session/expire 再回到新建页，不调用 UAM", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: true });
    await expect(createUser({}, form())).rejects.toThrow("NEXT_REDIRECT:/session/expire?next=%2Fusers%2Fnew");
    expect(stub.calls).toHaveLength(0);
  });

  it("没有会话：去登录页，登录后回到新建页", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "anonymous", hadCookie: false });
    await expect(createUser({}, form())).rejects.toThrow("NEXT_REDIRECT:/login?next=%2Fusers%2Fnew");
    expect(stub.calls).toHaveLength(0);
  });

  it("无法确认会话（UAM 暂不可用）：「操作未生效」，不调用 UAM，保留幂等键", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "unverified" });
    const state = await createUser({}, form());
    expect(state.formError).toBe("操作未生效");
    expect(state.idempotencyKey).toBe(KEY);
    expect(stub.calls).toHaveLength(0);
  });
});

describe("createUser 动作：输入校验（不调用 UAM，字段旁给出原因）", () => {
  it("三项都空：三处必填提示；不回填密码；幂等键不变（请求没发出去）", async () => {
    const state = await createUser({}, form({ userName: "", accountName: "", password: "" }));
    expect(state.fieldErrors).toEqual({ userName: "请填写用户名", accountName: "请填写账号名", password: "请填写初始密码" });
    expect(state.idempotencyKey).toBe(KEY);
    expect(stub.calls).toHaveLength(0);
  });

  it("缺字段（非字符串）按空处理", async () => {
    const fd = new FormData();
    fd.set("idempotencyKey", KEY);
    const state = await createUser({}, fd);
    expect(Object.keys(state.fieldErrors ?? {}).sort()).toEqual(["accountName", "password", "userName"]);
  });

  it("用户名只有空白视为空；超过 64 个字符（按字符数）被拒", async () => {
    expect((await createUser({}, form({ userName: "   " }))).fieldErrors?.userName).toBe("请填写用户名");
    const tooLong = await createUser({}, form({ userName: "字".repeat(65) }));
    expect(tooLong.fieldErrors?.userName).toBe("用户名最长 64 个字符");
    expect(tooLong.fieldErrors?.accountName).toBeUndefined();
    expect(stub.calls).toHaveLength(0);
  });

  it("用户名恰好 64 个字符（含代理对字符）通过", async () => {
    created();
    await expect(createUser({}, form({ userName: "𠮷".repeat(64) }))).rejects.toThrow("NEXT_REDIRECT");
    expect(stub.calls).toHaveLength(1);
  });

  it.each([["ab"], ["a".repeat(33)], ["has space"], ["中文账号"], ["bad/char"], ["a@b.c"]])(
    "账号名 %j 不合规：给出规则说明",
    async (accountName) => {
      const state = await createUser({}, form({ accountName }));
      expect(state.fieldErrors).toEqual({ accountName: "账号名为 3–32 个字母、数字或 _ . -" });
      expect(state.values?.accountName).toBe(accountName);
      expect(stub.calls).toHaveLength(0);
    },
  );

  it.each([
    ["short1A"],
    ["onlyletters-long"],
    ["1234567890123"],
    ["           "],
  ])("弱密码 %j：字段旁给出强度要求，返回状态不含密码", async (password) => {
    const state = await createUser({}, form({ password }));
    expect(state.fieldErrors).toEqual({ password: "密码至少 10 位，且同时包含字母和数字" });
    expect(JSON.stringify(state)).not.toContain(password.trim() || "\u0000");
    expect(stub.calls).toHaveLength(0);
  });

  it("超长密码在本地拒绝", async () => {
    const state = await createUser({}, form({ password: "a1".repeat(129) }));
    expect(state.fieldErrors?.password).toMatch(/256/);
    expect(stub.calls).toHaveLength(0);
  });

  it("账号名去首尾空白后校验与提交；已填内容（去空白）随状态返回，密码除外", async () => {
    const state = await createUser({}, form({ userName: "  王五  ", accountName: "  ab  ", password: "weak" }));
    expect(state.values).toEqual({ userName: "王五", accountName: "ab" });
    expect(JSON.stringify(state)).not.toContain("weak");
  });
});

describe("createUser 动作：成功", () => {
  it("带服务密钥、操作人请求头与 Idempotency-Key 调用 UAM；请求体为规范化后的输入；303 到详情页并带一次性提示", async () => {
    created();
    await expect(
      createUser({}, form({ userName: "  王五 ", accountName: " wangwu ", password: PASSWORD })),
    ).rejects.toThrow(`NEXT_REDIRECT:/users/${CREATED_ID}?created=1`);

    expect(stub.calls).toHaveLength(1);
    const [call] = stub.calls;
    expect(call.operationId).toBe("createUser");
    expect(call.headers.get("authorization")).toBe(`Bearer ${UAM_STUB_SERVICE_KEY}`);
    expect(call.headers.get("idempotency-key")).toBe(KEY);
    expect(call.headers.get("x-operator-user-id")).toBe(FIXTURE_IDS.userId);
    expect(call.headers.get("x-operator-account-id")).toBe(FIXTURE_IDS.accountId);
    expect(call.headers.get("x-operator-session-ref")).toBe(FIXTURE_IDS.sessionRef);
    expect(call.body).toEqual({ userName: "王五", accountName: "wangwu", password: PASSWORD });
  });
});

describe("createUser 动作：UAM 的结论", () => {
  it("409 account_name_taken：提示在账号名字段旁，保留已填内容（无密码），换新幂等键以便改名后重试", async () => {
    stub.respond("createUser", { status: 409, body: problemBody(409, "account_name_taken") });
    const state = await createUser({}, form({ accountName: "WangWu" }));
    expect(state.fieldErrors).toEqual({ accountName: "账号名已被使用" });
    expect(state.formError).toBeUndefined();
    expect(state.values).toEqual({ userName: "王五", accountName: "WangWu" });
    expect(state.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(state.idempotencyKey).not.toBe(KEY);
    expect(JSON.stringify(state)).not.toContain(PASSWORD);
  });

  it("422 validation_failed：按 errors[].field / reason 显示在对应字段旁（未知字段忽略），换新幂等键", async () => {
    stub.respond("createUser", {
      status: 422,
      body: problemBody(422, "validation_failed", {
        errors: [
          { field: "password", reason: "too_weak" },
          { field: "accountName", reason: "taken" },
          { field: "userName", reason: "too_long" },
          { field: "somethingElse", reason: "required" },
        ],
      }),
    });
    const state = await createUser({}, form());
    expect(state.fieldErrors).toEqual({
      password: "密码至少 10 位，且同时包含字母和数字",
      accountName: "账号名已被使用",
      userName: "用户名最长 64 个字符",
    });
    expect(state.idempotencyKey).not.toBe(KEY);
    expect(state.values).toEqual({ userName: "王五", accountName: "wangwu" });
  });

  it("422 里没有可显示的字段或原因未知：给通用提示 / 通用原因", async () => {
    stub.respond("createUser", { status: 422, body: problemBody(422, "validation_failed") });
    expect((await createUser({}, form())).formError).toBe("输入有误，请按提示修改后重试");

    stub.respond("createUser", {
      status: 422,
      body: problemBody(422, "validation_failed", { errors: [{ field: "accountName", reason: "brand_new" }] }),
    });
    expect((await createUser({}, form())).fieldErrors).toEqual({ accountName: "输入不合法" });
  });

  it("422 的字段原因按字段给出专门文案", async () => {
    stub.respond("createUser", {
      status: 422,
      body: problemBody(422, "validation_failed", {
        errors: [
          { field: "userName", reason: "required" },
          { field: "accountName", reason: "too_short" },
          { field: "password", reason: "too_short" },
        ],
      }),
    });
    expect((await createUser({}, form())).fieldErrors).toEqual({
      userName: "请填写用户名",
      accountName: "账号名为 3–32 个字母、数字或 _ . -",
      password: "密码至少 10 位，且同时包含字母和数字",
    });
  });

  it.each([
    ["连接失败", { networkError: true } as const],
    ["超时", { status: 201, body: makeDetail(), delayMs: 400 } as const],
  ])("UAM 不可用（%s）：「操作未生效」，不显示成功，保留已填内容与同一个幂等键（重试不会重复创建）", async (_name, reply) => {
    process.env.UAM_TIMEOUT_MS = "40";
    stub.respond("createUser", reply);
    const state = await createUser({}, form());
    expect(state.formError).toBe("操作未生效");
    expect(state.idempotencyKey).toBe(KEY);
    expect(state.values).toEqual({ userName: "王五", accountName: "wangwu" });
    expect(JSON.stringify(state)).not.toContain(PASSWORD);
  });

  it("UAM 拒绝了 Admin Center（服务密钥错误）：对用户只显示「操作未生效」，运维告警一条（不含秘密）", async () => {
    stub.respond("createUser", { status: 403, body: problemBody(403, "client_forbidden") });
    const state = await createUser({}, form());
    expect(state.formError).toBe("操作未生效");
    expect(state.idempotencyKey).toBe(KEY);
    expect(console.error).toHaveBeenCalledTimes(1);
  });

  it("其它业务问题码（operator_required 或未知）：「操作未生效」，保持原幂等键", async () => {
    for (const code of ["operator_required", "brand_new_code"]) {
      stub.respond("createUser", { status: 400, body: problemBody(400, code) });
      const state = await createUser({}, form());
      expect(state.formError).toBe("操作未生效");
      expect(state.idempotencyKey).toBe(KEY);
    }
  });

  it("非 UAM 错误是程序问题：原样抛出，不伪装成失败提示", async () => {
    process.env.UAM_BASE_URL = "not-a-url";
    await expect(createUser({}, form())).rejects.toThrow();
  });
});
