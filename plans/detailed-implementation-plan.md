# Детальный план реализации TruconfProxyDB

Дата подготовки: 2026-05-05

Исходный документ: `plans/global-plan.md`

## 1. Цель и границы v1

Цель v1 - создать standalone-сервис на Java 21 и Spring Boot 4.0.5, который принимает исходящие задания через HTTP API или прямые insert-ы в PostgreSQL, надежно ставит их в очередь и доставляет в TrueConf через Chatbot Connector.

В v1 реализуются:

- постановка заданий в outbox;
- отправка текста, файлов, опросов;
- редактирование текста и опросов;
- удаление и пересылка сообщений;
- разрешение `userId -> chatId` через `createP2PChat` с кешем в PostgreSQL;
- LISTEN/NOTIFY + fallback polling;
- retry/backoff и восстановление после истекших lock-ов;
- API-key защита через `X-API-Key`;
- базовые actuator health/metrics;
- покрытие unit, JDBC/Testcontainers, WebMvc и fake TrueConf server тестами.

В v1 не реализуются:

- хранение входящих сообщений TrueConf как бизнес-сущностей;
- full inbound bot workflow;
- Exactly-once доставка на стороне TrueConf. Сервис дает at-least-once: при падении после отправки, но до записи ответа, возможна повторная отправка;
- UI/admin panel;
- распределенный leader election. Горизонтальное масштабирование обеспечивается claim-логикой БД.

## 2. Проверенные внешние предпосылки

- Spring Boot 4.0.5 доступен в Maven Central как `org.springframework.boot:spring-boot-starter-parent:4.0.5`.
- В Spring Boot 4 присутствуют новые starters `spring-boot-starter-webmvc`, `spring-boot-starter-webmvc-test`, `spring-boot-starter-websocket`, `spring-boot-starter-jdbc`, `spring-boot-starter-flyway`, `spring-boot-starter-validation`, `spring-boot-starter-security`, `spring-boot-starter-actuator`.
- TrueConf Chatbot Connector требует получить OAuth token через HTTP, открыть WebSocket `/websocket/chat_bot/`, выполнить `auth`, затем отправлять request-сообщения с монотонным `id`.
- TrueConf server request-и нужно ACK-ать ответом вида `{"type":2,"id":...}`.
- `createP2PChat` принимает `userId`; если чат уже существовал, возвращает существующий чат.
- Отправка файла в TrueConf состоит из трех шагов: `uploadFile` по WebSocket, HTTP multipart upload в `/bridge/api/client/v1/files` с `Upload-Task-Id`, затем `sendFile` с временным идентификатором файла.
- Опросы (`sendSurvey`, `editSurvey`) требуют заранее созданную survey campaign и payload с `url`, `appVersion`, `path`, `title`, `description`, `buttonText`, `secret`, `alt`.

Источники:

- Spring Boot starters: https://docs.spring.io/spring-boot/reference/using/build-systems.html
- Spring Boot Maven plugin: https://docs.spring.io/spring-boot/maven-plugin/getting-started.html
- Maven Central artifact: https://central.sonatype.com/artifact/org.springframework.boot/spring-boot-starter-parent/4.0.5
- TrueConf auth/WebSocket: https://trueconf.com/docs/chatbot-connector/en/connect-and-auth/
- TrueConf objects/P2P/messages: https://trueconf.com/docs/chatbot-connector/en/objects/
- TrueConf files: https://trueconf.com/docs/chatbot-connector/en/files/
- TrueConf surveys: https://trueconf.com/docs/chatbot-connector/en/surveys/

## 3. Архитектурный срез

Основные подсистемы:

- HTTP API - валидирует запросы, сохраняет outbox-задания и файлы, возвращает статус.
- Outbox storage - PostgreSQL schema, repositories, claim/retry/status transitions.
- Dispatcher - получает сигнал из `pg_notify`, периодически poll-ит БД, claim-ит batch, передает задания executor-у.
- TrueConf client - управляет OAuth token, WebSocket lifecycle, `auth`, command correlation по `id`, reconnect.
- Command executor - маппит outbox operation в TrueConf command flow.
- File storage - сохраняет multipart uploads на диск, читает DISK/DB файлы для отправки.
- Security - API-key filter, открытый только health endpoint.
- Observability - structured logs, actuator, counters/timers.

Поток данных:

1. Клиент вызывает `POST /api/v1/outbox` или `POST /api/v1/outbox/files`, либо внешний процесс делает direct insert в `truconf_outbox`.
2. Insert в статусе `NEW` вызывает trigger `pg_notify('truconf_outbox_new', id)`.
3. Dispatcher получает notify или fallback poll tick.
4. Worker claim-ит задания через `FOR UPDATE SKIP LOCKED`.
5. Executor гарантирует `chatId`: берет из задания либо вызывает `createP2PChat` для `userId`.
6. Executor вызывает нужный TrueConf flow.
7. Repository фиксирует `SENT`, `RETRY_WAIT` или `FAILED`, сохраняет response/error поля.

