package ru.truconf.proxydb.delivery;

import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import ru.truconf.proxydb.domain.OutboxFile;
import ru.truconf.proxydb.domain.OutboxJob;
import ru.truconf.proxydb.files.FileStorageException;
import ru.truconf.proxydb.files.FileStorageService;
import ru.truconf.proxydb.outbox.OutboxError;
import ru.truconf.proxydb.outbox.OutboxJobExecutor;
import ru.truconf.proxydb.outbox.OutboxRepository;
import ru.truconf.proxydb.outbox.SentOutboxResult;
import ru.truconf.proxydb.truconf.TrueConfClient;
import ru.truconf.proxydb.truconf.TrueConfException;
import ru.truconf.proxydb.truconf.TrueConfResponse;
import ru.truconf.proxydb.truconf.TrueConfUploadFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Primary
@Component
public class OutboxDeliveryExecutor implements OutboxJobExecutor {

  private static final Logger log = LoggerFactory.getLogger(OutboxDeliveryExecutor.class);
  private static final String DEFAULT_ERROR_CODE = "OUTBOX_DELIVERY_FAILED";

  private final OutboxRepository repository;
  private final P2pChatResolver p2pChatResolver;
  private final TrueConfClient trueConfClient;
  private final FileStorageService fileStorageService;
  private final RetryPolicy retryPolicy;
  private final ObjectMapper objectMapper;

  public OutboxDeliveryExecutor(
      OutboxRepository repository,
      P2pChatResolver p2pChatResolver,
      TrueConfClient trueConfClient,
      FileStorageService fileStorageService,
      RetryPolicy retryPolicy,
      ObjectMapper objectMapper) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
    this.p2pChatResolver = Objects.requireNonNull(p2pChatResolver, "p2pChatResolver must not be null");
    this.trueConfClient = Objects.requireNonNull(trueConfClient, "trueConfClient must not be null");
    this.fileStorageService = Objects.requireNonNull(fileStorageService, "fileStorageService must not be null");
    this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  @Override
  public void execute(OutboxJob job, String workerId) {
    Objects.requireNonNull(job, "job must not be null");

    try {
      DeliveryResult result = dispatch(job);
      markSent(job, workerId, result);
    } catch (InvalidOutboxJobException ex) {
      markFailed(job, workerId, new OutboxError(
          ex.code(),
          ex.getMessage(),
          false,
          responseJson(ex.rawResponse())));
    } catch (FileStorageException ex) {
      recordFailureOrRetry(job, workerId, ex.code(), ex.getMessage(), ex.retryable(), null);
    } catch (TrueConfException ex) {
      recordFailureOrRetry(job, workerId, ex.code(), ex.getMessage(), ex.retryable(),
          responseJson(ex.rawResponse()));
    } catch (RuntimeException ex) {
      recordFailureOrRetry(
          job,
          workerId,
          DEFAULT_ERROR_CODE,
          ex.getMessage() == null ? "Outbox delivery failed" : ex.getMessage(),
          true,
          null);
    }
  }

  private DeliveryResult dispatch(OutboxJob job) {
    JsonNode payload = payload(job);
    return switch (job.operation()) {
      case SEND_MESSAGE -> sendMessage(job, payload);
      case SEND_FILE -> sendFile(job, payload);
      case SEND_SURVEY -> sendSurvey(job, payload);
      case EDIT_MESSAGE -> editMessage(job, payload);
      case EDIT_SURVEY -> editSurvey(job, payload);
      case REMOVE_MESSAGE -> removeMessage(job, payload);
      case FORWARD_MESSAGE -> forwardMessage(job);
    };
  }

  private DeliveryResult sendMessage(OutboxJob job, JsonNode payload) {
    String chatId = p2pChatResolver.resolveChatId(job);
    TrueConfResponse response = trueConfClient.sendMessage(
        chatId,
        requiredText(payload, "text"),
        optionalText(payload, "parseMode"),
        job.replyMessageId());
    return new DeliveryResult(response, chatId);
  }

  private DeliveryResult sendFile(OutboxJob job, JsonNode payload) {
    String chatId = p2pChatResolver.resolveChatId(job);
    OutboxFile file = repository.findFileByOutboxId(job.id())
        .orElseThrow(() -> invalid("OUTBOX_FILE_MISSING", "SEND_FILE job has no file row"));

    TrueConfResponse response = trueConfClient.sendFile(
        chatId,
        mainUploadFile(file),
        previewUploadFile(file),
        optionalText(payload, "caption"),
        optionalText(payload, "parseMode"),
        job.replyMessageId());
    return new DeliveryResult(response, chatId);
  }

  private DeliveryResult sendSurvey(OutboxJob job, JsonNode payload) {
    String chatId = p2pChatResolver.resolveChatId(job);
    validateSurveyPayload(payload);
    TrueConfResponse response = trueConfClient.sendSurvey(chatId, payload, job.replyMessageId());
    return new DeliveryResult(response, chatId);
  }

  private DeliveryResult editMessage(OutboxJob job, JsonNode payload) {
    TrueConfResponse response = trueConfClient.editMessage(
        requiredTargetMessageId(job),
        requiredText(payload, "text"),
        optionalText(payload, "parseMode"));
    return new DeliveryResult(response, null);
  }

  private DeliveryResult editSurvey(OutboxJob job, JsonNode payload) {
    validateSurveyPayload(payload);
    TrueConfResponse response = trueConfClient.editSurvey(requiredTargetMessageId(job), payload);
    return new DeliveryResult(response, null);
  }

  private DeliveryResult removeMessage(OutboxJob job, JsonNode payload) {
    TrueConfResponse response = trueConfClient.removeMessage(
        requiredTargetMessageId(job),
        optionalBoolean(payload, "forAll", true));
    return new DeliveryResult(response, null);
  }

