package ru.truconf.proxydb.truconf;

import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class TrueConfResponseMapper {

  public Optional<TrueConfError> extractError(JsonNode response) {
    if (response == null) {
      return Optional.empty();
    }

    JsonNode payload = payload(response);
    String code = firstText(
        child(payload, "errorCode"),
        child(payload, "error"),
        child(response, "errorCode"),
        child(response, "error"));
    if (code == null) {
      return Optional.empty();
    }

    String message = firstText(
        child(payload, "errorDescription"),
        child(payload, "error_description"),
        child(payload, "message"),
        child(response, "errorDescription"),
        child(response, "error_description"),
        child(response, "message"));
    if (message == null) {
      message = "TrueConf error " + code;
    }

    return Optional.of(new TrueConfError(code, message, response));
  }

  public TrueConfResponse mapSuccess(JsonNode response) {
    JsonNode payload = payload(response);
    return new TrueConfResponse(
        firstText(child(payload, "chatId"), child(response, "chatId")),
        firstText(child(payload, "messageId"), child(response, "messageId")),
        firstText(child(payload, "fileId"), child(response, "fileId")),
        firstLong(child(payload, "timestamp"), child(response, "timestamp")),
        firstText(child(payload, "uploadTaskId"), child(response, "uploadTaskId")),
        firstText(child(payload, "temporalFileId"), child(response, "temporalFileId")),
        firstText(child(payload, "userId"), child(response, "userId")),
        response);
  }

  private static JsonNode payload(JsonNode response) {
    JsonNode payload = child(response, "payload");
    return payload == null || !payload.isObject() ? response : payload;
  }

  private static JsonNode child(JsonNode node, String fieldName) {
    if (node == null || !node.isObject()) {
      return null;
    }
    JsonNode child = node.get(fieldName);
    return child == null || child.isNull() || child.isMissingNode() ? null : child;
  }

  private static String firstText(JsonNode... nodes) {
    for (JsonNode node : nodes) {
      String value = textValue(node);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private static String textValue(JsonNode node) {
    if (node == null) {
      return null;
    }
    if (node.isTextual()) {
      String value = node.asText();
      return value.isBlank() ? null : value;
    }
    if (node.isNumber() || node.isBoolean()) {
      return node.asText();
    }
    return null;
  }

  private static Long firstLong(JsonNode... nodes) {
    for (JsonNode node : nodes) {
      if (node == null) {
        continue;
      }
      if (node.isLong() || node.isInt() || node.canConvertToLong()) {
        return node.asLong();
      }
      if (node.isTextual()) {
        try {
          return Long.parseLong(node.asText());
        } catch (NumberFormatException ignored) {
          // Try the next candidate.
        }
      }
    }
    return null;
  }
}
