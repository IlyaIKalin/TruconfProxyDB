package ru.truconf.proxydb.api;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.truconf.proxydb.api.OutboxDtos.CreateOutboxFileRequest;
import ru.truconf.proxydb.api.OutboxDtos.CreateOutboxRequest;
import ru.truconf.proxydb.api.OutboxDtos.CreateOutboxResponse;
import ru.truconf.proxydb.api.OutboxDtos.OutboxStatusResponse;
import ru.truconf.proxydb.config.AppProperties;
import ru.truconf.proxydb.domain.OutboxJob;
import ru.truconf.proxydb.domain.OutboxOperation;
import ru.truconf.proxydb.outbox.CreateOutboxJobCommand;
import ru.truconf.proxydb.outbox.EnqueuedOutboxJob;
import ru.truconf.proxydb.outbox.OutboxService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/outbox")
public class OutboxController {

  private final OutboxService service;
  private final ObjectMapper objectMapper;
  private final AppProperties properties;

  public OutboxController(
      OutboxService service,
      ObjectMapper objectMapper,
      AppProperties properties) {
    this.service = service;
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  @PostMapping
  public ResponseEntity<CreateOutboxResponse> create(
      @Valid @RequestBody CreateOutboxRequest request) {
    validateBusinessRules(request);

    EnqueuedOutboxJob result = service.enqueue(toCommand(request));
    HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;

    return ResponseEntity.status(status)
        .location(URI.create("/api/v1/outbox/" + result.job().id()))
        .body(CreateOutboxResponse.from(result.job()));
  }

  @PostMapping(path = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<CreateOutboxResponse> createFile(
      @Valid @RequestPart("request") CreateOutboxFileRequest request,
      @RequestPart("file") MultipartFile file,
      @RequestPart(value = "preview", required = false) MultipartFile preview) {
    validateFileParts(file, preview);

    EnqueuedOutboxJob result = service.enqueueFile(toFileCommand(request), file, preview);
    HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;

    return ResponseEntity.status(status)
        .location(URI.create("/api/v1/outbox/" + result.job().id()))
        .body(CreateOutboxResponse.from(result.job()));
  }

  @GetMapping("/{id}")
  public OutboxStatusResponse getById(@PathVariable long id) {
    return toStatusResponse(service.getById(id));
  }

  @GetMapping("/by-external-id/{externalId}")
  public OutboxStatusResponse getByExternalId(@PathVariable String externalId) {
    return toStatusResponse(service.getByExternalId(externalId));
  }

  private CreateOutboxJobCommand toCommand(CreateOutboxRequest request) {
    return new CreateOutboxJobCommand(
        normalizeBlank(request.externalId()),
        request.operation(),
        request.recipient().kind(),
        normalizeBlank(request.recipient().chatId()),
        normalizeBlank(request.recipient().userId()),
        normalizeBlank(request.targetMessageId()),
        normalizeBlank(request.replyMessageId()),
        writePayload(request.payload()),
        request.maxAttempts() == null ? properties.retry().maxAttempts() : request.maxAttempts(),
        null);
  }

  private CreateOutboxJobCommand toFileCommand(CreateOutboxFileRequest request) {
    return new CreateOutboxJobCommand(
        normalizeBlank(request.externalId()),
        OutboxOperation.SEND_FILE,
        request.recipient().kind(),
        normalizeBlank(request.recipient().chatId()),
        normalizeBlank(request.recipient().userId()),
        null,
        normalizeBlank(request.replyMessageId()),
        writePayload(filePayload(request)),
        request.maxAttempts() == null ? properties.retry().maxAttempts() : request.maxAttempts(),
        null);
  }

  private OutboxStatusResponse toStatusResponse(OutboxJob job) {
    return new OutboxStatusResponse(
        job.id(),
        job.externalId(),
        job.operation(),
        job.recipientKind(),
        job.chatId(),
        job.userId(),
        job.targetMessageId(),
        job.replyMessageId(),
        readJson(job.payloadJson()),
        job.status(),
        job.attemptCount(),
        job.maxAttempts(),
        job.nextAttemptAt(),
        job.trueconfChatId(),
        job.trueconfMessageId(),
        job.trueconfFileId(),
        job.trueconfTimestamp(),
        job.lastErrorCode(),
        job.lastErrorMessage(),
        job.lastErrorRetryable(),
        readNullableJson(job.lastResponseJson()),
        job.createdAt(),
        job.updatedAt(),
        job.sentAt(),
        job.failedAt());
  }

  private void validateBusinessRules(CreateOutboxRequest request) {
    if (request.payload() != null && !request.payload().isObject()) {
      throw new ApiValidationException("payload must be a JSON object");
    }

    if (request.operation() == OutboxOperation.SEND_MESSAGE) {
      JsonNode text = request.payload() == null ? null : request.payload().get("text");
      if (text == null || !text.isTextual() || text.asText().isBlank()) {
        throw new ApiValidationException("payload.text is required for SEND_MESSAGE");
      }
    }

    if (requiresTargetMessageId(request.operation())
        && normalizeBlank(request.targetMessageId()) == null) {
      throw new ApiValidationException(
          "targetMessageId is required for " + request.operation().name());
    }
  }

  private void validateFileParts(MultipartFile file, MultipartFile preview) {
    if (file == null) {
      throw new ApiValidationException("file part is required");
    }
    if (file.isEmpty()) {
      throw new ApiValidationException("file must not be empty");
    }
    if (preview != null && preview.isEmpty()) {
      throw new ApiValidationException("preview must not be empty");
    }
  }

  private JsonNode filePayload(CreateOutboxFileRequest request) {
    var payload = objectMapper.createObjectNode();
    String caption = normalizeBlank(request.caption());
    String parseMode = normalizeBlank(request.parseMode());
    if (caption != null) {
      payload.put("caption", caption);
    }
    if (parseMode != null) {
      payload.put("parseMode", parseMode);
    }
    return payload;
  }

  private boolean requiresTargetMessageId(OutboxOperation operation) {
    return operation == OutboxOperation.EDIT_MESSAGE
        || operation == OutboxOperation.EDIT_SURVEY
        || operation == OutboxOperation.REMOVE_MESSAGE
        || operation == OutboxOperation.FORWARD_MESSAGE;
  }

  private JsonNode readJson(String json) {
    try {
      return objectMapper.readTree(json);
    } catch (JacksonException ex) {
      throw new IllegalStateException("Stored payload_json is not valid JSON", ex);
    }
  }

  private JsonNode readNullableJson(String json) {
    return json == null ? null : readJson(json);
  }

  private String writePayload(JsonNode payload) {
    try {
      JsonNode normalized = payload == null ? objectMapper.createObjectNode() : payload;
      return objectMapper.writeValueAsString(normalized);
    } catch (JacksonException ex) {
      throw new IllegalArgumentException("payload must be valid JSON", ex);
    }
  }

  private static String normalizeBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