## 4. Итоговая структура проекта

Создать Maven-проект:

```text
Dockerfile
docker-compose.yml
.dockerignore
.env.example
pom.xml
src/main/java/ru/truconf/proxydb/
  TruconfProxyDbApplication.java
  api/
    OutboxController.java
    OutboxDtos.java
    ApiExceptionHandler.java
  config/
    AppProperties.java
    SecurityConfig.java
    WebConfig.java
    TaskExecutorConfig.java
  domain/
    OutboxJob.java
    OutboxFile.java
    P2pChatCacheEntry.java
    OutboxOperation.java
    OutboxStatus.java
    RecipientKind.java
    FileStorageKind.java
  outbox/
    OutboxRepository.java
    OutboxService.java
    OutboxDispatcher.java
    PostgresNotifyListener.java
    RetryPolicy.java
    WorkerIdProvider.java
  truconf/
    TrueConfClient.java
    TrueConfSession.java
    TrueConfTokenService.java
    TrueConfCommandFactory.java
    TrueConfResponseMapper.java
    TrueConfErrorClassifier.java
    TrueConfException.java
  files/
    FileStorageService.java
    DiskFileStorageService.java
  observability/
    HealthIndicators.java
src/main/resources/
  application.yml
  db/migration/V1__init.sql
src/test/java/ru/truconf/proxydb/
  api/
  outbox/
  truconf/
  support/
```

Рекомендуемые package boundaries:

- `api` не должен знать о SQL и WebSocket.
- `outbox` не должен знать деталей JSON формата TrueConf, кроме opaque `payload_json`.
- `truconf` не должен писать в БД напрямую.
- `files` не должен знать о TrueConf upload protocol.

## 5. Maven и базовая конфигурация

### 5.1. `pom.xml`

Сделать parent:

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>4.0.5</version>
</parent>
```

Параметры:

- `java.version=21`;
- `maven.compiler.release=21`;
- packaging `jar`;
- `spring-boot-maven-plugin`;
- `maven-surefire-plugin` через Boot parent.

Dependencies:

- `spring-boot-starter-webmvc`;
- `spring-boot-starter-restclient` для OAuth и HTTP file upload;
- `spring-boot-starter-websocket`;
- `spring-boot-starter-jdbc`;
- `spring-boot-starter-flyway`;
- `spring-boot-starter-validation`;
- `spring-boot-starter-security`;
- `spring-boot-starter-actuator`;
- `spring-boot-starter-jackson`;
- `org.postgresql:postgresql`;
- test: `spring-boot-starter-test`, `spring-boot-starter-webmvc-test`, `spring-boot-starter-jdbc-test`, `spring-boot-starter-security-test`, `org.testcontainers:postgresql`, `org.testcontainers:junit-jupiter`, `com.squareup.okhttp3:mockwebserver` или embedded WebSocket test server.

Не добавлять Lombok в v1: Java records и явные классы уменьшают зависимость от annotation processing.

### 5.2. `application.yml`

Группы настроек:

- `spring.datasource.url`, `username`, `password`;
- `spring.flyway.enabled=true`;
- `server.port`;
- `management.endpoints.web.exposure.include=health,info,metrics,prometheus`;
- `truconf.http-base-url`;
- `truconf.ws-url`;
- `truconf.username`;
- `truconf.password`;
- `truconf.proxy-api-key`;
- `truconf.file-storage-dir`;
- `truconf.dispatcher.batch-size`;
- `truconf.dispatcher.poll-interval`;
- `truconf.dispatcher.lock-timeout`;
- `truconf.dispatcher.worker-threads`;
- `truconf.retry.max-attempts`;
- `truconf.retry.initial-delay`;
- `truconf.retry.max-delay`;
- `truconf.retry.multiplier`;
- `truconf.websocket.request-timeout`;
- `truconf.websocket.connect-timeout`;
- `truconf.websocket.reconnect-delay`;

Все secrets задавать через environment variables. В `application.yml` оставить только `${ENV:default}` placeholders без реальных credential-ов.

### 5.3. Docker и Docker Compose для локальной среды

Добавить минимальную локальную среду, которая поднимает PostgreSQL и сам сервис одной командой.

Файлы:

- `Dockerfile` - production-like image сервиса;
- `docker-compose.yml` - локальная среда `postgres + app`;
- `.dockerignore` - исключить `target/`, `.git/`, IDE-файлы, временные файлы и локальные uploads;
- `.env.example` - документированный шаблон переменных без реальных secrets.

`Dockerfile`:

- использовать multi-stage build;
- build stage: Maven + Eclipse Temurin JDK 21;
- runtime stage: Eclipse Temurin JRE 21 или аналогичный минимальный Java 21 runtime;
- собирать jar через `mvn -DskipTests package`;
- запускать `java -jar /app/app.jar`;
- не копировать локальный `target/` из рабочей директории;
- предусмотреть non-root user, если базовый образ позволяет без лишней сложности.

`docker-compose.yml`:

- сервис `postgres`:
  - image `postgres:17` или актуальный стабильный major, зафиксированный явно;
  - env: `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`;
  - volume `postgres-data:/var/lib/postgresql/data`;
  - healthcheck через `pg_isready`;
  - порт `5432:5432` для локального direct insert/debug.
- сервис `app`:
  - `build: .`;
  - `depends_on.postgres.condition: service_healthy`;
  - порт `8080:8080`;
  - env datasource на `jdbc:postgresql://postgres:5432/${POSTGRES_DB}`;
  - env для TrueConf URL/credentials/API key/retry/dispatcher/file storage;
  - volume `app-files:/var/lib/truconf-proxydb/files`;
  - healthcheck через `GET /actuator/health`;
  - `restart: unless-stopped` только если это не мешает локальной отладке.