  private DeliveryResult forwardMessage(OutboxJob job) {
    String chatId = p2pChatResolver.resolveChatId(job);
    TrueConfResponse response = trueConfClient.forwardMessage(chatId, requiredTargetMessageId(job));
    return new DeliveryResult(response, chatId);
  }

  private void markSent(OutboxJob job, String workerId, DeliveryResult result) {
    TrueConfResponse response = result.response();
    SentOutboxResult sent = new SentOutboxResult(
        firstText(response.chatId(), result.fallbackChatId()),
        response.messageId(),
        response.fileId(),
        response.timestamp(),
        responseJson(response.rawResponse()));

    if (repository.markSent(job.id(), workerId, sent).isEmpty()) {
      log.warn("Outbox job {} was delivered, but SENT transition did not match current lock", job.id());
    }
  }

  private void recordFailureOrRetry(
      OutboxJob job,
      String workerId,
      String code,
      String message,
      boolean retryable,
      String responseJson) {
    OutboxError error = new OutboxError(
        normalizeErrorCode(code),
        normalizeErrorMessage(message),
        retryable,
        responseJson);

    if (retryable && retryPolicy.canRetry(job)) {
      Duration delay = retryPolicy.nextDelay(job);
      if (repository.markRetry(job.id(), workerId, delay, error).isEmpty()) {
        log.warn("Outbox job {} retry transition did not match current lock", job.id());
      }
      return;
    }

    OutboxError finalError = retryable
        ? new OutboxError(
            error.code(),
            "Retry attempts exhausted after " + job.attemptCount() + " attempts: " + error.message(),
            false,
            error.responseJson())
        : error;
    markFailed(job, workerId, finalError);
  }

  private void markFailed(OutboxJob job, String workerId, OutboxError error) {
    if (repository.markFailed(job.id(), workerId, error).isEmpty()) {
      log.warn("Outbox job {} failed, but FAILED transition did not match current lock", job.id());
    }
  }

  private TrueConfUploadFile mainUploadFile(OutboxFile file) {
    return new TrueConfUploadFile(
        file.fileName(),
        file.mimeType(),
        file.sizeBytes(),
        () -> fileStorageService.open(file.storageKind(), file.filePath(), file.fileData()));
  }

  private TrueConfUploadFile previewUploadFile(OutboxFile file) {
    if (file.previewFileName() == null || file.previewFileName().isBlank()) {
      return null;
    }
    if (file.previewSizeBytes() == null) {
      throw invalid("OUTBOX_PREVIEW_SIZE_MISSING", "SEND_FILE preview size is missing");
    }
    return new TrueConfUploadFile(
        file.previewFileName(),
        file.previewMimeType(),
        file.previewSizeBytes(),
        () -> fileStorageService.open(file.storageKind(), file.previewFilePath(), file.previewFileData()));
  }

  private JsonNode payload(OutboxJob job) {
    try {
      JsonNode payload = objectMapper.readTree(job.payloadJson());
      if (payload == null || !payload.isObject()) {
        throw invalid("INVALID_OUTBOX_PAYLOAD", "payload_json must be a JSON object");
      }
      return payload;
    } catch (JacksonException ex) {
      throw new InvalidOutboxJobException(
          "INVALID_OUTBOX_PAYLOAD",
          "payload_json is not valid JSON",
          ex);
    }
  }

  private void validateSurveyPayload(JsonNode payload) {
    requiredText(payload, "url");
    requiredText(payload, "appVersion");
    requiredText(payload, "path");
    requiredText(payload, "title");
    requiredText(payload, "description");
    requiredText(payload, "buttonText");
    requiredText(payload, "secret");
    requiredText(payload, "alt");
  }

  private String requiredTargetMessageId(OutboxJob job) {
    String targetMessageId = job.targetMessageId();
    if (targetMessageId == null || targetMessageId.isBlank()) {
      throw invalid(
          "TARGET_MESSAGE_ID_MISSING",
          "targetMessageId is required for " + job.operation().name());
    }
    return targetMessageId;
  }

  private static String requiredText(JsonNode payload, String fieldName) {
    String value = optionalText(payload, fieldName);
    if (value == null) {
      throw invalid("PAYLOAD_FIELD_MISSING", "payload." + fieldName + " is required");
    }
    return value;
  }

  private static String optionalText(JsonNode payload, String fieldName) {
    if (payload == null || !payload.isObject()) {
      return null;
    }
    JsonNode value = payload.get(fieldName);
    if (value == null || value.isNull() || value.isMissingNode() || !value.isTextual()) {
      return null;
    }
    String text = value.asText();
    return text == null || text.isBlank() ? null : text;
  }

  private static boolean optionalBoolean(JsonNode payload, String fieldName, boolean defaultValue) {
    if (payload == null || !payload.isObject()) {
      return defaultValue;
    }
    JsonNode value = payload.get(fieldName);
    return value != null && value.isBoolean() ? value.asBoolean() : defaultValue;
  }

  private String responseJson(JsonNode node) {
    if (node == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(node);
    } catch (JacksonException ex) {
      return node.toString();
    }
  }

  private static String firstText(String first, String second) {
    return first == null || first.isBlank() ? second : first;
  }

  private static String normalizeErrorCode(String code) {
    return code == null || code.isBlank() ? DEFAULT_ERROR_CODE : code;
  }

  private static String normalizeErrorMessage(String message) {
    return message == null || message.isBlank() ? "Outbox delivery failed" : message;
  }

  private static InvalidOutboxJobException invalid(String code, String message) {
    return new InvalidOutboxJobException(code, message);
  }

  private record DeliveryResult(TrueConfResponse response, String fallbackChatId) {
  }
}
