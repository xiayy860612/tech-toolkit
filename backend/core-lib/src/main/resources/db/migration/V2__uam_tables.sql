-- uam_* tables: User, Account, Login Method, password Credential and Opaque Token (owned by the `uam` package).
-- No foreign keys to core_* tables: tenant_id is a plain column checked by the service layer,
-- so the uam tables can later be moved to their own database by prefix.

CREATE TABLE uam_tenant_login_method (
    tenant_id    uuid        NOT NULL,
    method_type  text        NOT NULL CHECK (method_type IN ('PASSWORD')),
    enabled_at   timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, method_type)
);

CREATE TABLE uam_user (
    id              uuid        PRIMARY KEY,
    tenant_user_id  text        NOT NULL UNIQUE,
    tenant_id       uuid        NOT NULL,
    display_name    text        NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 100),
    status          text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at      timestamptz NOT NULL
);
CREATE INDEX uam_user_tenant_idx ON uam_user (tenant_id, created_at DESC);

CREATE TABLE uam_account (
    id          uuid        PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES uam_user (id),
    tenant_id   uuid        NOT NULL,
    type        text        NOT NULL CHECK (type IN ('PASSWORD')),
    provider    text        NOT NULL DEFAULT '',
    subject     text        NOT NULL CHECK (subject ~ '^[a-z0-9][a-z0-9._-]{2,63}$'),
    created_at  timestamptz NOT NULL,
    UNIQUE (tenant_id, type, provider, subject)
);
-- MVP: at most one PASSWORD Account per User
CREATE UNIQUE INDEX uam_account_one_password_per_user ON uam_account (user_id) WHERE type = 'PASSWORD';

CREATE TABLE uam_credential_password (
    account_id            uuid        PRIMARY KEY REFERENCES uam_account (id),
    password_hash         text        NOT NULL,
    must_change_password  boolean     NOT NULL DEFAULT true,
    updated_at            timestamptz NOT NULL,
    failed_count          integer     NOT NULL DEFAULT 0,
    locked_until          timestamptz
);

CREATE TABLE uam_opaque_token (
    token_hash    bytea       PRIMARY KEY,
    sid           text        NOT NULL UNIQUE,
    account_id    uuid        NOT NULL REFERENCES uam_account (id),
    user_id       uuid        NOT NULL REFERENCES uam_user (id),
    tenant_id     uuid        NOT NULL,
    created_at    timestamptz NOT NULL,
    last_seen_at  timestamptz NOT NULL,
    expires_at    timestamptz NOT NULL,
    revoked_at    timestamptz
);
