import type { components } from "@/lib/uam/types.generated";

type UserSummary = components["schemas"]["UserSummary"];
type UserDetail = components["schemas"]["UserDetail"];
type Account = components["schemas"]["Account"];

/** 符合契约的用户 / 账号夹具（单元测试共用）。 */
export const USER_IDS = {
  zhang: "11111111-1111-4111-8111-111111111111",
  li: "22222222-2222-4222-8222-222222222222",
  wang: "33333333-3333-4333-8333-333333333333",
};

export function makeSummary(overrides: Partial<UserSummary> = {}): UserSummary {
  return {
    id: USER_IDS.zhang,
    name: "张三",
    status: "ACTIVE",
    accountCount: 1,
    createdAt: "2026-10-01T08:30:00Z",
    ...overrides,
  };
}

export function makeAccount(overrides: Partial<Account> = {}): Account {
  return {
    id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    loginName: "zhangsan",
    kind: "PASSWORD",
    status: "ACTIVE",
    createdAt: "2026-10-01T08:30:00Z",
    ...overrides,
  };
}

export function makeDetail(overrides: Partial<UserDetail> = {}): UserDetail {
  return {
    id: USER_IDS.li,
    name: "李四",
    status: "ACTIVE",
    createdAt: "2026-09-20T01:02:00Z",
    accounts: [
      makeAccount({ id: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", loginName: "lisi" }),
      makeAccount({ id: "cccccccc-cccc-4ccc-8ccc-cccccccccccc", loginName: "lisi.mobile", createdAt: "2026-09-25T10:00:00Z" }),
    ],
    ...overrides,
  };
}
