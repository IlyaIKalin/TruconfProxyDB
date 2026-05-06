package ru.truconf.proxydb.outbox;

public record SentOutboxResult(
    String trueconfChatId,
    String trueconfMessageId,
    String trueconfFileId,
    Long trueconfTimestamp,
    String responseJson) {
}
