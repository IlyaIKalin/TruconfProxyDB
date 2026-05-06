# Execution checklist

Источник: `plans/detailed-implementation-plan.md`

## Этап 0. Bootstrap проекта

Статус: done

- [x] Проверить исходное состояние репозитория.
- [x] Проверить локальные Java/Maven.
- [x] Создать `pom.xml`.
- [x] Создать `TruconfProxyDbApplication`.
- [x] Создать `application.yml`.
- [x] Создать config properties с `@ConfigurationProperties`.
- [x] Подключить actuator.
- [x] Добавить smoke test контекста.
- [x] Прогнать `mvn test`.
- [x] Проверить `GET /actuator/health` после старта.

Заметки:

- Этап 0 делается без подключения JDBC/Flyway dependencies, чтобы bootstrap стартовал без обязательной локальной PostgreSQL. Эти зависимости добавляются на этапе 1 вместе с миграциями.
- `mvn test` прошел: 2 теста, 0 failures/errors.
- Ручной старт проверен через `SERVER_PORT=18080 mvn spring-boot:run`, потому что локальный порт `8080` уже был занят. `curl http://localhost:18080/actuator/health` вернул `HTTP 200` и `{"groups":["liveness","readiness"],"status":"UP"}`.

## Этап 1. Миграции и доменная модель

Статус: done

- [x] Добавить зависимости PostgreSQL, JDBC, Flyway и Testcontainers PostgreSQL.
- [x] Написать `src/main/resources/db/migration/V1__init.sql`.
- [x] Описать Java enums: `OutboxOperation`, `OutboxStatus`, `RecipientKind`, `FileStorageKind`.
- [x] Описать domain records/classes: `OutboxJob`, `OutboxFile`, `P2pChatCacheEntry`.
- [x] Написать row mappers: `OutboxJobRowMapper`, `OutboxFileRowMapper`, `P2pChatCacheEntryRowMapper`.
- [x] Написать migration tests через Testcontainers PostgreSQL.
- [x] Проверить, что Flyway migration applies cleanly.
- [x] Проверить DB constraints по recipient/file/status/operation.
- [x] Проверить minimal direct insert example.
- [x] Прогнать `mvn test`.

Заметки:

- Миграция создает `truconf_outbox`, `truconf_outbox_file`, `truconf_p2p_chat_cache`, CHECK constraints для текстовых enum-ов, recipient/file constraints, индексы, `updated_at` trigger и notify trigger `truconf_outbox_new` для insert-ов со статусом `NEW`.
- Testcontainers использует `postgres:17-alpine`; `FlywayMigrationTests` чистит схему перед каждым тестом и применяет V1 в пустой PostgreSQL.
- Прогоны:
  - `mvn test` сначала упал до фикса POM: версии `org.testcontainers:junit-jupiter` и `org.testcontainers:postgresql` не управлялись Boot parent.
  - После добавления Testcontainers BOM `mvn test` упал из-за Docker Engine 29: docker-java/Testcontainers пытался использовать API `1.32`, а сервер требует минимум `1.40`.
  - `mvn -Dapi.version=1.40 -Dtest=FlywayMigrationTests test` прошел: 5 тестов, 0 failures/errors.
  - После настройки Surefire system property `api.version=1.40`, `mvn test` прошел: 7 тестов, 0 failures/errors.

## Этап 2. Repository и state machine

Статус: done

- [x] Реализовать `OutboxRepository.create`.
- [x] Реализовать lookup по id и externalId.
- [x] Реализовать claim batch через `FOR UPDATE SKIP LOCKED`.
- [x] Реализовать `markSent`.
- [x] Реализовать `markRetry`.
- [x] Реализовать `markFailed`.
- [x] Реализовать stale lock recovery.
- [x] Покрыть repository/state-machine тестами с PostgreSQL Testcontainers, включая concurrency с двумя transactions.
- [x] Прогнать `mvn test`.

