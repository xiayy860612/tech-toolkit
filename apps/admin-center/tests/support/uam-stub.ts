/**
 * UAM 桩（msw）：按 `contracts/uam/openapi.yaml` 校验每个请求与响应。
 *
 * - UAM 不认证调用方：请求带了 `Authorization` 头即算违约（记入 `violations`），Admin Center 不应再发它。
 * - 写操作缺操作人请求头 → 桩回 400 `operator_required`（与真实 UAM 一致）。
 * - 请求违反契约时桩同样模拟真实 UAM 的拒绝：请求体字段不符 → 422 `validation_failed`；读不懂的请求
 *   （请求头 / 路径 / 查询参数类型不符、无请求体等）→ 400 `bad_request`。
 * - 同时，以下违反契约的请求（路径参数、查询参数、请求头格式、请求体）与响应（状态码未声明、响应体不符合 schema）
 *   记入 `violations`，测试在 afterEach 里断言它为空。
 * 只能用在 `@jest-environment node` 的测试里。
 */
import fs from "node:fs";
import path from "node:path";
import Ajv2020 from "ajv/dist/2020";
import addFormats from "ajv-formats";
import yaml from "js-yaml";
import { delay, http, HttpResponse } from "msw";
import { setupServer } from "msw/node";

export const UAM_STUB_BASE_URL = "http://uam.test";

type Json = Record<string, unknown>;
type Method = "get" | "post" | "delete";

export interface StubRequest {
  operationId: string;
  method: string;
  path: string;
  params: Record<string, string>;
  query: URLSearchParams;
  headers: Headers;
  body: unknown;
}

export type StubReply =
  | { status: number; body?: unknown; headers?: Record<string, string>; delayMs?: number }
  | { networkError: true };

export type Responder = StubReply | ((req: StubRequest) => StubReply);

interface Operation {
  operationId: string;
  method: Method;
  pathTemplate: string;
  spec: Json;
  parameters: Json[];
}

const SPEC_PATH = path.resolve(__dirname, "../../../../contracts/uam/openapi.yaml");

function loadSpec(): Json {
  return yaml.load(fs.readFileSync(SPEC_PATH, "utf8")) as Json;
}

function problem(status: number, code: string, title: string): StubReply {
  return { status, body: { type: "about:blank", title, status, code } };
}

