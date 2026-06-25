package ru.truconf.proxydb.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;
import java.util.List;
import ru.truconf.proxydb.domain.OutboxStatus;
import ru.truconf.proxydb.delivery.GroupChatService.ParticipantStatus;
import tools.jackson.databind.JsonNode;

public final class SpringFlowIntegrationDtos {

  private SpringFlowIntegrationDtos() {
  }

  public record SyncProjectChatRequest(
      @NotBlank String projectKey,
      @NotBlank String projectName,
      @NotBlank String chatTitle,
      @Valid List<ProjectChatParticipantRequest> participants,
      Boolean displayHistory) {
  }

  public record SyncManagedChatRequest(
      @NotBlank String ownerKind,
      @NotBlank String ownerKey,
      @NotBlank String projectKey,
      @NotBlank String projectName,
      String chatTitle,
      String existingChatId,
      @Valid List<ProjectChatParticipantRequest> participants,
      Boolean displayHistory) {
  }

  public record ProjectChatParticipantRequest(
      String email,
      String userId) {
  }

  public record SyncProjectChatResponse(
      String projectKey,
      String ownerKind,
      String ownerKey,
      String chatId,
      String chatTitle,
      boolean created,
      int addedCount,
      int ignoredCount,
      int failedCount,
      OffsetDateTime lastSyncAt,
      List<ProjectChatParticipantResultResponse> participants) {
  }

  public record ProjectChatParticipantResultResponse(
      int index,
      ParticipantStatus status,
      String email,
      String userId,
      String code,
      String message) {
  }

  public record SendProjectNotificationRequest(
      @NotBlank String externalId,
      @NotBlank String projectKey,
      @NotBlank String projectName,
      @NotBlank String chatTitle,
      JsonNode payload,
      @Min(1)
      Integer maxAttempts) {
  }

  public record SendManagedChatNotificationRequest(
      @NotBlank String externalId,
      @NotBlank String ownerKind,
      @NotBlank String ownerKey,
      @NotBlank String projectKey,
      @NotBlank String projectName,
      String chatTitle,
      String existingChatId,
      JsonNode payload,
      @Min(1)
      Integer maxAttempts) {
  }

  public record SendProjectNotificationResponse(
      long outboxId,
      String externalId,
      OutboxStatus status,
      boolean created,
      String projectKey,
      String ownerKind,
      String ownerKey,
      String chatId,
      String chatTitle) {
  }
}