Заметки:

- Этап 2 начат с реализации JDBC repository поверх схемы V1.
- `mvn -Dtest=OutboxRepositoryTests test` прошел: 6 тестов, 0 failures/errors.
- `mvn test` прошел: 13 тестов, 0 failures/errors.

## Этап 3. HTTP API и security

Статус: done

- [x] Добавить зависимости Jackson, Spring Security, WebMvc test и Security test.
- [x] Реализовать HTTP DTO для создания outbox-задания и чтения статуса.
- [x] Реализовать `OutboxService` поверх `OutboxRepository`.
- [x] Реализовать idempotency по `externalId`.
- [x] Реализовать `OutboxController`.
- [x] Реализовать `POST /api/v1/outbox`.
- [x] Реализовать `GET /api/v1/outbox/{id}`.
- [x] Реализовать `GET /api/v1/outbox/by-external-id/{externalId}`.
- [x] Реализовать `ApiExceptionHandler` с предсказуемым JSON ошибок.
- [x] Реализовать API-key security через header `X-API-Key`.
- [x] Оставить `GET /actuator/health` и health subpaths доступными без API key.
- [x] Покрыть WebMvc/security тестами: valid API key create, missing/wrong API key, health без ключа, lookup by id/externalId, 404, validation 400.
- [x] Прогнать `mvn test`.

Заметки:

- HTTP JSON использует Jackson 3 типы `tools.jackson.*`, которые автоконфигурирует Spring Boot 4.
- `POST /api/v1/outbox` возвращает `201` для нового задания и `200` для уже существующего `externalId`.
- Ошибки API возвращаются в формате `{"error":{"code":"...","message":"...","details":[...]}}`.
- `SEND_MESSAGE` сейчас валидирует обязательный `payload.text`; multipart/file-specific DTO остаются для этапа 4.
- Прогоны:
  - `mvn -Dtest=OutboxApiSecurityTests test` прошел: 9 тестов, 0 failures/errors.
  - `mvn test` прошел: 22 теста, 0 failures/errors.

## Этап 4. File storage и multipart endpoint

Статус: done

- [x] Реализовать `FileStorageService`.
- [x] Реализовать `DiskFileStorageService`.
- [x] Реализовать safe path/safe file name для сохранения multipart-файлов на диск.
- [x] Добавить настройку max upload size.
- [x] Расширить repository записью `truconf_outbox_file`.
- [x] Добавить service flow для idempotent `SEND_FILE`.
- [x] Реализовать multipart controller branch `POST /api/v1/outbox/files`.
- [x] Добавить validation/API errors для multipart.
- [x] Покрыть tests на successful upload, security, missing parts, duplicate externalId, path traversal и preview.
- [x] Прогнать `mvn test`.

Заметки:

- Файлы сохраняются в `${truconf.file-storage-dir}/yyyy/MM/dd/<outbox-id>/<uuid>_<safe-file-name>`.
- Multipart endpoint возвращает `201` для нового `SEND_FILE` job и `200` для существующего `externalId`; duplicate `externalId` не пишет второй job/file row.
- `request`/`file` parts обязательны; пустой `file` и пустой `preview` отклоняются как `400`.
- При ошибке после физического сохранения файлов до завершения записи file row сервис пытается удалить уже сохраненные main/preview файлы.
- Прогоны:
  - `mvn -Dtest=OutboxApiSecurityTests test` прошел: 15 тестов, 0 failures/errors.
  - `mvn test` прошел: 28 тестов, 0 failures/errors.

## Этап 5. Dispatcher, LISTEN/NOTIFY и polling

Статус: done