export function createUamStub() {
  const spec = loadSpec();
  const ajv = new Ajv2020({ strict: false, allErrors: true });
  addFormats(ajv);
  ajv.addSchema(spec, "uam");

  const calls: StubRequest[] = [];
  const violations: string[] = [];
  const responders = new Map<string, Responder>();

  const deref = <T = Json>(node: unknown): T => {
    let cur = node as Json;
    while (cur && typeof cur.$ref === "string") {
      const ref = cur.$ref as string;
      cur = ref
        .replace(/^#\//, "")
        .split("/")
        .reduce<unknown>((acc, key) => (acc as Json)[key], spec) as Json;
    }
    return cur as T;
  };

  const rewriteRefs = (node: unknown): unknown => {
    if (Array.isArray(node)) return node.map(rewriteRefs);
    if (node && typeof node === "object") {
      return Object.fromEntries(
        Object.entries(node as Json).map(([k, v]) => [
          k,
          k === "$ref" && typeof v === "string" ? v.replace(/^#\//, "uam#/") : rewriteRefs(v),
        ]),
      );
    }
    return node;
  };

  const validators = new Map<string, ReturnType<typeof ajv.compile>>();
  const validate = (schema: unknown, data: unknown): string | null => {
    const key = JSON.stringify(schema);
    let fn = validators.get(key);
    if (!fn) {
      fn = ajv.compile(rewriteRefs(schema) as object);
      validators.set(key, fn);
    }
    return fn(data) ? null : ajv.errorsText(fn.errors);
  };

  // ---- 收集全部操作 ----
  const operations: Operation[] = [];
  for (const [pathTemplate, item] of Object.entries(spec.paths as Json)) {
    for (const method of ["get", "post", "delete"] as const) {
      const op = (item as Json)[method] as Json | undefined;
      if (!op) continue;
      const shared = ((item as Json).parameters as Json[] | undefined) ?? [];
      const own = (op.parameters as Json[] | undefined) ?? [];
      operations.push({
        operationId: op.operationId as string,
        method,
        pathTemplate,
        spec: op,
        parameters: [...shared, ...own].map((p) => deref(p)),
      });
    }
  }

  const checkRequest = (op: Operation, req: StubRequest): StubReply | null => {
    const bad = (msg: string, kind: "unparsable" | "invalid" = "unparsable"): StubReply => {
      violations.push(`[${op.operationId}] 请求违反契约：${msg}`);
      return kind === "invalid"
        ? problem(422, "validation_failed", "stub: request violates contract")
        : problem(400, "bad_request", "stub: request violates contract");
    };

    // UAM 不认证调用方：不应带 Authorization
    if (req.headers.has("authorization")) violations.push(`[${op.operationId}] 请求不应带 Authorization 头（UAM 不认证调用方）`);

    // 操作人请求头：缺失按真实 UAM 回 400 operator_required；格式错误算违约
    for (const p of op.parameters) {
      if (p.in !== "header") continue;
      const name = p.name as string;
      const value = req.headers.get(name);
      if (value === null) {
        if (p.required) {
          if (name.startsWith("X-Operator-")) return problem(400, "operator_required", "Operator required");
          return bad(`缺少必填请求头 ${name}`);
        }
        continue;
      }
      const err = validate(p.schema, value);
      if (err) return bad(`请求头 ${name}：${err}`);
    }

    // 路径参数
    for (const p of op.parameters.filter((x) => x.in === "path")) {
      const err = validate(p.schema, req.params[p.name as string]);
      if (err) return bad(`路径参数 ${p.name}：${err}`);
    }

    // 查询参数
    const declared = new Map(op.parameters.filter((x) => x.in === "query").map((x) => [x.name as string, x]));
    for (const [name, value] of req.query.entries()) {
      const p = declared.get(name);
      if (!p) return bad(`未声明的查询参数 ${name}`);
      const schema = p.schema as Json;
      const data = schema.type === "integer" ? Number(value) : value;
      const err = validate(schema, data);
      // 类型不符（如 page=abc）读不懂 → 400；类型对但越界（如 page=0）是规则问题 → 422
      if (err) return bad(`查询参数 ${name}：${err}`, Number.isNaN(data) ? "unparsable" : "invalid");
    }

    // 请求体
    const bodySpec = op.spec.requestBody as Json | undefined;
    if (bodySpec) {
      const schema = ((bodySpec.content as Json)["application/json"] as Json).schema;
      if (req.body === undefined) {
        if (bodySpec.required) return bad("缺少请求体");
      } else {
        const err = validate(schema, req.body);
        if (err) return bad(`请求体：${err}`, "invalid");
      }
    } else if (req.body !== undefined) {
      return bad("该接口没有请求体");
    }
    return null;
  };

  const checkResponse = (op: Operation, reply: Extract<StubReply, { status: number }>): void => {
    const responses = op.spec.responses as Json;
    const declared = responses[String(reply.status)] ?? responses.default;
    const tag = `[${op.operationId}] 响应违反契约（${reply.status}）：`;
    if (!declared) {
      violations.push(`${tag}状态码未在契约中声明`);
      return;
    }
    const def = deref(declared);
    const content = def.content as Json | undefined;
    if (!content) {
      if (reply.body !== undefined) violations.push(`${tag}契约声明无响应体`);
    } else {
      const mediaType = reply.status >= 400 ? "application/problem+json" : "application/json";
      const media = content[mediaType] as Json | undefined;
      if (!media) violations.push(`${tag}契约未声明 ${mediaType}`);
      else if (reply.body === undefined) violations.push(`${tag}缺少响应体`);
      else {
        const err = validate(media.schema, reply.body);
        if (err) violations.push(`${tag}${err}`);
      }
    }
    for (const header of Object.keys((def.headers as Json | undefined) ?? {})) {
      const present = Object.keys(reply.headers ?? {}).some((h) => h.toLowerCase() === header.toLowerCase());
      if (!present) violations.push(`${tag}缺少响应头 ${header}`);
    }
  };

  const handlers = operations.map((op) =>
    http[op.method](`${UAM_STUB_BASE_URL}${op.pathTemplate.replace(/\{(\w+)\}/g, ":$1")}`, async ({ request, params }) => {
      const url = new URL(request.url);
      const text = await request.text();
      const req: StubRequest = {
        operationId: op.operationId,
        method: request.method,
        path: url.pathname,
        params: Object.fromEntries(Object.entries(params).map(([k, v]) => [k, String(v)])),
        query: url.searchParams,
        headers: request.headers,
        body: text ? JSON.parse(text) : undefined,
      };
      calls.push(req);

      let reply = checkRequest(op, req);
      // 桩自己合成的拒绝（401 / 400）不再对照契约检查响应；只检查测试配置的响应
      const synthetic = reply !== null;
      if (!reply) {
        const responder = responders.get(op.operationId);
        if (!responder) {
          violations.push(`[${op.operationId}] 桩里没有配置响应`);
          reply = problem(500, "stub_unconfigured", "stub: no responder");
        } else {
          reply = typeof responder === "function" ? responder(req) : responder;
        }
      }
      if ("networkError" in reply) return HttpResponse.error();
      if (!synthetic) checkResponse(op, reply);
      if (reply.delayMs) await delay(reply.delayMs);
      const contentType = reply.status >= 400 ? "application/problem+json" : "application/json";
      return new HttpResponse(reply.body === undefined ? null : JSON.stringify(reply.body), {
        status: reply.status,
        headers: reply.body === undefined ? reply.headers : { "content-type": contentType, ...reply.headers },
      });
    }),
  );

  const server = setupServer(...handlers);

  return {
    server,
    calls,
    violations,
    operationIds: operations.map((o) => o.operationId),
    /** 为某个操作配置响应（固定或函数）。 */
    respond(operationId: string, responder: Responder) {
      if (!operations.some((o) => o.operationId === operationId)) throw new Error(`契约里没有操作 ${operationId}`);
      responders.set(operationId, responder);
    },
    listen() {
      server.listen({ onUnhandledRequest: "error" });
    },
    reset() {
      calls.length = 0;
      violations.length = 0;
      responders.clear();
    },
    close() {
      server.close();
    },
  };
}

export type UamStub = ReturnType<typeof createUamStub>;

// ---- 常用夹具（均符合契约） ----
export const FIXTURE_IDS = {
  userId: "11111111-1111-4111-8111-111111111111",
  accountId: "22222222-2222-4222-8222-222222222222",
  sessionRef: "33333333-3333-4333-8333-333333333333",
};

export function verifyResponseBody(overrides: Json = {}) {
  return {
    user: { id: FIXTURE_IDS.userId, name: "张三" },
    account: { id: FIXTURE_IDS.accountId, loginName: "zhangsan", kind: "PASSWORD" },
    sessionRef: FIXTURE_IDS.sessionRef,
    expiresAt: "2026-10-24T12:00:00Z",
    ...overrides,
  };
}

/** `login` 成功响应（会话标识只在这里出现，给 Admin Center 写 cookie）。 */
export function loginResponseBody(overrides: Json = {}) {
  return {
    sessionId: "stub-session-id-not-a-secret",
    maxAgeSeconds: 43200,
    ...verifyResponseBody(),
    ...overrides,
  };
}

export function problemBody(status: number, code: string, extra: Json = {}) {
  return { type: "about:blank", title: code, status, code, ...extra };
}
