package ru.truconf.proxydb.domain;

import java.time.OffsetDateTime;

public record OutboxJob(
    long id,
    String externalId,
    OutboxOperation operation,
    RecipientKind recipientKind,
    String chatId,
    String userId,
    String targetMessageId,
    String replyMessageId,
    String payloadJson,
    OutboxStatus status,
    int attemptCount,
    int maxAttempts,
    OffsetDateTime nextAttemptAt,
    String lockedBy,
    OffsetDateTime lockedUntil,
    String trueconfChatId,
    String trueconfMessageId,
    String trueconfFileId,
    Long trueconfTimestamp,
    String lastErrorCode,
    String lastErrorMessage,
    Boolean lastErrorRetryable,
    String lastResponseJson,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    OffsetDateTime sentAt,
    OffsetDateTime failedAt) {
}