- [x] Реализовать dispatcher skeleton.
- [x] Добавить lifecycle start/stop.
- [x] Настроить worker thread pool через `truconf.dispatcher.worker-threads`.
- [x] Использовать `truconf.dispatcher.batch-size`.
- [x] Использовать `truconf.dispatcher.lock-timeout`.
- [x] Реализовать fallback polling без busy loop.
- [x] Учитывать `truconf.dispatcher.poll-interval`.
- [x] Реализовать `PostgresNotifyListener` для канала `truconf_outbox_new`.
- [x] Будить dispatcher при notify.
- [x] Сделать reconnect/failure handling listener-а без падения приложения.
- [x] Использовать отдельное JDBC connection для LISTEN.
- [x] Добавить worker execution boundary без фиктивного `SENT`.
- [x] Реализовать stale lock recovery scheduling.
- [x] Добавить executor configuration/factory без поломки smoke context.
- [x] Покрыть dispatcher polling ready jobs.
- [x] Покрыть future `next_attempt_at` jobs.
- [x] Покрыть stale lock recovery через dispatcher.
- [x] Покрыть notify path.
- [x] Покрыть graceful start/stop.
- [x] Прогнать `mvn test`.

Заметки:

- Production-заглушка `NoopOutboxJobExecutor` только логирует claimed job и не переводит его в `SENT`; реальные state transitions будут добавлены вместе с TrueConf executor.
- Добавлен `truconf.dispatcher.enabled` с default `true`; в старых HTTP/smoke тестах dispatcher отключен, чтобы background claim не менял ожидаемый статус `NEW`.
- Прогоны:
  - `mvn -Dtest=OutboxDispatcherTests test` прошел: 4 теста, 0 failures/errors.
  - `mvn -Dtest=TruconfProxyDbApplicationTests,OutboxApiSecurityTests,OutboxDispatcherTests test` прошел: 21 тест, 0 failures/errors.
  - `mvn test` прошел: 32 теста, 0 failures/errors.

## Этап 6. TrueConf JSON protocol

Статус: done

- [x] Сверить следующий незавершенный блок с `plans/detailed-implementation-plan.md`.
- [x] Описать TrueConf command factory без сетевых/DB зависимостей.
- [x] Реализовать builders для `auth`, ACK, `createP2PChat`, `sendMessage`, `uploadFile`, `sendFile`, `sendSurvey`, `editMessage`, `editSurvey`, `removeMessage`, `forwardMessage`.
- [x] Реализовать response mapper для success payload fields.
- [x] Реализовать tolerant error extraction для WebSocket и HTTP error shapes.
- [x] Реализовать базовую классификацию retryable/terminal TrueConf error codes.
- [x] Покрыть protocol unit tests.
- [x] Прогнать точечные тесты.
- [x] Прогнать `mvn test`.

Заметки:

- Этап 6 реализуется как изолированный package `truconf`: без JDBC, без WebSocket lifecycle, без outbox state transitions.
- Production `OutboxJobExecutor` остается `NoopOutboxJobExecutor`, поэтому реальные jobs по-прежнему не переводятся в `SENT`.
- Формы команд сверялись с официальными страницами TrueConf Chatbot Connector по auth/base format, chats, messages, files, surveys и errors; WebSocket request-и строятся с `type=1`, ACK с `type=2`.
- `removeMessage` builder по умолчанию ставит `forAll=true`, потому что поле обязательно в TrueConf API; при необходимости есть overload с явным `forAll`.
- Классификация ошибок на этом этапе базовая: retryable только явно временные коды `100`, `101`, `203`, `300`, `301`, `311`; неизвестные коды считаются terminal до уточнения на fake/real стенде.
- Прогоны:
  - `mvn -Dtest=TrueConfCommandFactoryTests,TrueConfResponseMapperTests test` сначала упал из-за сравнения Jackson 3 `ObjectNode` через `equals`, после перехода на сравнение нормализованной JSON-строки прошел: 9 тестов, 0 failures/errors.
  - `mvn test` прошел: 41 тест, 0 failures/errors.

## Этап 7. OAuth и WebSocket session

Статус: done

