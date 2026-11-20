import type { components } from "./schema";
import type { components as bff } from "./bff-schema";

/** 契约生成类型的别名（schema.d.ts / bff-schema.d.ts 由 `pnpm generate:api` 生成，勿手改）。 */
type S = components["schemas"];

export type Me = S["Me"];
export type Role = S["Role"];
export type Status = S["Status"];
export type User = S["User"];
export type UserPage = S["UserPage"];
export type CreateUserRequest = S["CreateUserRequest"];
export type CreateUserResult = S["CreateUserResult"];
export type UpdateUserRequest = S["UpdateUserRequest"];
export type Account = S["Account"];
export type AccountWithTemporaryPassword = S["AccountWithTemporaryPassword"];
export type CreateAccountRequest = S["CreateAccountRequest"];
export type LoginSession = S["LoginSession"];
export type LoginSessionPage = S["LoginSessionPage"];
export type BrowserMe = bff["schemas"]["BrowserMe"];
