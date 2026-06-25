create table truconf_managed_chat (
  id bigserial primary key,
  owner_system text not null,
  owner_kind text not null,
  owner_key text not null,
  chat_id text not null,
  title text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  last_sync_at timestamptz null,
  constraint truconf_managed_chat_owner_system_check check (owner_system <> ''),
  constraint truconf_managed_chat_owner_kind_check check (owner_kind <> ''),
  constraint truconf_managed_chat_owner_key_check check (owner_key <> ''),
  constraint truconf_managed_chat_chat_id_check check (chat_id <> ''),
  constraint truconf_managed_chat_title_check check (title <> '')
);

create unique index truconf_managed_chat_owner_uq
  on truconf_managed_chat (owner_system, owner_kind, owner_key);

create index truconf_managed_chat_chat_id_idx
  on truconf_managed_chat (chat_id);

create trigger truconf_managed_chat_set_updated_at
before update on truconf_managed_chat
for each row
execute function truconf_set_updated_at();
