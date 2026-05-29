package ru.truconf.proxydb.truconf;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TrueConfResponseMapperTests {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final TrueConfResponseMapper mapper = new TrueConfResponseMapper();
  private final TrueConfErrorClassifier classifier = new TrueConfErrorClassifier();

  @Test
  void mapsSuccessFieldsFromWebSocketPayload() throws Exception {
    TrueConfResponse response = mapper.mapSuccess(objectMapper.readTree("""
        {
          "type": 2,
          "id": 6,
          "payload": {
            "chatId": "chat-1",
            "timestamp": 1735134222098,
            "messageId": "message-1",
            "fileId": "file-1",
            "uploadTaskId": "upload-task-1",
            "userId": "bot@example.com"
          }
        }
        """));

    assertThat(response.chatId()).isEqualTo("chat-1");
    assertThat(response.messageId()).isEqualTo("message-1");
    assertThat(response.fileId()).isEqualTo("file-1");
    assertThat(response.timestamp()).isEqualTo(1735134222098L);
    assertThat(response.uploadTaskId()).isEqualTo("upload-task-1");
    assertThat(response.userId()).isEqualTo("bot@example.com");
  }

  @Test
  void mapsSuccessFieldsFromHttpUploadBody() throws Exception {
    TrueConfResponse response = mapper.mapSuccess(objectMapper.readTree("""
        {
          "temporalFileId": "temporal-file-1"
        }
        """));

    assertThat(response.temporalFileId()).isEqualTo("temporal-file-1");
    assertThat(response.rawResponse().get("temporalFileId").asText()).isEqualTo("temporal-file-1");
  }

  @Test
  void extractsWebSocketAndHttpErrorShapes() throws Exception {
    TrueConfError websocketError = mapper.extractError(objectMapper.readTree("""
        {
          "type": 2,
          "id": 1,
          "payload": {
            "errorCode": 201
          }
        }
        """)).orElseThrow();

    assertThat(websocketError.code()).isEqualTo("201");
    assertThat(websocketError.message()).isEqualTo("INVALID_CREDENTIALS: Invalid credentials");
    assertThat(classifier.isRetryable(websocketError)).isFalse();

    TrueConfError httpError = mapper.extractError(objectMapper.readTree("""
        {
          "error": 310,
          "error_description": "No upload task ID provided"
        }
        """)).orElseThrow();

    assertThat(httpError.code()).isEqualTo("310");
    assertThat(httpError.message()).isEqualTo("No upload task ID provided");
    assertThat(classifier.isRetryable(httpError)).isFalse();
  }

  @Test
  void classifiesKnownRetryableAndTerminalCodes() {
    assertThat(classifier.isRetryable(new TrueConfError("100", "connect", null))).isTrue();
    assertThat(classifier.isRetryable(new TrueConfError("301", "timeout", null))).isTrue();
    assertThat(classifier.isRetryable(new TrueConfError("304", "chat not found", null))).isFalse();
    assertThat(classifier.isRetryable(new TrueConfError("unknown", "unknown", null))).isFalse();
  }
}
