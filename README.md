# TruconfProxyDB

Standalone Java 21 / Spring Boot service for reliable delivery of outgoing
jobs to TrueConf Chatbot Connector. Jobs can be enqueued through HTTP API or by
direct PostgreSQL inserts into the outbox table.

The service provides at-least-once delivery. If the process stops after a
TrueConf command succeeds but before PostgreSQL is updated, the same job can be
sent again.

## Local Docker Run

Create a local env file from the example and replace all `change-me` values:

```bash
cp .env.example .env
```

Start PostgreSQL and the app:

```bash
docker compose --env-file .env up --build
```

If local port `8080` is already used, set another `SERVER_PORT` in `.env` or
override it for the command:

```bash
SERVER_PORT=18080 docker compose --env-file .env up --build
```

Check the app:

```bash
curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8080/actuator/health/readiness
```

For direct insert/debug access:

```bash
psql "postgresql://truconf_proxydb:change-me@localhost:5432/truconf_proxydb"
```

Stop the local environment:

```bash
docker compose --env-file .env down
```

Add `-v` to also delete local PostgreSQL and file-storage volumes.

## Configuration

All secrets must come from environment variables, container secrets, or a
runtime secret manager. Do not commit `.env`, real API keys, TrueConf passwords,
OAuth tokens, or database passwords.

| Variable | Default/example | Purpose |
| --- | --- | --- |
| `POSTGRES_DB` | `truconf_proxydb` | Compose PostgreSQL database name. |
| `POSTGRES_USER` | `truconf_proxydb` | Compose PostgreSQL user. |
| `POSTGRES_PASSWORD` | `change-me` | Compose PostgreSQL password. |
| `SERVER_PORT` | `8080` | HTTP port inside the app container and on the host. |
| `SPRING_DATASOURCE_URL` | composed in `docker-compose.yml` | JDBC URL. |
| `SPRING_DATASOURCE_USERNAME` | `${POSTGRES_USER}` | JDBC username. |
| `SPRING_DATASOURCE_PASSWORD` | `${POSTGRES_PASSWORD}` | JDBC password. |
| `SPRING_FLYWAY_ENABLED` | `true` | Enables DB migrations on startup. |
| `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE` | `100MB` | Max uploaded file size. |
| `SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE` | `110MB` | Max multipart request size. |
| `TRUCONF_HTTP_BASE_URL` | `https://trueconf.example.local` | TrueConf HTTP base URL. |
| `TRUCONF_WS_URL` | `wss://trueconf.example.local/websocket/chat_bot/` | TrueConf bot WebSocket URL. |
| `TRUCONF_USERNAME` | `bot-user` | TrueConf bot username. |
| `TRUCONF_PASSWORD` | `change-me` | TrueConf bot password. |
| `TRUCONF_PROXY_API_KEY` | `change-me` | Required `X-API-Key` value for `/api/v1/**`. |
| `TRUCONF_FILE_STORAGE_DIR` | `/var/lib/truconf-proxydb/files` | Root directory for stored files. |
| `TRUCONF_DISPATCHER_ENABLED` | `true` | Enables background outbox processing. |
| `TRUCONF_DISPATCHER_BATCH_SIZE` | `50` | Max jobs claimed per dispatcher tick. |
| `TRUCONF_DISPATCHER_POLL_INTERVAL` | `5s` | Fallback polling interval. |
| `TRUCONF_DISPATCHER_LOCK_TIMEOUT` | `2m` | Processing lock timeout. |
| `TRUCONF_DISPATCHER_WORKER_THREADS` | `4` | Worker pool size. |
| `TRUCONF_RETRY_MAX_ATTEMPTS` | `10` | Default max attempts for new jobs. |
| `TRUCONF_RETRY_INITIAL_DELAY` | `5s` | Initial retry delay. |
| `TRUCONF_RETRY_MAX_DELAY` | `5m` | Retry delay cap. |
| `TRUCONF_RETRY_MULTIPLIER` | `2.0` | Exponential backoff multiplier. |
| `TRUCONF_WEBSOCKET_REQUEST_TIMEOUT` | `30s` | TrueConf command timeout. |
| `TRUCONF_WEBSOCKET_CONNECT_TIMEOUT` | `10s` | WebSocket connect timeout. |
| `TRUCONF_WEBSOCKET_RECONNECT_DELAY` | `5s` | Reconnect delay after session failure. |

