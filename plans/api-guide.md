# Полный гайд по HTTP API TruconfProxyDB

## Базовые правила

Все endpoint-ы `/api/v1/**` требуют API-ключ:

```http
X-API-Key: <TRUCONF_PROXY_API_KEY>
```

В примерах ниже:

```bash
export BASE_URL=http://localhost:8080
export API_KEY=change-me
```

Если сервис опубликован под context path, например `/tconf`, базовый URL будет:

```bash
export BASE_URL=http://localhost:8080/tconf
```

Все JSON-запросы отправляются с:

```http
Content-Type: application/json
```

## Получатели

Поле `recipient` обязательно для создания outbox-задания.

### Чат

```json
{
  "kind": "CHAT",
  "chatId": "chat-123"
}
```

Используйте, если уже известен TrueConf `chatId`.

### Пользователь по TrueConf-id

```json
{
  "kind": "USER",
  "userId": "gd.rt.ru\\helpdesk_chatbot@s13.trueconf.rt.ru"
}
```

Используйте, если уже известен TrueConf-id пользователя. Для P2P-доставки сервис
создаст или переиспользует P2P-чат и отправит сообщение туда.

### Пользователь по email через AD

```json
{
  "kind": "USER_EMAIL",
  "email": "user@example.com"
}
```

Используйте, если известен только email. Сервис:

1. нормализует email;
2. ищет локальную связку `email -> trueconfId`;
3. если связки нет, ищет пользователя в AD;
4. берет TrueConf-id из `extensionAttribute5`;
5. сохраняет связку в кэш;
6. отправляет как обычному `USER`.

Для этого режима должны быть настроены `TRUCONF_AD_*`.

## POST /api/v1/outbox

Создает outbox-задание для JSON-операций.

Общая форма:

```json
{
  "externalId": "optional-idempotency-key",
  "operation": "SEND_MESSAGE",
  "recipient": {
    "kind": "CHAT",
    "chatId": "chat-123"
  },
  "targetMessageId": "message-id-for-edit-remove-forward",
  "replyMessageId": "optional-reply-message-id",
  "payload": {},
  "maxAttempts": 10
}
```

Поля:

| Поле | Обязательность | Описание |
| --- | --- | --- |
| `externalId` | нет | Идемпотентный внешний ключ. Повтор с тем же `externalId` вернет уже созданное задание. |
| `operation` | да | Одна из операций ниже. |
| `recipient` | да | Получатель: `CHAT`, `USER`, `USER_EMAIL`. |
| `targetMessageId` | зависит от операции | Нужен для edit/remove/forward. |
| `replyMessageId` | нет | ID сообщения, на которое нужно ответить. Используется для send message/file/survey. |
| `payload` | зависит от операции | JSON-объект с данными операции. |
| `maxAttempts` | нет | Переопределяет число попыток доставки. Должно быть `>= 1`. |

Ответ при создании:

```json
{
  "id": 1,
  "externalId": "optional-idempotency-key",
  "status": "NEW"
}
```

HTTP-статусы:

| Статус | Значение |
| --- | --- |
| `201 Created` | Создано новое outbox-задание. |
| `200 OK` | Задание с таким `externalId` уже существовало. |
| `400 Bad Request` | Некорректный JSON, поля или бизнес-валидация. |
| `401 Unauthorized` | Нет или неверный `X-API-Key`. |

## SEND_MESSAGE

Отправляет текстовое сообщение.

Обязательные поля:

| Поле | Описание |
| --- | --- |
| `recipient` | Куда отправить. |
| `payload.text` | Текст сообщения. |

Опциональные поля:

| Поле | Описание |
| --- | --- |
| `payload.parseMode` | Режим разметки, передается в TrueConf. |
| `replyMessageId` | Ответ на сообщение. |

### В чат

```bash
curl -i "$BASE_URL/api/v1/outbox" \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "externalId": "msg-chat-001",
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

### Пользователю по TrueConf-id

```bash
curl -i "$BASE_URL/api/v1/outbox" \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "externalId": "msg-user-001",
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

### Пользователю по email

```bash
curl -i "$BASE_URL/api/v1/outbox" \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "externalId": "msg-email-001",
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

### Ответом на сообщение

```bash
curl -i "$BASE_URL/api/v1/outbox" \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "externalId": "msg-reply-001",
    "operation": "SEND_MESSAGE",
    "recipient": {
      "kind": "CHAT",
      "chatId": "chat-123"
    },
    "replyMessageId": "message-to-reply",
    "payload": {
      "text": "Ответ на сообщение"
    }
  }'
