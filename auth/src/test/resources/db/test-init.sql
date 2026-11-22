-- Test container init: the container user (auth_owner) owns the schema and runs Flyway;
-- tests run the application as this restricted role, like production.
CREATE ROLE auth_app LOGIN PASSWORD 'auth_app';
