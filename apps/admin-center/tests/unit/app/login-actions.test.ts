/**
 * @jest-environment node
 */
import { cookies, headers } from "next/headers";
import { login } from "@/app/(auth)/login/actions";
import { login as uamLogin } from "@/lib/uam/client";
import {
  createUamStub,
  loginResponseBody,
  problemBody,
  UAM_STUB_BASE_URL,
  UAM_STUB_SERVICE_KEY,
} from "../../support/uam-stub";

jest.mock("next/headers", () => ({ headers: jest.fn(), cookies: jest.fn() }));
jest.mock("next/navigation", () => ({
  redirect: jest.fn((to: string) => {
    throw new Error(`NEXT_REDIRECT:${to}`);
  }),
}));

const stub = createUamStub();
const saved = { ...process.env };
const cookieSet = jest.fn();
const PASSWORD = "Sup3r-Secret-pass";

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

function form(values: { accountName?: string; password?: string; next?: string } = {}) {
  const fd = new FormData();
  fd.set("accountName", values.accountName ?? "zhangsan");
  fd.set("password", values.password ?? PASSWORD);
  if (values.next !== undefined) fd.set("next", values.next);
  return fd;
}

const logs: jest.SpyInstance[] = [];

beforeAll(() => stub.listen());
afterAll(() => stub.close());
beforeEach(() => {
  process.env.UAM_BASE_URL = UAM_STUB_BASE_URL;
  process.env.UAM_SERVICE_KEY = UAM_STUB_SERVICE_KEY;
  process.env.SESSION_COOKIE_NAME = "asid";
  process.env.TRUSTED_PROXY_HOPS = "1";
  jest.mocked(cookies).mockResolvedValue({ set: cookieSet } as never);
  requestHeaders();
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
  cookieSet.mockClear();
  process.env = { ...saved };
});

describe("login 动作：输入校验", () => {
  it("账号名为空：字段提示必填，不调用 UAM，不写 cookie", async () => {
    const state = await login({}, form({ accountName: "" }));
    expect(state.fieldErrors).toEqual({ accountName: "请填写账号名" });
    expect(stub.calls).toHaveLength(0);
    expect(cookieSet).not.toHaveBeenCalled();
  });

  it("只有空白的账号名同样视为空", async () => {
    const state = await login({}, form({ accountName: "   " }));
    expect(state.fieldErrors?.accountName).toBe("请填写账号名");
    expect(stub.calls).toHaveLength(0);
  });

  it("密码为空：字段提示必填，不调用 UAM", async () => {
    const state = await login({}, form({ password: "" }));
    expect(state.fieldErrors).toEqual({ password: "请填写密码" });
    expect(state.accountName).toBe("zhangsan");
    expect(stub.calls).toHaveLength(0);
  });

  it("两项都空：两处提示；缺字段（非字符串）按空处理", async () => {
    const state = await login({}, new FormData());
    expect(state.fieldErrors).toEqual({ accountName: "请填写账号名", password: "请填写密码" });
    expect(stub.calls).toHaveLength(0);
  });

  it("超长输入在本地拒绝（与 UAM 契约的长度上限一致），不调用 UAM", async () => {
    const state = await login({}, form({ accountName: "a".repeat(65), password: "p".repeat(257) }));
    expect(state.fieldErrors?.accountName).toMatch(/64/);
    expect(state.fieldErrors?.password).toMatch(/256/);
    expect(stub.calls).toHaveLength(0);
  });

  it("返回的状态从不包含密码（不回填）", async () => {
    const state = await login({}, form({ accountName: "" }));
    expect(JSON.stringify(state)).not.toContain(PASSWORD);
  });
});