- volumes: `postgres-data`, `app-files`;
- отдельная bridge network, если это требуется для читаемости compose.

`.env.example` должен содержать:

```dotenv
POSTGRES_DB=truconf_proxydb
POSTGRES_USER=truconf_proxydb
POSTGRES_PASSWORD=change-me
SERVER_PORT=8080
TRUCONF_HTTP_BASE_URL=https://trueconf.example.local
TRUCONF_WS_URL=wss://trueconf.example.local/websocket/chat_bot/
TRUCONF_USERNAME=bot-user
TRUCONF_PASSWORD=change-me
TRUCONF_PROXY_API_KEY=change-me
TRUCONF_FILE_STORAGE_DIR=/var/lib/truconf-proxydb/files
```

Требования к запуску:

- `README.md` объясняет локальный шаг `cp .env.example .env` и замену placeholder secrets;
- `docker compose --env-file .env up --build` поднимает БД и сервис;
- Flyway миграции применяются автоматически при старте `app`;
- `curl http://localhost:8080/actuator/health` возвращает healthy/readiness состояние после старта;
- direct insert в PostgreSQL доступен через published port `5432`;
- реальные credentials не коммитятся, в репозитории остается только `.env.example`.

## 6. База данных

### 6.1. Enum-ы на уровне CHECK constraints

Использовать `text` + `CHECK`, а не PostgreSQL enum, чтобы проще добавлять значения миграциями.

`operation`:

- `SEND_MESSAGE`;
- `SEND_FILE`;
- `SEND_SURVEY`;
- `EDIT_MESSAGE`;
- `EDIT_SURVEY`;
- `REMOVE_MESSAGE`;
- `FORWARD_MESSAGE`.

`recipient_kind`:

- `CHAT`;
- `USER`.

`status`:

- `NEW`;
- `PROCESSING`;
- `RETRY_WAIT`;
- `SENT`;
- `FAILED`.

`storage_kind`:

- `DISK`;
- `DB`.

### 6.2. `truconf_outbox`

Поля:

- `id bigserial primary key`;
- `external_id text null`;
- `operation text not null`;
- `recipient_kind text not null`;
- `chat_id text null`;
- `user_id text null`;
- `target_message_id text null`;
- `reply_message_id text null`;
- `payload_json jsonb not null default '{}'::jsonb`;
- `status text not null default 'NEW'`;
- `attempt_count int not null default 0`;
- `max_attempts int not null default 10`;
- `next_attempt_at timestamptz not null default now()`;
- `locked_by text null`;
- `locked_until timestamptz null`;
- `trueconf_chat_id text null`;
- `trueconf_message_id text null`;
- `trueconf_file_id text null`;
- `trueconf_timestamp bigint null`;
- `last_error_code text null`;
- `last_error_message text null`;
- `last_error_retryable boolean null`;
- `last_response_json jsonb null`;
- `created_at timestamptz not null default now()`;
- `updated_at timestamptz not null default now()`;
- `sent_at timestamptz null`;
- `failed_at timestamptz null`.

Constraints:

- `external_id` unique where not null;
- для `recipient_kind='CHAT'` нужен `chat_id`;
- для `recipient_kind='USER'` нужен `user_id`;
- `attempt_count >= 0`;
- `max_attempts > 0`;
- `payload_json` object.

Индексы:

- partial index на готовые к claim задания:
  `status in ('NEW','RETRY_WAIT') and next_attempt_at <= now()` нельзя сделать напрямую из-за `now()`, поэтому индексировать `(status, next_attempt_at, id)`;
- partial index stale locks: `(locked_until) where status='PROCESSING'`;
- index `external_id`;
- index `trueconf_message_id`;
- index `(created_at, id)`.

### 6.3. `truconf_outbox_file`

Поля:

