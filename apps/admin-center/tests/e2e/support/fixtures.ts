import { test as base, expect, type Page } from "@playwright/test";
import { psql, uamCli } from "./stack";

/** 本地 E2E 的测试账号；密码只在测试里使用。 */
export const ADMIN = { account: "admin", password: "E2e-Admin-Pass-1", name: "系统管理员" };

/** 部署期命令 init-admin：造出初始管理员（栈刚起来时调用一次）。 */
export function seedAdmin(): void {
  uamCli(["init-admin", "--account-name", ADMIN.account, "--user-name", ADMIN.name], `${ADMIN.password}\n`);
}

/** US2 的造数：固定标识，重复执行（每个视口项目各跑一次）不会产生重复数据。 */
export const USERS_SEED = {
  /** 3 个账号的用户（账号名大小写混合，用于大小写不敏感搜索）。 */
  multi: { id: "e2e00002-0000-4000-8000-000000000001", name: "多账号用户", accounts: ["E2eMulti.Alpha", "E2eMulti.Beta", "e2emulti.gamma"] },
  /** 没有账号的用户。 */
  none: { id: "e2e00002-0000-4000-8000-000000000002", name: "无账号用户" },
  /** 已停用、有 1 个账号的用户。 */
  disabled: { id: "e2e00002-0000-4000-8000-000000000003", name: "已停用用户", account: "e2e.disabled" },
  /** 与上面都不匹配、也不存在的用户标识。 */
  missing: "e2e00002-0000-4000-8000-0000000000ff",
  /** 分页用户：名称含此前缀，数量足以超过一页（20 条）。 */
  pagePrefix: "分页用户",
  pageCount: 25,
  password: "E2e-Seed-Pass-1",
};

const sqlText = (value: string) => `'${value.replace(/'/g, "''")}'`;

/**
 * 造 US2 需要的用户：多账号、无账号、已停用，以及多于一页的用户。
 * 创建用户的 API 是 US4，这里用 SQL 插入用户（及已停用用户的账号行），账号用部署期命令 add-account 追加（密码只经标准输入）。
 */
export function seedUsers(): void {
  const { multi, none, disabled, pagePrefix, pageCount, password } = USERS_SEED;
  psql(`
    INSERT INTO app_user (id, name, status, updated_at) VALUES
      (${sqlText(multi.id)}, ${sqlText(multi.name)}, 'ACTIVE', now()),
      (${sqlText(none.id)}, ${sqlText(none.name)}, 'ACTIVE', now()),
      (${sqlText(disabled.id)}, ${sqlText(disabled.name)}, 'DISABLED', now())
    ON CONFLICT (id) DO NOTHING;
    INSERT INTO account (id, user_id, kind, login_name, login_name_norm, status)
    VALUES (gen_random_uuid(), ${sqlText(disabled.id)}, 'PASSWORD', ${sqlText(disabled.account)}, ${sqlText(disabled.account.toLowerCase())}, 'ACTIVE')
    ON CONFLICT (login_name_norm) DO NOTHING;
    INSERT INTO app_user (id, name, status, updated_at)
    SELECT gen_random_uuid(), ${sqlText(pagePrefix)} || lpad(n::text, 2, '0'), 'ACTIVE', now()
    FROM generate_series(1, ${pageCount}) AS n
    WHERE (SELECT count(*) FROM app_user WHERE name LIKE ${sqlText(pagePrefix + "%")}) < ${pageCount};
  `);
  for (const account of multi.accounts) {
    const exists = psql(`SELECT 1 FROM account WHERE login_name_norm = ${sqlText(account.toLowerCase())};`).length > 0;
    if (!exists) uamCli(["add-account", "--user-id", multi.id, "--account-name", account], `${password}\n`);
  }
}

/** UAM（数据库）里某用户实际的账号数（SC-003 对账用）。 */
export function actualAccountCount(userId: string): number {
  return Number(psql(`SELECT count(*) FROM account WHERE user_id = ${sqlText(userId)};`)[0]);
}

/** UAM（数据库）里的用户总数（与不带搜索词的列表「共 N 位用户」对账）。 */
export function actualUserCount(): number {
  return Number(psql("SELECT count(*) FROM app_user;")[0]);
}

/** 登录辅助：走真实的登录页。 */
export async function login(page: Page, account = ADMIN.account, password = ADMIN.password): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("账号").fill(account);
  await page.getByLabel("密码").fill(password);
  await page.getByRole("button", { name: "登录" }).click();
}

/** 栈前面没有反向代理：用例统一带上 X-Forwarded-For 作为「终端来源地址」（playwright.config.ts 的 use.extraHTTPHeaders 引用）。 */
export const SOURCE_HEADERS = { "x-forwarded-for": "203.0.113.7" };

export const test = base;

export { expect };
