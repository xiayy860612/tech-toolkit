"use server";

import "server-only";
import { randomUUID } from "node:crypto";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { isSameOriginRequest } from "@/lib/security/origin-check";
import { getSession } from "@/lib/session/get-session";
import { createUser as uamCreateUser } from "@/lib/uam/client";
import { UamError, uamErrorMessage } from "@/lib/uam/errors";
import {
  ACCOUNT_NAME_TAKEN_MESSAGE,
  mapUamFieldErrors,
  parseNewUserForm,
  readIdempotencyKey,
  readNewUserValues,
  type NewUserFormState,
} from "@/lib/validation/new-user";

const NEW_USER_PATH = "/users/new";
/** 无法确认会话（UAM 暂不可用）：与 UAM 不可用同一文案「操作未生效」。 */
const UNVERIFIED = new UamError("unavailable", "无法确认会话");

/**
 * UAM 失败 → 表单状态（contracts/web-routes.md §5）。
 * - 409 / 422：UAM 明确拒绝，什么都没创建 → 字段旁给原因，并换新幂等键（同键重放可能原样返回这次拒绝，用户改正后就提交不了）；
 * - 其余（不可用、密钥配置错误、未知问题码）：「操作未生效」，**保持原幂等键**——超时时可能已经创建成功，重试同键会拿回原结果而不是建第二个。
 */
function failureState(error: UamError, values: NonNullable<NewUserFormState["values"]>, key: string): NewUserFormState {
  if (error.kind === "problem") {
    if (error.code === "account_name_taken") {
      return { fieldErrors: { accountName: ACCOUNT_NAME_TAKEN_MESSAGE }, values, idempotencyKey: randomUUID() };
    }
    if (error.code === "validation_failed") {
      const fieldErrors = mapUamFieldErrors(error.fieldErrors ?? []);
      const state = Object.keys(fieldErrors).length > 0 ? { fieldErrors } : { formError: uamErrorMessage(error, "write") };
      return { ...state, values, idempotencyKey: randomUUID() };
    }
  }
  return { formError: uamErrorMessage(error, "write"), values, idempotencyKey: key };
}

/**
 * 新建用户 Server Action：同源校验 → 会话三态 → 输入校验 → 调 UAM `createUser`（操作人头 + Idempotency-Key）→ 303 到详情页。
 * 返回值只含提示、已填的用户名 / 账号名与下一次提交的幂等键，**不含密码**；成功时不返回（`redirect` 抛出）。
 */
export async function createUser(_previous: NewUserFormState, formData: FormData): Promise<NewUserFormState> {
  const values = readNewUserValues(formData);
  const key = readIdempotencyKey(formData);
  if (!key) return { formError: "页面已过期，请刷新页面后重试", values };

  if (!isSameOriginRequest("POST", await headers())) {
    return { formError: "请求来源校验失败，请刷新页面后重试", values, idempotencyKey: key };
  }

  const state = await getSession();
  if (state.status === "anonymous") {
    // 会话已结束：经 /session/expire 清掉失效 cookie（动作里也可写 cookie，但与布局的处理保持同一条路径），登录后回到新建页
    const next = `?next=${encodeURIComponent(NEW_USER_PATH)}`;
    redirect(`${state.hadCookie ? "/session/expire" : "/login"}${next}`);
  }
  if (state.status === "unverified") return { formError: uamErrorMessage(UNVERIFIED, "write"), values, idempotencyKey: key };

  const parsed = parseNewUserForm(formData);
  if (!parsed.success) return { fieldErrors: parsed.fieldErrors, values, idempotencyKey: key };

  const { user, account, sessionRef } = state.session;
  let created;
  try {
    created = await uamCreateUser(parsed.data, { userId: user.id, accountId: account.id, sessionRef }, key);
  } catch (error) {
    if (error instanceof UamError) return failureState(error, values, key);
    throw error; // 非 UAM 错误是程序问题，不要伪装成失败提示
  }

  redirect(`/users/${encodeURIComponent(created.id)}?created=1`);
}
