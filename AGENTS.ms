# Контекст для следующих чатов Codex

Проект: `TruconfProxyDB`

Рабочая директория:

```text
/home/coder/workspace/TruconfProxyDB
```

## Что это

Мы реализуем standalone-сервис на Java 21 и Spring Boot 4.0.6:

- принимает задания через HTTP API или direct insert в PostgreSQL;
- кладет их в outbox;
- обрабатывает через dispatcher с PostgreSQL `LISTEN/NOTIFY` и fallback polling;
- доставляет сообщения/файлы/опросы в TrueConf Chatbot Connector;
- поддерживает retry/backoff, stale lock recovery, P2P `userId -> chatId` cache;
- защищает API через header `X-API-Key`;
- дает базовые actuator endpoints.

Главный план: `plans/detailed-implementation-plan.md`.
Текущий чеклист и фактический статус: `plans/execution-checklist.md`.

## Текущий статус на 2026-05-06

Реализованы и отмечены как `done` этапы 0-8 из `plans/execution-checklist.md`:

- bootstrap Maven/Spring Boot проекта;
- Flyway миграция и доменная модель;
- JDBC repository и outbox state machine;
- HTTP API и API-key security;
- file storage и multipart endpoint;
- dispatcher, `LISTEN/NOTIFY` и polling;
- TrueConf JSON protocol builders/mapper/error classifier;
- OAuth и WebSocket session;
- production command executor.

Последний зафиксированный полный прогон из чеклиста:

```bash
mvn test
```

Результат: 53 tests, 0 failures/errors.

На момент создания этого файла `git status --short` был чистым.

## Важные технические детали

- Java: 21.
- Spring Boot parent: `org.springframework.boot:spring-boot-starter-parent:4.0.6`.
- Jackson в Boot 4 используется через `tools.jackson.*`, не `com.fasterxml.jackson.*` в новом коде проекта.
- Lombok намеренно не добавлялся.
- PostgreSQL enum-ы реализованы как `text` + `CHECK constraints`.
- Testcontainers PostgreSQL использует `postgres:17-alpine`.
- В `pom.xml` для Surefire задано:

```xml
<api.version>1.40</api.version>
```

Это нужно из-за Docker Engine 29/Testcontainers compatibility.

## Основные пакеты

```text
src/main/java/ru/truconf/proxydb/
  api/        HTTP controllers, DTO, API errors
  config/     properties, security, executors
  domain/     records/enums
  outbox/     repository, dispatcher, notify listener, state transitions
  truconf/    OAuth, WebSocket session, command factory, TrueConf client
  files/      disk/DB file storage access
  delivery/   production OutboxJobExecutor and P2P resolver
```

Boundary-правила из плана все еще актуальны:

- `api` не должен знать о SQL и WebSocket;
- `outbox` не должен знать детали JSON-формата TrueConf, кроме opaque `payload_json`;
- `truconf` не должен писать в БД напрямую;
- `files` не должен знать о TrueConf upload protocol.

## Что уже есть

Ключевые файлы:

- `pom.xml`
- `src/main/resources/application.yml`
- `src/main/resources/db/migration/V1__init.sql`
- `src/main/java/ru/truconf/proxydb/outbox/OutboxRepository.java`
- `src/main/java/ru/truconf/proxydb/outbox/OutboxDispatcher.java`
- `src/main/java/ru/truconf/proxydb/truconf/TrueConfSession.java`
- `src/main/java/ru/truconf/proxydb/truconf/DefaultTrueConfClient.java`
- `src/main/java/ru/truconf/proxydb/delivery/OutboxDeliveryExecutor.java`
- `src/main/java/ru/truconf/proxydb/delivery/P2pChatResolver.java`

Тесты уже покрывают:

- Flyway schema/constraints/direct insert;
- repository state machine и concurrency;
- WebMvc/security/API validation;
- multipart upload/path traversal/preview/idempotency;
- dispatcher polling/notify/stale recovery/start-stop;
- TrueConf command factory/response mapper/error classifier;
- OAuth token service/WebSocket session;
- command executor flows;
- fake TrueConf integration happy path.

## Что, вероятно, делать дальше

Следующий незавершенный блок по `plans/detailed-implementation-plan.md` и чеклисту - hardening и эксплуатационная документация, фактически `Этап 9`.

На момент создания файла в корне проекта не было:

- `README.md`;
- `Dockerfile`;
- `docker-compose.yml`;
- `.dockerignore`;
- `.env.example`.

Ожидаемые задачи этапа 9:

- добавить `README.md` с env vars, запуском, API examples, direct insert examples и known limitations;
- добавить `.env.example` без реальных secrets;
- добавить production-like `Dockerfile`;
- добавить `docker-compose.yml` для `postgres + app`;
- добавить `.dockerignore`;
- проверить `docker compose --env-file .env config`;
- по возможности сделать smoke-запуск compose-среды;
- прогнать `mvn test`.

## Операционные ограничения и решения из плана

- Сервис дает at-least-once доставку. При падении после отправки в TrueConf, но до записи ответа в БД, возможна повторная отправка.
- `externalId` обеспечивает idempotency только на этапе enqueue.
- Direct insert-ы частично валидируются constraint-ами БД, остальное валидируется при processing и может завершиться `FAILED`.
- Для `SEND_FILE` direct insert с `storage_kind='DISK'` файл должен лежать внутри configured `truconf.file-storage-dir`, иначе executor должен считать это terminal failure.
- Command timeout должен быть меньше outbox lock timeout.
- Не логировать API key, password, OAuth token и другие secrets.

## Полезные команды

```bash
mvn test
mvn -Dtest=OutboxDeliveryExecutorTests test
mvn -Dtest='ru.truconf.proxydb.truconf.*Tests' test
mvn spring-boot:run
curl http://localhost:8080/actuator/health
```

Если порт `8080` занят:

```bash
SERVER_PORT=18080 mvn spring-boot:run
curl http://localhost:18080/actuator/health
```

## Правила при продолжении работы

- Перед изменениями сначала читать `plans/execution-checklist.md`, затем нужный раздел `plans/detailed-implementation-plan.md`.
- Держать изменения узко привязанными к текущему этапу.
- Не откатывать чужие изменения в рабочем дереве.
- Использовать существующие паттерны проекта: Java records/explicit classes, Spring beans, JDBC repositories, Jackson `ObjectMapper`.
- После существенных изменений прогонять как минимум точечные тесты и, если возможно, полный `mvn test`.
