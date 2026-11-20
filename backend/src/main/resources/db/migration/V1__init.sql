-- Tenant Admin Center（UAM）初始结构。
-- 用户名密码内置于 app_user（每个 User 必然有）；日后手机号、微信、OAuth2.0 等 Account 各用自己的表，
-- 以 (tenant_id, user_id) 关联 app_user 并各自 UNIQUE (tenant_id, user_id)（每种类型至多一个）。
-- 本期不涉及租户管理：没有 tenant 表，租户仅由各表的 tenant_id（bigint，本期恒为 0）标识。
-- 所有表启用 Row-Level Security；应用以非属主角色 tenantcore_app 连接，策略读取事务内的 app.tenant_id。

-- 应用角色：由 DBA 预先创建并授予 LOGIN/密码；此处仅在缺失时创建无登录权限的占位，保证授权语句可执行。
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'tenantcore_app') THEN
    CREATE ROLE tenantcore_app NOLOGIN;
  END IF;
END
$$;

CREATE TABLE app_user (
  id                   bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id            bigint       NOT NULL,
  display_name         varchar(50)  NOT NULL CHECK (char_length(btrim(display_name)) > 0),
  role                 varchar(20)  NOT NULL CHECK (role IN ('TENANT_ADMIN', 'MEMBER')),
  status               varchar(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
  username             varchar(64)  NOT NULL,
  username_normalized  varchar(64)  NOT NULL,
  password_hash        text         NOT NULL,
  must_change_password boolean      NOT NULL,
  password_changed_at  timestamptz,
  failed_attempts      integer      NOT NULL DEFAULT 0,
  locked_until         timestamptz,
  last_login_at        timestamptz,
  version              bigint       NOT NULL DEFAULT 0,
  created_at           timestamptz  NOT NULL DEFAULT now(),
  updated_at           timestamptz  NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, username_normalized)
);
CREATE INDEX app_user_list_idx ON app_user (tenant_id, status, display_name);
CREATE INDEX app_user_name_idx ON app_user (tenant_id, lower(display_name));

-- 会话由本服务统一维护；令牌明文不落库，只存 SHA-256。会话 id 是对外公开的标识，保持 UUID（不可枚举）。
-- 撤销后保留至清理任务清除，因此不对 app_user 建外键。
CREATE TABLE session (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id           bigint       NOT NULL,
  token_hash          bytea        NOT NULL UNIQUE,
  user_id             bigint       NOT NULL,
  login_type          varchar(30)  NOT NULL DEFAULT 'USERNAME_PASSWORD',
  created_at          timestamptz  NOT NULL DEFAULT now(),
  last_seen_at        timestamptz  NOT NULL,
  absolute_expires_at timestamptz  NOT NULL,
  revoked_at          timestamptz,
  revoke_reason       varchar(30)  CHECK (revoke_reason IN ('LOGOUT', 'USER_DISABLED',
                        'PASSWORD_RESET', 'PASSWORD_CHANGED', 'ADMIN_REVOKED')),
  ip                  inet,
  user_agent          varchar(512),
  device              varchar(100) NOT NULL DEFAULT ''
);
CREATE INDEX session_active_idx ON session (tenant_id, last_seen_at DESC) WHERE revoked_at IS NULL;
CREATE INDEX session_user_idx ON session (user_id) WHERE revoked_at IS NULL;

CREATE TABLE audit_log (
  id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint      NOT NULL,
  occurred_at   timestamptz NOT NULL DEFAULT now(),
  actor_user_id bigint,
  action        varchar(40) NOT NULL,
  target_type   varchar(10) CHECK (target_type IN ('USER')),
  target_id     bigint,
  result        varchar(10) NOT NULL CHECK (result IN ('SUCCESS', 'FAILURE')),
  reason        varchar(100),
  ip            inet,
  detail        jsonb
);
CREATE INDEX audit_log_time_idx ON audit_log (tenant_id, occurred_at DESC);

-- 审计日志只追加：触发器对任何角色（含属主）拒绝更新与删除。
CREATE FUNCTION audit_log_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'audit_log is append-only';
END
$$;
CREATE TRIGGER audit_log_no_change BEFORE UPDATE OR DELETE ON audit_log
  FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();

-- Row-Level Security：未设置 app.tenant_id 时策略为 NULL，不返回也不允许写入任何行。
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['app_user', 'session', 'audit_log'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
      || ' WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)', t);
  END LOOP;
END
$$;

GRANT SELECT, INSERT, UPDATE, DELETE ON app_user, session TO tenantcore_app;
GRANT SELECT, INSERT ON audit_log TO tenantcore_app;

-- 跨租户的会话清理（定时任务无租户上下文）：以属主身份执行，绕过 RLS，只清除过期/已撤销超过保留期的行。
CREATE FUNCTION purge_sessions(retention interval, idle interval) RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE n integer;
BEGIN
  DELETE FROM session
   WHERE (revoked_at IS NOT NULL AND revoked_at < now() - retention)
      OR absolute_expires_at < now() - retention
      OR last_seen_at < now() - idle - retention;
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END
$$;
REVOKE ALL ON FUNCTION purge_sessions(interval, interval) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION purge_sessions(interval, interval) TO tenantcore_app;
