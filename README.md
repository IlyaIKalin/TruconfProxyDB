# TruconfProxyDB

Standalone-сервис на Java 21 / Spring Boot для надёжной доставки исходящих
заданий в TrueConf Chatbot Connector. Задания можно ставить в очередь через
HTTP API или прямыми вставками в таблицу outbox в PostgreSQL.

Сервис доставляет сообщения по модели at-least-once. Если процесс остановится
после успешной команды TrueConf, но до обновления PostgreSQL, то же задание
может быть отправлено повторно.

## Локальный запуск в Docker

Создайте локальный env-файл из примера и замените все значения `change-me`:

```bash
cp .env.example .env
```

Соберите приложение локально:

```bash
mvn clean package -DskipTests
```

Запустите PostgreSQL и приложение:

```bash
docker compose --env-file .env up --build
```

Если локальный порт `8080` уже занят, задайте другой `SERVER_PORT` в `.env`
или переопределите его для команды:

```bash
SERVER_PORT=18080 docker compose --env-file .env up --build
```

Проверьте приложение:

```bash
curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8080/actuator/health/readiness
```

Откройте web-портал для ручного тестирования:

```text
http://localhost:8080/
```

Портал доступен без авторизации, но все действия с `/api/v1/**` всё равно
требуют `X-API-Key`; ключ вводится в UI и хранится только в `sessionStorage`
текущей вкладки браузера.

Если сервис публикуется не от корня домена, задайте servlet context path.
Например, для публикации под `https://bis.rt.ru/tconf/` установите:

```bash
SERVER_SERVLET_CONTEXT_PATH=/tconf
```

В этом режиме портал будет доступен по `/tconf/`, API по
`/tconf/api/v1/**`, а health endpoints по `/tconf/actuator/health`.

В портале есть формы для всех HTTP-сценариев сервиса: отправка сообщения,
файла, survey, редактирование, удаление, forward, проверка статуса, список
чатов и прямой диагностический `createP2PChat`.

Для direct insert и отладки подключитесь к базе:

```bash
psql "postgresql://truconf_proxydb:change-me@localhost:15432/truconf_proxydb"
```

Остановите локальное окружение:

```bash
docker compose --env-file .env down
```

Добавьте `-v`, чтобы вместе с контейнерами удалить локальные тома PostgreSQL и
file storage.

## Конфигурация

Все секреты должны поступать из переменных окружения, container secrets или
runtime secret manager. Не коммитьте `.env`, реальные API-ключи, пароли
TrueConf, OAuth-токены и пароли к базе данных.

| Переменная | Значение по умолчанию / пример | Назначение |
| --- | --- | --- |
| `POSTGRES_DB` | `truconf_proxydb` | Имя базы данных PostgreSQL в Compose. |
| `POSTGRES_USER` | `truconf_proxydb` | Пользователь PostgreSQL в Compose. |
| `POSTGRES_PASSWORD` | `change-me` | Пароль PostgreSQL в Compose. |
| `POSTGRES_PORT` | `15432` | Порт PostgreSQL на хосте для direct insert и отладки. |
| `SERVER_PORT` | `8080` | HTTP-порт внутри контейнера приложения и на хосте. |
| `SERVER_SERVLET_CONTEXT_PATH` | пусто, пример `/tconf` | Servlet context path для публикации приложения под path prefix. |
| `SPRING_DATASOURCE_URL` | собирается в `docker-compose.yml` | JDBC URL. |
| `SPRING_DATASOURCE_USERNAME` | `${POSTGRES_USER}` | Имя пользователя JDBC. |
| `SPRING_DATASOURCE_PASSWORD` | `${POSTGRES_PASSWORD}` | Пароль JDBC. |
| `SPRING_FLYWAY_ENABLED` | `true` | Включает миграции БД при старте. |
| `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE` | `100MB` | Максимальный размер загружаемого файла. |
| `SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE` | `110MB` | Максимальный размер multipart-запроса. |
| `TRUCONF_HTTP_BASE_URL` | `https://trueconf.example.local` | Базовый HTTP URL TrueConf. |
| `TRUCONF_WS_URL` | `wss://trueconf.example.local/websocket/chat_bot/` | WebSocket URL бота TrueConf. |
| `TRUCONF_CLIENT_ID` | `change-me` | OAuth client id, выданный TrueConf. |
| `TRUCONF_USERNAME` | `bot-user` | Имя пользователя бота TrueConf. |
| `TRUCONF_PASSWORD` | `change-me` | Пароль бота TrueConf. |
| `TRUCONF_PROXY_API_KEY` | `change-me` | Обязательное значение `X-API-Key` для `/api/v1/**`. |
| `TRUCONF_FILE_STORAGE_DIR` | `/var/lib/truconf-proxydb/files` | Корневой каталог для сохранённых файлов. |
| `TRUCONF_TLS_INSECURE_SKIP_VERIFY` | `false` | Отключает проверку TLS-сертификатов только для исходящих клиентов TrueConf. Используйте только как временный workaround для стендов с некорректной цепочкой сертификатов. |
| `TRUCONF_DISPATCHER_ENABLED` | `true` | Включает фоновую обработку outbox. |
| `TRUCONF_DISPATCHER_BATCH_SIZE` | `50` | Максимум заданий, которые dispatcher забирает за один tick. |
| `TRUCONF_DISPATCHER_POLL_INTERVAL` | `5s` | Интервал fallback polling. |
| `TRUCONF_DISPATCHER_LOCK_TIMEOUT` | `2m` | Тайм-аут processing lock. |
| `TRUCONF_DISPATCHER_WORKER_THREADS` | `4` | Размер пула worker-потоков. |
| `TRUCONF_RETRY_MAX_ATTEMPTS` | `10` | Максимум попыток по умолчанию для новых заданий. |
| `TRUCONF_RETRY_INITIAL_DELAY` | `5s` | Начальная задержка retry. |
| `TRUCONF_RETRY_MAX_DELAY` | `5m` | Верхняя граница задержки retry. |
| `TRUCONF_RETRY_MULTIPLIER` | `2.0` | Множитель exponential backoff. |
| `TRUCONF_RATE_LIMIT_COMMANDS_PER_SECOND` | `10` | Максимум исходящих команд TrueConf в секунду на один процесс. |
| `TRUCONF_WEBSOCKET_REQUEST_TIMEOUT` | `30s` | Тайм-аут команды TrueConf. |
| `TRUCONF_WEBSOCKET_CONNECT_TIMEOUT` | `10s` | Тайм-аут подключения WebSocket. |
| `TRUCONF_WEBSOCKET_RECONNECT_DELAY` | `5s` | Задержка переподключения после сбоя сессии. |

