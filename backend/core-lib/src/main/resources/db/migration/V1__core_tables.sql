-- core_* tables: Tenant registry and Module Subscription (owned by the `tenant` package).

CREATE TABLE core_tenant (
    id          uuid        PRIMARY KEY,
    slug        text        NOT NULL UNIQUE CHECK (slug ~ '^[a-z][a-z0-9-]{2,31}$'),
    name        text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    status      text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at  timestamptz NOT NULL
);

CREATE TABLE core_module_subscription (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL REFERENCES core_tenant (id),
    module_code    text        NOT NULL CHECK (module_code IN ('platform')),
    status         text        NOT NULL DEFAULT 'SUBSCRIBED' CHECK (status IN ('SUBSCRIBED')),
    subscribed_at  timestamptz NOT NULL,
    UNIQUE (tenant_id, module_code)
);