```

## POST /api/v1/outbox/files

Создает задание `SEND_FILE` и прикрепляет файл.

Это `multipart/form-data` endpoint. Части запроса:

| Part | Обязательность | Тип | Описание |
| --- | --- | --- | --- |
| `request` | да | `application/json` | JSON с получателем, подписью и настройками. |
| `file` | да | файл | Основной файл. Не должен быть пустым. |
| `preview` | нет | файл | Preview-файл. Не должен быть пустым, если передан. |

JSON part `request`:

```json
{
  "externalId": "file-001",
  "recipient": {
    "kind": "CHAT",
    "chatId": "chat-123"
  },
  "caption": "Подпись к файлу",
  "parseMode": "text",
  "replyMessageId": "optional-reply-message-id",
  "maxAttempts": 10
}
```

Поля:

| Поле | Обязательность | Описание |
| --- | --- | --- |
| `externalId` | нет | Идемпотентный внешний ключ. |
| `recipient` | да | `CHAT`, `USER` или `USER_EMAIL`. |
| `caption` | нет | Подпись к файлу. |
| `parseMode` | нет | Режим разметки подписи. |
| `replyMessageId` | нет | Отправить файл как ответ на сообщение. |
| `maxAttempts` | нет | Число попыток доставки, `>= 1`. |

Файлы сохраняются в `TRUCONF_FILE_STORAGE_DIR`, а при доставке сервис загружает
их в TrueConf и отправляет сообщение с файлом.

### Отправить файл в чат

```bash
curl -i "$BASE_URL/api/v1/outbox/files" \
  -H "X-API-Key: $API_KEY" \
  -F 'request={
    "externalId": "file-chat-001",
    "recipient": {
      "kind": "CHAT",
      "chatId": "chat-123"
    },
    "caption": "Отчет за квартал"
  };type=application/json' \
  -F "file=@./report.pdf;type=application/pdf"
```

### Отправить файл пользователю по TrueConf-id

```bash
curl -i "$BASE_URL/api/v1/outbox/files" \
  -H "X-API-Key: $API_KEY" \
  -F 'request={
    "externalId": "file-user-001",
    "recipient": {
      "kind": "USER",
      "userId": "gd.rt.ru\\helpdesk_chatbot@s13.trueconf.rt.ru"
    },
    "caption": "Документ"
  };type=application/json' \
  -F "file=@./document.docx;type=application/vnd.openxmlformats-officedocument.wordprocessingml.document"
```

### Отправить файл пользователю по email

```bash
curl -i "$BASE_URL/api/v1/outbox/files" \
  -H "X-API-Key: $API_KEY" \
  -F 'request={
    "externalId": "file-email-001",
    "recipient": {
      "kind": "USER_EMAIL",
      "email": "user@example.com"
    },
    "caption": "Документ"
  };type=application/json' \
  -F "file=@./document.pdf;type=application/pdf"
```

### Отправить файл с preview

```bash
curl -i "$BASE_URL/api/v1/outbox/files" \
  -H "X-API-Key: $API_KEY" \
  -F 'request={
    "externalId": "file-preview-001",
    "recipient": {
      "kind": "CHAT",
      "chatId": "chat-123"
    },
    "caption": "Изображение с превью"
  };type=application/json' \
  -F "file=@./image.png;type=image/png" \
  -F "preview=@./image-preview.jpg;type=image/jpeg"
```

### Частые ошибки при загрузке файлов

| Ошибка | Причина |
| --- | --- |
| `Missing multipart part: request` | Нет JSON-part `request`. |
| `Missing multipart part: file` | Нет file-part `file`. |
| `file must not be empty` | Основной файл пустой. |
| `preview must not be empty` | Preview передан, но пустой. |
| `Request validation failed` | В JSON-part не хватает `recipient` или обязательного поля получателя. |

Повторный запрос с тем же `externalId` вернет существующее задание и не заменит
уже сохраненный файл.

## SEND_SURVEY

Отправляет survey-сообщение.

Обязательные поля payload:

| Поле |
| --- |
| `url` |
| `appVersion` |
| `path` |
| `title` |
| `description` |
| `buttonText` |
| `secret` |
| `alt` |

Пример:

```bash
curl -i "$BASE_URL/api/v1/outbox" \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "externalId": "survey-001",
    "operation": "SEND_SURVEY",
    "recipient": {
      "kind": "CHAT",
      "chatId": "chat-123"
    },
    "payload": {
      "url": "https://example.com/survey",
      "appVersion": "1.0.0",
      "path": "/survey",
      "title": "Опрос",
      "description": "Оцените качество",
      "buttonText": "Открыть",
      "secret": "secret-value",
      "alt": "Опрос"
    }
  }'
