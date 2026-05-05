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
