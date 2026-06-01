package ru.truconf.proxydb.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;
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
import ru.truconf.proxydb.truconf.DefaultTrueConfClient;
import ru.truconf.proxydb.truconf.TrueConfCommandFactory;
import ru.truconf.proxydb.truconf.TrueConfErrorClassifier;
import ru.truconf.proxydb.truconf.TrueConfFileUploadClient;
import ru.truconf.proxydb.truconf.TrueConfRateLimiter;
import ru.truconf.proxydb.truconf.TrueConfResponseMapper;
import ru.truconf.proxydb.truconf.TrueConfSession;
import ru.truconf.proxydb.truconf.TrueConfTokenService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class OutboxDeliveryFakeTrueConfIntegrationTests {

  private static final String WORKER_ID = "fake-trueconf-worker";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("truconf_proxydb")
          .withUsername("truconf_proxydb")
          .withPassword("truconf_proxydb");

  private final ObjectMapper objectMapper = new ObjectMapper();
  private MockWebServer server;
  private TrueConfSession session;
  private OutboxRepository repository;

  @BeforeEach
  void setUp() throws Exception {
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

    server = new MockWebServer();
    server.enqueue(tokenResponse());
    server.enqueue(new MockResponse().withWebSocketUpgrade(new TrueConfWebSocket()));
    server.enqueue(fileUploadResponse());
    server.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (session != null) {
      session.close();
    }
    if (server != null) {
      server.shutdown();
    }
  }

  @Test
  void sendFileJobRunsThroughRealOAuthWebSocketAndHttpUploadFlow() throws Exception {
    AppProperties properties = properties();
    TrueConfCommandFactory commandFactory = new TrueConfCommandFactory(objectMapper);
    TrueConfResponseMapper responseMapper = new TrueConfResponseMapper();
    TrueConfErrorClassifier errorClassifier = new TrueConfErrorClassifier();
    TrueConfTokenService tokenService = new TrueConfTokenService(
        properties,
        RestClient.builder(),
        objectMapper);
    session = new TrueConfSession(
        properties,
        tokenService,
        commandFactory,
        responseMapper,
        errorClassifier,
        objectMapper);
    TrueConfFileUploadClient fileUploadClient = new TrueConfFileUploadClient(
        properties,
        RestClient.builder(),
        tokenService,
        responseMapper,
        errorClassifier,
        objectMapper);
    DefaultTrueConfClient trueConfClient = new DefaultTrueConfClient(
        session,
        commandFactory,
        fileUploadClient,
        new TrueConfRateLimiter(properties));
    OutboxDeliveryExecutor executor = new OutboxDeliveryExecutor(
        repository,
        new P2pChatResolver(repository, trueConfClient, email -> java.util.Optional.empty()),
        trueConfClient,
        new DbOnlyFileStorageService(),
        new RetryPolicy(properties.retry()),
        objectMapper);

    OutboxJob job = claimedFileJob();

    executor.execute(job, WORKER_ID);

    OutboxJob stored = repository.findById(job.id()).orElseThrow();
    assertThat(stored.status()).isEqualTo(OutboxStatus.SENT);
    assertThat(stored.trueconfChatId()).isEqualTo("chat-1");
    assertThat(stored.trueconfMessageId()).isEqualTo("message-file-1");
    assertThat(stored.trueconfFileId()).isEqualTo("file-1");

    RecordedRequest tokenRequest = server.takeRequest(2, TimeUnit.SECONDS);
    RecordedRequest websocketRequest = server.takeRequest(2, TimeUnit.SECONDS);
    RecordedRequest uploadRequest = server.takeRequest(2, TimeUnit.SECONDS);
    assertThat(tokenRequest.getPath()).isEqualTo("/bridge/api/client/v1/oauth/token");
    assertThat(websocketRequest.getPath()).isEqualTo("/websocket/chat_bot/");
    assertThat(uploadRequest.getPath()).isEqualTo("/bridge/api/client/v1/files");
    assertThat(uploadRequest.getHeader("Upload-Task-Id")).isEqualTo("upload-task-1");
    assertThat(uploadRequest.getHeader("Authorization")).isEqualTo("Bearer token-1");
    assertThat(uploadRequest.getBody().readUtf8()).contains("report.txt", "hello file");
  }

  private OutboxJob claimedFileJob() {
    OutboxJob created = repository.create(new CreateOutboxJobCommand(
        "fake-server-file",
        OutboxOperation.SEND_FILE,
        RecipientKind.CHAT,
        "chat-1",
        null,
        null,
        "reply-1",
        """
            {"caption":"caption","parseMode":"text"}
            """,
        3,
        OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1)));
    repository.createFile(new CreateOutboxFileCommand(
        created.id(),
        "report.txt",
        "text/plain",
        10,
        FileStorageKind.DB,
        null,
        "hello file".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        null,
        null,
        null,
        null,
        null));
    return repository.claimBatch(WORKER_ID, Duration.ofMinutes(2), 1).getFirst();
  }

  private AppProperties properties() {
    return new AppProperties(
        server.url("").toString(),
        server.url("/websocket/chat_bot/").toString().replace("http://", "ws://"),
        "bot-client",
        "bot-user",
        "bot-password",
        "api-key",
        "/tmp/truconf-proxydb-test-files",
        new AppProperties.Dispatcher(10, Duration.ofSeconds(5), Duration.ofMinutes(2), 2),
        new AppProperties.Retry(3, Duration.ofMillis(100), Duration.ofSeconds(5), 2.0),
        new AppProperties.RateLimit(1_000),
        new AppProperties.Websocket(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofMillis(100)));
  }

  private MockResponse tokenResponse() {
    return new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""
            {"access_token":"token-1","expires_in":3600}
            """);
  }

  private MockResponse fileUploadResponse() {
    return new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""
            {"payload":{"temporalFileId":"temporal-file-1"}}
            """);
  }

  private String response(long id, String payloadJson) {
    return """
        {"type":2,"id":%d,"payload":%s}
        """.formatted(id, payloadJson);
  }

  private JsonNode read(String text) {
    try {
      return objectMapper.readTree(text);
    } catch (Exception ex) {
      throw new IllegalArgumentException(ex);
    }
  }

  private final class TrueConfWebSocket extends WebSocketListener {

    @Override
    public void onMessage(WebSocket webSocket, String text) {
      JsonNode message = read(text);
      long id = message.get("id").asLong();
      String method = message.get("method").asText();
      if ("auth".equals(method)) {
        webSocket.send(response(id, "{}"));
        return;
      }
      if ("uploadFile".equals(method)) {
        webSocket.send(response(id, """
            {"uploadTaskId":"upload-task-1"}
            """));
        return;
      }
      if ("sendFile".equals(method)) {
        assertThat(message.get("payload").get("content").get("temporalFileId").asText())
            .isEqualTo("temporal-file-1");
        webSocket.send(response(id, """
            {
              "chatId":"chat-1",
              "messageId":"message-file-1",
              "fileId":"file-1",
              "timestamp":1735134222098
            }
            """));
        return;
      }
      throw new AssertionError("Unexpected method: " + method);
    }
  }

  private static final class DbOnlyFileStorageService implements FileStorageService {

    @Override
    public StoredFile store(long outboxId, MultipartFile file) {
      throw new UnsupportedOperationException("store is not used in fake TrueConf integration test");
    }

    @Override
    public java.io.InputStream open(FileStorageKind storageKind, String filePath, byte[] fileData) {
      assertThat(storageKind).isEqualTo(FileStorageKind.DB);
      return new java.io.ByteArrayInputStream(fileData);
    }

    @Override
    public void delete(StoredFile file) {
      throw new UnsupportedOperationException("delete is not used in fake TrueConf integration test");
    }
  }
}
