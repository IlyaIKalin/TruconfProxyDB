package ru.truconf.proxydb.truconf;

import tools.jackson.databind.JsonNode;

public record TrueConfResponse(
    String chatId,
    String chatTitle,
    String messageId,
    String fileId,
    Long timestamp,
    String uploadTaskId,
    String temporalFileId,
    String userId,
    JsonNode rawResponse) {
}
