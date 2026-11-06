// DTOs mirroring specs/001-tenant-core-mvp/contracts/tenant-core-api.yaml.

export interface LoginRequest {
  tenantSlug: string;
  username: string;
  password: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

/** Response of login / change-password. Server-side only: `token` is the Opaque Token. */
export interface LoginResponse {
  token: string;
  expiresAt: string;
  mustChangePassword: boolean;
}

export interface Me {
  tenantUserId: string;
  displayName: string;
  accountUsername: string;
  tenantSlug: string;
  mustChangePassword: boolean;
}

export interface Tenant {
  slug: string;
  name: string;
  status: 'ACTIVE' | 'SUSPENDED';
  createdAt: string;
}

export interface ModuleSubscription {
  moduleCode: string;
  tenantSlug: string;
  status: 'SUBSCRIBED';
  subscribedAt: string;
}

export interface User {
  tenantUserId: string;
  displayName: string;
  accountUsernames: string[];
  tenantSlug: string;
  status: 'ACTIVE' | 'DISABLED';
  createdAt: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface PageQuery {
  page: number;
  size: number;
}

export type ApiErrorCode =
  | 'INVALID_CREDENTIALS'
  | 'MODULE_NOT_SUBSCRIBED'
  | 'UNAUTHORIZED'
  | 'PASSWORD_CHANGE_REQUIRED'
  | 'PASSWORD_POLICY'
  | 'VALIDATION'
  | 'UNKNOWN';