- [x] Сверить следующий незавершенный блок с `plans/detailed-implementation-plan.md`.
- [x] Добавить зависимости для RestClient/WebSocket и fake WebSocket тестов.
- [x] Реализовать `TrueConfTokenService`.
- [x] Реализовать `TrueConfSession`.
- [x] Реализовать pending requests map.
- [x] Реализовать timeout handling.
- [x] Реализовать reconnect/failure handling с retryable exceptions для pending futures.
- [x] Реализовать ACK server requests.
- [x] Покрыть fake server тестами.
- [x] Прогнать точечные тесты.
- [x] Прогнать `mvn test`.

Заметки:

- В `plans/detailed-implementation-plan.md` этот блок называется `Этап 6. OAuth и WebSocket session`; в текущем чеклисте `Этап 6` уже занят блоком `TrueConf JSON protocol`, поэтому следующий раздел ведется как `Этап 7`.
- Production `OutboxJobExecutor` остается `NoopOutboxJobExecutor`, поэтому реальные jobs пока не переводятся в `SENT`.
- `TrueConfSession` получает OAuth token до открытия WebSocket, затем выполняет `auth`; при error response на `auth` token инвалидируется и auth повторяется один раз на новом подключении.
- WebSocket client использует JDK `java.net.http.WebSocket` и subprotocol preference `json.v1`; pending requests завершаются retryable `TrueConfException` при timeout, close/error или send failure.
- `mvn -Dtest=TrueConfTokenServiceTests,TrueConfSessionTests test` сначала упал из-за неправильного порядка OAuth/WS handshake, после исправления прошел: 5 тестов, 0 failures/errors.
- `mvn -Dtest='ru.truconf.proxydb.truconf.*Tests' test` прошел: 14 тестов, 0 failures/errors.
- `mvn test` сначала упал из-за выбора конструктора Spring context-ом для `TrueConfSession`; после явного `@Autowired` на production-конструкторах `TrueConfSession` и `TrueConfTokenService` `mvn -Dtest=TruconfProxyDbApplicationTests,OutboxApiSecurityTests test` прошел: 17 тестов, 0 failures/errors.
- `mvn test` прошел: 46 тестов, 0 failures/errors.

## Этап 8. Command executor

Статус: done

- [x] Сверить следующий незавершенный блок с `plans/detailed-implementation-plan.md`.
- [x] Реализовать `P2pChatResolver`.
- [x] Реализовать production `OutboxJobExecutor`.
- [x] Поддержать `SEND_MESSAGE`.
- [x] Поддержать `SEND_FILE` с трехшаговым upload flow.
- [x] Поддержать `SEND_SURVEY`.
- [x] Поддержать `EDIT_MESSAGE`, `EDIT_SURVEY`, `REMOVE_MESSAGE`, `FORWARD_MESSAGE`.
- [x] Сохранять success/error в repository.
- [x] Покрыть executor flow тестами.
- [x] Прогнать точечные тесты.
- [x] Прогнать `mvn test`.

Заметки:

