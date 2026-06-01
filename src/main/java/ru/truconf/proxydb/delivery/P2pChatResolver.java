package ru.truconf.proxydb.delivery;

import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;
import ru.truconf.proxydb.domain.OutboxJob;
import ru.truconf.proxydb.domain.RecipientKind;
import ru.truconf.proxydb.outbox.OutboxRepository;
import ru.truconf.proxydb.truconf.TrueConfClient;
import ru.truconf.proxydb.truconf.TrueConfResponse;

@Component
public class P2pChatResolver {

  private final OutboxRepository repository;
  private final TrueConfClient trueConfClient;
  private final TrueConfUserDirectory userDirectory;

  public P2pChatResolver(
      OutboxRepository repository,
      TrueConfClient trueConfClient,
      TrueConfUserDirectory userDirectory) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
    this.trueConfClient = Objects.requireNonNull(trueConfClient, "trueConfClient must not be null");
    this.userDirectory = Objects.requireNonNull(userDirectory, "userDirectory must not be null");
  }

  public String resolveChatId(OutboxJob job) {
    Objects.requireNonNull(job, "job must not be null");

    if (job.recipientKind() == RecipientKind.CHAT) {
      return requireText(job.chatId(), "chatId");
    }

    if (job.recipientKind() != RecipientKind.USER && job.recipientKind() != RecipientKind.USER_EMAIL) {
      throw invalid("INVALID_RECIPIENT_KIND", "Unsupported recipient kind: " + job.recipientKind());
    }

    String userId = job.recipientKind() == RecipientKind.USER
        ? requireText(job.userId(), "userId")
        : resolveTrueconfIdByEmail(requireText(job.recipientEmail(), "recipientEmail"));
    return repository.findP2pChatByUserId(userId)
        .map(entry -> entry.chatId())
        .orElseGet(() -> createAndCacheP2pChat(userId));
  }

  private String resolveTrueconfIdByEmail(String email) {
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

  private String createAndCacheP2pChat(String userId) {
    TrueConfResponse response = trueConfClient.createP2PChat(userId);
    String chatId = requireText(response.chatId(), "chatId");
    repository.upsertP2pChat(userId, chatId);
    return chatId;
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw invalid("INVALID_OUTBOX_RECIPIENT", fieldName + " is required");
    }
    return value;
  }

  private static String normalizeEmail(String value) {
    return requireText(value, "recipientEmail").trim().toLowerCase(Locale.ROOT);
  }

  private static InvalidOutboxJobException invalid(String code, String message) {
    return new InvalidOutboxJobException(code, message);
  }
}