Держите `TRUCONF_WEBSOCKET_REQUEST_TIMEOUT` меньше
`TRUCONF_DISPATCHER_LOCK_TIMEOUT`, иначе worker может работать дольше, чем
действует его DB lock.

### Nginx под `/tconf`

Если приложение запущено с `SERVER_SERVLET_CONTEXT_PATH=/tconf`, upstream
должен получать URI с тем же префиксом:

```nginx
location = /tconf {
    return 301 /tconf/;
}

location /tconf/ {
    proxy_pass http://dev.bis.rt.ru:38085/tconf/;

    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

## HTTP API

Все запросы к `/api/v1/**` требуют `X-API-Key`. Health endpoints доступны без
ключа:

```bash
export API_KEY=change-me
```

Поставить текстовое сообщение в очередь:

```bash
curl -i http://localhost:8080/api/v1/outbox \
  -H "Content-Type: application/json" \
  -H "X-API-Key: ${API_KEY}" \
  -d '{
    "externalId": "demo-message-1",
    "operation": "SEND_MESSAGE",
    "recipient": { "kind": "CHAT", "chatId": "chat-123" },
    "payload": { "text": "Hello from TruconfProxyDB" }
  }'
```

Поставить в очередь текстовое P2P-сообщение. Сервис разрешает `userId` в
`chatId` через `createP2PChat` и кэширует результат в PostgreSQL:

```bash
curl -i http://localhost:8080/api/v1/outbox \
  -H "Content-Type: application/json" \
  -H "X-API-Key: ${API_KEY}" \
  -d '{
    "externalId": "demo-user-message-1",
    "operation": "SEND_MESSAGE",
    "recipient": { "kind": "USER", "userId": "user-123" },
    "payload": { "text": "Hello in P2P chat" }
  }'
```

Загрузить и отправить файл:

```bash
curl -i http://localhost:8080/api/v1/outbox/files \
  -H "X-API-Key: ${API_KEY}" \
  -F 'request={
    "externalId": "demo-file-1",
    "recipient": { "kind": "CHAT", "chatId": "chat-123" },
    "caption": "Report"
  };type=application/json' \
  -F "file=@./report.pdf;type=application/pdf"
```

Получить статус:

```bash
curl -s http://localhost:8080/api/v1/outbox/1 \
  -H "X-API-Key: ${API_KEY}"

curl -s http://localhost:8080/api/v1/outbox/by-external-id/demo-message-1 \
  -H "X-API-Key: ${API_KEY}"

curl -s http://localhost:8080/api/v1/outbox/by-trueconf-message-id/306a64ad-3bc7-4504-b3b9-e6f2a72550ca \
  -H "X-API-Key: ${API_KEY}"
```

Операции:

| Операция | Обязательные поля |
| --- | --- |
| `SEND_MESSAGE` | `recipient`, `payload.text` |
| `SEND_FILE` | Используйте `POST /api/v1/outbox/files` или вставьте строку файла напрямую. |
| `SEND_SURVEY` | `recipient`, поля survey payload из списка ниже. |
| `EDIT_MESSAGE` | `targetMessageId`, `payload.text` |
| `EDIT_SURVEY` | `targetMessageId`, поля survey payload из списка ниже. |
| `REMOVE_MESSAGE` | `targetMessageId`; опционально `payload.forAll`, по умолчанию `true`. |
| `FORWARD_MESSAGE` | `recipient`, `targetMessageId` |

Поля survey payload: `url`, `appVersion`, `path`, `title`, `description`,
`buttonText`, `secret`, `alt`.

`externalId` необязателен. Если он указан, значение должно быть уникальным и
делает enqueue идемпотентным: повторные HTTP-вызовы вернут существующее задание
outbox.

## Примеры direct insert

Миграция устанавливает триггер, который при вставке задания в статусе `NEW`
отправляет `pg_notify('truconf_outbox_new', id)`. Если уведомление потеряется,
dispatcher всё равно заберёт готовые задания через polling.

Вставить сообщение в чат:

```sql
insert into truconf_outbox (
  external_id,
  operation,
  recipient_kind,
  chat_id,
  payload_json
) values (
  'sql-message-1',
  'SEND_MESSAGE',
  'CHAT',
  'chat-123',
  '{"text":"Hello from SQL"}'::jsonb
);
```

Вставить P2P-сообщение:

```sql
insert into truconf_outbox (
  external_id,
  operation,
  recipient_kind,
  user_id,
  payload_json,
  max_attempts
) values (
  'sql-user-message-1',
  'SEND_MESSAGE',
  'USER',
  'user-123',
  '{"text":"Hello from SQL P2P"}'::jsonb,
  5
);
```

Вставить задание на отправку файла, который уже лежит внутри
`TRUCONF_FILE_STORAGE_DIR`. Путь должен оставаться внутри настроенного
storage root, иначе обработка завершится terminal failure.

```sql
with job as (
  insert into truconf_outbox (
    external_id,
    operation,
    recipient_kind,
    chat_id,
    payload_json
  ) values (
    'sql-file-1',
    'SEND_FILE',
    'CHAT',
    'chat-123',
    '{"caption":"File from direct insert"}'::jsonb
  )
  returning id
)
insert into truconf_outbox_file (
  outbox_id,
  file_name,
  mime_type,
  size_bytes,
  storage_kind,
  file_path
)
select
  id,
  'report.pdf',
  'application/pdf',
  12345,
  'DISK',
  '/var/lib/truconf-proxydb/files/manual/report.pdf'
from job;
```

Вставить задание на удаление сообщения:

```sql
insert into truconf_outbox (
  external_id,
  operation,
  recipient_kind,
  chat_id,
  target_message_id,
  payload_json
) values (
  'sql-remove-1',
  'REMOVE_MESSAGE',
  'CHAT',
  'chat-123',
  'message-456',
  '{"forAll":true}'::jsonb
);
```

Проверить результат обработки:

```sql
select
  id,
  status,
  attempt_count,
  next_attempt_at,
  trueconf_chat_id,
  trueconf_message_id,
  trueconf_file_id,
  last_error_code,
  last_error_message
from truconf_outbox
order by id desc
limit 10;
```

## Безопасность

- Не открывайте `/api/v1/**` для недоверенных сетей без дополнительной защиты
  на edge: mTLS, VPN, allow lists в reverse proxy или правил WAF.
- Ротируйте `TRUCONF_PROXY_API_KEY`, `TRUCONF_CLIENT_ID`,
  `TRUCONF_PASSWORD` и пароли к базе данных, если они попали за пределы
  runtime-окружения.
- Системы удалённой конфигурации должны хранить секреты в зашифрованном виде и
  не печатать разрешённые значения свойств в логах или диагностике.
- Логи приложения должны содержать только id заданий и операционные ошибки. Не
  добавляйте логирование запросов и ответов, если оно печатает `X-API-Key`,
  OAuth-токены, пароли TrueConf, заголовки `Authorization` или значения
  `secret` в survey payload.

## Известные ограничения

- Доставка работает по модели at-least-once, а не exactly-once.
- `externalId` предотвращает дублирование enqueue только до начала обработки; он
  не делает доставку в TrueConf exactly-once.
- Direct insert-ы частично защищены constraints БД. Детали payload проверяются
  во время обработки, поэтому некорректные задания могут перейти в `FAILED`.
- Direct `SEND_FILE` с `storage_kind='DISK'` требует существующего файла внутри
  `TRUCONF_FILE_STORAGE_DIR`.
- В v1 нет входящего workflow для сообщений TrueConf, UI/admin panel и
  distributed leader election.
- Retry backoff — детерминированный exponential backoff без jitter.

## Разработка

Запустить тесты:

```bash
mvn test
```

Запустить локально без Docker Compose, если PostgreSQL уже доступен:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/truconf_proxydb \
SPRING_DATASOURCE_USERNAME=truconf_proxydb \
SPRING_DATASOURCE_PASSWORD=change-me \
TRUCONF_PROXY_API_KEY=change-me \
mvn spring-boot:run
```

Проверить Compose-файл:

```bash
docker compose --env-file .env config
```
