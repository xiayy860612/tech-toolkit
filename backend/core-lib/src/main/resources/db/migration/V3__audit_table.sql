-- audit_event: append-only trail of Bootstrap CLI operations and login events.

CREATE TABLE audit_event (
    id           uuid        PRIMARY KEY,
    occurred_at  timestamptz NOT NULL,
    actor        text        NOT NULL,
    action       text        NOT NULL,
    tenant_id    uuid,
    target       text        NOT NULL,
    outcome      text        NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'NOOP')),
    trace_id     text
);
CREATE INDEX audit_event_occurred_idx ON audit_event (occurred_at);

-- Runtime role (created outside migrations, see deploy/initdb/01-roles.sh): full access to the
-- business tables, append-only access to audit_event.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tenant_core_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO tenant_core_app;
        REVOKE UPDATE, DELETE, TRUNCATE ON audit_event FROM tenant_core_app;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public
            GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tenant_core_app;
    END IF;
END
$$;