- `id bigserial primary key`;
- `outbox_id bigint not null references truconf_outbox(id) on delete cascade`;
- `file_name text not null`;
- `mime_type text null`;
- `size_bytes bigint not null`;
- `storage_kind text not null`;
- `file_path text null`;
- `file_data bytea null`;
- `preview_file_name text null`;
- `preview_mime_type text null`;
- `preview_size_bytes bigint null`;
- `preview_file_path text null`;
- `preview_file_data bytea null`;
- `created_at timestamptz not null default now()`.

Constraints:

- `size_bytes >= 0`;
- для `DISK` нужен `file_path`;
- для `DB` нужен `file_data`;
- один основной файл на outbox: unique `(outbox_id)`.

### 6.4. `truconf_p2p_chat_cache`

Поля:

- `user_id text primary key`;
- `chat_id text not null`;
- `created_at timestamptz not null default now()`;
- `updated_at timestamptz not null default now()`;
- `last_used_at timestamptz not null default now()`.

Индекс:

- `(last_used_at)`.

### 6.5. Triggers

`updated_at` trigger для всех таблиц с `updated_at`.

Notify trigger:

```sql
perform pg_notify('truconf_outbox_new', NEW.id::text);
```

Условие: notify только при insert в `truconf_outbox` со статусом `NEW`.

## 7. Outbox state machine

Разрешенные переходы:

- `NEW -> PROCESSING`;
- `RETRY_WAIT -> PROCESSING`;
- `PROCESSING -> SENT`;
- `PROCESSING -> RETRY_WAIT`;
- `PROCESSING -> FAILED`;
- `PROCESSING -> NEW` только при recovery stale lock;
- `NEW -> FAILED` для явной валидации direct insert, если будет отдельный validator job.

Claim query:

```sql
with candidate as (
  select id
  from truconf_outbox
  where status in ('NEW', 'RETRY_WAIT')
    and next_attempt_at <= now()
  order by next_attempt_at, id
  for update skip locked
  limit ?
)
update truconf_outbox o
set status = 'PROCESSING',
    locked_by = ?,
    locked_until = now() + (?::interval),
    attempt_count = attempt_count + 1,
    updated_at = now()
from candidate
where o.id = candidate.id
returning o.*;
```

Stale recovery:

```sql
update truconf_outbox
set status = 'RETRY_WAIT',
    locked_by = null,
    locked_until = null,
    next_attempt_at = now(),
    updated_at = now(),
    last_error_code = 'LOCK_EXPIRED',
    last_error_message = 'Processing lock expired before completion',
    last_error_retryable = true
where status = 'PROCESSING'
  and locked_until < now();
```

Условие завершения:

- `SENT` фиксирует TrueConf response fields и `sent_at`;
- `FAILED` фиксирует terminal error и `failed_at`;
- `RETRY_WAIT` фиксирует error, очищает lock, рассчитывает `next_attempt_at`.

## 8. Retry policy и классификация ошибок

Retryable:

- network connect timeout/read timeout;
- WebSocket disconnected before response;
- OAuth endpoint временно недоступен;
- HTTP 5xx от TrueConf Bridge;
- TrueConf transient/rate-limit/server error, если код явно не terminal;
- `auth` failure из-за token expired после успешного refresh.

Terminal:

- invalid request payload;
- отсутствующий `chat_id`/`user_id`;
- файл не найден на диске;
- file size/mime не проходит локальные ограничения;
- TrueConf permission denied;
- unknown chat/message/user, если retry не может изменить состояние;
- survey payload missing required fields;
- превышен `max_attempts`.

Backoff:

- exponential backoff: `initial-delay * multiplier^(attempt_count - 1)`;
- cap `max-delay`;
- добавить небольшой jitter, чтобы несколько инстансов не просыпались одновременно;
- `max_attempts` хранить на уровне задания, default брать из config.

## 9. HTTP API

### 9.1. Security

Все endpoints, кроме health, требуют header:

```http
X-API-Key: <configured key>
```

Открыть без ключа:

- `GET /actuator/health`;
- опционально `GET /actuator/health/liveness`;
- опционально `GET /actuator/health/readiness`.

Реализация:

- `SecurityFilterChain`;
- custom `OncePerRequestFilter`;
- constant-time comparison для API key;
- 401 без деталей при неверном ключе;
- 403 не нужен для v1, если нет ролей.

### 9.2. `POST /api/v1/outbox`

Назначение: создать JSON-задание.

Request shape:

```json
{
  "externalId": "crm-123",
  "operation": "SEND_MESSAGE",
  "recipient": {
    "kind": "USER",
    "userId": "user@example.com"
  },
  "targetMessageId": null,
  "replyMessageId": null,
  "payload": {
    "text": "Hello",
    "parseMode": "text"
  },
  "file": null,
  "maxAttempts": 10
}
```

Response `201`:

```json
{
  "id": 1,
  "externalId": "crm-123",
  "status": "NEW"
}
```

Idempotency:

