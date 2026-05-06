package ru.truconf.proxydb.domain;

public enum OutboxStatus {
  NEW,
  PROCESSING,
  RETRY_WAIT,
  SENT,
  FAILED
}
