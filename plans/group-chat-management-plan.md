# План реализации: групповые чаты TrueConf через TruconfProxyDB

## Summary

Добавить синхронное управление групповыми чатами через TruconfProxyDB:
создание группового чата, добавление пачки участников в существующий чат и
создание чата сразу с участниками. Операции выполняются напрямую через
TrueConf Chatbot Connector и сразу возвращают результат клиенту, без outbox и
без новых таблиц.

## Public API

- `POST /api/v1/trueconf/group-chats`
  - Создает групповой чат через `createGroupChat`.
  - Request: `title`, optional `participants`, optional `displayHistory`.
  - Если `participants` пустой или отсутствует, создается чат без добавления
    участников.
  - `displayHistory` default: `true`.
- `POST /api/v1/trueconf/group-chats/{chatId}/participants`
  - Добавляет пачку участников в существующий групповой чат через
    `addChatParticipant`.
  - Каждый participant содержит ровно одно поле: `email` или `userId`.
  - `email` резолвится в TrueConf ID через существующую AD/LDAP-связку и
    `truconf_user_email_cache`.
- Ответы по участникам возвращаются поэлементно:
  - `ADDED` - участник добавлен;
  - `IGNORED` - ожидаемая ошибка, например уже состоит в чате;
  - `FAILED` - email не найден, ошибка AD/LDAP или ошибка TrueConf для
    конкретного участника.

## Implementation Changes

- Расширить TrueConf WebSocket client командами `createGroupChat(title)` и
  `addChatParticipant(chatId, userId, displayHistory)`.
- Вынести email -> TrueConf ID resolve из `P2pChatResolver` в общий resolver.
- Добавить сервис управления групповыми чатами:
  - создать чат;
  - последовательно обработать участников в порядке входного массива;
  - продолжать batch после ошибок по отдельным участникам;
  - вернуть `index`, исходный `email`/`userId`, resolved `userId`, `status`,
    `code`, `message`.
- Не добавлять новые outbox operations и миграции БД.

## Test Plan

- Unit tests для `TrueConfCommandFactory`: JSON `createGroupChat` и
  `addChatParticipant`.
- Client tests для вызовов через WebSocket transport/rate limiter.
- Service tests для пустого чата, mixed `email`/`userId`, default
  `displayHistory=true`, batch из одного и нескольких участников,
  `IGNORED`/`FAILED` per-item результатов.

## Assumptions

- В scope только групповые чаты: каналы, роли, удаление участников и удаление
  чатов не входят в эту итерацию.
- Клиент сам хранит returned `chatId`, если он нужен дальше.
- Batch добавления участников best-effort: один проблемный участник не
  блокирует остальных.
