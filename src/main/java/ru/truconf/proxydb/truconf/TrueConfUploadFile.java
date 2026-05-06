package ru.truconf.proxydb.truconf;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public record TrueConfUploadFile(
    String fileName,
    String mimeType,
    long sizeBytes,
    InputStreamSource inputStreamSource) {

  public TrueConfUploadFile {
    if (fileName == null || fileName.isBlank()) {
      throw new IllegalArgumentException("fileName must not be blank");
    }
    if (sizeBytes < 0) {
      throw new IllegalArgumentException("sizeBytes must not be negative");
    }
    Objects.requireNonNull(inputStreamSource, "inputStreamSource must not be null");
  }

  public InputStream openStream() throws IOException {
    return inputStreamSource.openStream();
  }

  @FunctionalInterface
  public interface InputStreamSource {

    InputStream openStream() throws IOException;
  }
}
