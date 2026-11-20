import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest, loginUrlFor, setSessionExpiredHandler } from "@/lib/api/client";
import { ApiError, errorMessage } from "@/lib/api/errors";

let fetchMock: ReturnType<typeof vi.fn>;
const onExpired = vi.fn();

beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
  onExpired.mockReset();
  setSessionExpiredHandler(onExpired);
});
afterEach(() => vi.unstubAllGlobals());

const jsonRes = (status: number, body: unknown, type = "application/json") =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": type } });

describe("apiRequest", () => {
  it("only talks to same-origin /api/v1", async () => {
    fetchMock.mockResolvedValue(jsonRes(200, { ok: true }));
    await apiRequest("/users?page=0");
    expect(fetchMock.mock.calls[0]![0]).toBe("/api/v1/users?page=0");
    expect(fetchMock.mock.calls[0]![1].credentials).toBe("same-origin");
  });

  it.each(["https://evil.test/x", "//evil.test/x", "users", "/../admin"])(
    "refuses non-API path %s",
    async (p) => {
      await expect(apiRequest(p)).rejects.toThrow();
      expect(fetchMock).not.toHaveBeenCalled();
    },
  );

  it("sends JSON bodies with a content-type", async () => {
    fetchMock.mockResolvedValue(jsonRes(200, {}));
    await apiRequest("/users", { method: "POST", body: { displayName: "A" } });
    const init = fetchMock.mock.calls[0]![1];
    expect(init.method).toBe("POST");
    expect(init.body).toBe('{"displayName":"A"}');
    expect(new Headers(init.headers).get("content-type")).toBe("application/json");
  });

  it("returns undefined for 204", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    expect(await apiRequest("/sessions/x", { method: "DELETE" })).toBeUndefined();
  });

  it("maps problem+json to ApiError with a stable code", async () => {
    fetchMock.mockResolvedValue(
      jsonRes(
        409,
        {
          type: "about:blank",
          title: "Conflict",
          status: 409,
          code: "USERNAME_TAKEN",
          errors: [{ field: "username", message: "x" }],
        },
        "application/problem+json",
      ),
    );
    const err = (await apiRequest("/users", { method: "POST", body: {} }).catch(
      (e) => e,
    )) as ApiError;
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(409);
    expect(err.code).toBe("USERNAME_TAKEN");
    expect(err.fieldErrors).toEqual([{ field: "username", message: "x" }]);
  });

  it("falls back to a generic ApiError for non-problem failures", async () => {
    fetchMock.mockResolvedValue(new Response("boom", { status: 500 }));
    const err = (await apiRequest("/users").catch((e) => e)) as ApiError;
    expect(err.status).toBe(500);
    expect(err.code).toBe("UNKNOWN");
  });

  it("maps network failures to an ApiError", async () => {
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));
    const err = (await apiRequest("/users").catch((e) => e)) as ApiError;
    expect(err.code).toBe("NETWORK_ERROR");
  });

  it("calls the session-expired handler on 401 and still throws", async () => {
    fetchMock.mockResolvedValue(
      jsonRes(
        401,
        { type: "x", title: "x", status: 401, code: "UNAUTHORIZED" },
        "application/problem+json",
      ),
    );
    await expect(apiRequest("/users")).rejects.toBeInstanceOf(ApiError);
    expect(onExpired).toHaveBeenCalledTimes(1);
  });

  it("does not treat a failed login (401) as an expired session", async () => {
    fetchMock.mockResolvedValue(
      jsonRes(
        401,
        { type: "x", title: "x", status: 401, code: "INVALID_CREDENTIALS" },
        "application/problem+json",
      ),
    );
    await expect(apiRequest("/auth/login", { method: "POST", body: {} })).rejects.toBeInstanceOf(
      ApiError,
    );
    expect(onExpired).not.toHaveBeenCalled();
  });
});

describe("login redirect helpers", () => {
  it("keeps the original target page in ?next=", () => {
    expect(loginUrlFor("/users", "?page=2")).toBe("/login?next=%2Fusers%3Fpage%3D2");
  });
  it("does not add next for root or login itself", () => {
    expect(loginUrlFor("/", "")).toBe("/login");
    expect(loginUrlFor("/login", "")).toBe("/login");
  });
});

describe("errorMessage", () => {
  it.each([
    ["INVALID_CREDENTIALS", "用户名或密码错误"],
    ["USERNAME_TAKEN", "该用户名已被使用"],
    ["VERSION_CONFLICT", "数据已被他人修改，请刷新后重试"],
    ["LAST_ADMIN", "不能停用或降级最后一名管理员"],
    ["CANNOT_DISABLE_SELF", "不能停用自己"],
    ["CANNOT_REVOKE_CURRENT_SESSION", "不能下线当前会话"],
    ["CANNOT_REVOKE_OWN_SESSIONS", "不能下线自己的全部会话"],
    ["FORBIDDEN", "没有权限执行此操作"],
  ])("maps %s", (code, text) => {
    expect(errorMessage(code)).toBe(text);
  });
  it("falls back to a generic message", () => {
    expect(errorMessage("SOMETHING_NEW")).toBe("操作失败，请稍后重试");
    expect(errorMessage(undefined)).toBe("操作失败，请稍后重试");
  });
});
