# TruConfProxyDB: Java 21 / Spring Boot 4.0.5 сервис очереди TrueConf

## docs https://trueconf.ru/docs/chatbot-connector/ru/overview/
 - есть api через py https://github.com/trueconf/python-trueconf-bot/

## Summary

- Создать новый standalone Maven-проект в `/home/coder/workspace/TruconfProxyDB` на Java 21 и `org.springframework.boot:spring-boot-starter-parent:4.0.5`.
- Boot 4.0.5 по официальным docs требует Spring Framework 7.0.6+ и Java 17+, поэтому код сразу писать на `jakarta.*`, без `javax.*`.
- Сервис хранит исходящие задания в PostgreSQL, принимает задания через HTTP API с `X-API-Key` и поддерживает прямые insert-ы в БД.
- TrueConf отправка идет через Chatbot Connector: OAuth token, WebSocket `/websocket/chat_bot/`, `auth`, затем команды `sendMessage`, `sendFile`, `sendSurvey`, `editMessage`, `editSurvey`, `removeMessage`, `forwardMessage`.
- Для `userId` сервис сначала выполняет `createP2PChat`, кеширует `userId -> chatId`, затем отправляет сообщение.

## Key Changes

- Проект:
  - `pom.xml` с Java `21`, Spring Boot `4.0.5`, `spring-boot-starter-webmvc`, `spring-boot-starter-websocket`, JDBC, Security, Validation, Actuator, Flyway, PostgreSQL driver, Jackson.
  - `application.yml` через env: `SPRING_DATASOURCE_*`, `TRUCONF_HTTP_BASE_URL`, `TRUCONF_WS_URL`, `TRUCONF_USERNAME`, `TRUCONF_PASSWORD`, `TRUCONF_PROXY_API_KEY`, `TRUCONF_FILE_STORAGE_DIR`, retry/batch/timeout настройки.
- БД/Flyway:
  - `truconf_outbox`: `id`, `external_id`, `operation`, `recipient_kind`, `chat_id`, `user_id`, `target_message_id`, `reply_message_id`, `payload_json`, `status`, `attempt_count`, `max_attempts`, `next_attempt_at`, `locked_by`, `locked_until`, TrueConf response fields, error fields, timestamps.
  - `truconf_outbox_file`: связь с outbox, `file_name`, `mime_type`, `size_bytes`, `storage_kind` = `DISK|DB`, `file_path`, `file_data`, optional preview fields.
  - `truconf_p2p_chat_cache`: `user_id`, `chat_id`, timestamps.
  - PostgreSQL trigger `pg_notify('truconf_outbox_new', id)` после insert в статусе `NEW`.
- HTTP API:
  - `POST /api/v1/outbox` для JSON-заданий: text, survey, edit/delete/forward, file by DB/path reference.
  - `POST /api/v1/outbox/files` multipart: сервис сохраняет файл на диск, кладет в БД ссылку, создает `SEND_FILE`.
  - `GET /api/v1/outbox/{id}` возвращает статус, попытки, TrueConf ids и ошибку.
  - Все endpoints, кроме actuator health, требуют `X-API-Key`.
- Dispatcher:
  - LISTEN/NOTIFY + fallback polling; claim через `FOR UPDATE SKIP LOCKED`.
  - At-least-once доставка: retry с backoff для сетевых/timeout/временных ошибок; terminal fail для неверного chat/message/user доступа.
  - WebSocket request id монотонный в рамках подключения; ответы мапятся по `id`; входящие server requests подтверждаются `{"type":2,"id":...}`.
  - При token expired / auth failure: получить новый token, переподключиться, не терять задания.

## Test Plan

- Unit: сериализация всех TrueConf команд и обработка success/error payload.
- JDBC/Testcontainers PostgreSQL: миграции, direct insert, notify trigger, claim locking, retry transitions, file `DB` и `DISK` modes.
- MockMvc/WebMvcTest: `X-API-Key`, JSON API, multipart upload, status endpoint.
- Fake WebSocket/HTTP TrueConf server: OAuth, auth, P2P resolve, send text/file/survey, edit/delete/forward, timeout, reconnect, token expired.
- Build check: `mvn test`.

## Assumptions

- Используем Spring Boot 4.0.5 именно как requested baseline; Spring 5 больше не целевой стек.
- В v1 поддерживаются текст, файлы, опросы, edit/delete/forward; входящие сообщения TrueConf не сохраняем, только ACK для протокола.
- Малые файлы при прямом insert могут храниться в `bytea`; HTTP-загрузка всегда сохраняет файл на диск и пишет ссылку в БД.
- Перед push нужно заменить git remote с embedded credential на безопасный credential helper или tokenless URL.
