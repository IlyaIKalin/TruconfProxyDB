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
      message = errorMessage(code);
    }

    return Optional.of(new TrueConfError(code, message, response));
  }

  private static String errorMessage(String code) {
    return switch (code) {
      case "100" -> "CONNECTION_ERROR: Connection error";
      case "101" -> "CONNECTION_TIMEOUT: Connection timeout";
      case "102" -> "TLS_ERROR: TLS/SSL error";
      case "103" -> "UNSUPPORTED_PROTOCOL: Unsupported protocol";
      case "104" -> "ROUTE_NOT_FOUND: Route not found";
      case "200" -> "NOT_AUTHORIZED: Not authorized";
      case "201" -> "INVALID_CREDENTIALS: Invalid credentials";
      case "202" -> "USER_DISABLED: User disabled";
      case "203" -> "CREDENTIALS_EXPIRED: Credentials expired";
      case "204" -> "UNSUPPORTED_CREDENTIALS: Invalid token type";
      case "300" -> "INTERNAL_ERROR: Internal server error";
      case "301" -> "TIMEOUT: Operation timeout";
      case "302" -> "ACCESS_DENIED: Access denied";
      case "303" -> "NOT_ENOUGH_RIGHTS: Insufficient rights";
      case "304" -> "CHAT_NOT_FOUND: Chat not found";
      case "305" -> "USER_IS_NOT_CHAT_PARTICIPANT: User is not a chat participant";
      case "306" -> "MESSAGE_NOT_FOUND: Message not found";
      case "307" -> "UNKNOWN_MESSAGE: Unknown message";
      case "308" -> "FILE_NOT_FOUND: File not found";
      case "309" -> "USER_IS_ALREADY_CHAT_PARTICIPANT: User is already a chat participant";
      case "310" -> "FILE_UPLOAD_FAILED: File upload error";
      case "311" -> "FILE_NOT_READY: File is not ready yet";
      case "312" -> "ROLE_NOT_FOUND: Role not found";
      default -> "TrueConf error " + code;
    };
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
