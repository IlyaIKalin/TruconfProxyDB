package ru.truconf.proxydb.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.JsonNode;
import ru.truconf.proxydb.domain.OutboxJob;
import ru.truconf.proxydb.domain.OutboxOperation;
import ru.truconf.proxydb.domain.OutboxStatus;
import ru.truconf.proxydb.domain.RecipientKind;

public final class OutboxDtos {

  private OutboxDtos() {
  }

  public record CreateOutboxRequest(
      String externalId,
      @NotNull OutboxOperation operation,
      @Valid @NotNull RecipientDto recipient,
      String targetMessageId,
      String replyMessageId,
      JsonNode payload,
      @Min(1) Integer maxAttempts) {

    @JsonIgnore
    @AssertTrue(message = "recipient.chatId is required when recipient.kind is CHAT")
    public boolean isChatRecipientValid() {
      return recipient == null
          || recipient.kind() != RecipientKind.CHAT
          || hasText(recipient.chatId());
    }

    @JsonIgnore
    @AssertTrue(message = "recipient.userId is required when recipient.kind is USER")
    public boolean isUserRecipientValid() {
      return recipient == null
          || recipient.kind() != RecipientKind.USER
          || hasText(recipient.userId());
    }
  }

  public record CreateOutboxFileRequest(
      String externalId,
      @Valid @NotNull RecipientDto recipient,
      String caption,
      String parseMode,
      String replyMessageId,
      @Min(1) Integer maxAttempts) {

    @JsonIgnore
    @AssertTrue(message = "recipient.chatId is required when recipient.kind is CHAT")
    public boolean isChatRecipientValid() {
      return recipient == null
          || recipient.kind() != RecipientKind.CHAT
          || hasText(recipient.chatId());
    }

    @JsonIgnore
    @AssertTrue(message = "recipient.userId is required when recipient.kind is USER")
    public boolean isUserRecipientValid() {
      return recipient == null
          || recipient.kind() != RecipientKind.USER
          || hasText(recipient.userId());
    }
  }

  public record RecipientDto(
      @NotNull RecipientKind kind,
      String chatId,
      String userId) {
  }

  public record CreateOutboxResponse(
      long id,
      String externalId,
      OutboxStatus status) {

    public static CreateOutboxResponse from(OutboxJob job) {
      return new CreateOutboxResponse(job.id(), job.externalId(), job.status());
    }
  }

  public record OutboxStatusResponse(
      long id,
      String externalId,
      OutboxOperation operation,
      RecipientKind recipientKind,
      String chatId,
      String userId,
      String targetMessageId,
      String replyMessageId,
      JsonNode payload,
      OutboxStatus status,
      int attemptCount,
      int maxAttempts,
      OffsetDateTime nextAttemptAt,
      String trueconfChatId,
      String trueconfMessageId,
      String trueconfFileId,
      Long trueconfTimestamp,
      String lastErrorCode,
      String lastErrorMessage,
      Boolean lastErrorRetryable,
      JsonNode lastResponse,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      OffsetDateTime sentAt,
      OffsetDateTime failedAt) {
  }

  public record ErrorBody(ErrorDetail error) {
  }

  public record ErrorDetail(
      String code,
      String message,
      List<FieldErrorDto> details) {
  }

  public record FieldErrorDto(
      String field,
      String message) {
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
