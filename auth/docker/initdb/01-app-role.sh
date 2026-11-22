#!/bin/sh
# Creates the restricted application role. The schema owner (POSTGRES_USER) runs Flyway migrations;
# the migrations grant this role only what the service needs (audit table: INSERT and SELECT only).
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
  CREATE ROLE auth_app LOGIN PASSWORD '${AUTH_DB_APP_PASSWORD}';
EOSQL
