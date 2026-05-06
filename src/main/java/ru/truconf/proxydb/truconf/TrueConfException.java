package ru.truconf.proxydb.truconf;

import tools.jackson.databind.JsonNode;

public class TrueConfException extends RuntimeException {

  private final String code;
  private final boolean retryable;
  private final JsonNode rawResponse;

  public TrueConfException(String code, String message, boolean retryable) {
    this(code, message, retryable, null, null);
  }

  public TrueConfException(
      String code,
      String message,
      boolean retryable,
      JsonNode rawResponse) {
    this(code, message, retryable, rawResponse, null);
  }

  public TrueConfException(
      String code,
      String message,
      boolean retryable,
      Throwable cause) {
    this(code, message, retryable, null, cause);
  }

  public TrueConfException(
      String code,
      String message,
      boolean retryable,
      JsonNode rawResponse,
      Throwable cause) {
    super(message, cause);
    this.code = code;
    this.retryable = retryable;
    this.rawResponse = rawResponse;
  }

  public String code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }

  public JsonNode rawResponse() {
    return rawResponse;
  }
}
