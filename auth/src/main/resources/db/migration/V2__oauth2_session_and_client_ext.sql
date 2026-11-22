-- Spring Authorization Server standard tables (PostgreSQL variant: blob -> text, timestamp -> timestamptz).
create table oauth2_registered_client (
    id                            varchar(100)  not null,
    client_id                     varchar(100)  not null,
    client_id_issued_at           timestamptz   not null default current_timestamp,
    client_secret                 varchar(200)  default null,
    client_secret_expires_at      timestamptz   default null,
    client_name                   varchar(200)  not null,
    client_authentication_methods varchar(1000) not null,
    authorization_grant_types     varchar(1000) not null,
    redirect_uris                 varchar(1000) default null,
    post_logout_redirect_uris     varchar(1000) default null,
    scopes                        varchar(1000) not null,
    client_settings               varchar(2000) not null,
    token_settings                varchar(2000) not null,
    primary key (id),
    constraint oauth2_registered_client_client_id_uk unique (client_id)
);

create table oauth2_authorization (
    id                            varchar(100)  not null,
    registered_client_id          varchar(100)  not null,
    principal_name                varchar(200)  not null,
    authorization_grant_type      varchar(100)  not null,
    authorized_scopes             varchar(1000) default null,
    attributes                    text          default null,
    state                         varchar(500)  default null,
    authorization_code_value      text          default null,
    authorization_code_issued_at  timestamptz   default null,
    authorization_code_expires_at timestamptz   default null,
    authorization_code_metadata   text          default null,
    access_token_value            text          default null,
    access_token_issued_at        timestamptz   default null,
    access_token_expires_at       timestamptz   default null,
    access_token_metadata         text          default null,
    access_token_type             varchar(100)  default null,
    access_token_scopes           varchar(1000) default null,
    oidc_id_token_value           text          default null,
    oidc_id_token_issued_at       timestamptz   default null,
    oidc_id_token_expires_at      timestamptz   default null,
    oidc_id_token_metadata        text          default null,
    refresh_token_value           text          default null,
    refresh_token_issued_at       timestamptz   default null,
    refresh_token_expires_at      timestamptz   default null,
    refresh_token_metadata        text          default null,
    user_code_value               text          default null,
    user_code_issued_at           timestamptz   default null,
    user_code_expires_at          timestamptz   default null,
    user_code_metadata            text          default null,
    device_code_value             text          default null,
    device_code_issued_at         timestamptz   default null,
    device_code_expires_at        timestamptz   default null,
    device_code_metadata          text          default null,
    -- not part of the framework schema: the SSO session (sid) this authorization belongs to,
    -- so that logout and revocation can delete exactly one session's authorizations
    auth_session_id               varchar(36)   default null,
    primary key (id)
);
create index oauth2_authorization_principal_ix on oauth2_authorization (principal_name);
create index oauth2_authorization_client_ix on oauth2_authorization (registered_client_id);
create index oauth2_authorization_session_ix on oauth2_authorization (auth_session_id);

-- Spring Session JDBC standard tables (SSO sessions)
create table spring_session (
    primary_id            char(36) not null,
    session_id            char(36) not null,
    creation_time         bigint   not null,
    last_access_time      bigint   not null,
    max_inactive_interval int      not null,
    expiry_time           bigint   not null,
    principal_name        varchar(100),
    constraint spring_session_pk primary key (primary_id)
);
create unique index spring_session_ix1 on spring_session (session_id);
create index spring_session_ix2 on spring_session (expiry_time);
create index spring_session_ix3 on spring_session (principal_name);

create table spring_session_attributes (
    session_primary_id char(36)     not null,
    attribute_name     varchar(200) not null,
    attribute_bytes    bytea        not null,
    constraint spring_session_attributes_pk primary key (session_primary_id, attribute_name),
    constraint spring_session_attributes_fk foreign key (session_primary_id)
        references spring_session (primary_id) on delete cascade
);

-- auth's own tables (data-model.md)
create table auth_client_ext (
    client_id                  varchar(100)  not null primary key,
    enabled                    boolean       not null default true,
    backchannel_logout_uri     varchar(2000),
    post_logout_redirect_uris  text,
    access_token_ttl_seconds   int,
    refresh_token_ttl_seconds  int
);

create table auth_sso_client (
    session_id    varchar(36)  not null,
    client_id     varchar(100) not null,
    first_seen_at timestamptz  not null default now(),
    primary key (session_id, client_id)
);

create table auth_logout_delivery (
    id              bigint generated always as identity primary key,
    client_id       varchar(100) not null,
    session_id      varchar(36)  not null,
    attempts        int          not null default 0,
    next_attempt_at timestamptz  not null default now(),
    status          varchar(16)  not null default 'PENDING',
    constraint auth_logout_delivery_status_chk check (status in ('PENDING', 'DELIVERED', 'GAVE_UP'))
);
create index auth_logout_delivery_due_ix on auth_logout_delivery (status, next_attempt_at);

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'auth_app') then
        grant select, insert, update, delete on
            oauth2_registered_client, oauth2_authorization, spring_session, spring_session_attributes,
            auth_client_ext, auth_sso_client, auth_logout_delivery
        to auth_app;
    end if;
end $$;