- если `externalId` уже существует, вернуть `200` с существующим job status;
- если `externalId` не передан, каждый запрос создает новое задание.

Валидация:

- `operation` обязателен;
- `recipient.kind` обязателен;
- `CHAT` требует `chatId`;
- `USER` требует `userId`;
- `SEND_MESSAGE` требует `payload.text`;
- `SEND_FILE` через JSON требует `file.storageKind` и file reference/data;
- `SEND_SURVEY` требует survey fields;
- `EDIT_*` и `REMOVE_MESSAGE` требуют `targetMessageId`;
- `FORWARD_MESSAGE` требует `targetMessageId` и target recipient.

### 9.3. `POST /api/v1/outbox/files`

Назначение: принять multipart upload, сохранить файл на диск, создать `SEND_FILE`.

Multipart parts:

- `request` - JSON с `externalId`, `recipient`, optional `caption`, `parseMode`, `replyMessageId`, `maxAttempts`;
- `file` - обязательный файл;
- `preview` - опционально.

Response `201` как для JSON endpoint.

Файлы хранить:

```text
${truconf.file-storage-dir}/yyyy/MM/dd/<outbox-id>/<safe-file-name>
```

Имя файла:

- нормализовать path traversal;
- сохранить исходное имя в БД;
- физическое имя можно делать `<uuid>_<sanitized-original-name>`.

### 9.4. `GET /api/v1/outbox/{id}`

Response:

```json
{
  "id": 1,
  "externalId": "crm-123",
  "operation": "SEND_MESSAGE",
  "status": "SENT",
  "attemptCount": 1,
  "maxAttempts": 10,
  "nextAttemptAt": null,
  "trueconf": {
    "chatId": "...",
    "messageId": "...",
    "fileId": null,
    "timestamp": 1735134222098
  },
  "error": null,
  "createdAt": "...",
  "updatedAt": "...",
  "sentAt": "..."
}
```

404 для неизвестного id.

## 10. Direct insert contract

Документировать минимальный insert:

```sql
insert into truconf_outbox (
  external_id,
  operation,
  recipient_kind,
  user_id,
  payload_json
) values (
  'crm-123',
  'SEND_MESSAGE',
  'USER',
  'user@example.com',
  '{"text":"Hello","parseMode":"text"}'::jsonb
);
```

Требования к прямым insert-ам:

- `status` можно не задавать, default `NEW`;
- `next_attempt_at` можно не задавать, default `now()`;
- file direct insert должен создавать `truconf_outbox_file` в той же транзакции;
- для `storage_kind='DB'` допустимо хранить малые файлы в `bytea`;
- для `storage_kind='DISK'` файл должен быть доступен сервису по `file_path`;
- валидация direct insert-ов частично выполняется DB constraints, частично при processing.

## 11. TrueConf client

### 11.1. OAuth token

`TrueConfTokenService`:

- использует `RestClient`;
- получает token через `/bridge/api/client/v1/oauth/token`;
- хранит token, expiry и refresh margin;
- защищает refresh lock-ом, чтобы несколько worker-ов не обновляли token одновременно;
- при auth/token error инвалидирует token и повторяет один refresh.

### 11.2. WebSocket session lifecycle

`TrueConfSession`:

- открывает WebSocket на `truconf.ws-url`;
- указывает subprotocol `json.v1`, если client API это поддерживает;
- после connect отправляет `auth`;
- хранит `AtomicLong requestId`;
- хранит `ConcurrentHashMap<Long, PendingRequest>`;
- pending request содержит `CompletableFuture<JsonNode>`, method, deadline;
- при ответе `type=2` завершает future по `id`;
- при server request `type=1` без pending request отправляет ACK `{"type":2,"id":...}`;
- при close/error завершает pending request-и retryable exception-ом;
- reconnect выполняется с backoff.

Ограничения:

- request id монотонный в рамках подключения;
- после reconnect допустимо начать с 1, если protocol не требует глобальной уникальности;
- command timeout должен быть меньше outbox lock timeout.

### 11.3. Command factory

Создать методы:

- `auth(token, userIdOrBotId)`;
- `createP2PChat(userId)`;
- `sendMessage(chatId, text, parseMode, replyMessageId)`;
- `uploadFile(fileName, fileSize)`;
- `sendFile(chatId, temporalFileId, caption, parseMode, replyMessageId)`;
- `sendSurvey(chatId, surveyPayload, replyMessageId)`;
- `editMessage(messageId, textPayload)`;
- `editSurvey(messageId, surveyPayload)`;
- `removeMessage(messageId)`;
- `forwardMessage(chatId, messageId)`.

Все builders должны возвращать JSON через Jackson ObjectNode/records, а не ручную конкатенацию строк.

### 11.4. Response mapper

Маппить успешные payload fields:

- `chatId`;
- `messageId`;
- `fileId`;
- `timestamp`.

Ошибки:

