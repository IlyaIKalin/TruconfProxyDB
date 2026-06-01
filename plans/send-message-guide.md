# Гайд: отправка сообщения через API и прямую запись в БД

## Через HTTP API

Для всех запросов к `/api/v1/**` нужен заголовок `X-API-Key` со значением из
`TRUCONF_PROXY_API_KEY`.

### Отправка в чат

Используйте этот вариант, если уже известен `chatId`.

```bash
curl -i http://localhost:8080/api/v1/outbox \
  -H "X-API-Key: ${API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{
    "externalId": "msg-001",
    "operation": "SEND_MESSAGE",
    "recipient": {
      "kind": "CHAT",
      "chatId": "chat-123"
    },
    "payload": {
      "text": "Текст сообщения"
    }
  }'
```

### Отправка пользователю по TrueConf-id

Используйте этот вариант, если уже известен TrueConf-id пользователя.

```bash
curl -i http://localhost:8080/api/v1/outbox \
  -H "X-API-Key: ${API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{
    "externalId": "msg-002",
    "operation": "SEND_MESSAGE",
    "recipient": {
      "kind": "USER",
      "userId": "gd.rt.ru\\helpdesk_chatbot@s13.trueconf.rt.ru"
    },
    "payload": {
      "text": "Текст сообщения"
    }
  }'
```

### Отправка пользователю по email через AD

Используйте этот вариант, если известен email, а TrueConf-id нужно получить из
AD. Сервис найдет запись по email, возьмет TrueConf-id из `extensionAttribute5`
и сохранит связку `email -> trueconfId` в локальный кэш.

```bash
curl -i http://localhost:8080/api/v1/outbox \
  -H "X-API-Key: ${API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{
    "externalId": "msg-003",
    "operation": "SEND_MESSAGE",
    "recipient": {
      "kind": "USER_EMAIL",
      "email": "user@example.com"
    },
    "payload": {
      "text": "Текст сообщения"
    }
  }'
```

Для `USER_EMAIL` должны быть настроены `TRUCONF_AD_*`, а в AD у найденной записи
должен быть заполнен `extensionAttribute5` с TrueConf-id.

### Проверка статуса

По `externalId`:

```bash
curl -s http://localhost:8080/api/v1/outbox/by-external-id/msg-003 \
  -H "X-API-Key: ${API_KEY}"
```

По числовому `id` задания:

```bash
curl -s http://localhost:8080/api/v1/outbox/1 \
  -H "X-API-Key: ${API_KEY}"
```

## Через прямую запись в БД

Dispatcher подхватывает записи из `truconf_outbox` со статусом `NEW`.
При вставке срабатывает `pg_notify('truconf_outbox_new', id)`. Если уведомление
потеряется, dispatcher все равно заберет готовые задания через polling.

### Отправка в чат

```sql
insert into truconf_outbox (
  external_id,
  operation,
  recipient_kind,
  chat_id,
  payload_json
) values (
  'sql-msg-001',
  'SEND_MESSAGE',
  'CHAT',
  'chat-123',
  '{"text":"Текст сообщения"}'::jsonb
);
```

### Отправка пользователю по TrueConf-id

```sql
insert into truconf_outbox (
  external_id,
  operation,
  recipient_kind,
  user_id,
  payload_json
) values (
  'sql-msg-002',
  'SEND_MESSAGE',
  'USER',
  'gd.rt.ru\helpdesk_chatbot@s13.trueconf.rt.ru',
  '{"text":"Текст сообщения"}'::jsonb
);
```

### Отправка пользователю по email через AD

```sql
insert into truconf_outbox (
  external_id,
  operation,
  recipient_kind,
  recipient_email,
  payload_json
) values (
  'sql-msg-003',
  'SEND_MESSAGE',
  'USER_EMAIL',
  'user@example.com',
  '{"text":"Текст сообщения"}'::jsonb
);
```

## Проверка результата в БД

```sql
select
  id,
  external_id,
  status,
  attempt_count,
  trueconf_chat_id,
  trueconf_message_id,
  last_error_code,
  last_error_message
from truconf_outbox
where external_id in ('sql-msg-001', 'sql-msg-002', 'sql-msg-003')
order by id;
```

Основные статусы:

| Статус | Значение |
| --- | --- |
| `NEW` | Задание создано и ожидает обработки. |
| `PROCESSING` | Задание взято worker-ом. |
| `RETRY_WAIT` | Была retryable-ошибка, задание ждет повторной попытки. |
| `SENT` | Сообщение отправлено. |
| `FAILED` | Доставка завершилась ошибкой без дальнейших повторов. |

Если `status = FAILED`, смотрите `last_error_code` и `last_error_message`.

