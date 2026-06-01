package ru.truconf.proxydb.outbox;

import java.time.OffsetDateTime;
import java.util.Objects;
import ru.truconf.proxydb.domain.OutboxOperation;
import ru.truconf.proxydb.domain.RecipientKind;

public record CreateOutboxJobCommand(
    String externalId,
    OutboxOperation operation,
    RecipientKind recipientKind,
    String chatId,
    String userId,
    String recipientEmail,
    String targetMessageId,
    String replyMessageId,
    String payloadJson,
    int maxAttempts,
    OffsetDateTime nextAttemptAt) {

  public CreateOutboxJobCommand(
      String externalId,
      OutboxOperation operation,
      RecipientKind recipientKind,
      String chatId,
      String userId,
      String targetMessageId,
      String replyMessageId,
      String payloadJson,
      int maxAttempts,
      OffsetDateTime nextAttemptAt) {
    this(
        externalId,
        operation,
        recipientKind,
        chatId,
        userId,
        null,
        targetMessageId,
        replyMessageId,
        payloadJson,
        maxAttempts,
        nextAttemptAt);
  }

  public CreateOutboxJobCommand {
    Objects.requireNonNull(operation, "operation must not be null");
    Objects.requireNonNull(recipientKind, "recipientKind must not be null");
    if (payloadJson == null || payloadJson.isBlank()) {
      payloadJson = "{}";
    }
    if (maxAttempts <= 0) {
      throw new IllegalArgumentException("maxAttempts must be positive");
    }
  }
}
