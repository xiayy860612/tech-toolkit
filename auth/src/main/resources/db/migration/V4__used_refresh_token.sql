-- Refresh tokens that have been exchanged for a new one. The authorization row only keeps the
-- newest refresh token, so an old one simply vanishes from it; remembering its hash is what lets
-- the server tell "used before" (reuse: end the whole chain, RFC 9700) from "never existed".
-- Only SHA-256 hashes are stored, never the token itself. The primary key also decides a race:
-- of two callers presenting the same token, only one can insert its hash.
create table auth_used_refresh_token (
    token_hash       char(64)     primary key,
    authorization_id varchar(100) not null,
    used_at          timestamptz  not null default now()
);
create index auth_used_refresh_token_authorization_ix on auth_used_refresh_token (authorization_id);

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'auth_app') then
        grant select, insert, delete on auth_used_refresh_token to auth_app;
    end if;
end $$;
