package ru.truconf.proxydb.files;

public class FileStorageException extends RuntimeException {

  private final String code;
  private final boolean retryable;

  public FileStorageException(String code, String message, boolean retryable) {
    super(message);
    this.code = code;
    this.retryable = retryable;
  }

  public FileStorageException(String code, String message, boolean retryable, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.retryable = retryable;
  }

  public String code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }
}
