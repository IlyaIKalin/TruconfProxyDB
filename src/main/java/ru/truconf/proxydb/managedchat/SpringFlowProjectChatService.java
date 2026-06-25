package ru.truconf.proxydb.managedchat;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.truconf.proxydb.api.ApiValidationException;
import ru.truconf.proxydb.config.AppProperties;
import ru.truconf.proxydb.delivery.GroupChatService;
import ru.truconf.proxydb.delivery.GroupChatService.AddParticipantsCommand;
import ru.truconf.proxydb.delivery.GroupChatService.ChatInfoResult;
import ru.truconf.proxydb.delivery.GroupChatService.CreateGroupChatCommand;
import ru.truconf.proxydb.delivery.GroupChatService.ParticipantCommand;
import ru.truconf.proxydb.delivery.GroupChatService.ParticipantResult;
import ru.truconf.proxydb.delivery.GroupChatService.ParticipantStatus;
import ru.truconf.proxydb.domain.OutboxJob;
import ru.truconf.proxydb.domain.OutboxOperation;
import ru.truconf.proxydb.domain.RecipientKind;
import ru.truconf.proxydb.domain.TruconfManagedChat;
import ru.truconf.proxydb.outbox.CreateOutboxJobCommand;
import ru.truconf.proxydb.outbox.EnqueuedOutboxJob;
import ru.truconf.proxydb.outbox.OutboxService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class SpringFlowProjectChatService {

  public static final String OWNER_SYSTEM = "SPRINGFLOW";
  public static final String OWNER_KIND_PROJECT = "PROJECT";
  public static final String OWNER_KIND_PROCESS_FAMILY = "PROCESS_FAMILY";
  public static final String OWNER_KIND_NOTIFICATION_CHANNEL = "NOTIFICATION_CHANNEL";
  public static final String OWNER_KIND_PROCESS_NOTIFICATION_CHANNEL = "PROCESS_NOTIFICATION_CHANNEL";

  private final ManagedChatRepository managedChatRepository;
  private final GroupChatService groupChatService;
  private final OutboxService outboxService;
  private final ObjectMapper objectMapper;
  private final AppProperties properties;

  public SpringFlowProjectChatService(
      ManagedChatRepository managedChatRepository,
      GroupChatService groupChatService,
      OutboxService outboxService,
      ObjectMapper objectMapper,
      AppProperties properties) {
    this.managedChatRepository = Objects.requireNonNull(
        managedChatRepository,
        "managedChatRepository must not be null");
    this.groupChatService = Objects.requireNonNull(
        groupChatService,
        "groupChatService must not be null");
    this.outboxService = Objects.requireNonNull(outboxService, "outboxService must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
  }

  @Transactional
  public SyncProjectChatResult syncProjectChat(SyncProjectChatCommand command) {
    Objects.requireNonNull(command, "command must not be null");

    String projectKey = requireText(command.projectKey(), "projectKey");
    String chatTitle = requireText(command.chatTitle(), "chatTitle");
    requireText(command.projectName(), "projectName");
    List<ParticipantCommand> participants = normalizeParticipants(command.participants());

    managedChatRepository.lockOwner(OWNER_SYSTEM, OWNER_KIND_PROJECT, projectKey);
    ManagedChatState chatState = findOrCreateManagedChat(OWNER_KIND_PROJECT, projectKey, chatTitle, null);

    List<ParticipantResult> participantResults = participants.isEmpty()
        ? List.of()
        : groupChatService.addParticipants(new AddParticipantsCommand(
            chatState.chat().chatId(),
            participants,
            command.displayHistory()))
            .participants();

    TruconfManagedChat syncedChat = managedChatRepository.markSynced(
        OWNER_SYSTEM,
        OWNER_KIND_PROJECT,
        projectKey,
        chatTitle);

    return new SyncProjectChatResult(
        projectKey,
        OWNER_KIND_PROJECT,
        projectKey,
        syncedChat.chatId(),
        syncedChat.title(),
        chatState.created(),
        count(participantResults, ParticipantStatus.ADDED),
        count(participantResults, ParticipantStatus.IGNORED),
        count(participantResults, ParticipantStatus.FAILED),
        syncedChat.lastSyncAt(),
        participantResults);
  }

  @Transactional
  public SyncProjectChatResult syncManagedChat(SyncManagedChatCommand command) {
    Objects.requireNonNull(command, "command must not be null");

    String ownerKind = normalizeOwnerKind(command.ownerKind());
    String ownerKey = requireText(command.ownerKey(), "ownerKey");
    String projectKey = requireText(command.projectKey(), "projectKey");
    requireText(command.projectName(), "projectName");
    String existingChatId = normalizeText(command.existingChatId());
    String chatTitle = managedChatTitle(existingChatId, command.chatTitle());
    List<ParticipantCommand> participants = normalizeParticipants(command.participants());

    managedChatRepository.lockOwner(OWNER_SYSTEM, ownerKind, ownerKey);
    ManagedChatState chatState = findOrCreateManagedChat(ownerKind, ownerKey, chatTitle, existingChatId);

    List<ParticipantResult> participantResults = participants.isEmpty()
        ? List.of()
        : groupChatService.addParticipants(new AddParticipantsCommand(
            chatState.chat().chatId(),
            participants,
            command.displayHistory()))
            .participants();

    TruconfManagedChat syncedChat = managedChatRepository.markSynced(
        OWNER_SYSTEM,
        ownerKind,
        ownerKey,
        chatTitle);

    return new SyncProjectChatResult(
        projectKey,
        ownerKind,
        ownerKey,
        syncedChat.chatId(),
        syncedChat.title(),
        chatState.created(),
        count(participantResults, ParticipantStatus.ADDED),
        count(participantResults, ParticipantStatus.IGNORED),
        count(participantResults, ParticipantStatus.FAILED),
        syncedChat.lastSyncAt(),
        participantResults);
  }

  @Transactional
  public SendProjectNotificationResult sendProjectNotification(
      SendProjectNotificationCommand command) {
    Objects.requireNonNull(command, "command must not be null");

    String externalId = requireText(command.externalId(), "externalId");
    String projectKey = requireText(command.projectKey(), "projectKey");
    String chatTitle = requireText(command.chatTitle(), "chatTitle");
    requireText(command.projectName(), "projectName");
    JsonNode payload = validateNotificationPayload(command.payload());
    int maxAttempts = command.maxAttempts() == null
        ? properties.retry().maxAttempts()
        : command.maxAttempts();
    if (maxAttempts <= 0) {
      throw new ApiValidationException("maxAttempts must be positive");
    }

    managedChatRepository.lockOwner(OWNER_SYSTEM, OWNER_KIND_PROJECT, projectKey);
    ManagedChatState chatState = findOrCreateManagedChat(OWNER_KIND_PROJECT, projectKey, chatTitle, null);

    EnqueuedOutboxJob enqueued = outboxService.enqueue(new CreateOutboxJobCommand(
        externalId,
        OutboxOperation.SEND_MESSAGE,
        RecipientKind.CHAT,
        chatState.chat().chatId(),
        null,
        null,
        null,
        null,
        writePayload(payload),
        maxAttempts,
        null));
    OutboxJob job = enqueued.job();

    return new SendProjectNotificationResult(
        job.id(),
        job.externalId(),
        job.status(),
        enqueued.created(),
        projectKey,
        OWNER_KIND_PROJECT,
        projectKey,
        chatState.chat().chatId(),
        chatState.chat().title());
  }

  @Transactional
  public SendProjectNotificationResult sendManagedChatNotification(
      SendManagedChatNotificationCommand command) {
    Objects.requireNonNull(command, "command must not be null");

    String externalId = requireText(command.externalId(), "externalId");
    String ownerKind = normalizeOwnerKind(command.ownerKind());
    String ownerKey = requireText(command.ownerKey(), "ownerKey");
    String projectKey = requireText(command.projectKey(), "projectKey");
    requireText(command.projectName(), "projectName");
    String existingChatId = normalizeText(command.existingChatId());
    String chatTitle = managedChatTitle(existingChatId, command.chatTitle());
    JsonNode payload = validateNotificationPayload(command.payload());
    int maxAttempts = command.maxAttempts() == null
        ? properties.retry().maxAttempts()
        : command.maxAttempts();
    if (maxAttempts <= 0) {
      throw new ApiValidationException("maxAttempts must be positive");
    }

    managedChatRepository.lockOwner(OWNER_SYSTEM, ownerKind, ownerKey);
    ManagedChatState chatState = findOrCreateManagedChat(ownerKind, ownerKey, chatTitle, existingChatId);

    EnqueuedOutboxJob enqueued = outboxService.enqueue(new CreateOutboxJobCommand(
        externalId,
        OutboxOperation.SEND_MESSAGE,
        RecipientKind.CHAT,
        chatState.chat().chatId(),
        null,
        null,
        null,
        null,
        writePayload(payload),
        maxAttempts,
        null));
    OutboxJob job = enqueued.job();

    return new SendProjectNotificationResult(
        job.id(),
        job.externalId(),
        job.status(),
        enqueued.created(),
        projectKey,
        ownerKind,
        ownerKey,
        chatState.chat().chatId(),
        chatState.chat().title());
  }

  private ManagedChatState findOrCreateManagedChat(
      String ownerKind,
      String ownerKey,
      String chatTitle,
      String existingChatId) {
    if (existingChatId != null) {
      TruconfManagedChat chat = managedChatRepository.register(
          OWNER_SYSTEM,
          ownerKind,
          ownerKey,
          existingChatId,
          chatTitle);
      return new ManagedChatState(chat, false);
    }
    return managedChatRepository.findByOwner(OWNER_SYSTEM, ownerKind, ownerKey)
        .map(chat -> new ManagedChatState(chat, false))
        .orElseGet(() -> {
          String chatId = groupChatService.createGroupChat(new CreateGroupChatCommand(
              chatTitle,
              List.of(),
              null))
              .chatId();
          TruconfManagedChat chat = managedChatRepository.create(
              OWNER_SYSTEM,
              ownerKind,
              ownerKey,
              chatId,
              chatTitle);
          return new ManagedChatState(chat, true);
        });
  }

  private String managedChatTitle(String existingChatId, String requestedTitle) {
    if (existingChatId == null) {
      return requireText(requestedTitle, "chatTitle");
    }
    ChatInfoResult chatInfo = groupChatService.getChatInfo(existingChatId);
    return chatInfo.title();
  }

  private JsonNode validateNotificationPayload(JsonNode payload) {
    if (payload == null || !payload.isObject()) {
      throw new ApiValidationException("payload must be a JSON object");
    }
    JsonNode text = payload.get("text");
    if (text == null || !text.isTextual() || text.asText().isBlank()) {
      throw new ApiValidationException("payload.text is required");
    }
    return payload;
  }

  private List<ParticipantCommand> normalizeParticipants(List<ParticipantInput> participants) {
    if (participants == null) {
      return List.of();
    }
    return participants.stream()
        .map(participant -> participant == null
            ? null
            : new ParticipantCommand(
                normalizeEmail(participant.email()),
                normalizeText(participant.userId())))
        .toList();
  }

  private String writePayload(JsonNode payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JacksonException ex) {
      throw new IllegalArgumentException("payload must be valid JSON", ex);
    }
  }

  private static int count(List<ParticipantResult> results, ParticipantStatus status) {
    return Math.toIntExact(results.stream()
        .filter(result -> result.status() == status)
        .count());
  }

  private static String requireText(String value, String fieldName) {
    String normalized = normalizeText(value);
    if (normalized == null) {
      throw new ApiValidationException(fieldName + " must not be blank");
    }
    return normalized;
  }

  private static String normalizeOwnerKind(String value) {
    String normalized = requireText(value, "ownerKind").toUpperCase(Locale.ROOT);
    if (OWNER_KIND_PROJECT.equals(normalized)
        || OWNER_KIND_PROCESS_FAMILY.equals(normalized)
        || OWNER_KIND_NOTIFICATION_CHANNEL.equals(normalized)
        || OWNER_KIND_PROCESS_NOTIFICATION_CHANNEL.equals(normalized)) {
      return normalized;
    }
    throw new ApiValidationException("ownerKind is not supported: " + value);
  }

  private static String normalizeText(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String normalizeEmail(String value) {
    String normalized = normalizeText(value);
    return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
  }

  private record ManagedChatState(
      TruconfManagedChat chat,
      boolean created) {
  }

  public record SyncProjectChatCommand(
      String projectKey,
      String projectName,
      String chatTitle,
      List<ParticipantInput> participants,
      Boolean displayHistory) {
  }

  public record ParticipantInput(
      String email,
      String userId) {
  }

  public record SyncProjectChatResult(
      String projectKey,
      String ownerKind,
      String ownerKey,
      String chatId,
      String chatTitle,
      boolean created,
      int addedCount,
      int ignoredCount,
      int failedCount,
      java.time.OffsetDateTime lastSyncAt,
      List<ParticipantResult> participants) {
  }

  public record SendProjectNotificationCommand(
      String externalId,
      String projectKey,
      String projectName,
      String chatTitle,
      JsonNode payload,
      Integer maxAttempts) {
  }

  public record SendProjectNotificationResult(
      long outboxId,
      String externalId,
      ru.truconf.proxydb.domain.OutboxStatus status,
      boolean created,
      String projectKey,
      String ownerKind,
      String ownerKey,
      String chatId,
      String chatTitle) {
  }

  public record SyncManagedChatCommand(
      String ownerKind,
      String ownerKey,
      String projectKey,
      String projectName,
      String chatTitle,
      String existingChatId,
      List<ParticipantInput> participants,
      Boolean displayHistory) {
  }

  public record SendManagedChatNotificationCommand(
      String externalId,
      String ownerKind,
      String ownerKey,
      String projectKey,
      String projectName,
      String chatTitle,
      String existingChatId,
      JsonNode payload,
      Integer maxAttempts) {
  }
}
