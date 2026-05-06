package ru.truconf.proxydb.api;

public class ApiValidationException extends RuntimeException {

  public ApiValidationException(String message) {
    super(message);
  }
}