- поддержать варианты `errorCode`, `error`, `error_description`, `payload.errorCode`;
- сохранять raw response в `last_response_json`;
- классификацию делать в `TrueConfErrorClassifier`.

## 12. File sending flow

Для `SEND_FILE`:

1. Получить outbox file metadata и InputStream.
2. Убедиться, что session authenticated.
3. Отправить WebSocket command `uploadFile` с `fileSize`, `fileName`.
4. Из ответа получить `uploadTaskId`.
5. Выполнить HTTP multipart `POST /bridge/api/client/v1/files`:
   - header `Upload-Task-Id`;
   - part `file`;
   - optional part `preview`;
   - OAuth/Bridge auth headers по документации TrueConf, если требуются конкретной инсталляцией.
6. Из HTTP response получить `temporalFileId`.
7. Отправить WebSocket command `sendFile` с `temporalFileId`.
8. Сохранить `messageId`, `fileId`, `chatId`, `timestamp`.

Если HTTP upload успешен, а `sendFile` упал до ответа, retry может повторно загрузить файл и отправить его еще раз. Это ожидаемое at-least-once поведение v1.

## 13. P2P chat cache

`P2pChatResolver`:

- если задание содержит `chat_id`, использовать его напрямую;
- если `recipient_kind='USER'`, сначала искать `truconf_p2p_chat_cache`;
- при miss вызывать `createP2PChat(userId)`;
- сохранить/обновить cache запись;
- обновлять `last_used_at` при каждом использовании.

Concurrency:

- при одновременном miss для одного user возможны параллельные `createP2PChat`;
- TrueConf возвращает существующий чат, поэтому это допустимо;
- upsert cache по `user_id`.

## 14. Dispatcher

Компоненты:

- `PostgresNotifyListener` держит отдельное JDBC connection для LISTEN `truconf_outbox_new`;
- `OutboxDispatcher` принимает notify signal в bounded queue или просто будит polling loop;
- fallback polling работает независимо от notify;
- worker pool обрабатывает claim-нутый batch.

Loop:

1. На старте выполнить stale recovery.
2. Запустить LISTEN thread.
3. Каждые `poll-interval`:
   - выполнить stale recovery;
   - claim batch;
   - отправить jobs в executor.
4. При notify:
   - не обрабатывать конкретный id напрямую;
   - просто ускорить claim batch, чтобы не зависеть от порядка и потерь notify.

Shutdown:

- остановить intake;
- дождаться активных jobs до configured timeout;
- pending jobs, которые не успели завершиться, останутся `PROCESSING` и вернутся через stale recovery.

## 15. Service-level validation

Валидация перед сохранением HTTP requests:

- Bean Validation annotations на DTO;
- ручная operation-specific validation;
- max file size до записи на диск;
- допустимые `parseMode`: начать с `text`, `html`, `markdown`, если TrueConf docs и инсталляция подтверждают значения;
- survey `description` только `{{Survey}}` или `{{Anonymous survey}}`;
- `secret` для survey разрешить передавать явно; опционально генерировать, если не передан.

Валидация при processing direct insert:

- отсутствует payload field - terminal `FAILED`;
- отсутствует файл - terminal `FAILED`;
- JSON не object - DB constraint или terminal `FAILED`;
- неизвестный `operation` - DB constraint.

## 16. Observability

Логи:

- все логи с `outboxId`, `externalId`, `operation`, `attempt`, `workerId`;
- не логировать API key, password, token;
- raw TrueConf response логировать на debug, sanitized.

Metrics:

- `truconf_outbox_claimed_total`;
- `truconf_outbox_sent_total`;
- `truconf_outbox_failed_total`;
- `truconf_outbox_retry_total`;
- `truconf_outbox_processing_seconds`;
- `truconf_ws_reconnect_total`;
- `truconf_ws_pending_requests`;
- `truconf_file_upload_seconds`;
- `truconf_p2p_cache_hit_total`;
- `truconf_p2p_cache_miss_total`.

Health:

- readiness включает datasource и базовую способность claim query;
- TrueConf health лучше сделать отдельным indicator `UNKNOWN`/`DOWN`, но не блокировать liveness;
- liveness не зависит от TrueConf, чтобы orchestrator не перезапускал сервис из-за внешней недоступности.

## 17. Тестовая стратегия

### 17.1. Unit tests

Покрыть:

- JSON builders для всех TrueConf commands;
- response mapper success/error;
- error classifier;
- retry backoff с cap и jitter boundaries;
- P2P cache resolver happy path/cache miss/cache hit;
- file name sanitization.

### 17.2. JDBC + Testcontainers PostgreSQL

Покрыть:

- Flyway migration applies cleanly;
- DB constraints по recipient/file/status/operation;
- direct insert создает notify;
- claim query не берет один job двумя transactions;
- stale lock recovery;
- status transitions;
- unique `external_id`;
- file `DISK` и `DB` modes.

### 17.3. WebMvc tests

