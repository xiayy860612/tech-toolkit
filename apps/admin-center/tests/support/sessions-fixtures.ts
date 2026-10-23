import type { components } from "@/lib/uam/types.generated";

type OnlineUser = components["schemas"]["OnlineUser"];
type Session = components["schemas"]["Session"];

/** 符合契约的在线用户 / 会话夹具（单元测试共用）。 */
export const SESSION_REFS = {
  one: "5d0c8f3a-1b2c-4d3e-8f4a-0123456789ab",
  two: "9a8b7c6d-5e4f-4a3b-9c2d-ba9876543210",
};

/** 固定的「当前时间」：相对时间文案按它计算，测试不依赖真实时钟。 */
export const NOW = Date.parse("2026-10-24T12:00:00Z");

export function makeOnlineUser(overrides: Partial<OnlineUser> = {}): OnlineUser {
  return {
    userId: "11111111-1111-4111-8111-111111111111",
    userName: "张三",
    sessionCount: 1,
    lastActiveAt: "2026-10-24T11:55:00Z",
    ...overrides,
  };
}

export function makeSession(overrides: Partial<Session> = {}): Session {
  return {
    sessionRef: SESSION_REFS.one,
    accountId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    accountName: "zhangsan",
    accountKind: "PASSWORD",
    loginAt: "2026-10-24T10:00:00Z",
    lastActiveAt: "2026-10-24T11:55:00Z",
    expiresAt: "2026-10-24T22:00:00Z",
    current: false,
    ...overrides,
  };
}