```

## EDIT_MESSAGE

Редактирует ранее отправленное сообщение.

Обязательные поля:

| Поле | Описание |
| --- | --- |
| `targetMessageId` | ID сообщения в TrueConf. |
| `payload.text` | Новый текст. |

Текущий API-контракт требует `recipient` для всех операций, хотя при
`EDIT_MESSAGE` он не используется доставщиком. Обычно передавайте исходный чат.

```bash
curl -i "$BASE_URL/api/v1/outbox" \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "externalId": "edit-message-001",
    "operation": "EDIT_MESSAGE",
    "recipient": {
      "kind": "CHAT",
      "chatId": "chat-123"
    },
    "targetMessageId": "message-123",
    "payload": {
      "text": "Новый текст",
      "parseMode": "text"
    }
  }'
```

## EDIT_SURVEY

Редактирует survey-сообщение.

Обязательные поля:

| Поле | Описание |
| --- | --- |
| `targetMessageId` | ID сообщения в TrueConf. |
| `payload.*` | Все обязательные поля survey payload. |

```bash
curl -i "$BASE_URL/api/v1/outbox" \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "externalId": "edit-survey-001",
    "operation": "EDIT_SURVEY",
    "recipient": {
      "kind": "CHAT",
      "chatId": "chat-123"
    },
    "targetMessageId": "message-123",
    "payload": {
      "url": "https://example.com/survey-updated",
      "appVersion": "1.0.0",
      "path": "/survey",
      "title": "Опрос",
      "description": "Обновленное описание",
      "buttonText": "Открыть",
      "secret": "secret-value",
      "alt": "Опрос"
    }
  }'
```

## REMOVE_MESSAGE

Удаляет сообщение.

Обязательные поля:

| Поле | Описание |
| --- | --- |
| `targetMessageId` | ID сообщения в TrueConf. |

Опциональные поля:

| Поле | Описание |
| --- | --- |
| `payload.forAll` | Удалить для всех. По умолчанию `true`. |

`recipient` обязателен текущим API-контрактом, но при доставке не используется.

```bash
curl -i "$BASE_URL/api/v1/outbox" \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "externalId": "remove-message-001",
    "operation": "REMOVE_MESSAGE",
    "recipient": {
      "kind": "CHAT",
      "chatId": "chat-123"
    },
    "targetMessageId": "message-123",
    "payload": {
      "forAll": true
    }
  }'
```

## FORWARD_MESSAGE

Пересылает сообщение в другого получателя.

Обязательные поля:

| Поле | Описание |
| --- | --- |
| `recipient` | Куда переслать. |
| `targetMessageId` | Какое сообщение переслать. |

```bash
curl -i "$BASE_URL/api/v1/outbox" \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "externalId": "forward-message-001",
    "operation": "FORWARD_MESSAGE",
    "recipient": {
      "kind": "CHAT",
      "chatId": "target-chat-123"
    },
    "targetMessageId": "source-message-123"
  }'
```

## Проверка статуса задания

### GET /api/v1/outbox/{id}

```bash
curl -s "$BASE_URL/api/v1/outbox/1" \
  -H "X-API-Key: $API_KEY"
```

### GET /api/v1/outbox/by-external-id/{externalId}

```bash
curl -s "$BASE_URL/api/v1/outbox/by-external-id/msg-chat-001" \
  -H "X-API-Key: $API_KEY"
```

### GET /api/v1/outbox/by-trueconf-message-id/{trueconfMessageId}

```bash
curl -s "$BASE_URL/api/v1/outbox/by-trueconf-message-id/message-123" \
  -H "X-API-Key: $API_KEY"
