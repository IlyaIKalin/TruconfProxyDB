package ru.truconf.proxydb.outbox;

public class OutboxJobNotFoundException extends RuntimeException {

  public OutboxJobNotFoundException(String message) {
    super(message);
  }
}
