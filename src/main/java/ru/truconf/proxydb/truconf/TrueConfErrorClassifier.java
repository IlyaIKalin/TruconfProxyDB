package ru.truconf.proxydb.truconf;

import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class TrueConfErrorClassifier {

  private static final Set<String> RETRYABLE_CODES = Set.of(
      "100",
      "101",
      "203",
      "300",
      "301",
      "311");

  private static final Set<String> TERMINAL_CODES = Set.of(
      "200",
      "201",
      "202",
      "204",
      "302",
      "303",
      "304",
      "305",
      "306",
      "307",
      "308",
      "309",
      "310",
      "312");

  public boolean isRetryable(TrueConfError error) {
    if (error == null || error.code() == null) {
      return false;
    }
    String code = error.code().trim();
    if (RETRYABLE_CODES.contains(code)) {
      return true;
    }
    if (TERMINAL_CODES.contains(code)) {
      return false;
    }
    return false;
  }
}