describe("login 动作：同源与来源地址", () => {
  it("跨站请求（Sec-Fetch-Site: cross-site）：拒绝，不调用 UAM，不写 cookie", async () => {
    requestHeaders({ "sec-fetch-site": "cross-site" });
    const state = await login({}, form());
    expect(state.formError).toBeTruthy();
    expect(stub.calls).toHaveLength(0);
    expect(cookieSet).not.toHaveBeenCalled();
  });

  it("Origin 与主机不一致：拒绝", async () => {
    requestHeaders({ origin: "http://evil.test", "sec-fetch-site": null });
    await login({}, form());
    expect(stub.calls).toHaveLength(0);
  });

  it("既无 Origin 也无 Sec-Fetch-Site：拒绝", async () => {
    requestHeaders({ origin: null, "sec-fetch-site": null });
    await login({}, form());
    expect(stub.calls).toHaveLength(0);
  });

  it("取 X-Forwarded-For 右数第 TRUSTED_PROXY_HOPS 项作为 clientIp，左边用户自带的不采信", async () => {
    requestHeaders({ "x-forwarded-for": "1.2.3.4, 203.0.113.9" });
    stub.respond("login", { status: 200, body: loginResponseBody() });
    await expect(login({}, form())).rejects.toThrow("NEXT_REDIRECT");
    expect((stub.calls[0].body as { clientIp: string }).clientIp).toBe("203.0.113.9");
  });

  it("取不到来源地址：不调用 UAM、不共用限流桶，按「服务暂不可用」失败并记一条不含秘密的告警", async () => {
    requestHeaders({ "x-forwarded-for": null });
    const state = await login({}, form());
    expect(state.formError).toBe("服务暂不可用，请稍后重试");
    expect(stub.calls).toHaveLength(0);
    expect(cookieSet).not.toHaveBeenCalled();
    expect(console.warn).toHaveBeenCalledTimes(1);
  });

  it("X-Forwarded-For 不是合法 IP：同样拒绝", async () => {
    requestHeaders({ "x-forwarded-for": "not-an-ip" });
    const state = await login({}, form());
    expect(state.formError).toBe("服务暂不可用，请稍后重试");
    expect(stub.calls).toHaveLength(0);
  });
});

describe("login 动作：UAM 的结论", () => {
  it("invalid_credentials：统一提示，不说明哪一项错，不写 cookie，不回填密码", async () => {
    stub.respond("login", { status: 401, body: problemBody(401, "invalid_credentials") });
    const state = await login({}, form());
    expect(state.formError).toBe("账号名或密码错误");
    expect(state.fieldErrors).toBeUndefined();
    expect(state.accountName).toBe("zhangsan");
    expect(JSON.stringify(state)).not.toContain(PASSWORD);
    expect(cookieSet).not.toHaveBeenCalled();
  });

  it("throttled：提示稍后再试，并带 Retry-After 的秒数", async () => {
    stub.respond("login", { status: 429, body: problemBody(429, "throttled"), headers: { "Retry-After": "900" } });
    const state = await login({}, form());
    expect(state.formError).toBe("尝试过于频繁，请 900 秒后再试");
    expect(cookieSet).not.toHaveBeenCalled();
  });

  it("UAM 返回字段级 validation_failed：显示在对应字段旁，其余字段忽略", async () => {
    stub.respond("login", {
      status: 422,
      body: problemBody(422, "validation_failed", {
        errors: [
          { field: "accountName", reason: "invalid_chars" },
          { field: "clientIp", reason: "required" },
        ],
      }),
    });
    const state = await login({}, form());
    expect(state.fieldErrors).toEqual({ accountName: "含有不允许的字符" });
    expect(state.formError).toBeUndefined();
  });

  it("validation_failed 没有可显示的字段：给通用提示", async () => {
    stub.respond("login", { status: 422, body: problemBody(422, "validation_failed") });
    const state = await login({}, form());
    expect(state.formError).toBe("输入有误，请按提示修改后重试");
  });

  it("UAM 返回 400 bad_request（Admin Center 不会发出读不懂的请求，属意外拒绝）：按「服务暂不可用」处理，不当作凭据错误，不写 cookie，不回显输入", async () => {
    stub.respond("login", { status: 400, body: problemBody(400, "bad_request") });
    const state = await login({}, form());
    expect(state).toEqual({ formError: "服务暂不可用，请稍后重试", accountName: "zhangsan" });
    expect(state.formError).not.toBe("账号名或密码错误");
    expect(JSON.stringify(state)).not.toContain(PASSWORD);
    expect(cookieSet).not.toHaveBeenCalled();
  });

  it("UAM 不可用（连接失败）：提示服务暂不可用，不写 cookie", async () => {
    stub.respond("login", { networkError: true });
    const state = await login({}, form());
    expect(state.formError).toBe("服务暂不可用，请稍后重试");
    expect(cookieSet).not.toHaveBeenCalled();
  });

  it("UAM 拒绝了 Admin Center（服务密钥错误）：对用户显示服务暂不可用，并写运维告警（不含秘密）", async () => {
    stub.respond("login", { status: 403, body: problemBody(403, "client_forbidden") });
    const state = await login({}, form());
    expect(state.formError).toBe("服务暂不可用，请稍后重试");
    expect(console.error).toHaveBeenCalledTimes(1);
    expect(cookieSet).not.toHaveBeenCalled();
  });
});

