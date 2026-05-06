package ru.truconf.proxydb.domain;

import java.time.OffsetDateTime;

public record OutboxFile(
    long id,
    long outboxId,
    String fileName,
    String mimeType,
    long sizeBytes,
    FileStorageKind storageKind,
    String filePath,
    byte[] fileData,
    String previewFileName,
    String previewMimeType,
    Long previewSizeBytes,
    String previewFilePath,
    byte[] previewFileData,
    OffsetDateTime createdAt) {
}
