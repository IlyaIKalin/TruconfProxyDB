package ru.truconf.proxydb.delivery;

import java.util.Optional;

public interface TrueConfUserDirectory {

  Optional<Entry> findByEmail(String email);

  record Entry(
      String email,
      String trueconfId,
      String displayName) {
  }
}
