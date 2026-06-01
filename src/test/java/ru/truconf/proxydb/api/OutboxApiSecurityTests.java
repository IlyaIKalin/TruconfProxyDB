package ru.truconf.proxydb.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.FileSystemUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.truconf.proxydb.domain.OutboxFile;
import ru.truconf.proxydb.domain.OutboxJob;
import ru.truconf.proxydb.domain.OutboxOperation;
import ru.truconf.proxydb.domain.OutboxStatus;
import ru.truconf.proxydb.domain.RecipientKind;
import ru.truconf.proxydb.outbox.CreateOutboxJobCommand;
import ru.truconf.proxydb.outbox.OutboxRepository;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "truconf.proxy-api-key=test-api-key",
    "management.health.db.enabled=false",
    "truconf.dispatcher.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class OutboxApiSecurityTests {

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
  private OutboxRepository repository;

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
  void cleanDatabase() throws IOException {
    jdbc.update("truncate table truconf_outbox restart identity cascade");
    jdbc.update("truncate table truconf_user_email_cache");
    FileSystemUtils.deleteRecursively(STORAGE_DIR);
    Files.createDirectories(STORAGE_DIR);
  }

  @Test
  void createJobWithValidApiKeyPersistsJob() throws Exception {
    mockMvc.perform(post("/api/v1/outbox")
            .header(API_KEY_HEADER, "test-api-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "externalId": "crm-api-create-1",
                  "operation": "SEND_MESSAGE",
                  "recipient": {
                    "kind": "USER",
                    "userId": "user@example.com"
                  },
                  "payload": {
                    "text": "Hello",
                    "parseMode": "text"
                  },
                  "maxAttempts": 6
                }
                """))
        .andExpect(status().isCreated())
        .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/outbox/1"))
        .andExpect(jsonPath("$.externalId", equalTo("crm-api-create-1")))
        .andExpect(jsonPath("$.status", equalTo("NEW")));

    OutboxJob stored = repository.findByExternalId("crm-api-create-1").orElseThrow();
    assertThat(stored.operation()).isEqualTo(OutboxOperation.SEND_MESSAGE);
    assertThat(stored.recipientKind()).isEqualTo(RecipientKind.USER);
    assertThat(stored.userId()).isEqualTo("user@example.com");
    assertThat(stored.payloadJson()).contains("\"text\": \"Hello\"");
    assertThat(stored.maxAttempts()).isEqualTo(6);
  }

  @Test
  void createJobWithUserEmailRecipientPersistsNormalizedEmail() throws Exception {
    mockMvc.perform(post("/api/v1/outbox")
            .header(API_KEY_HEADER, "test-api-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "externalId": "crm-api-create-email-1",
                  "operation": "SEND_MESSAGE",
                  "recipient": {
                    "kind": "USER_EMAIL",
                    "email": "User@Example.COM"
                  },
                  "payload": {
                    "text": "Hello"
                  }
                }
                """))
        .andExpect(status().isCreated());

    OutboxJob stored = repository.findByExternalId("crm-api-create-email-1").orElseThrow();
    assertThat(stored.recipientKind()).isEqualTo(RecipientKind.USER_EMAIL);
    assertThat(stored.recipientEmail()).isEqualTo("user@example.com");
    assertThat(stored.userId()).isNull();
  }

  @Test
  void duplicateExternalIdReturnsExistingJobWithoutCreatingDuplicate() throws Exception {
    MvcResult first = mockMvc.perform(post("/api/v1/outbox")
            .header(API_KEY_HEADER, "test-api-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content(validCreateJson("crm-duplicate-1", "First")))
        .andExpect(status().isCreated())
        .andReturn();

    long countAfterFirstCreate = countOutboxRows();

    mockMvc.perform(post("/api/v1/outbox")
            .header(API_KEY_HEADER, "test-api-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content(validCreateJson("crm-duplicate-1", "Second")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", equalTo(readId(first))))
        .andExpect(jsonPath("$.externalId", equalTo("crm-duplicate-1")))
        .andExpect(jsonPath("$.status", equalTo("NEW")));

    assertThat(countOutboxRows()).isEqualTo(countAfterFirstCreate);
  }

  @Test
  void requestWithoutApiKeyIsRejected() throws Exception {
    mockMvc.perform(get("/api/v1/outbox/1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code", equalTo("UNAUTHORIZED")));
  }

  @Test
  void requestWithWrongApiKeyIsRejected() throws Exception {
    mockMvc.perform(get("/api/v1/outbox/1")
            .header(API_KEY_HEADER, "wrong-key"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code", equalTo("UNAUTHORIZED")));
  }

  @Test
  void healthEndpointIsAvailableWithoutApiKey() throws Exception {
    mockMvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", equalTo("UP")));
  }

  @Test
  void portalIsAvailableWithoutApiKey() throws Exception {
    mockMvc.perform(get("/"))
        .andExpect(status().isOk());

    mockMvc.perform(get("/index.html"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("TruconfProxyDB Console")));
  }

  @Test
  void lookupByIdAndExternalIdReturnsJobStatus() throws Exception {
    OutboxJob created = createJob("crm-api-lookup-1");

    mockMvc.perform(get("/api/v1/outbox/{id}", created.id())
            .header(API_KEY_HEADER, "test-api-key"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", equalTo((int) created.id())))
        .andExpect(jsonPath("$.externalId", equalTo("crm-api-lookup-1")))
        .andExpect(jsonPath("$.operation", equalTo("SEND_MESSAGE")))
        .andExpect(jsonPath("$.recipientKind", equalTo("USER")))
        .andExpect(jsonPath("$.userId", equalTo("crm-api-lookup-1@example.com")))
        .andExpect(jsonPath("$.payload.text", equalTo("crm-api-lookup-1")))
        .andExpect(jsonPath("$.status", equalTo("NEW")))
        .andExpect(jsonPath("$.attemptCount", equalTo(0)));

    mockMvc.perform(get("/api/v1/outbox/by-external-id/{externalId}", "crm-api-lookup-1")
            .header(API_KEY_HEADER, "test-api-key"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", equalTo((int) created.id())))
        .andExpect(jsonPath("$.externalId", equalTo("crm-api-lookup-1")))
        .andExpect(jsonPath("$.status", equalTo("NEW")));
  }

  @Test
  void lookupByTrueconfMessageIdReturnsJobStatus() throws Exception {
    OutboxJob created = createJob("crm-api-trueconf-message-lookup-1");
    jdbc.update("""
        update truconf_outbox
        set trueconf_message_id = ?
        where id = ?
        """,
        "306a64ad-3bc7-4504-b3b9-e6f2a72550ca",
        created.id());

    mockMvc.perform(get(
            "/api/v1/outbox/by-trueconf-message-id/{trueconfMessageId}",
            "306a64ad-3bc7-4504-b3b9-e6f2a72550ca")
            .header(API_KEY_HEADER, "test-api-key"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", equalTo((int) created.id())))
        .andExpect(jsonPath("$.externalId", equalTo("crm-api-trueconf-message-lookup-1")))
        .andExpect(jsonPath("$.trueconfMessageId",
            equalTo("306a64ad-3bc7-4504-b3b9-e6f2a72550ca")));
  }

  @Test
  void missingJobReturns404() throws Exception {
    mockMvc.perform(get("/api/v1/outbox/{id}", 9_999)
            .header(API_KEY_HEADER, "test-api-key"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code", equalTo("NOT_FOUND")));
  }

  @Test
  void validationErrorsReturn400() throws Exception {
    mockMvc.perform(post("/api/v1/outbox")
            .header(API_KEY_HEADER, "test-api-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "recipient": {
                    "kind": "USER",
                    "userId": "user@example.com"
                  },
                  "payload": {
                    "text": "Hello"
                  },
                  "maxAttempts": 0
                }
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_ERROR")))
        .andExpect(jsonPath("$.error.details[*].field", hasItem("operation")))
        .andExpect(jsonPath("$.error.details[*].field", hasItem("maxAttempts")));
  }

  @Test
  void businessValidationErrorsReturn400() throws Exception {
    mockMvc.perform(post("/api/v1/outbox")
            .header(API_KEY_HEADER, "test-api-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "operation": "SEND_MESSAGE",
                  "recipient": {
                    "kind": "USER",
                    "userId": "user@example.com"
                  },
                  "payload": {}
                }
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_ERROR")))
        .andExpect(jsonPath("$.error.message", equalTo("payload.text is required for SEND_MESSAGE")));
  }

  @Test
  void createFileJobWithValidApiKeyPersistsJobFileRowAndDiskFiles() throws Exception {
    MvcResult result = mockMvc.perform(multipart("/api/v1/outbox/files")
            .file(jsonPart("request", validFileRequestJson("crm-file-create-1")))
            .file(filePart("file", "report.txt", "file body"))
            .file(filePart("preview", "preview.txt", "preview body"))
            .header(API_KEY_HEADER, "test-api-key"))
        .andExpect(status().isCreated())
        .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/outbox/1"))
        .andExpect(jsonPath("$.externalId", equalTo("crm-file-create-1")))
        .andExpect(jsonPath("$.status", equalTo("NEW")))
        .andReturn();

    long jobId = readId(result);
    OutboxJob stored = repository.findByExternalId("crm-file-create-1").orElseThrow();
    assertThat(stored.id()).isEqualTo(jobId);
    assertThat(stored.operation()).isEqualTo(OutboxOperation.SEND_FILE);
    assertThat(stored.recipientKind()).isEqualTo(RecipientKind.USER);
    assertThat(stored.userId()).isEqualTo("file-user@example.com");
    assertThat(stored.payloadJson()).contains("\"caption\": \"Quarterly report\"");

    OutboxFile storedFile = repository.findFileByOutboxId(jobId).orElseThrow();
    assertThat(storedFile.fileName()).isEqualTo("report.txt");
    assertThat(storedFile.mimeType()).isEqualTo(MediaType.TEXT_PLAIN_VALUE);
    assertThat(storedFile.sizeBytes()).isEqualTo("file body".getBytes(StandardCharsets.UTF_8).length);
    assertThat(storedFile.previewFileName()).isEqualTo("preview.txt");
    assertThat(storedFile.previewSizeBytes())
        .isEqualTo((long) "preview body".getBytes(StandardCharsets.UTF_8).length);

    Path savedFile = Path.of(storedFile.filePath());
    Path savedPreview = Path.of(storedFile.previewFilePath());
    assertThat(savedFile).startsWith(STORAGE_DIR.toAbsolutePath().normalize());
    assertThat(savedPreview).startsWith(STORAGE_DIR.toAbsolutePath().normalize());
    assertThat(Files.readString(savedFile)).isEqualTo("file body");
    assertThat(Files.readString(savedPreview)).isEqualTo("preview body");
  }

  @Test
  void multipartRequestWithoutApiKeyIsRejected() throws Exception {
    mockMvc.perform(multipart("/api/v1/outbox/files")
            .file(jsonPart("request", validFileRequestJson("crm-file-no-key")))
            .file(filePart("file", "report.txt", "file body")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code", equalTo("UNAUTHORIZED")));
  }

  @Test
  void multipartMissingRequestOrFileReturns400() throws Exception {
    mockMvc.perform(multipart("/api/v1/outbox/files")
            .file(filePart("file", "report.txt", "file body"))
            .header(API_KEY_HEADER, "test-api-key"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_ERROR")))
        .andExpect(jsonPath("$.error.message", equalTo("Missing multipart part: request")));

    mockMvc.perform(multipart("/api/v1/outbox/files")
            .file(jsonPart("request", validFileRequestJson("crm-file-missing-file")))
            .header(API_KEY_HEADER, "test-api-key"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_ERROR")))
        .andExpect(jsonPath("$.error.message", equalTo("Missing multipart part: file")));
  }

  @Test
  void multipartEmptyFileAndInvalidRequestReturn400() throws Exception {
    mockMvc.perform(multipart("/api/v1/outbox/files")
            .file(jsonPart("request", validFileRequestJson("crm-file-empty")))
            .file(new MockMultipartFile(
                "file",
                "empty.txt",
                MediaType.TEXT_PLAIN_VALUE,
                new byte[0]))
            .header(API_KEY_HEADER, "test-api-key"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_ERROR")))
        .andExpect(jsonPath("$.error.message", equalTo("file must not be empty")));

    mockMvc.perform(multipart("/api/v1/outbox/files")
            .file(jsonPart("request", """
                {
                  "externalId": "crm-file-invalid-request",
                  "recipient": {
                    "kind": "USER"
                  }
                }
                """))
            .file(filePart("file", "report.txt", "file body"))
            .header(API_KEY_HEADER, "test-api-key"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_ERROR")))
        .andExpect(jsonPath("$.error.details[*].field", hasItem("userRecipientValid")));

    mockMvc.perform(post("/api/v1/outbox")
            .header(API_KEY_HEADER, "test-api-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "operation": "SEND_MESSAGE",
                  "recipient": {
                    "kind": "USER_EMAIL"
                  },
                  "payload": {
                    "text": "Hello"
                  }
                }
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.details[*].field", hasItem("userEmailRecipientValid")));
  }

  @Test
  void duplicateFileExternalIdReturnsExistingJobWithoutCreatingDuplicateRows() throws Exception {
    MvcResult first = mockMvc.perform(multipart("/api/v1/outbox/files")
            .file(jsonPart("request", validFileRequestJson("crm-file-duplicate-1")))
            .file(filePart("file", "first.txt", "first body"))
            .header(API_KEY_HEADER, "test-api-key"))
        .andExpect(status().isCreated())
        .andReturn();

    mockMvc.perform(multipart("/api/v1/outbox/files")
            .file(jsonPart("request", validFileRequestJson("crm-file-duplicate-1")))
            .file(filePart("file", "second.txt", "second body"))
            .header(API_KEY_HEADER, "test-api-key"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", equalTo(readId(first))))
        .andExpect(jsonPath("$.externalId", equalTo("crm-file-duplicate-1")))
        .andExpect(jsonPath("$.status", equalTo("NEW")));

    assertThat(countOutboxRows()).isEqualTo(1);
    assertThat(countOutboxFileRows()).isEqualTo(1);
    OutboxFile storedFile = repository.findFileByOutboxId(readId(first)).orElseThrow();
    assertThat(storedFile.fileName()).isEqualTo("first.txt");
    assertThat(Files.readString(Path.of(storedFile.filePath()))).isEqualTo("first body");
  }

  @Test
  void pathTraversalFileNameIsNormalizedBeforeSavingToDisk() throws Exception {
    MvcResult result = mockMvc.perform(multipart("/api/v1/outbox/files")
            .file(jsonPart("request", validFileRequestJson("crm-file-path-1")))
            .file(filePart("file", "../nested/../../secret.txt", "secret body"))
            .header(API_KEY_HEADER, "test-api-key"))
        .andExpect(status().isCreated())
        .andReturn();

    OutboxFile storedFile = repository.findFileByOutboxId(readId(result)).orElseThrow();
    Path savedFile = Path.of(storedFile.filePath());

    assertThat(storedFile.fileName()).isEqualTo("secret.txt");
    assertThat(savedFile).startsWith(STORAGE_DIR.toAbsolutePath().normalize());
    assertThat(savedFile.getFileName().toString()).endsWith("_secret.txt");
    assertThat(savedFile.toString()).doesNotContain("..");
    assertThat(Files.readString(savedFile)).isEqualTo("secret body");
  }

  private OutboxJob createJob(String externalId) {
    return repository.create(new CreateOutboxJobCommand(
        externalId,
        OutboxOperation.SEND_MESSAGE,
        RecipientKind.USER,
        null,
        externalId + "@example.com",
        null,
        null,
        "{\"text\":\"" + externalId + "\"}",
        10,
        null));
  }

  private long countOutboxRows() {
    Long count = jdbc.queryForObject("select count(*) from truconf_outbox", Long.class);
    return count == null ? 0 : count;
  }

  private long countOutboxFileRows() {
    Long count = jdbc.queryForObject("select count(*) from truconf_outbox_file", Long.class);
    return count == null ? 0 : count;
  }

  private static String validCreateJson(String externalId, String text) {
    return """
        {
          "externalId": "%s",
          "operation": "SEND_MESSAGE",
          "recipient": {
            "kind": "USER",
            "userId": "user@example.com"
          },
          "payload": {
            "text": "%s"
          }
        }
        """.formatted(externalId, text);
  }

  private int readId(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asInt();
  }

  private static MockMultipartFile jsonPart(String name, String json) {
    return new MockMultipartFile(
        name,
        "",
        MediaType.APPLICATION_JSON_VALUE,
        json.getBytes(StandardCharsets.UTF_8));
  }

  private static MockMultipartFile filePart(String name, String fileName, String content) {
    return new MockMultipartFile(
        name,
        fileName,
        MediaType.TEXT_PLAIN_VALUE,
        content.getBytes(StandardCharsets.UTF_8));
  }

  private static String validFileRequestJson(String externalId) {
    return """
        {
          "externalId": "%s",
          "recipient": {
            "kind": "USER",
            "userId": "file-user@example.com"
          },
          "caption": "Quarterly report",
          "parseMode": "text",
          "maxAttempts": 5
        }
        """.formatted(externalId);
  }

  private static Path createStorageDir() {
    try {
      return Files.createTempDirectory("truconf-proxydb-files-test-");
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }
}
