package ru.truconf.proxydb.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.truconf.proxydb.config.AppProperties;
import ru.truconf.proxydb.domain.FileStorageKind;
import ru.truconf.proxydb.domain.OutboxJob;
import ru.truconf.proxydb.domain.OutboxOperation;
import ru.truconf.proxydb.domain.OutboxStatus;
import ru.truconf.proxydb.domain.RecipientKind;
import ru.truconf.proxydb.files.FileStorageService;
import ru.truconf.proxydb.files.StoredFile;
import ru.truconf.proxydb.outbox.CreateOutboxFileCommand;
import ru.truconf.proxydb.outbox.CreateOutboxJobCommand;
import ru.truconf.proxydb.outbox.OutboxRepository;
import ru.truconf.proxydb.truconf.TrueConfClient;
import ru.truconf.proxydb.truconf.TrueConfException;
import ru.truconf.proxydb.truconf.TrueConfResponse;
import ru.truconf.proxydb.truconf.TrueConfUploadFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class OutboxDeliveryExecutorTests {

  private static final String WORKER_ID = "executor-test-worker";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("truconf_proxydb")
          .withUsername("truconf_proxydb")
          .withPassword("truconf_proxydb");

  private final ObjectMapper objectMapper = new ObjectMapper();
  private OutboxRepository repository;
  private FakeTrueConfClient trueConfClient;
  private FakeUserDirectory userDirectory;
  private OutboxDeliveryExecutor executor;

  @BeforeEach
  void migrateCleanDatabase() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(),
        POSTGRES.getUsername(),
        POSTGRES.getPassword());

    Flyway flyway = Flyway.configure()
        .dataSource(dataSource)
        .cleanDisabled(false)
        .load();

    flyway.clean();
    flyway.migrate();

    repository = new OutboxRepository(new JdbcTemplate(dataSource));
    trueConfClient = new FakeTrueConfClient(objectMapper);
    userDirectory = new FakeUserDirectory();
    executor = new OutboxDeliveryExecutor(
        repository,
        new P2pChatResolver(
            repository,
            trueConfClient,
            new TrueConfUserIdResolver(repository, userDirectory)),
        trueConfClient,
        new DbOnlyFileStorageService(),
        new RetryPolicy(new AppProperties.Retry(
            10,
            Duration.ofMillis(100),
            Duration.ofSeconds(5),
            2.0)),
        objectMapper);
  }

  @Test
  void sendMessageToUserCreatesP2pCacheAndMarksSent() {
    OutboxJob job = claimedJob(
        "send-message-user",
        OutboxOperation.SEND_MESSAGE,
        RecipientKind.USER,
        null,
        "user-1",
        null,
        null,
        """
            {"text":"Hello","parseMode":"markdown"}
            """,
        3);

    executor.execute(job, WORKER_ID);

    OutboxJob stored = repository.findById(job.id()).orElseThrow();
    assertThat(stored.status()).isEqualTo(OutboxStatus.SENT);
    assertThat(stored.trueconfChatId()).isEqualTo("p2p-user-1");
    assertThat(stored.trueconfMessageId()).isEqualTo("message-1");
    assertThat(read(stored.lastResponseJson()).get("payload").get("messageId").asText())
        .isEqualTo("message-1");
    assertThat(repository.findP2pChatByUserId("user-1"))
        .isPresent()
        .get()
        .extracting(entry -> entry.chatId())
        .isEqualTo("p2p-user-1");
    assertThat(trueConfClient.calls()).containsExactly(
        "createP2PChat:user-1",
        "sendMessage:p2p-user-1:Hello:markdown:null");
  }

  @Test
  void sendMessageUsesCachedP2pChat() {
    repository.upsertP2pChat("user-2", "cached-chat-2");
    OutboxJob job = claimedJob(
        "send-message-cache",
        OutboxOperation.SEND_MESSAGE,
        RecipientKind.USER,
        null,
        "user-2",
        null,
        null,
        """
            {"text":"Cached hello"}
            """,
        3);

    executor.execute(job, WORKER_ID);

    assertThat(repository.findById(job.id()).orElseThrow().status()).isEqualTo(OutboxStatus.SENT);
    assertThat(trueConfClient.calls()).containsExactly("sendMessage:cached-chat-2:Cached hello:null:null");
  }

  @Test
  void sendMessageToUserEmailResolvesTrueconfIdFromAdAndCachesIt() {
    userDirectory.add("employee@example.com", "gd.rt.ru\\employee@s13.trueconf.rt.ru", "Employee User");
    OutboxJob job = claimedEmailJob(
        "send-message-user-email",
        "Employee@Example.COM",
        """
            {"text":"Hello by email"}
            """);

    executor.execute(job, WORKER_ID);

    OutboxJob stored = repository.findById(job.id()).orElseThrow();
    assertThat(stored.status()).isEqualTo(OutboxStatus.SENT);
    assertThat(stored.trueconfChatId()).isEqualTo("p2p-gd.rt.ru\\employee@s13.trueconf.rt.ru");
    assertThat(repository.findTrueconfIdByEmail("employee@example.com"))
        .contains("gd.rt.ru\\employee@s13.trueconf.rt.ru");
    assertThat(repository.findP2pChatByUserId("gd.rt.ru\\employee@s13.trueconf.rt.ru"))
        .isPresent();
    assertThat(userDirectory.lookups()).containsExactly("employee@example.com");
    assertThat(trueConfClient.calls()).containsExactly(
        "createP2PChat:gd.rt.ru\\employee@s13.trueconf.rt.ru",
        "sendMessage:p2p-gd.rt.ru\\employee@s13.trueconf.rt.ru:Hello by email:null:null");
  }

  @Test
  void sendMessageToUserEmailUsesCachedTrueconfIdBeforeAdLookup() {
    repository.upsertUserEmailCache(
        "cached@example.com",
        "gd.rt.ru\\cached@s13.trueconf.rt.ru",
        "Cached User");
    OutboxJob job = claimedEmailJob(
        "send-message-user-email-cache",
        "cached@example.com",
        """
            {"text":"Cached email"}
            """);

    executor.execute(job, WORKER_ID);

    assertThat(repository.findById(job.id()).orElseThrow().status()).isEqualTo(OutboxStatus.SENT);
    assertThat(userDirectory.lookups()).isEmpty();
    assertThat(trueConfClient.calls()).containsExactly(
        "createP2PChat:gd.rt.ru\\cached@s13.trueconf.rt.ru",
        "sendMessage:p2p-gd.rt.ru\\cached@s13.trueconf.rt.ru:Cached email:null:null");
  }

  @Test
  void retryableTrueConfErrorMarksRetryWaitUntilAttemptsAreExhausted() {
    trueConfClient.nextSendMessageException = new TrueConfException("300", "temporarily unavailable", true);
    OutboxJob retryable = claimedJob(
        "retryable-error",
        OutboxOperation.SEND_MESSAGE,
        RecipientKind.CHAT,
        "chat-1",
        null,
        null,
        null,
        """
            {"text":"try later"}
            """,
        2);

    executor.execute(retryable, WORKER_ID);

    OutboxJob retryStored = repository.findById(retryable.id()).orElseThrow();
    assertThat(retryStored.status()).isEqualTo(OutboxStatus.RETRY_WAIT);
    assertThat(retryStored.lastErrorCode()).isEqualTo("300");
    assertThat(retryStored.lastErrorRetryable()).isTrue();
    assertThat(retryStored.nextAttemptAt()).isAfter(retryable.nextAttemptAt());

    trueConfClient.nextSendMessageException = new TrueConfException("300", "temporarily unavailable", true);
    OutboxJob exhausted = claimedJob(
        "retry-exhausted",
        OutboxOperation.SEND_MESSAGE,
        RecipientKind.CHAT,
        "chat-1",
        null,
        null,
        null,
        """
            {"text":"last try"}
            """,
        1);

    executor.execute(exhausted, WORKER_ID);

    OutboxJob failed = repository.findById(exhausted.id()).orElseThrow();
    assertThat(failed.status()).isEqualTo(OutboxStatus.FAILED);
    assertThat(failed.lastErrorCode()).isEqualTo("300");
    assertThat(failed.lastErrorRetryable()).isFalse();
    assertThat(failed.lastErrorMessage()).contains("Retry attempts exhausted");
  }

  @Test
  void invalidPayloadMarksFailedWithoutCallingTrueConf() {
    OutboxJob job = claimedJob(
        "invalid-payload",
        OutboxOperation.SEND_MESSAGE,
        RecipientKind.CHAT,
        "chat-1",
        null,
        null,
        null,
        "{}",
        3);

    executor.execute(job, WORKER_ID);

    OutboxJob stored = repository.findById(job.id()).orElseThrow();
    assertThat(stored.status()).isEqualTo(OutboxStatus.FAILED);
    assertThat(stored.lastErrorCode()).isEqualTo("PAYLOAD_FIELD_MISSING");
    assertThat(stored.lastErrorRetryable()).isFalse();
    assertThat(trueConfClient.calls()).isEmpty();
  }

  @Test
  void supportsFileSurveyEditRemoveAndForwardOperations() {
    OutboxJob fileJob = claimedJob(
        "send-file",
        OutboxOperation.SEND_FILE,
        RecipientKind.CHAT,
        "chat-file",
        null,
        null,
        "reply-1",
        """
            {"caption":"file caption","parseMode":"html"}
            """,
        3);
    repository.createFile(new CreateOutboxFileCommand(
        fileJob.id(),
        "report.txt",
        "text/plain",
        11,
        FileStorageKind.DB,
        null,
        "hello file!".getBytes(StandardCharsets.UTF_8),
        "preview.txt",
        "text/plain",
        7L,
        null,
        "preview".getBytes(StandardCharsets.UTF_8)));

    executor.execute(fileJob, WORKER_ID);
    assertSent(fileJob.id(), "file-message-1", "file-1");
    assertThat(trueConfClient.uploadedFileText).isEqualTo("hello file!");
    assertThat(trueConfClient.uploadedPreviewText).isEqualTo("preview");

    OutboxJob surveyJob = claimedJob(
        "send-survey",
        OutboxOperation.SEND_SURVEY,
        RecipientKind.CHAT,
        "chat-survey",
        null,
        null,
        null,
        surveyPayload(),
        3);
    executor.execute(surveyJob, WORKER_ID);
    assertSent(surveyJob.id(), "survey-message-1", null);

    OutboxJob editMessage = claimedJob(
        "edit-message",
        OutboxOperation.EDIT_MESSAGE,
        RecipientKind.CHAT,
        "chat-unused",
        null,
        "message-edit",
        null,
        """
            {"text":"edited","parseMode":"text"}
            """,
        3);
    executor.execute(editMessage, WORKER_ID);
    assertSent(editMessage.id(), "message-edit", null);

    OutboxJob editSurvey = claimedJob(
        "edit-survey",
        OutboxOperation.EDIT_SURVEY,
        RecipientKind.CHAT,
        "chat-unused",
        null,
        "survey-edit",
        null,
        surveyPayload(),
        3);
    executor.execute(editSurvey, WORKER_ID);
    assertSent(editSurvey.id(), "survey-edit", null);

    OutboxJob removeMessage = claimedJob(
        "remove-message",
        OutboxOperation.REMOVE_MESSAGE,
        RecipientKind.CHAT,
        "chat-unused",
        null,
        "remove-1",
        null,
        """
            {"forAll":false}
            """,
        3);
    executor.execute(removeMessage, WORKER_ID);
    assertSent(removeMessage.id(), "remove-1", null);

    OutboxJob forward = claimedJob(
        "forward-message",
        OutboxOperation.FORWARD_MESSAGE,
        RecipientKind.CHAT,
        "chat-forward",
        null,
        "source-1",
        null,
        "{}",
        3);
    executor.execute(forward, WORKER_ID);
    assertSent(forward.id(), "forwarded-source-1", null);

    assertThat(trueConfClient.calls()).contains(
        "sendFile:chat-file:report.txt:file caption:html:reply-1",
        "sendSurvey:chat-survey",
        "editMessage:message-edit:edited:text",
        "editSurvey:survey-edit",
        "removeMessage:remove-1:false",
        "forwardMessage:chat-forward:source-1");
  }

  private OutboxJob claimedJob(
      String externalId,
      OutboxOperation operation,
      RecipientKind recipientKind,
      String chatId,
      String userId,
      String targetMessageId,
      String replyMessageId,
      String payloadJson,
      int maxAttempts) {
    repository.create(new CreateOutboxJobCommand(
        externalId,
        operation,
        recipientKind,
        chatId,
        userId,
        targetMessageId,
        replyMessageId,
        payloadJson,
        maxAttempts,
        OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1)));
    return repository.claimBatch(WORKER_ID, Duration.ofMinutes(2), 1).getFirst();
  }

  private OutboxJob claimedEmailJob(String externalId, String email, String payloadJson) {
    repository.create(new CreateOutboxJobCommand(
        externalId,
        OutboxOperation.SEND_MESSAGE,
        RecipientKind.USER_EMAIL,
        null,
        null,
        email,
        null,
        null,
        payloadJson,
        3,
        OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1)));
    return repository.claimBatch(WORKER_ID, Duration.ofMinutes(2), 1).getFirst();
  }

  private void assertSent(long jobId, String messageId, String fileId) {
    OutboxJob stored = repository.findById(jobId).orElseThrow();
    assertThat(stored.status()).isEqualTo(OutboxStatus.SENT);
    assertThat(stored.trueconfMessageId()).isEqualTo(messageId);
    assertThat(stored.trueconfFileId()).isEqualTo(fileId);
  }

  private String surveyPayload() {
    return """
        {
          "url":"https://survey.example.test/campaign",
          "appVersion":"1",
          "path":"/campaign",
          "title":"Survey",
          "description":"{{Survey}}",
          "buttonText":"Open",
          "secret":"secret",
          "alt":"Survey fallback"
        }
        """;
  }

  private JsonNode read(String json) {
    try {
      return objectMapper.readTree(json);
    } catch (Exception ex) {
      throw new IllegalArgumentException(ex);
    }
  }

  private static final class DbOnlyFileStorageService implements FileStorageService {

    @Override
    public StoredFile store(long outboxId, MultipartFile file) {
      throw new UnsupportedOperationException("store is not used in executor tests");
    }

    @Override
    public InputStream open(FileStorageKind storageKind, String filePath, byte[] fileData) {
      assertThat(storageKind).isEqualTo(FileStorageKind.DB);
      return new ByteArrayInputStream(fileData);
    }

    @Override
    public void delete(StoredFile file) {
      throw new UnsupportedOperationException("delete is not used in executor tests");
    }
  }

  private static final class FakeTrueConfClient implements TrueConfClient {

    private final ObjectMapper objectMapper;
    private final List<String> calls = new ArrayList<>();
    private TrueConfException nextSendMessageException;
    private String uploadedFileText;
    private String uploadedPreviewText;

    private FakeTrueConfClient(ObjectMapper objectMapper) {
      this.objectMapper = objectMapper;
    }

    @Override
    public TrueConfResponse createP2PChat(String userId) {
      calls.add("createP2PChat:" + userId);
      return response("p2p-" + userId, null, null, null);
    }

    @Override
    public TrueConfResponse getChats(int count, int page) {
      calls.add("getChats:" + count + ":" + page);
      return response(null, null, null, null);
    }

    @Override
    public TrueConfResponse createGroupChat(String title) {
      calls.add("createGroupChat:" + title);
      return response("group-" + title, null, null, null);
    }

    @Override
    public TrueConfResponse addChatParticipant(
        String chatId,
        String userId,
        boolean displayHistory) {
      calls.add("addChatParticipant:" + chatId + ":" + userId + ":" + displayHistory);
      return response(chatId, null, null, null);
    }

    @Override
    public TrueConfResponse sendMessage(
        String chatId,
        String text,
        String parseMode,
        String replyMessageId) {
      calls.add("sendMessage:" + chatId + ":" + text + ":" + parseMode + ":" + replyMessageId);
      if (nextSendMessageException != null) {
        TrueConfException exception = nextSendMessageException;
        nextSendMessageException = null;
        throw exception;
      }
      return response(chatId, "message-1", null, 1735134222098L);
    }

    @Override
    public TrueConfResponse sendFile(
        String chatId,
        TrueConfUploadFile file,
        TrueConfUploadFile preview,
        String caption,
        String parseMode,
        String replyMessageId) {
      calls.add("sendFile:" + chatId + ":" + file.fileName() + ":" + caption + ":" + parseMode
          + ":" + replyMessageId);
      uploadedFileText = read(file);
      uploadedPreviewText = preview == null ? null : read(preview);
      return response(chatId, "file-message-1", "file-1", 1735134222099L);
    }

    @Override
    public TrueConfResponse sendSurvey(String chatId, JsonNode surveyPayload, String replyMessageId) {
      calls.add("sendSurvey:" + chatId);
      return response(chatId, "survey-message-1", null, 1735134222100L);
    }

    @Override
    public TrueConfResponse editMessage(String messageId, String text, String parseMode) {
      calls.add("editMessage:" + messageId + ":" + text + ":" + parseMode);
      return response(null, messageId, null, 1735134222101L);
    }

    @Override
    public TrueConfResponse editSurvey(String messageId, JsonNode surveyPayload) {
      calls.add("editSurvey:" + messageId);
      return response(null, messageId, null, 1735134222102L);
    }

    @Override
    public TrueConfResponse removeMessage(String messageId, boolean forAll) {
      calls.add("removeMessage:" + messageId + ":" + forAll);
      return response(null, messageId, null, 1735134222103L);
    }

    @Override
    public TrueConfResponse forwardMessage(String chatId, String messageId) {
      calls.add("forwardMessage:" + chatId + ":" + messageId);
      return response(chatId, "forwarded-" + messageId, null, 1735134222104L);
    }

    private List<String> calls() {
      return calls;
    }

    private TrueConfResponse response(String chatId, String messageId, String fileId, Long timestamp) {
      JsonNode raw = rawResponse(chatId, messageId, fileId, timestamp);
      return new TrueConfResponse(chatId, messageId, fileId, timestamp, null, null, null, raw);
    }

    private JsonNode rawResponse(String chatId, String messageId, String fileId, Long timestamp) {
      var payload = objectMapper.createObjectNode();
      put(payload, "chatId", chatId);
      put(payload, "messageId", messageId);
      put(payload, "fileId", fileId);
      if (timestamp != null) {
        payload.put("timestamp", timestamp);
      }
      var root = objectMapper.createObjectNode();
      root.put("type", 2);
      root.put("id", 1);
      root.set("payload", payload);
      return root;
    }

    private void put(tools.jackson.databind.node.ObjectNode node, String field, String value) {
      if (value != null) {
        node.put(field, value);
      }
    }

    private String read(TrueConfUploadFile file) {
      try (InputStream input = file.openStream()) {
        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
    }
  }

  private static final class FakeUserDirectory implements TrueConfUserDirectory {

    private final java.util.Map<String, Entry> entries = new java.util.LinkedHashMap<>();
    private final List<String> lookups = new ArrayList<>();

    private void add(String email, String trueconfId, String displayName) {
      entries.put(email.toLowerCase(java.util.Locale.ROOT), new Entry(email, trueconfId, displayName));
    }

    @Override
    public java.util.Optional<Entry> findByEmail(String email) {
      lookups.add(email);
      return java.util.Optional.ofNullable(entries.get(email));
    }

    private List<String> lookups() {
      return lookups;
    }
  }
}