Покрыть:

- health без API key;
- protected endpoints без key - 401;
- wrong key - 401;
- valid key - доступ;
- JSON enqueue;
- duplicate `externalId`;
- validation errors;
- multipart upload;
- get status 200/404.

### 17.4. Fake TrueConf integration tests

Минимальный fake server:

- HTTP OAuth token endpoint;
- HTTP file upload endpoint;
- WebSocket endpoint `/websocket/chat_bot/`;
- поддержка `auth`, `createP2PChat`, `uploadFile`, `sendMessage`, `sendFile`, `sendSurvey`, `editMessage`, `editSurvey`, `removeMessage`, `forwardMessage`;
- сценарии timeout, close, token expired, malformed error, transient 5xx upload.

Покрыть:

- успешную отправку текста в chat;
- успешную отправку текста в user через P2P resolve;
- успешную отправку файла;
- успешный survey flow;
- retry после disconnect;
- token refresh после auth failure;
- terminal error переводит job в `FAILED`;
- server notification получает ACK.

### 17.5. Build gates

Минимум перед завершением:

```bash
mvn test
```

Желательно:

```bash
mvn -q test
mvn spring-boot:run
curl /actuator/health
docker compose --env-file .env config
docker compose --env-file .env up --build -d
curl http://localhost:8080/actuator/health
docker compose --env-file .env down
```

## 18. Порядок реализации

### Этап 0. Bootstrap проекта

Результат:

- Maven project собирается;
- Spring Boot app стартует;
- health endpoint отвечает.

Задачи:

1. Создать `pom.xml`.
2. Создать `TruconfProxyDbApplication`.
3. Создать `application.yml`.
4. Создать config properties с `@ConfigurationProperties`.
5. Подключить actuator.
6. Добавить smoke test контекста.

Критерий готовности:

- `mvn test` проходит;
- `GET /actuator/health` доступен без API key после старта.

### Этап 1. Миграции и доменная модель

Результат:

- PostgreSQL schema создана Flyway миграцией;
- domain records/enums готовы;
- Testcontainers подтверждает миграции.

Задачи:

1. Написать `V1__init.sql`.
2. Описать Java enums.
3. Описать `OutboxJob`, `OutboxFile`, `P2pChatCacheEntry`.
4. Написать row mappers.
5. Написать первые migration tests.

Критерий готовности:

- миграции применяются в чистый PostgreSQL;
- constraints ловят некорректные rows;
- direct insert minimal example проходит.

### Этап 2. Repository и state machine

Результат:

- outbox можно создавать, читать, claim-ить, завершать, retry-ить.

Задачи:

1. Реализовать `OutboxRepository.create`.
2. Реализовать lookup по id/externalId.
3. Реализовать claim batch.
4. Реализовать `markSent`.
5. Реализовать `markRetry`.
6. Реализовать `markFailed`.
7. Реализовать stale recovery.
8. Покрыть concurrency тестами с двумя transactions.

Критерий готовности:

- один job не claim-ится одновременно двумя worker-ами;
- retry считает `next_attempt_at`;
- stale lock возвращается в обработку.

### Этап 3. HTTP API и security

Результат:

- задания можно создавать и читать через HTTP;
- API-key защита работает.

Задачи:

1. Реализовать `SecurityConfig` и API-key filter.
2. Реализовать DTO и validation.
3. Реализовать `OutboxService.enqueue`.
4. Реализовать `OutboxController`.
5. Реализовать `ApiExceptionHandler`.
6. Реализовать idempotency по `externalId`.
7. Покрыть WebMvc tests.

Критерий готовности:

- protected endpoints без ключа недоступны;
- валидные JSON jobs сохраняются;
- duplicate `externalId` не создает дубль;
- status endpoint возвращает нужный contract.

### Этап 4. File storage и multipart endpoint

Результат:

- multipart endpoint сохраняет файл на диск и создает `SEND_FILE`.

Задачи:

1. Реализовать `FileStorageService`.
2. Реализовать safe path/safe file name.
3. Добавить настройку max upload size.
4. Реализовать multipart controller branch.
5. Сохранять `truconf_outbox_file`.
6. Покрыть tests на path traversal, missing file, preview.

Критерий готовности:

- файл физически сохраняется в configured dir;
- БД содержит ссылку;
- некорректное имя файла не выходит за storage dir.

### Этап 5. TrueConf JSON protocol

Результат:

- command builders и response mapper полностью протестированы без сети.

Задачи:

1. Описать request/response records или Jackson factories.
2. Реализовать builders для всех commands.
3. Реализовать response mapper.
4. Реализовать error classifier.
5. Написать unit tests по fixture JSON.

Критерий готовности:

- snapshot/JSONAssert тесты подтверждают форму команд;
- mapper извлекает ids/timestamp/errors.

### Этап 6. OAuth и WebSocket session

Результат:

