package ru.truconf.proxydb.domain;

import java.time.OffsetDateTime;

public record P2pChatCacheEntry(
    String userId,
    String chatId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    OffsetDateTime lastUsedAt) {
}
