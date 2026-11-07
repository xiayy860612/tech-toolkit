#!/bin/sh
# Creates the application role used at runtime. The owner role (POSTGRES_USER) runs Flyway migrations.
# The password comes from the environment; nothing secret is stored in the repository.
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<EOSQL
CREATE ROLE tenant_core_app LOGIN PASSWORD '${TENANT_CORE_APP_PASSWORD}';
GRANT CONNECT ON DATABASE "$POSTGRES_DB" TO tenant_core_app;
GRANT USAGE ON SCHEMA public TO tenant_core_app;
EOSQL