```

Пример ответа:

```json
{
  "id": 1,
  "externalId": "msg-chat-001",
  "operation": "SEND_MESSAGE",
  "recipientKind": "CHAT",
  "chatId": "chat-123",
  "userId": null,
  "recipientEmail": null,
  "targetMessageId": null,
  "replyMessageId": null,
  "payload": {
    "text": "Текст сообщения"
  },
  "status": "SENT",
  "attemptCount": 1,
  "maxAttempts": 10,
  "nextAttemptAt": "2026-06-01T08:00:00Z",
  "trueconfChatId": "chat-123",
  "trueconfMessageId": "message-123",
  "trueconfFileId": null,
  "trueconfTimestamp": 1780000000000,
  "lastErrorCode": null,
  "lastErrorMessage": null,
  "lastErrorRetryable": null,
  "lastResponse": {},
  "createdAt": "2026-06-01T08:00:00Z",
  "updatedAt": "2026-06-01T08:00:01Z",
  "sentAt": "2026-06-01T08:00:01Z",
  "failedAt": null
}
```

Статусы:

| Статус | Значение |
| --- | --- |
| `NEW` | Задание создано и ожидает обработки. |
| `PROCESSING` | Задание взято worker-ом. |
| `RETRY_WAIT` | Была retryable-ошибка, задание ждет повторной попытки. |
| `SENT` | Доставка завершилась успешно. |
| `FAILED` | Доставка завершилась ошибкой без дальнейших повторов. |

Если `status = FAILED`, смотрите `lastErrorCode`, `lastErrorMessage`,
`lastErrorRetryable` и `lastResponse`.

## Диагностические endpoint-ы TrueConf

Эти endpoint-ы тоже требуют `X-API-Key`.

### GET /api/v1/trueconf/chats

Получить список чатов через bot websocket API.

Параметры:

| Параметр | По умолчанию | Ограничения |
| --- | --- | --- |
| `count` | `50` | `1..100` |
| `page` | `1` | `>= 1` |

```bash
curl -s "$BASE_URL/api/v1/trueconf/chats?count=50&page=1" \
  -H "X-API-Key: $API_KEY"
```

### POST /api/v1/trueconf/p2p-chats

Создать P2P-чат с пользователем по TrueConf-id.

```bash
curl -i "$BASE_URL/api/v1/trueconf/p2p-chats" \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "gd.rt.ru\\helpdesk_chatbot@s13.trueconf.rt.ru"
  }'
```

### GET /api/v1/trueconf/users/search

Найти пользователя через TrueConf Server API `/api/v4/accounts`.

Параметры:

| Параметр | По умолчанию | Ограничения |
| --- | --- | --- |
| `query` | нет | Обязательный, не blank. |
| `limit` | `20` | `1..100` |

```bash
curl -s "$BASE_URL/api/v1/trueconf/users/search?query=Иванов&limit=20" \
  -H "X-API-Key: $API_KEY"
```

В ответе ищите `trueconfId`; его можно передавать как `recipient.userId`.

## Формат ошибок

Ошибки возвращаются в едином формате:

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Request validation failed",
    "details": [
      {
        "field": "recipient",
        "message": "must not be null"
      }
    ]
  }
}
```

Частые коды:

| HTTP | `error.code` | Когда возникает |
| --- | --- | --- |
| `400` | `VALIDATION_ERROR` | Не хватает обязательных полей или нарушены бизнес-правила. |
| `400` | `INVALID_JSON` | Тело JSON не читается. |
| `400` | `BAD_REQUEST` | Некорректный запрос или constraint БД. |
| `401` | `UNAUTHORIZED` | Нет или неверный `X-API-Key`. |
| `404` | `NOT_FOUND` | Задание не найдено. |
| `502/503` | TrueConf/AD error code | Ошибка внешнего TrueConf/AD-вызова. |

## Важные замечания

- Для файлов используйте только `POST /api/v1/outbox/files`. JSON-операция
  `SEND_FILE` без file-row будет создана некорректно и завершится ошибкой при
  доставке.
- `externalId` делает enqueue идемпотентным, но не делает доставку в TrueConf
  exactly-once.
- Для `USER` и `USER_EMAIL` сервис отправляет в P2P-чат: сначала находит или
  создает P2P-чат, потом отправляет сообщение.
- Для `USER_EMAIL` нужен доступ к AD и заполненный атрибут `extensionAttribute5`.
- Файл физически сохраняется в `TRUCONF_FILE_STORAGE_DIR`; имя файла
  нормализуется, path traversal из имени файла отбрасывается.
- Health endpoint доступен без API-ключа: `GET /actuator/health`.

