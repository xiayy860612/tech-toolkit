import type { Account } from "@/lib/uam/client";

/** 账号种类的界面文案（用户详情的账号表与会话卡片共用）。 */
export const ACCOUNT_KIND_LABELS: Record<Account["kind"], string> = { PASSWORD: "账号名 / 密码" };
