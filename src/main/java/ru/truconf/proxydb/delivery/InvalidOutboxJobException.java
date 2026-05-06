package ru.truconf.proxydb.delivery;

import tools.jackson.databind.JsonNode;

class InvalidOutboxJobException extends RuntimeException {

  private final String code;
  private final JsonNode rawResponse;

  InvalidOutboxJobException(String code, String message) {
    this(code, message, null);
  }

  InvalidOutboxJobException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.rawResponse = null;
  }

  String code() {
    return code;
  }

  JsonNode rawResponse() {
    return rawResponse;
  }
}
