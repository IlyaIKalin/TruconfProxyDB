package ru.truconf.proxydb.delivery;

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

  public P2pChatResolver(OutboxRepository repository, TrueConfClient trueConfClient) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
    this.trueConfClient = Objects.requireNonNull(trueConfClient, "trueConfClient must not be null");
  }

  public String resolveChatId(OutboxJob job) {
    Objects.requireNonNull(job, "job must not be null");

    if (job.recipientKind() == RecipientKind.CHAT) {
      return requireText(job.chatId(), "chatId");
    }

    if (job.recipientKind() != RecipientKind.USER) {
      throw invalid("INVALID_RECIPIENT_KIND", "Unsupported recipient kind: " + job.recipientKind());
    }

    String userId = requireText(job.userId(), "userId");
    return repository.findP2pChatByUserId(userId)
        .map(entry -> entry.chatId())
        .orElseGet(() -> createAndCacheP2pChat(userId));
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

  private static InvalidOutboxJobException invalid(String code, String message) {
    return new InvalidOutboxJobException(code, message);
  }
}
