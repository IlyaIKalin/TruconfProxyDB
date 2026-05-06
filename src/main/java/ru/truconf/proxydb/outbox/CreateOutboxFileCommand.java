package ru.truconf.proxydb.outbox;

import java.util.Objects;
import ru.truconf.proxydb.domain.FileStorageKind;

public record CreateOutboxFileCommand(
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
    byte[] previewFileData) {

  public CreateOutboxFileCommand {
    if (outboxId <= 0) {
      throw new IllegalArgumentException("outboxId must be positive");
    }
    if (fileName == null || fileName.isBlank()) {
      throw new IllegalArgumentException("fileName must not be blank");
    }
    if (sizeBytes < 0) {
      throw new IllegalArgumentException("sizeBytes must not be negative");
    }
    Objects.requireNonNull(storageKind, "storageKind must not be null");
    if (storageKind == FileStorageKind.DISK && (filePath == null || filePath.isBlank())) {
      throw new IllegalArgumentException("filePath must not be blank for DISK storage");
    }
    if (storageKind == FileStorageKind.DB && fileData == null) {
      throw new IllegalArgumentException("fileData must not be null for DB storage");
    }
    if (previewSizeBytes != null && previewSizeBytes < 0) {
      throw new IllegalArgumentException("previewSizeBytes must not be negative");
    }
  }
}
