package ru.truconf.proxydb.truconf;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class DefaultTrueConfClientTests {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final TrueConfCommandFactory commandFactory = new TrueConfCommandFactory(objectMapper);

  @Test
  void sendFileRunsUploadTaskHttpUploadAndSendFileInOrder() {
    RecordingTransport transport = new RecordingTransport(objectMapper);
    RecordingFileUploader uploader = new RecordingFileUploader(objectMapper);
    RecordingRateLimiter rateLimiter = new RecordingRateLimiter();
    DefaultTrueConfClient client = new DefaultTrueConfClient(
        transport,
        commandFactory,
        uploader,
        rateLimiter);

    TrueConfResponse response = client.sendFile(
        "chat-1",
        uploadFile("report.txt", "file body"),
        uploadFile("preview.txt", "preview body"),
        "caption",
        "html",
        "reply-1");

    assertThat(response.messageId()).isEqualTo("message-1");
    assertThat(response.fileId()).isEqualTo("file-1");
    assertThat(transport.methods()).containsExactly("uploadFile", "sendFile");
    assertThat(rateLimiter.acquireCount()).isEqualTo(3);
    assertThat(uploader.uploadTaskId()).isEqualTo("upload-task-1");
    assertThat(uploader.uploadedFileText()).isEqualTo("file body");
    assertThat(uploader.uploadedPreviewText()).isEqualTo("preview body");

    JsonNode sendFileCommand = transport.commands().get(1);
    assertThat(sendFileCommand.get("payload").get("content").get("temporalFileId").asText())
        .isEqualTo("temporal-file-1");
    assertThat(sendFileCommand.get("payload").get("replyMessageId").asText()).isEqualTo("reply-1");
  }

  private TrueConfUploadFile uploadFile(String fileName, String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    return new TrueConfUploadFile(
        fileName,
        "text/plain",
        bytes.length,
        () -> new ByteArrayInputStream(bytes));
  }

  private static final class RecordingRateLimiter extends TrueConfRateLimiter {

    private int acquireCount;

    private RecordingRateLimiter() {
      super(1_000_000, System::nanoTime, ignored -> {
      });
    }

    @Override
    public void acquire() {
      acquireCount++;
    }

    private int acquireCount() {
      return acquireCount;
    }
  }

  private static final class RecordingTransport implements TrueConfCommandTransport {

    private final ObjectMapper objectMapper;
    private final AtomicLong sequence = new AtomicLong();
    private final List<String> methods = new ArrayList<>();
    private final List<JsonNode> commands = new ArrayList<>();

    private RecordingTransport(ObjectMapper objectMapper) {
      this.objectMapper = objectMapper;
    }

    @Override
    public TrueConfResponse request(Function<Long, ObjectNode> commandBuilder) {
      ObjectNode command = commandBuilder.apply(sequence.incrementAndGet());
      commands.add(command);
      String method = command.get("method").asText();
      methods.add(method);
      if ("uploadFile".equals(method)) {
        return new TrueConfResponse(
            null,
            null,
            null,
            null,
            "upload-task-1",
            null,
            null,
            raw("uploadTaskId", "upload-task-1"));
      }
      if ("sendFile".equals(method)) {
        return new TrueConfResponse(
            "chat-1",
            "message-1",
            "file-1",
            1735134222098L,
            null,
            null,
            null,
            raw("messageId", "message-1"));
      }
      throw new AssertionError("Unexpected method: " + method);
    }

    private List<String> methods() {
      return methods;
    }

    private List<JsonNode> commands() {
      return commands;
    }

    private JsonNode raw(String field, String value) {
      ObjectNode payload = objectMapper.createObjectNode();
      payload.put(field, value);
      ObjectNode root = objectMapper.createObjectNode();
      root.put("type", 2);
      root.set("payload", payload);
      return root;
    }
  }

  private static final class RecordingFileUploader implements TrueConfFileUploader {

    private final ObjectMapper objectMapper;
    private String uploadTaskId;
    private String uploadedFileText;
    private String uploadedPreviewText;

    private RecordingFileUploader(ObjectMapper objectMapper) {
      this.objectMapper = objectMapper;
    }

    @Override
    public TrueConfResponse upload(
        String uploadTaskId,
        TrueConfUploadFile file,
        TrueConfUploadFile preview) {
      this.uploadTaskId = uploadTaskId;
      this.uploadedFileText = read(file);
      this.uploadedPreviewText = read(preview);
      return new TrueConfResponse(
          null,
          null,
          null,
          null,
          null,
          "temporal-file-1",
          null,
          raw());
    }

    private String uploadTaskId() {
      return uploadTaskId;
    }

    private String uploadedFileText() {
      return uploadedFileText;
    }

    private String uploadedPreviewText() {
      return uploadedPreviewText;
    }

    private String read(TrueConfUploadFile file) {
      if (file == null) {
        return null;
      }
      try (var input = file.openStream()) {
        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
    }

    private JsonNode raw() {
      ObjectNode payload = objectMapper.createObjectNode();
      payload.put("temporalFileId", "temporal-file-1");
      ObjectNode root = objectMapper.createObjectNode();
      root.put("type", 2);
      root.set("payload", payload);
      return root;
    }
  }
}
