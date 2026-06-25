package ru.truconf.proxydb.delivery;

import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;
import ru.truconf.proxydb.outbox.OutboxRepository;

@Component
public class TrueConfUserIdResolver {

  private final OutboxRepository repository;
  private final TrueConfUserDirectory userDirectory;

  public TrueConfUserIdResolver(
      OutboxRepository repository,
      TrueConfUserDirectory userDirectory) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
    this.userDirectory = Objects.requireNonNull(userDirectory, "userDirectory must not be null");
  }

  public String resolveByEmail(String email) {
    String normalizedEmail = normalizeEmail(email);
    return repository.findTrueconfIdByEmail(normalizedEmail)
        .orElseGet(() -> lookupAndCacheTrueconfId(normalizedEmail));
  }

  private String lookupAndCacheTrueconfId(String email) {
    TrueConfUserDirectory.Entry entry = userDirectory.findByEmail(email)
        .orElseThrow(() -> invalid(
            "USER_EMAIL_NOT_FOUND",
            "Active Directory user with email " + email + " was not found or has no TrueConf ID"));
    String trueconfId = requireText(entry.trueconfId(), "trueconfId");
    repository.upsertUserEmailCache(email, trueconfId, entry.displayName());
    return trueconfId;
  }

  private static String normalizeEmail(String value) {
    return requireText(value, "email").trim().toLowerCase(Locale.ROOT);
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw invalid("INVALID_USER_IDENTIFIER", fieldName + " is required");
    }
    return value;
  }

  private static InvalidOutboxJobException invalid(String code, String message) {
    return new InvalidOutboxJobException(code, message);
  }
}
