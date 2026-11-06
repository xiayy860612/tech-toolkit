-- Test-only: mirrors deploy/initdb/01-roles.sh. The password below exists only for throw-away Testcontainers databases.
CREATE ROLE tenant_core_app LOGIN PASSWORD 'test-app';
GRANT CONNECT ON DATABASE test TO tenant_core_app;
GRANT USAGE ON SCHEMA public TO tenant_core_app;