- клиент умеет получить token, подключиться к fake WebSocket, выполнить auth и отправить команду.

Задачи:

1. Реализовать `TrueConfTokenService`.
2. Реализовать `TrueConfSession`.
3. Реализовать pending requests map.
4. Реализовать timeout handling.
5. Реализовать reconnect и completion pending futures retryable error-ом.
6. Реализовать ACK server requests.
7. Покрыть fake server тестами.

Критерий готовности:

- `auth` выполняется после connect;
- response correlates by id;
- server request получает ACK;
- disconnect приводит к retryable exception.

### Этап 7. Command executor

Результат:

- outbox job превращается в TrueConf command flow.

Задачи:

1. Реализовать `P2pChatResolver`.
2. Реализовать `OutboxJobExecutor`.
3. Поддержать `SEND_MESSAGE`.
4. Поддержать `SEND_FILE` с трехшаговым upload flow.
5. Поддержать `SEND_SURVEY`.
6. Поддержать edit/remove/forward.
7. Сохранять success/error в repository.
8. Покрыть fake TrueConf integration tests.

Критерий готовности:

- все operation values имеют обработчик;
- success переводит job в `SENT`;
- retryable errors переводят в `RETRY_WAIT`;
- terminal errors переводят в `FAILED`.

### Этап 8. Dispatcher LISTEN/NOTIFY + polling

Результат:

- сервис автоматически обрабатывает новые задания из БД.

Задачи:

1. Реализовать dedicated LISTEN connection.
2. Реализовать polling loop.
3. Связать notify signal с ускоренным claim.
4. Добавить worker pool.
5. Реализовать graceful shutdown.
6. Добавить metrics.
7. Покрыть integration tests direct insert -> processing.

Критерий готовности:

- direct insert без HTTP подхватывается;
- потеря notify компенсируется polling;
- несколько worker threads не дублируют claim.

### Этап 9. Hardening и документация эксплуатации

Результат:

- сервис готов к локальному запуску и базовой эксплуатации.

Задачи:

1. Добавить `README.md` с env vars и examples.
2. Добавить SQL examples для direct insert.
3. Добавить `Dockerfile` для сборки runtime image сервиса.
4. Добавить `docker-compose.yml` для локального запуска PostgreSQL и сервиса.
5. Добавить `.dockerignore`.
6. Добавить `.env.example` с полным набором локальных переменных без реальных secrets.
7. Описать в `README.md` команды `docker compose --env-file .env up --build`, проверку health endpoint и пример direct insert.
8. Добавить логирование без secrets.
9. Проверить actuator health/readiness.
10. Проверить remote config security note.
11. Прогнать полный `mvn test`.
12. Проверить `docker compose config` и smoke-запуск локальной среды.

Критерий готовности:

- новый разработчик может поднять сервис локально по README;
- `docker compose --env-file .env up --build` поднимает PostgreSQL и сервис;
- Flyway миграции применяются внутри compose-среды;
- health endpoint сервиса отвечает через published port;
- тесты проходят;
- known limitations documented.

## 19. Риски и решения

### Риск: Boot 4 / Spring 7 API changes

Митигация:

- использовать официальные starters Boot 4;
- держать код на `jakarta.*`;
- начать с минимального context load test;
- не копировать Spring Boot 3 examples без проверки imports.

### Риск: TrueConf docs и реальная инсталляция расходятся

Митигация:

- fake server строить по документации;
- вынести protocol details в `TrueConfCommandFactory`;
- сохранить raw response в БД;
- сделать error mapper tolerant к разным error field names;
- на первом реальном стенде прогнать smoke commands: auth, createP2PChat, sendMessage, uploadFile/sendFile.

### Риск: повторная отправка при crash

Митигация:

- явно документировать at-least-once;
- использовать `externalId` только для enqueue idempotency;
- сохранять response immediately after success;
- держать command timeout меньше lock timeout.

### Риск: прямые insert-ы обходят API validation

Митигация:

- максимально выразить contract через DB constraints;
- при processing terminal fail с понятным error;
- дать SQL examples и payload schema в README.

### Риск: файл на диске исчез между enqueue и processing

Митигация:

- HTTP uploads сохранять в managed storage dir;
- direct insert DISK считать ответственностью внешней системы;
- при отсутствии файла terminal `FAILED`;
- опционально добавить cleanup policy только после `SENT/FAILED` и retention period.

## 20. Definition of Done

Проект считается реализованным для v1, когда:

- `mvn test` проходит на чистой checkout среде;
- Flyway создает все таблицы и triggers;
- JSON API и multipart API защищены `X-API-Key`;
- direct insert в `truconf_outbox` автоматически обрабатывается;
- fake TrueConf tests покрывают все операции;
- retry/backoff и stale lock recovery работают;
- token refresh/reconnect не теряют задания;
- README содержит env vars, запуск, API examples, direct insert examples и known limitations;
- в репозитории нет secrets или embedded credentials.