- Этап соответствует `Этап 7. Command executor` из detailed plan; в чеклисте он ведется как `Этап 8`, потому что dispatcher был реализован раньше отдельным этапом 5.
- Production executor теперь переводит реальные claimed jobs в `SENT`, `RETRY_WAIT` или `FAILED`; старый `NoopOutboxJobExecutor` остается в коде, но больше не является primary bean.
- Оркестрация вынесена в package `delivery`: `api` не знает SQL/WebSocket, `outbox` хранит payload opaque и P2P cache, `truconf` не пишет в БД, `files` только открывает сохраненный контент.
- `SEND_FILE` реализован как `uploadFile` по WebSocket, HTTP multipart upload в `/bridge/api/client/v1/files`, затем `sendFile`; HTTP upload добавляет `Authorization: Bearer <token>` и `Upload-Task-Id`.
- HTTP multipart upload реализован через `LinkedMultiValueMap`/`HttpEntity`, без `MultipartBodyBuilder`, потому что текущий classpath не содержит optional `org.reactivestreams.Publisher`.
- Для DISK direct insert чтение файла ограничено configured `truconf.file-storage-dir`; файл вне storage dir будет terminal `FAILED`.
- Survey payload на processing требует поля `url`, `appVersion`, `path`, `title`, `description`, `buttonText`, `secret`, `alt`; отсутствие поля считается terminal validation error.
- Retry backoff сейчас deterministic exponential с cap, без jitter; jitter остается hardening-задачей следующего этапа.
- Прогоны:
  - `mvn -DskipTests compile` сначала упал из-за type inference в `TrueConfFileUploadClient`, после явного branch прошел.
  - `mvn -Dtest=OutboxDeliveryExecutorTests test` сначала упал из-за сравнения форматированной JSON-строки, после перехода на структурный assert прошел: 5 tests, 0 failures/errors.
  - `mvn -Dtest=OutboxDeliveryExecutorTests,DefaultTrueConfClientTests test` прошел: 6 tests, 0 failures/errors.
  - `mvn -Dtest=TruconfProxyDbApplicationTests,OutboxApiSecurityTests,OutboxDispatcherTests test` сначала упал из-за выбора конструктора `RetryPolicy`; после `@Autowired` на production-конструкторе прошел: 21 tests, 0 failures/errors.
  - `mvn -Dtest=OutboxDeliveryFakeTrueConfIntegrationTests test` сначала упал из-за `MultipartBodyBuilder`/missing `org.reactivestreams.Publisher`; после перехода на `LinkedMultiValueMap`/`HttpEntity` прошел: 1 test, 0 failures/errors.
  - `mvn test` прошел: 53 tests, 0 failures/errors.

## Этап 9. Hardening и документация эксплуатации

Статус: done

- [x] Добавить `README.md` с env vars и examples.
- [x] Добавить SQL examples для direct insert.
- [x] Добавить `Dockerfile` для сборки runtime image сервиса.
- [x] Добавить `docker-compose.yml` для локального запуска PostgreSQL и сервиса.
- [x] Добавить `.dockerignore`.
- [x] Добавить `.env.example` с полным набором локальных переменных без реальных secrets.
- [x] Описать в `README.md` команды `docker compose --env-file .env up --build`, проверку health endpoint и пример direct insert.
- [x] Добавить логирование без secrets.
- [x] Проверить actuator health/readiness.
- [x] Проверить remote config security note.
- [x] Прогнать полный `mvn test`.
- [x] Проверить `docker compose config` и smoke-запуск локальной среды.

Заметки:

- Добавлены эксплуатационные артефакты: `README.md`, `.env.example`, `Dockerfile`, `docker-compose.yml`, `.dockerignore`.
- `.gitignore` расширен для локальных `.env` файлов, при этом `.env.example` остается отслеживаемым.
- `README.md` содержит env vars, HTTP API examples, multipart file example, direct insert SQL examples, health/readiness commands, security notes и known limitations.
- Явные log-сообщения в production коде проверены на отсутствие API key, password, OAuth token и Authorization header. Дополнительный security note о remote config и запрете secret logging добавлен в README.
- `docker compose --env-file .env.example config` прошел.
- `docker compose --env-file .env config` проверен через временный `.env`, созданный из `.env.example`; временный файл удален.
- Smoke compose-запуск выполнен с `SERVER_PORT=18080`, потому что локальный `8080` был занят. App container стал `healthy`, `curl http://localhost:18080/actuator/health` вернул `{"groups":["liveness","readiness"],"status":"UP"}`, `curl http://localhost:18080/actuator/health/readiness` вернул `{"status":"UP"}`.
- Flyway внутри compose-среды применил migration version `1` успешно (`flyway_schema_history`: `1|t`).
- После smoke-проверки compose-среда остановлена через `docker compose down -v`.
- `mvn test` прошел: 53 tests, 0 failures/errors.
