package ru.truconf.proxydb.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.truconf.proxydb.delivery.TrueConfUserDirectory;
import ru.truconf.proxydb.domain.OutboxJob;
import ru.truconf.proxydb.domain.RecipientKind;
import ru.truconf.proxydb.managedchat.ManagedChatRepository;
import ru.truconf.proxydb.managedchat.SpringFlowProjectChatService;
import ru.truconf.proxydb.managedchat.SpringFlowProjectChatService.SyncProjectChatCommand;
import ru.truconf.proxydb.truconf.TrueConfClient;
import ru.truconf.proxydb.truconf.TrueConfException;
import ru.truconf.proxydb.truconf.TrueConfResponse;
import ru.truconf.proxydb.truconf.TrueConfUploadFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "truconf.proxy-api-key=test-api-key",
    "management.health.db.enabled=false",
    "truconf.dispatcher.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class SpringFlowIntegrationControllerTests {

  private static final String API_KEY_HEADER = "X-API-Key";
  private static final Path STORAGE_DIR = createStorageDir();

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("truconf_proxydb")
          .withUsername("truconf_proxydb")
          .withPassword("truconf_proxydb");

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private ManagedChatRepository managedChatRepository;

  @Autowired
  private SpringFlowProjectChatService service;

  @Autowired
  private RecordingTrueConfClient trueConfClient;

  @Autowired
  private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("truconf.file-storage-dir", () -> STORAGE_DIR.toString());
  }

  @BeforeEach
  void cleanDatabase() {
    jdbc.update("truncate table truconf_outbox restart identity cascade");
    jdbc.update("truncate table truconf_managed_chat restart identity cascade");
    jdbc.update("truncate table truconf_user_email_cache");
    trueConfClient.reset();
  }

  @AfterEach
  void clearInterruptFlag() {
    Thread.interrupted();
  }

  @Test
  void syncCreatesManagedChatAndAddsParticipantsBestEffort() throws Exception {
    trueConfClient.alreadyParticipantUserId("already@example.com");

    mockMvc.perform(post("/api/v1/integrations/springflow/project-chats/sync")
            .header(API_KEY_HEADER, "test-api-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "projectKey": "demo",
                  "projectName": "Demo",
                  "chatTitle": "SpringFlow: Demo",
                  "participants": [
                    { "email": "User@Example.COM" },
                    { "userId": "already@example.com" },
                    { "email": "bad@example.com", "userId": "bad@example.com" }
                  ],
                  "displayHistory": false
                }
                """))
        .andExpect(status().isCreated())
        .andExpect(header().string(
            HttpHeaders.LOCATION,
            "/api/v1/integrations/springflow/project-chats/chat-1"))
        .andExpect(jsonPath("$.projectKey", equalTo("demo")))
        .andExpect(jsonPath("$.chatId", equalTo("chat-1")))
        .andExpect(jsonPath("$.chatTitle", equalTo("SpringFlow: Demo")))
        .andExpect(jsonPath("$.created", equalTo(true)))
        .andExpect(jsonPath("$.addedCount", equalTo(1)))
        .andExpect(jsonPath("$.ignoredCount", equalTo(1)))
        .andExpect(jsonPath("$.failedCount", equalTo(1)))
        .andExpect(jsonPath("$.participants[0].status", equalTo("ADDED")))
        .andExpect(jsonPath("$.participants[0].email", equalTo("user@example.com")))
        .andExpect(jsonPath("$.participants[0].userId", equalTo("tc:user@example.com")))
        .andExpect(jsonPath("$.participants[1].status", equalTo("IGNORED")))
        .andExpect(jsonPath("$.participants[1].code", equalTo("309")))
        .andExpect(jsonPath("$.participants[2].status", equalTo("FAILED")))
        .andExpect(jsonPath("$.participants[2].code", equalTo("INVALID_PARTICIPANT")));

    assertThat(trueConfClient.calls()).containsExactly(
        "createGroupChat:SpringFlow: Demo",
        "addChatParticipant:chat-1:tc:user@example.com:false",
        "addChatParticipant:chat-1:already@example.com:false");
    assertThat(countManagedChats()).isEqualTo(1);
    assertThat(managedChatRepository.findByOwner("SPRINGFLOW", "PROJECT", "demo"))
        .isPresent()
        .get()
        .satisfies(chat -> {
          assertThat(chat.chatId()).isEqualTo("chat-1");
          assertThat(chat.lastSyncAt()).isNotNull();
        });
  }

  @Test
  void repeatedSyncReusesExistingManagedChat() throws Exception {
    performSync("demo", "SpringFlow: Demo")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.chatId", equalTo("chat-1")))
        .andExpect(jsonPath("$.created", equalTo(true)));

    performSync("demo", "SpringFlow: Demo Updated")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.chatId", equalTo("chat-1")))
        .andExpect(jsonPath("$.chatTitle", equalTo("SpringFlow: Demo Updated")))
        .andExpect(jsonPath("$.created", equalTo(false)));

    assertThat(trueConfClient.calls())
        .filteredOn(call -> call.startsWith("createGroupChat:"))
        .containsExactly("createGroupChat:SpringFlow: Demo");
    assertThat(countManagedChats()).isEqualTo(1);
  }

  @Test
  void managedSyncAcceptsProcessNotificationOwnerAndRegistersExistingChat() throws Exception {
    mockMvc.perform(post("/api/v1/integrations/springflow/managed-chats/sync")
            .header(API_KEY_HEADER, "test-api-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "ownerKind": "PROCESS_NOTIFICATION_CHANNEL",
                  "ownerKey": "demo:10:20",
                  "projectKey": "demo",
                  "projectName": "Demo",
                  "existingChatId": "existing-chat",
                  "participants": []
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ownerKind", equalTo("PROCESS_NOTIFICATION_CHANNEL")))
        .andExpect(jsonPath("$.ownerKey", equalTo("demo:10:20")))
        .andExpect(jsonPath("$.chatId", equalTo("existing-chat")))
        .andExpect(jsonPath("$.chatTitle", equalTo("Fetched existing-chat")))
        .andExpect(jsonPath("$.created", equalTo(false)));

    assertThat(trueConfClient.calls()).contains("getChatById:existing-chat");
    assertThat(trueConfClient.calls())
        .filteredOn(call -> call.startsWith("createGroupChat:"))
        .isEmpty();
    assertThat(managedChatRepository.findByOwner(
        "SPRINGFLOW",
        "PROCESS_NOTIFICATION_CHANNEL",
        "demo:10:20"))
        .isPresent()
        .get()
        .satisfies(chat -> {
          assertThat(chat.chatId()).isEqualTo("existing-chat");
          assertThat(chat.title()).isEqualTo("Fetched existing-chat");
        });
  }

  @Test
  void projectNotificationCreatesChatAndEnqueuesChatRecipientOutboxJob() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/integrations/springflow/project-notifications")
            .header(API_KEY_HEADER, "test-api-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content(validNotificationJson("sf-demo-run-1", "Demo event")))
        .andExpect(status().isCreated())
        .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/outbox/1"))
        .andExpect(jsonPath("$.outboxId", equalTo(1)))
        .andExpect(jsonPath("$.externalId", equalTo("sf-demo-run-1")))
        .andExpect(jsonPath("$.status", equalTo("NEW")))
        .andExpect(jsonPath("$.created", equalTo(true)))
        .andExpect(jsonPath("$.projectKey", equalTo("demo")))
        .andExpect(jsonPath("$.chatId", equalTo("chat-1")))
        .andReturn();

    OutboxJob stored = readJob(readOutboxId(result));
    assertThat(stored.recipientKind()).isEqualTo(RecipientKind.CHAT);
    assertThat(stored.chatId()).isEqualTo("chat-1");
    assertThat(stored.payloadJson()).contains("\"text\": \"Demo event\"");
    assertThat(countManagedChats()).isEqualTo(1);
  }

  @Test
  void duplicateProjectNotificationExternalIdReturnsExistingOutboxJob() throws Exception {
    MvcResult first = mockMvc.perform(post("/api/v1/integrations/springflow/project-notifications")
            .header(API_KEY_HEADER, "test-api-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content(validNotificationJson("sf-duplicate-1", "First")))
        .andExpect(status().isCreated())
        .andReturn();

    mockMvc.perform(post("/api/v1/integrations/springflow/project-notifications")
            .header(API_KEY_HEADER, "test-api-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content(validNotificationJson("sf-duplicate-1", "Second")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outboxId", equalTo((int) readOutboxId(first))))
        .andExpect(jsonPath("$.created", equalTo(false)))
        .andExpect(jsonPath("$.chatId", equalTo("chat-1")));

    assertThat(countOutboxRows()).isEqualTo(1);
    assertThat(readJob(readOutboxId(first)).payloadJson()).contains("\"text\": \"First\"");
  }

  @Test
  void integrationEndpointsRequireApiKey() throws Exception {
    mockMvc.perform(post("/api/v1/integrations/springflow/project-chats/sync")
            .contentType(MediaType.APPLICATION_JSON)
            .content(validSyncJson("demo", "SpringFlow: Demo")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code", equalTo("UNAUTHORIZED")));

    mockMvc.perform(post("/api/v1/integrations/springflow/project-notifications")
            .contentType(MediaType.APPLICATION_JSON)
            .content(validNotificationJson("sf-no-key", "No key")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code", equalTo("UNAUTHORIZED")));
  }

  @Test
  void validationErrorsReturn400() throws Exception {
    mockMvc.perform(post("/api/v1/integrations/springflow/project-chats/sync")
            .header(API_KEY_HEADER, "test-api-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "projectName": "Demo",
                  "chatTitle": "SpringFlow: Demo"
                }
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_ERROR")))
        .andExpect(jsonPath("$.error.details[*].field", hasItem("projectKey")));

    mockMvc.perform(post("/api/v1/integrations/springflow/project-notifications")
            .header(API_KEY_HEADER, "test-api-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "externalId": "sf-invalid-payload",
                  "projectKey": "demo",
                  "projectName": "Demo",
                  "chatTitle": "SpringFlow: Demo",
                  "payload": {}
                }
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_ERROR")))
        .andExpect(jsonPath("$.error.message", equalTo("payload.text is required")));
  }

  @Test
  void concurrentSyncUsesAdvisoryLockAndCreatesSingleGroupChat() throws Exception {
    trueConfClient.delayFirstCreate(Duration.ofMillis(250));
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    Callable<SpringFlowProjectChatService.SyncProjectChatResult> task = () -> {
      ready.countDown();
      ready.await();
      return service.syncProjectChat(new SyncProjectChatCommand(
          "race",
          "Race",
          "SpringFlow: Race",
          List.of(),
          true));
    };

    try {
      Future<SpringFlowProjectChatService.SyncProjectChatResult> first = executor.submit(task);
      Future<SpringFlowProjectChatService.SyncProjectChatResult> second = executor.submit(task);

      SpringFlowProjectChatService.SyncProjectChatResult firstResult = first.get();
      SpringFlowProjectChatService.SyncProjectChatResult secondResult = second.get();

      assertThat(List.of(firstResult.chatId(), secondResult.chatId()))
          .containsOnly("chat-1");
      assertThat(List.of(firstResult.created(), secondResult.created()))
          .containsExactlyInAnyOrder(true, false);
      assertThat(countManagedChats()).isEqualTo(1);
      assertThat(trueConfClient.calls())
          .filteredOn(call -> call.startsWith("createGroupChat:"))
          .containsExactly("createGroupChat:SpringFlow: Race");
    } finally {
      executor.shutdownNow();
    }
  }

  private org.springframework.test.web.servlet.ResultActions performSync(
      String projectKey,
      String chatTitle) throws Exception {
    return mockMvc.perform(post("/api/v1/integrations/springflow/project-chats/sync")
        .header(API_KEY_HEADER, "test-api-key")
        .contentType(MediaType.APPLICATION_JSON)
        .content(validSyncJson(projectKey, chatTitle)));
  }

  private String validSyncJson(String projectKey, String chatTitle) {
    return """
        {
          "projectKey": "%s",
          "projectName": "Demo",
          "chatTitle": "%s",
          "participants": []
        }
        """.formatted(projectKey, chatTitle);
  }

  private static String validNotificationJson(String externalId, String text) {
    return """
        {
          "externalId": "%s",
          "projectKey": "demo",
          "projectName": "Demo",
          "chatTitle": "SpringFlow: Demo",
          "payload": {
            "text": "%s",
            "parseMode": "text"
          }
        }
        """.formatted(externalId, text);
  }

  private long countManagedChats() {
    Long count = jdbc.queryForObject("select count(*) from truconf_managed_chat", Long.class);
    return count == null ? 0 : count;
  }

  private long countOutboxRows() {
    Long count = jdbc.queryForObject("select count(*) from truconf_outbox", Long.class);
    return count == null ? 0 : count;
  }

  private OutboxJob readJob(long id) {
    return jdbc.queryForObject(
        "select * from truconf_outbox where id = ?",
        new ru.truconf.proxydb.outbox.OutboxJobRowMapper(),
        id);
  }

  private long readOutboxId(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("outboxId").asLong();
  }

  private static Path createStorageDir() {
    try {
      return Files.createTempDirectory("truconf-proxydb-springflow-test-");
    } catch (java.io.IOException ex) {
      throw new java.io.UncheckedIOException(ex);
    }
  }

  @TestConfiguration
  static class TestBeans {

    @Bean
    @Primary
    RecordingTrueConfClient recordingTrueConfClient() {
      return new RecordingTrueConfClient();
    }

    @Bean
    @Primary
    TrueConfUserDirectory testTrueConfUserDirectory() {
      return email -> Optional.of(new TrueConfUserDirectory.Entry(
          email,
          "tc:" + email,
          email));
    }
  }

  static final class RecordingTrueConfClient implements TrueConfClient {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger chatSequence = new AtomicInteger();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile String alreadyParticipantUserId;
    private volatile Duration firstCreateDelay = Duration.ZERO;

    @Override
    public TrueConfResponse createP2PChat(String userId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TrueConfResponse getChats(int count, int page) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TrueConfResponse getChatById(String chatId) {
      calls.add("getChatById:" + chatId);
      return new TrueConfResponse(
          chatId,
          "Fetched " + chatId,
          null,
          null,
          null,
          null,
          null,
          null,
          raw("chatId", chatId, "title", "Fetched " + chatId));
    }

    @Override
    public TrueConfResponse createGroupChat(String title) {
      int sequence = chatSequence.incrementAndGet();
      if (sequence == 1 && !firstCreateDelay.isZero()) {
        sleep(firstCreateDelay);
      }
      calls.add("createGroupChat:" + title);
      String chatId = "chat-" + sequence;
      return new TrueConfResponse(chatId, null, null, null, null, null, null, null, raw("chatId", chatId));
    }

    @Override
    public TrueConfResponse addChatParticipant(
        String chatId,
        String userId,
        boolean displayHistory) {
      calls.add("addChatParticipant:" + chatId + ":" + userId + ":" + displayHistory);
      if (userId.equals(alreadyParticipantUserId)) {
        throw new TrueConfException(
            "309",
            "User is already a chat participant",
            false,
            raw("errorCode", "309"));
      }
      return new TrueConfResponse(chatId, null, null, null, null, null, null, userId, raw("userId", userId));
    }

    @Override
    public TrueConfResponse sendMessage(
        String chatId,
        String text,
        String parseMode,
        String replyMessageId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TrueConfResponse sendFile(
        String chatId,
        TrueConfUploadFile file,
        TrueConfUploadFile preview,
        String caption,
        String parseMode,
        String replyMessageId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TrueConfResponse sendSurvey(
        String chatId,
        JsonNode surveyPayload,
        String replyMessageId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TrueConfResponse editMessage(String messageId, String text, String parseMode) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TrueConfResponse editSurvey(String messageId, JsonNode surveyPayload) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TrueConfResponse removeMessage(String messageId, boolean forAll) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TrueConfResponse forwardMessage(String chatId, String messageId) {
      throw new UnsupportedOperationException();
    }

    void alreadyParticipantUserId(String userId) {
      alreadyParticipantUserId = userId;
    }

    void delayFirstCreate(Duration delay) {
      firstCreateDelay = delay;
    }

    List<String> calls() {
      return calls;
    }

    void reset() {
      chatSequence.set(0);
      calls.clear();
      alreadyParticipantUserId = null;
      firstCreateDelay = Duration.ZERO;
    }

    private JsonNode raw(String fieldName, String value) {
      var payload = objectMapper.createObjectNode();
      payload.put(fieldName, value);
      var root = objectMapper.createObjectNode();
      root.put("type", 2);
      root.set("payload", payload);
      return root;
    }

    private JsonNode raw(String firstField, String firstValue, String secondField, String secondValue) {
      var payload = objectMapper.createObjectNode();
      payload.put(firstField, firstValue);
      payload.put(secondField, secondValue);
      var root = objectMapper.createObjectNode();
      root.put("type", 2);
      root.set("payload", payload);
      return root;
    }

    private static void sleep(Duration delay) {
      try {
        Thread.sleep(delay.toMillis());
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while delaying createGroupChat", ex);
      }
    }
  }
}
