create table truconf_outbox (
  id bigserial primary key,
  external_id text null,
  operation text not null,
  recipient_kind text not null,
  chat_id text null,
  user_id text null,
  target_message_id text null,
  reply_message_id text null,
  payload_json jsonb not null default '{}'::jsonb,
  status text not null default 'NEW',
  attempt_count int not null default 0,
  max_attempts int not null default 10,
  next_attempt_at timestamptz not null default now(),
  locked_by text null,
  locked_until timestamptz null,
  trueconf_chat_id text null,
  trueconf_message_id text null,
  trueconf_file_id text null,
  trueconf_timestamp bigint null,
  last_error_code text null,
  last_error_message text null,
  last_error_retryable boolean null,
  last_response_json jsonb null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  sent_at timestamptz null,
  failed_at timestamptz null,
  constraint truconf_outbox_operation_check check (
    operation in (
      'SEND_MESSAGE',
      'SEND_FILE',
      'SEND_SURVEY',
      'EDIT_MESSAGE',
      'EDIT_SURVEY',
      'REMOVE_MESSAGE',
      'FORWARD_MESSAGE'
    )
  ),
  constraint truconf_outbox_recipient_kind_check check (
    recipient_kind in ('CHAT', 'USER')
  ),
  constraint truconf_outbox_status_check check (
    status in ('NEW', 'PROCESSING', 'RETRY_WAIT', 'SENT', 'FAILED')
  ),
  constraint truconf_outbox_recipient_chat_check check (
    recipient_kind <> 'CHAT' or chat_id is not null
  ),
  constraint truconf_outbox_recipient_user_check check (
    recipient_kind <> 'USER' or user_id is not null
  ),
  constraint truconf_outbox_attempt_count_check check (attempt_count >= 0),
  constraint truconf_outbox_max_attempts_check check (max_attempts > 0),
  constraint truconf_outbox_payload_json_object_check check (
    jsonb_typeof(payload_json) = 'object'
  )
);

create unique index truconf_outbox_external_id_uq
  on truconf_outbox (external_id)
  where external_id is not null;

create index truconf_outbox_claim_ready_idx
  on truconf_outbox (status, next_attempt_at, id)
  where status in ('NEW', 'RETRY_WAIT');

create index truconf_outbox_stale_locks_idx
  on truconf_outbox (locked_until)
  where status = 'PROCESSING';

create index truconf_outbox_trueconf_message_id_idx
  on truconf_outbox (trueconf_message_id);

create index truconf_outbox_created_at_id_idx
  on truconf_outbox (created_at, id);

create table truconf_outbox_file (
  id bigserial primary key,
  outbox_id bigint not null references truconf_outbox (id) on delete cascade,
  file_name text not null,
  mime_type text null,
  size_bytes bigint not null,
  storage_kind text not null,
  file_path text null,
  file_data bytea null,
  preview_file_name text null,
  preview_mime_type text null,
  preview_size_bytes bigint null,
  preview_file_path text null,
  preview_file_data bytea null,
  created_at timestamptz not null default now(),
  constraint truconf_outbox_file_size_bytes_check check (size_bytes >= 0),
  constraint truconf_outbox_file_preview_size_bytes_check check (
    preview_size_bytes is null or preview_size_bytes >= 0
  ),
  constraint truconf_outbox_file_storage_kind_check check (
    storage_kind in ('DISK', 'DB')
  ),
  constraint truconf_outbox_file_disk_check check (
    storage_kind <> 'DISK' or file_path is not null
  ),
  constraint truconf_outbox_file_db_check check (
    storage_kind <> 'DB' or file_data is not null
  )
);

create unique index truconf_outbox_file_outbox_id_uq
  on truconf_outbox_file (outbox_id);

create table truconf_p2p_chat_cache (
  user_id text primary key,
  chat_id text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  last_used_at timestamptz not null default now()
);

create index truconf_p2p_chat_cache_last_used_at_idx
  on truconf_p2p_chat_cache (last_used_at);

create or replace function truconf_set_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

create trigger truconf_outbox_set_updated_at
before update on truconf_outbox
for each row
execute function truconf_set_updated_at();

create trigger truconf_p2p_chat_cache_set_updated_at
before update on truconf_p2p_chat_cache
for each row
execute function truconf_set_updated_at();

create or replace function truconf_notify_outbox_new()
returns trigger
language plpgsql
as $$
begin
  perform pg_notify('truconf_outbox_new', new.id::text);
  return new;
end;
$$;

create trigger truconf_outbox_notify_new
after insert on truconf_outbox
for each row
when (new.status = 'NEW')
execute function truconf_notify_outbox_new();
