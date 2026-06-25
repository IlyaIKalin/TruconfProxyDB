package ru.truconf.proxydb.truconf;

import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class TrueConfCommandFactory {

  public static final int REQUEST_TYPE = 1;
  public static final int RESPONSE_TYPE = 2;
  private static final String DEFAULT_TOKEN_TYPE = "JWT";
  private static final String DEFAULT_PARSE_MODE = "text";

  private final ObjectMapper objectMapper;

  public TrueConfCommandFactory(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public ObjectNode ack(long id) {
    ObjectNode command = objectMapper.createObjectNode();
    command.put("type", RESPONSE_TYPE);
    command.put("id", id);
    return command;
  }

  public ObjectNode auth(long id, String token) {
    return auth(id, token, false, true);
  }

  public ObjectNode auth(
      long id,
      String token,
      boolean receiveUnread,
      boolean receiveSystemMessageEnvelopes) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("token", requireText(token, "token"));
    payload.put("tokenType", DEFAULT_TOKEN_TYPE);
    payload.put("receiveUnread", receiveUnread);
    payload.put("receiveSystemMessageEnvelopes", receiveSystemMessageEnvelopes);
    return request(id, "auth", payload);
  }

  public ObjectNode createP2PChat(long id, String userId) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("userId", requireText(userId, "userId"));
    return request(id, "createP2PChat", payload);
  }

  public ObjectNode getChats(long id, int count, int page) {
    if (count < 1) {
      throw new IllegalArgumentException("count must be positive");
    }
    if (page < 1) {
      throw new IllegalArgumentException("page must be positive");
    }

    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("count", count);
    payload.put("page", page);
    return request(id, "getChats", payload);
  }

  public ObjectNode getChatById(long id, String chatId) {
    return request(id, "getChatByID", payloadWithChatId(chatId));
  }

  public ObjectNode createGroupChat(long id, String title) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("title", requireText(title, "title"));
    return request(id, "createGroupChat", payload);
  }

  public ObjectNode addChatParticipant(
      long id,
      String chatId,
      String userId,
      boolean displayHistory) {
    ObjectNode payload = payloadWithChatId(chatId);
    payload.put("userId", requireText(userId, "userId"));
    payload.put("displayHistory", displayHistory);
    return request(id, "addChatParticipant", payload);
  }

  public ObjectNode sendMessage(
      long id,
      String chatId,
      String text,
      String parseMode,
      String replyMessageId) {
    ObjectNode payload = payloadWithChatId(chatId);
    putOptionalText(payload, "replyMessageId", replyMessageId);
    payload.set("content", textContent(text, parseMode));
    return request(id, "sendMessage", payload);
  }

  public ObjectNode uploadFile(long id, String fileName, long fileSize) {
    if (fileSize < 0) {
      throw new IllegalArgumentException("fileSize must not be negative");
    }

    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("fileSize", fileSize);
    payload.put("fileName", requireText(fileName, "fileName"));
    return request(id, "uploadFile", payload);
  }

  public ObjectNode sendFile(
      long id,
      String chatId,
      String temporalFileId,
      String caption,
      String parseMode,
      String replyMessageId) {
    ObjectNode payload = payloadWithChatId(chatId);
    putOptionalText(payload, "replyMessageId", replyMessageId);

    ObjectNode content = objectMapper.createObjectNode();
    content.put("temporalFileId", requireText(temporalFileId, "temporalFileId"));
    String normalizedCaption = normalizeBlank(caption);
    if (normalizedCaption != null) {
      content.set("caption", textContent(normalizedCaption, parseMode));
    }
    payload.set("content", content);

    return request(id, "sendFile", payload);
  }

  public ObjectNode sendSurvey(
      long id,
      String chatId,
      JsonNode surveyPayload,
      String replyMessageId) {
    ObjectNode payload = payloadWithChatId(chatId);
    putOptionalText(payload, "replyMessageId", replyMessageId);
    payload.set("content", requiredObject(surveyPayload, "surveyPayload").deepCopy());
    return request(id, "sendSurvey", payload);
  }

  public ObjectNode editMessage(long id, String messageId, String text, String parseMode) {
    ObjectNode payload = payloadWithMessageId(messageId);
    payload.set("content", textContent(text, parseMode));
    return request(id, "editMessage", payload);
  }

  public ObjectNode editSurvey(long id, String messageId, JsonNode surveyPayload) {
    ObjectNode payload = payloadWithMessageId(messageId);
    payload.set("content", requiredObject(surveyPayload, "surveyPayload").deepCopy());
    return request(id, "editSurvey", payload);
  }

  public ObjectNode removeMessage(long id, String messageId) {
    return removeMessage(id, messageId, true);
  }

  public ObjectNode removeMessage(long id, String messageId, boolean forAll) {
    ObjectNode payload = payloadWithMessageId(messageId);
    payload.put("forAll", forAll);
    return request(id, "removeMessage", payload);
  }

  public ObjectNode forwardMessage(long id, String chatId, String messageId) {
    ObjectNode payload = payloadWithChatId(chatId);
    payload.put("messageId", requireText(messageId, "messageId"));
    return request(id, "forwardMessage", payload);
  }

  private ObjectNode request(long id, String method, ObjectNode payload) {
    ObjectNode command = objectMapper.createObjectNode();
    command.put("type", REQUEST_TYPE);
    command.put("id", id);
    command.put("method", method);
    command.set("payload", payload);
    return command;
  }

  private ObjectNode payloadWithChatId(String chatId) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("chatId", requireText(chatId, "chatId"));
    return payload;
  }

  private ObjectNode payloadWithMessageId(String messageId) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("messageId", requireText(messageId, "messageId"));
    return payload;
  }

  private ObjectNode textContent(String text, String parseMode) {
    ObjectNode content = objectMapper.createObjectNode();
    content.put("text", requireText(text, "text"));
    content.put("parseMode", defaultText(parseMode, DEFAULT_PARSE_MODE));
    return content;
  }

  private ObjectNode requiredObject(JsonNode value, String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (!value.isObject()) {
      throw new IllegalArgumentException(name + " must be a JSON object");
    }
    return value.asObject();
  }

  private void putOptionalText(ObjectNode object, String fieldName, String value) {
    String normalized = normalizeBlank(value);
    if (normalized != null) {
      object.put(fieldName, normalized);
    }
  }

  private static String defaultText(String value, String defaultValue) {
    String normalized = normalizeBlank(value);
    return normalized == null ? defaultValue : normalized;
  }

  private static String requireText(String value, String name) {
    String normalized = normalizeBlank(value);
    if (normalized == null) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return normalized;
  }

  private static String normalizeBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
