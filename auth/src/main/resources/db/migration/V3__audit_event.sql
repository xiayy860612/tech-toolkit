-- Append-only security audit log (FR-028, SC-005). detail never holds passwords or token values.
create table auth_audit_event (
    id              bigint generated always as identity primary key,
    occurred_at     timestamptz  not null default now(),
    type            varchar(48)  not null,
    actor           varchar(100),
    subject_user_id bigint,
    client_id       varchar(100),
    source_ip       varchar(64),
    result          varchar(16)  not null,
    detail          jsonb,
    constraint auth_audit_event_result_chk check (result in ('SUCCESS', 'FAILURE'))
);
create index auth_audit_event_occurred_ix on auth_audit_event (occurred_at);
create index auth_audit_event_subject_ix on auth_audit_event (subject_user_id);

-- the application role can only append and read: events cannot be rewritten or removed
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'auth_app') then
        grant select, insert on auth_audit_event to auth_app;
    end if;
end $$;