describe("login 动作：成功", () => {
  beforeEach(() => {
    stub.respond("login", {
      status: 200,
      body: loginResponseBody({ sessionId: "sid-from-uam", maxAgeSeconds: 43100 }),
    });
  });

  it("带服务密钥与规范化后的输入调用 UAM；写 cookie（属性齐全、Max-Age 取 UAM 返回值）并跳 /users", async () => {
    await expect(login({}, form({ accountName: "  zhangsan  " }))).rejects.toThrow("NEXT_REDIRECT:/users");
    expect(stub.calls).toHaveLength(1);
    expect(stub.calls[0].headers.get("authorization")).toBe(`Bearer ${UAM_STUB_SERVICE_KEY}`);
    expect(stub.calls[0].body).toEqual({ accountName: "zhangsan", password: PASSWORD, clientIp: "203.0.113.7" });
    expect(cookieSet).toHaveBeenCalledWith("asid", "sid-from-uam", {
      httpOnly: true,
      secure: true,
      sameSite: "strict",
      path: "/",
      maxAge: 43100,
    });
  });

  it.each([
    ["/users/abc?x=1", "/users/abc?x=1"],
    ["/sessions", "/sessions"],
  ])("next=%s（站内路径）登录后回到原页", async (next, expected) => {
    await expect(login({}, form({ next }))).rejects.toThrow(`NEXT_REDIRECT:${expected}`);
  });

  it.each([
    ["//evil.example"],
    ["https://evil.example/x"],
    ["/\\evil.example"],
    ["javascript:alert(1)"],
    ["/users\r\nSet-Cookie: x=1"],
    [""],
  ])("next=%j 不是安全的站内路径：忽略，进入 /users", async (next) => {
    await expect(login({}, form({ next }))).rejects.toThrow("NEXT_REDIRECT:/users");
  });

  it("没有 next 字段：进入 /users", async () => {
    await expect(login({}, form())).rejects.toThrow("NEXT_REDIRECT:/users");
  });
});

describe("uam client 的 login", () => {
  it("请求体里的密码不会出现在抛出的错误信息里", async () => {
    stub.respond("login", { status: 401, body: problemBody(401, "invalid_credentials") });
    const error = await uamLogin({ accountName: "zhangsan", password: PASSWORD, clientIp: "203.0.113.7" }).catch(
      (e: Error) => e,
    );
    expect(error).toBeInstanceOf(Error);
    expect(JSON.stringify(error)).not.toContain(PASSWORD);
    expect((error as Error).message).not.toContain(PASSWORD);
  });
});
