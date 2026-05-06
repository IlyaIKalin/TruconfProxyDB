package ru.truconf.proxydb.outbox;

public record OutboxError(
    String code,
    String message,
    boolean retryable,
    String responseJson) {
}
