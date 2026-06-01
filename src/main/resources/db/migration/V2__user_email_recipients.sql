alter table truconf_outbox
  add column recipient_email text null;

alter table truconf_outbox
  drop constraint truconf_outbox_recipient_kind_check;

alter table truconf_outbox
  add constraint truconf_outbox_recipient_kind_check check (
    recipient_kind in ('CHAT', 'USER', 'USER_EMAIL')
  );

alter table truconf_outbox
  add constraint truconf_outbox_recipient_email_check check (
    recipient_kind <> 'USER_EMAIL' or recipient_email is not null
  );

create table truconf_user_email_cache (
  email text primary key,
  trueconf_id text not null,
  display_name text null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  last_used_at timestamptz not null default now()
);

create index truconf_user_email_cache_last_used_at_idx
  on truconf_user_email_cache (last_used_at);

create trigger truconf_user_email_cache_set_updated_at
before update on truconf_user_email_cache
for each row
execute function truconf_set_updated_at();
