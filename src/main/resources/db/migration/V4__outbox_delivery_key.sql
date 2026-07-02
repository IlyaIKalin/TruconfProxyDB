alter table truconf_outbox
  add column delivery_key text generated always as (
    case recipient_kind
      when 'CHAT' then 'CHAT:' || chat_id
      when 'USER' then 'USER:' || user_id
      when 'USER_EMAIL' then 'USER_EMAIL:' || lower(recipient_email)
      else recipient_kind
    end
  ) stored;

alter table truconf_outbox
  add constraint truconf_outbox_delivery_key_check check (
    delivery_key is not null and delivery_key <> ''
  );

create index truconf_outbox_delivery_ready_idx
  on truconf_outbox (delivery_key, status, next_attempt_at, id)
  where status in ('NEW', 'RETRY_WAIT', 'PROCESSING');
