package ru.truconf.proxydb.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.truconf.proxydb.config.AppProperties;
import ru.truconf.proxydb.domain.FileStorageKind;

@Service
public class DiskFileStorageService implements FileStorageService {

  private final Path rootDirectory;
  private final Clock clock;

  @Autowired
  public DiskFileStorageService(AppProperties properties) {
    this(properties, Clock.systemUTC());
  }

  DiskFileStorageService(AppProperties properties, Clock clock) {
    this.rootDirectory = Path.of(properties.fileStorageDir()).toAbsolutePath().normalize();
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public StoredFile store(long outboxId, MultipartFile file) {
    Objects.requireNonNull(file, "file must not be null");
    if (outboxId <= 0) {
      throw new IllegalArgumentException("outboxId must be positive");
    }
    if (file.isEmpty()) {
      throw new IllegalArgumentException("file must not be empty");
    }

    String originalFileName = originalFileName(file);
    String safeFileName = UUID.randomUUID() + "_" + sanitizeFileName(originalFileName);
    LocalDate today = LocalDate.now(clock);
    Path directory = rootDirectory
        .resolve(String.format("%04d", today.getYear()))
        .resolve(String.format("%02d", today.getMonthValue()))
        .resolve(String.format("%02d", today.getDayOfMonth()))
        .resolve(Long.toString(outboxId))
        .normalize();
    Path target = directory.resolve(safeFileName).normalize();

    if (!target.startsWith(rootDirectory)) {
      throw new IllegalArgumentException("file path escapes configured storage directory");
    }

    try {
      Files.createDirectories(directory);
      try (var input = file.getInputStream()) {
        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to store uploaded file", ex);
    }

    return new StoredFile(
        originalFileName,
        normalizeBlank(file.getContentType()),
        file.getSize(),
        target.toString());
  }

  @Override
  public InputStream open(FileStorageKind storageKind, String filePath, byte[] fileData) {
    Objects.requireNonNull(storageKind, "storageKind must not be null");

    if (storageKind == FileStorageKind.DB) {
      if (fileData == null) {
        throw new FileStorageException(
            "FILE_DATA_MISSING",
            "Stored DB file data is missing",
            false);
      }
      return new java.io.ByteArrayInputStream(fileData);
    }

    if (filePath == null || filePath.isBlank()) {
      throw new FileStorageException(
          "FILE_PATH_MISSING",
          "Stored disk file path is missing",
          false);
    }

    Path path = Path.of(filePath).toAbsolutePath().normalize();
    if (!path.startsWith(rootDirectory)) {
      throw new FileStorageException(
          "FILE_PATH_OUTSIDE_STORAGE",
          "Stored disk file path is outside configured storage directory",
          false);
    }

    try {
      return Files.newInputStream(path);
    } catch (NoSuchFileException ex) {
      throw new FileStorageException(
          "FILE_NOT_FOUND",
          "Stored disk file does not exist",
          false,
          ex);
    } catch (IOException ex) {
      throw new FileStorageException(
          "FILE_READ_FAILED",
          "Stored disk file could not be opened",
          false,
          ex);
    }
  }

  @Override
  public void delete(StoredFile file) {
    if (file == null || file.filePath() == null || file.filePath().isBlank()) {
      return;
    }

    Path path = Path.of(file.filePath()).toAbsolutePath().normalize();
    if (!path.startsWith(rootDirectory)) {
      return;
    }

    try {
      Files.deleteIfExists(path);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to delete uploaded file", ex);
    }
  }

  private static String originalFileName(MultipartFile file) {
    String original = normalizeBlank(file.getOriginalFilename());
    if (original == null) {
      return "file";
    }

    String normalizedSeparators = original.replace('\\', '/');
    int lastSeparator = normalizedSeparators.lastIndexOf('/');
    String baseName = lastSeparator >= 0
        ? normalizedSeparators.substring(lastSeparator + 1)
        : normalizedSeparators;
    String normalized = normalizeBlank(baseName);
    return normalized == null ? "file" : normalized;
  }

  private static String sanitizeFileName(String fileName) {
    String sanitized = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
    sanitized = sanitized.replaceAll("^\\.+", "");
    sanitized = normalizeBlank(sanitized);
    return sanitized == null ? "file" : sanitized;
  }

  private static String normalizeBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
