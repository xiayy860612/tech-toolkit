-- UAM 初始结构：用户、账号、凭据（见 specs/002-admin-center-uam/data-model.md）
-- app_user.updated_at 与 credential.updated_at 没有数据库默认值，由仓储在每次插入与更新时显式设置。

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 用户：1 个用户可有多个账号
CREATE TABLE app_user (
    id          uuid         PRIMARY KEY,
    name        varchar(64)  NOT NULL,
    status      text         NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL,
    -- 去首尾空白后 1–64 字符；不要求唯一
    CONSTRAINT app_user_name_length CHECK (char_length(btrim(name)) BETWEEN 1 AND 64),
    CONSTRAINT app_user_status_valid CHECK (status IN ('ACTIVE', 'DISABLED'))
);

-- 名称包含匹配（pg_trgm）
CREATE INDEX idx_app_user_name_trgm ON app_user USING gin (name gin_trgm_ops);
-- 统计正常用户数
CREATE INDEX idx_app_user_status ON app_user (status);

-- 账号：本期仅「账号名 / 密码」；DELETED 为预留（名称仍被占用），本期没有任何接口会写入
CREATE TABLE account (
    id               uuid         PRIMARY KEY,
    user_id          uuid         NOT NULL REFERENCES app_user (id),
    kind             text         NOT NULL,
    login_name       varchar(32)  NOT NULL,
    login_name_norm  varchar(32)  NOT NULL,
    status           text         NOT NULL,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    -- 日后扩展账号种类时放宽
    CONSTRAINT account_kind_valid CHECK (kind IN ('PASSWORD')),
    CONSTRAINT account_status_valid CHECK (status IN ('ACTIVE', 'DISABLED', 'DELETED')),
    -- login_name 创建后不可改；规范化名由仓储写入，库里再守一道
    CONSTRAINT account_login_name_norm_lower CHECK (login_name_norm = lower(login_name))
);

CREATE INDEX idx_account_user_id ON account (user_id);
-- 全局唯一（含已停用用户的账号），大小写不敏感
CREATE UNIQUE INDEX uq_account_login_name_norm ON account (login_name_norm);
-- 按账号名搜索
CREATE INDEX idx_account_login_name_norm_trgm ON account USING gin (login_name_norm gin_trgm_ops);

-- 凭据：仅「账号名 / 密码」账号；明文永不落库、不进日志
CREATE TABLE credential (
    account_id     uuid         PRIMARY KEY REFERENCES account (id),
    -- Argon2id，DelegatingPasswordEncoder 格式，可升级
    password_hash  text         NOT NULL,
    updated_at     timestamptz  NOT NULL
);