Keep `TRUCONF_WEBSOCKET_REQUEST_TIMEOUT` below
`TRUCONF_DISPATCHER_LOCK_TIMEOUT`, otherwise a worker can outlive its DB lock.

## HTTP API

All `/api/v1/**` requests require `X-API-Key`. Health endpoints are public:

```bash
export API_KEY=change-me
```

Enqueue a text message:

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

Enqueue a P2P text message. The service resolves `userId` to `chatId` through
`createP2PChat` and caches the result in PostgreSQL:

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

Upload and send a file:

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

Get status:

```bash
curl -s http://localhost:8080/api/v1/outbox/1 \
  -H "X-API-Key: ${API_KEY}"

curl -s http://localhost:8080/api/v1/outbox/by-external-id/demo-message-1 \
  -H "X-API-Key: ${API_KEY}"
```

Operations:

| Operation | Required fields |
| --- | --- |
| `SEND_MESSAGE` | `recipient`, `payload.text` |
| `SEND_FILE` | Use `POST /api/v1/outbox/files` or insert a file row directly. |
| `SEND_SURVEY` | `recipient`, survey payload fields listed below. |
| `EDIT_MESSAGE` | `targetMessageId`, `payload.text` |
| `EDIT_SURVEY` | `targetMessageId`, survey payload fields listed below. |
| `REMOVE_MESSAGE` | `targetMessageId`; optional `payload.forAll`, default `true`. |
| `FORWARD_MESSAGE` | `recipient`, `targetMessageId` |

Survey payload fields: `url`, `appVersion`, `path`, `title`, `description`,
`buttonText`, `secret`, `alt`.

`externalId` is optional. When present, it is unique and makes enqueue
idempotent: repeated HTTP calls return the existing outbox job.

## Direct Insert Examples

The migration installs a trigger that sends `pg_notify('truconf_outbox_new',
id)` when a `NEW` job is inserted. If a notification is missed, dispatcher
polling still claims ready jobs.

Insert a chat message:

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

Insert a P2P message:

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

Insert a file job using a file already present under
`TRUCONF_FILE_STORAGE_DIR`. The path must stay inside the configured storage
root, otherwise processing fails terminally.

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

Insert a remove-message job:

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

Check processing result:

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

## Security Notes

- Do not expose `/api/v1/**` to untrusted networks without an additional edge
  control such as mTLS, VPN, reverse-proxy allow lists, or WAF rules.
- Rotate `TRUCONF_PROXY_API_KEY`, `TRUCONF_PASSWORD`, and database passwords
  when they are shared outside the runtime environment.
- Remote configuration systems must store secrets encrypted and must not print
  resolved property values in logs or diagnostics.
- Application logs should contain job ids and operational errors only. Do not
  add request/response logging that prints `X-API-Key`, OAuth tokens, TrueConf
  passwords, `Authorization` headers, or survey `secret` payload values.

## Known Limitations

- Delivery is at-least-once, not exactly-once.
- `externalId` prevents duplicate enqueue only before processing starts; it
  does not make TrueConf delivery exactly-once.
- Direct inserts are only partly protected by database constraints. Payload
  details are validated during processing and invalid jobs can become `FAILED`.
- Direct `SEND_FILE` with `storage_kind='DISK'` requires an existing file under
  `TRUCONF_FILE_STORAGE_DIR`.
- No inbound TrueConf message workflow, UI/admin panel, or distributed leader
  election is included in v1.
- Retry backoff is deterministic exponential backoff without jitter.

## Development

Run tests:

```bash
mvn test
```

Run locally without Docker Compose, assuming PostgreSQL is already available:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/truconf_proxydb \
SPRING_DATASOURCE_USERNAME=truconf_proxydb \
SPRING_DATASOURCE_PASSWORD=change-me \
TRUCONF_PROXY_API_KEY=change-me \
mvn spring-boot:run
```

Validate the Compose file:

```bash
docker compose --env-file .env config
```
