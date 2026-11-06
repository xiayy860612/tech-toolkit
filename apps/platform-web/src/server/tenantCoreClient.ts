import type {
  ApiErrorCode,
  ChangePasswordRequest,
  LoginRequest,
  LoginResponse,
  Me,
  ModuleSubscription,
  Page,
  PageQuery,
  Tenant,
  User,
} from '@/lib/types';

/** The backend rejected the Opaque Token (missing, invalid, expired or revoked). */
export class UnauthorizedError extends Error {
  constructor() {
    super('Session is no longer valid');
    this.name = 'UnauthorizedError';
  }
}

/** The Session must change its initial password first (403 PASSWORD_CHANGE_REQUIRED). */
export class PasswordChangeRequiredError extends Error {
  constructor() {
    super('Password change required');
    this.name = 'PasswordChangeRequiredError';
  }
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: ApiErrorCode,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export interface TenantCoreClientOptions {
  baseUrl: string;
  fetch?: typeof fetch;
}

interface RequestOptions {
  method?: 'GET' | 'POST';
  token?: string;
  body?: unknown;
  query?: PageQuery;
  /** Login has no Session: a 401 there means bad credentials, not an expired Session. */
  isLogin?: boolean;
}

async function readError(response: Response): Promise<{ code?: string; message: string }> {
  try {
    const body = (await response.json()) as { code?: string; message?: string };
    return { code: body.code, message: body.message ?? response.statusText };
  } catch {
    return { message: response.statusText };
  }
}

export function createTenantCoreClient({ baseUrl, fetch: fetchImpl }: TenantCoreClientOptions) {
  const root = baseUrl.replace(/\/+$/, '');

  async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const { method = 'GET', token, body, query, isLogin = false } = options;
    const search = query ? `?page=${query.page}&size=${query.size}` : '';
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    // The Opaque Token travels only in X-Session-Id, never in Authorization.
    if (token) headers['X-Session-Id'] = token;

    const doFetch = fetchImpl ?? fetch;
    const response = await doFetch(`${root}${path}${search}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      cache: 'no-store',
    });

    if (response.ok) {
      return (response.status === 204 ? undefined : await response.json()) as T;
    }

    const error = await readError(response);
    if (response.status === 401) {
      if (isLogin || error.code === 'INVALID_CREDENTIALS') {
        throw new ApiError(401, 'INVALID_CREDENTIALS', error.message);
      }
      throw new UnauthorizedError();
    }
    if (response.status === 403 && error.code === 'PASSWORD_CHANGE_REQUIRED') {
      throw new PasswordChangeRequiredError();
    }
    throw new ApiError(response.status, (error.code as ApiErrorCode) ?? 'UNKNOWN', error.message);
  }

  return {
    login: (credentials: LoginRequest) =>
      request<LoginResponse>('/v1/auth/login', {
        method: 'POST',
        body: credentials,
        isLogin: true,
      }),
    logout: (token: string) => request<void>('/v1/auth/logout', { method: 'POST', token }),
    changePassword: (token: string, passwords: ChangePasswordRequest) =>
      request<LoginResponse>('/v1/auth/change-password', {
        method: 'POST',
        token,
        body: passwords,
      }),
    me: (token: string) => request<Me>('/v1/auth/me', { token }),
    listTenants: (token: string, query: PageQuery) =>
      request<Page<Tenant>>('/v1/tenants', { token, query }),
    listModuleSubscriptions: (token: string, query: PageQuery) =>
      request<Page<ModuleSubscription>>('/v1/module-subscriptions', { token, query }),
    listUsers: (token: string, query: PageQuery) =>
      request<Page<User>>('/v1/users', { token, query }),
  };
}

export type TenantCoreClient = ReturnType<typeof createTenantCoreClient>;
