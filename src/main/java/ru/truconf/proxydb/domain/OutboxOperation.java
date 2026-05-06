package ru.truconf.proxydb.domain;

public enum OutboxOperation {
  SEND_MESSAGE,
  SEND_FILE,
  SEND_SURVEY,
  EDIT_MESSAGE,
  EDIT_SURVEY,
  REMOVE_MESSAGE,
  FORWARD_MESSAGE
}
