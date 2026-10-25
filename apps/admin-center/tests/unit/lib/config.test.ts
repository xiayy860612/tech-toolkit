/**
 * @jest-environment node
 */
import { ConfigError, getConfig, parseConfig } from "@/lib/config";

const VALID = {
  UAM_BASE_URL: "http://uam.internal:8080",
  UAM_SERVICE_KEY: "test-service-key",
};

describe("parseConfig", () => {
  it("接受只含必填项的环境并套用默认值", () => {
    const cfg = parseConfig(VALID);
    expect(cfg).toEqual({
      uamBaseUrl: "http://uam.internal:8080",
      uamServiceKey: "test-service-key",
      uamTimeoutMs: 1500,
      sessionCookieName: "__Host-asid",
      trustedProxyHops: 1,
    });
  });

  it("去掉 UAM_BASE_URL 末尾的斜杠", () => {
    expect(parseConfig({ ...VALID, UAM_BASE_URL: "http://uam:8080/" }).uamBaseUrl).toBe("http://uam:8080");
  });

  it("读取可选项", () => {
    const cfg = parseConfig({
      ...VALID,
      UAM_TIMEOUT_MS: "3000",
      SESSION_COOKIE_NAME: "asid",
      TRUSTED_PROXY_HOPS: "2",
    });
    expect(cfg.uamTimeoutMs).toBe(3000);
    expect(cfg.sessionCookieName).toBe("asid");
    expect(cfg.trustedProxyHops).toBe(2);
  });

  it("TRUSTED_PROXY_HOPS 允许 0（不信任任何代理头）", () => {
    expect(parseConfig({ ...VALID, TRUSTED_PROXY_HOPS: "0" }).trustedProxyHops).toBe(0);
  });

  it.each([
    ["缺少 UAM_SERVICE_KEY", { UAM_BASE_URL: VALID.UAM_BASE_URL }, "UAM_SERVICE_KEY"],
    ["UAM_SERVICE_KEY 为空串（.env.example 的占位）", { ...VALID, UAM_SERVICE_KEY: "" }, "UAM_SERVICE_KEY"],
    ["缺少 UAM_BASE_URL", { UAM_SERVICE_KEY: VALID.UAM_SERVICE_KEY }, "UAM_BASE_URL"],
    ["UAM_BASE_URL 不是合法地址", { ...VALID, UAM_BASE_URL: "not a url" }, "UAM_BASE_URL"],
    ["UAM_TIMEOUT_MS 不是正整数", { ...VALID, UAM_TIMEOUT_MS: "0" }, "UAM_TIMEOUT_MS"],
    ["UAM_TIMEOUT_MS 不是数字", { ...VALID, UAM_TIMEOUT_MS: "fast" }, "UAM_TIMEOUT_MS"],
    ["TRUSTED_PROXY_HOPS 为负数", { ...VALID, TRUSTED_PROXY_HOPS: "-1" }, "TRUSTED_PROXY_HOPS"],
    ["TRUSTED_PROXY_HOPS 不是整数", { ...VALID, TRUSTED_PROXY_HOPS: "1.5" }, "TRUSTED_PROXY_HOPS"],
  ])("%s 时失败，且只报变量名", (_name, env, variable) => {
    expect(() => parseConfig(env)).toThrow(ConfigError);
    expect(() => parseConfig(env)).toThrow(variable);
  });

  it("两个必填项都缺时一次列全", () => {
    expect(() => parseConfig({})).toThrow(/UAM_BASE_URL.*UAM_SERVICE_KEY|UAM_SERVICE_KEY.*UAM_BASE_URL/s);
  });

  it("错误信息不回显服务密钥的值", () => {
    try {
      parseConfig({ UAM_SERVICE_KEY: "super-secret-value", UAM_BASE_URL: "bad url" });
      throw new Error("应当抛错");
    } catch (error) {
      expect((error as Error).message).not.toContain("super-secret-value");
    }
  });
});

describe("getConfig", () => {
  const saved = { ...process.env };
  afterEach(() => {
    process.env = { ...saved };
  });

  it("每次调用才读 process.env，模块加载时不解析（next build 不受缺失变量影响）", () => {
    delete process.env.UAM_BASE_URL;
    delete process.env.UAM_SERVICE_KEY;
    // 能走到这里说明 import 时没有抛错
    expect(() => getConfig()).toThrow(ConfigError);

    process.env.UAM_BASE_URL = VALID.UAM_BASE_URL;
    process.env.UAM_SERVICE_KEY = VALID.UAM_SERVICE_KEY;
    expect(getConfig().uamServiceKey).toBe(VALID.UAM_SERVICE_KEY);
  });
});
