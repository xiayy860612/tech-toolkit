-- User accounts (spec FR-015, FR-018, FR-019, FR-031: single user pool).
create table auth_user (
    id                   bigint generated always as identity primary key,
    -- stored lower-case; 3-64 chars of [a-z0-9._-]
    username             varchar(64)  not null,
    password_hash        varchar(255) not null,
    status               varchar(16)  not null default 'ENABLED',
    is_admin             boolean      not null default false,
    must_change_password boolean      not null default false,
    failed_attempts      int          not null default 0,
    locked_until         timestamptz,
    password_changed_at  timestamptz  not null default now(),
    created_at           timestamptz  not null default now(),
    updated_at           timestamptz  not null default now(),
    constraint auth_user_username_uk unique (username),
    constraint auth_user_username_format check (username ~ '^[a-z0-9._-]{3,64}$'),
    constraint auth_user_status_chk check (status in ('ENABLED', 'DISABLED'))
);

-- the application role never deletes users (accounts are disabled, not removed)
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'auth_app') then
        grant select, insert, update on auth_user to auth_app;
    end if;
end $$;
