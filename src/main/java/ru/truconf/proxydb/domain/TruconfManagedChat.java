package ru.truconf.proxydb.domain;

import java.time.OffsetDateTime;

public record TruconfManagedChat(
    long id,
    String ownerSystem,
    String ownerKind,
    String ownerKey,
    String chatId,
    String title,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    OffsetDateTime lastSyncAt) {
}
