package ru.truconf.proxydb.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.truconf.proxydb.domain.OutboxJob;
import ru.truconf.proxydb.domain.OutboxOperation;
import ru.truconf.proxydb.domain.OutboxStatus;
import ru.truconf.proxydb.domain.RecipientKind;

@Testcontainers
class OutboxRepositoryTests {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("truconf_proxydb")
          .withUsername("truconf_proxydb")
          .withPassword("truconf_proxydb");

  private JdbcTemplate jdbc;
  private OutboxRepository repository;
  private DataSourceTransactionManager transactionManager;

  @BeforeEach
  void migrateCleanDatabase() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(),
        POSTGRES.getUsername(),
        POSTGRES.getPassword());

    Flyway flyway = Flyway.configure()
        .dataSource(dataSource)
        .cleanDisabled(false)
        .load();

    flyway.clean();
    flyway.migrate();

    jdbc = new JdbcTemplate(dataSource);
    repository = new OutboxRepository(jdbc);
    transactionManager = new DataSourceTransactionManager(dataSource);
  }

  @Test
  void createAndLookupByIdAndExternalId() {
    OutboxJob created = repository.create(new CreateOutboxJobCommand(
        "crm-lookup-1",
        OutboxOperation.SEND_MESSAGE,
        RecipientKind.USER,
        null,
        "user@example.com",
        null,
        "reply-123",
        "{\"text\":\"Hello\"}",
        7,
        null));

    assertThat(created.id()).isPositive();
    assertThat(created.externalId()).isEqualTo("crm-lookup-1");
    assertThat(created.operation()).isEqualTo(OutboxOperation.SEND_MESSAGE);
    assertThat(created.recipientKind()).isEqualTo(RecipientKind.USER);
    assertThat(created.userId()).isEqualTo("user@example.com");
    assertThat(created.replyMessageId()).isEqualTo("reply-123");
    assertThat(created.status()).isEqualTo(OutboxStatus.NEW);
    assertThat(created.maxAttempts()).isEqualTo(7);
    assertThat(created.payloadJson()).contains("\"text\": \"Hello\"");

    assertThat(repository.findById(created.id()))
        .isPresent()
        .get()
        .extracting(OutboxJob::externalId)
        .isEqualTo("crm-lookup-1");

    assertThat(repository.findByExternalId("crm-lookup-1"))
        .isPresent()
        .get()
        .extracting(OutboxJob::id)
        .isEqualTo(created.id());

    assertThat(repository.findById(-1)).isEmpty();
    assertThat(repository.findByExternalId("missing")).isEmpty();
  }

  @Test
  void userEmailCacheUpsertsAndRefreshesLastUsedAt() {
    assertThat(repository.findTrueconfIdByEmail("user@example.com")).isEmpty();

    repository.upsertUserEmailCache(
        "user@example.com",
        "gd.rt.ru\\user@s13.trueconf.rt.ru",
        "User Display");

    assertThat(repository.findTrueconfIdByEmail("user@example.com"))
        .contains("gd.rt.ru\\user@s13.trueconf.rt.ru");

    repository.upsertUserEmailCache(
        "user@example.com",
        "gd.rt.ru\\user2@s13.trueconf.rt.ru",
        "User Display 2");

    assertThat(repository.findTrueconfIdByEmail("user@example.com"))
        .contains("gd.rt.ru\\user2@s13.trueconf.rt.ru");
  }

  @Test
  void claimBatchClaimsOnlyReadyRowsAndMarksThemProcessing() {
    OutboxJob first = createReadyJob("claim-1");
    OutboxJob second = createReadyJob("claim-2");
    createFutureJob("claim-future", Duration.ofMinutes(10));

    List<OutboxJob> claimed = repository.claimBatch("worker-a", Duration.ofMinutes(2), 10);

    assertThat(claimed)
        .extracting(OutboxJob::id)
        .containsExactly(first.id(), second.id());
    assertThat(claimed)
        .allSatisfy(job -> {
          assertThat(job.status()).isEqualTo(OutboxStatus.PROCESSING);
          assertThat(job.lockedBy()).isEqualTo("worker-a");
          assertThat(job.lockedUntil()).isAfter(OffsetDateTime.now(ZoneOffset.UTC));
          assertThat(job.attemptCount()).isEqualTo(1);
        });

    assertThat(repository.findByExternalId("claim-future"))
        .isPresent()
        .get()
        .extracting(OutboxJob::status)
        .isEqualTo(OutboxStatus.NEW);
  }

  @Test
  void claimBatchClaimsOnlyOneReadyJobPerDeliveryKey() {
    OutboxJob firstChatMessage = createReadyChatJob("same-chat-1", "chat-shared");
    OutboxJob secondChatMessage = createReadyChatJob("same-chat-2", "chat-shared");
    OutboxJob otherChatMessage = createReadyChatJob("other-chat-1", "chat-other");

    List<OutboxJob> claimed = repository.claimBatch("worker-a", Duration.ofMinutes(2), 10);

    assertThat(claimed)
        .extracting(OutboxJob::id)
        .containsExactly(firstChatMessage.id(), otherChatMessage.id());
    assertThat(repository.findById(secondChatMessage.id()))
        .isPresent()
        .get()
        .extracting(OutboxJob::status)
        .isEqualTo(OutboxStatus.NEW);
  }

  @Test
  void claimBatchSkipsDeliveryKeyThatIsAlreadyProcessing() {
    OutboxJob first = createReadyChatJob("processing-chat-1", "chat-processing");
    OutboxJob second = createReadyChatJob("processing-chat-2", "chat-processing");
    OutboxJob other = createReadyChatJob("processing-other-1", "chat-other-processing");

    List<OutboxJob> firstClaim = repository.claimBatch("worker-a", Duration.ofMinutes(2), 1);
    assertThat(firstClaim).extracting(OutboxJob::id).containsExactly(first.id());

    List<OutboxJob> secondClaim = repository.claimBatch("worker-b", Duration.ofMinutes(2), 10);

    assertThat(secondClaim).extracting(OutboxJob::id).containsExactly(other.id());
    assertThat(repository.findById(second.id()))
        .isPresent()
        .get()
        .extracting(OutboxJob::status)
        .isEqualTo(OutboxStatus.NEW);
  }

  @Test
  void claimBatchAllowsNextJobForDeliveryKeyAfterSentTransition() {
    OutboxJob first = createReadyChatJob("sent-chat-1", "chat-after-sent");
    OutboxJob second = createReadyChatJob("sent-chat-2", "chat-after-sent");

    OutboxJob claim = repository.claimBatch("worker-a", Duration.ofMinutes(2), 1)
        .getFirst();
    assertThat(claim.id()).isEqualTo(first.id());
    repository.markSent(
        claim.id(),
        "worker-a",
        new SentOutboxResult("chat-after-sent", "message-1", null, null, "{\"ok\":true}"));

    List<OutboxJob> claimed = repository.claimBatch("worker-b", Duration.ofMinutes(2), 10);

    assertThat(claimed).extracting(OutboxJob::id).containsExactly(second.id());
  }

  @Test
  void claimBatchAllowsNextNewJobForDeliveryKeyAfterRetryTransition() {
    OutboxJob first = createReadyChatJob("retry-chat-1", "chat-after-retry");
    OutboxJob second = createReadyChatJob("retry-chat-2", "chat-after-retry");

    OutboxJob claim = repository.claimBatch("worker-a", Duration.ofMinutes(2), 1)
        .getFirst();
    assertThat(claim.id()).isEqualTo(first.id());
    repository.markRetry(
        claim.id(),
        "worker-a",
        Duration.ofMinutes(5),
        new OutboxError("TEMPORARY", "Temporary failure", true, null));

    List<OutboxJob> claimed = repository.claimBatch("worker-b", Duration.ofMinutes(2), 10);

    assertThat(claimed).extracting(OutboxJob::id).containsExactly(second.id());
  }

  @Test
  void claimBatchPrefersNewJobOverReadyRetryWaitForSameDeliveryKey() {
    OutboxJob retryCandidate = createReadyChatJob("retry-priority-1", "chat-retry-priority");
    OutboxJob claimedForRetry = repository.claimBatch("worker-a", Duration.ofMinutes(2), 1)
        .getFirst();
    assertThat(claimedForRetry.id()).isEqualTo(retryCandidate.id());
    repository.markRetry(
        claimedForRetry.id(),
        "worker-a",
        Duration.ZERO,
        new OutboxError("TEMPORARY", "Temporary failure", true, null));
    OutboxJob newCandidate = createReadyChatJob("retry-priority-2", "chat-retry-priority");

    List<OutboxJob> claimed = repository.claimBatch("worker-b", Duration.ofMinutes(2), 1);

    assertThat(claimed).extracting(OutboxJob::id).containsExactly(newCandidate.id());
  }

  @Test
  void oneJobIsNotClaimedByTwoWorkersInConcurrentTransactions() {
    OutboxJob job = createReadyJob("concurrent-1");

    TransactionTemplate workerOneTransaction = new TransactionTemplate(transactionManager);
    workerOneTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    TransactionTemplate workerTwoTransaction = new TransactionTemplate(transactionManager);
    workerTwoTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    List<OutboxJob> workerTwoClaimed = workerOneTransaction.execute(status -> {
      List<OutboxJob> workerOneClaimed =
          repository.claimBatch("worker-one", Duration.ofMinutes(1), 1);
      assertThat(workerOneClaimed).extracting(OutboxJob::id).containsExactly(job.id());

      return workerTwoTransaction.execute(innerStatus ->
          repository.claimBatch("worker-two", Duration.ofMinutes(1), 1));
    });

    assertThat(workerTwoClaimed).isEmpty();

    OutboxJob stored = repository.findById(job.id()).orElseThrow();
    assertThat(stored.status()).isEqualTo(OutboxStatus.PROCESSING);
    assertThat(stored.lockedBy()).isEqualTo("worker-one");
    assertThat(stored.attemptCount()).isEqualTo(1);
  }

  @Test
  void markSentRetryAndFailedPersistStateTransitions() {
    OutboxJob sentJob = createReadyJob("sent-1");
    OutboxJob sentCandidate = repository.claimBatch(
        "worker-a",
        Duration.ofMinutes(1),
        1)
        .getFirst();
    assertThat(sentCandidate.id()).isEqualTo(sentJob.id());

    OutboxJob sent = repository.markSent(
        sentCandidate.id(),
        "worker-a",
        new SentOutboxResult(
            "chat-1",
            "message-1",
            "file-1",
            123456789L,
            "{\"ok\":true}"))
        .orElseThrow();

    assertThat(sent.status()).isEqualTo(OutboxStatus.SENT);
    assertThat(sent.lockedBy()).isNull();
    assertThat(sent.lockedUntil()).isNull();
    assertThat(sent.trueconfChatId()).isEqualTo("chat-1");
    assertThat(sent.trueconfMessageId()).isEqualTo("message-1");
    assertThat(sent.trueconfFileId()).isEqualTo("file-1");
    assertThat(sent.trueconfTimestamp()).isEqualTo(123456789L);
    assertThat(sent.lastResponseJson()).contains("\"ok\": true");
    assertThat(sent.sentAt()).isNotNull();
    assertThat(sent.failedAt()).isNull();

    OutboxJob retryJob = createReadyJob("retry-1");
    OutboxJob retryCandidate = repository.claimBatch(
        "worker-b",
        Duration.ofMinutes(1),
        1)
        .getFirst();
    assertThat(retryCandidate.id()).isEqualTo(retryJob.id());
    OffsetDateTime beforeRetry = OffsetDateTime.now(ZoneOffset.UTC);

    OutboxJob retry = repository.markRetry(
        retryCandidate.id(),
        "worker-b",
        Duration.ofSeconds(30),
        new OutboxError("TC_TIMEOUT", "TrueConf request timeout", true, "{\"timeout\":true}"))
        .orElseThrow();

    assertThat(retry.status()).isEqualTo(OutboxStatus.RETRY_WAIT);
    assertThat(retry.lockedBy()).isNull();
    assertThat(retry.lockedUntil()).isNull();
    assertThat(retry.lastErrorCode()).isEqualTo("TC_TIMEOUT");
    assertThat(retry.lastErrorMessage()).isEqualTo("TrueConf request timeout");
    assertThat(retry.lastErrorRetryable()).isTrue();
    assertThat(retry.lastResponseJson()).contains("\"timeout\": true");
    assertThat(retry.nextAttemptAt()).isAfter(beforeRetry.plusSeconds(25));
    assertThat(retry.nextAttemptAt()).isBefore(beforeRetry.plusSeconds(40));

    OutboxJob failedJob = createReadyJob("failed-1");
    OutboxJob failedCandidate = repository.claimBatch(
        "worker-c",
        Duration.ofMinutes(1),
        1)
        .getFirst();
    assertThat(failedCandidate.id()).isEqualTo(failedJob.id());

    OutboxJob failed = repository.markFailed(
        failedCandidate.id(),
        "worker-c",
        new OutboxError("BAD_REQUEST", "Invalid payload", false, "{\"error\":\"bad\"}"))
        .orElseThrow();

    assertThat(failed.status()).isEqualTo(OutboxStatus.FAILED);
    assertThat(failed.lockedBy()).isNull();
    assertThat(failed.lockedUntil()).isNull();
    assertThat(failed.lastErrorCode()).isEqualTo("BAD_REQUEST");
    assertThat(failed.lastErrorRetryable()).isFalse();
    assertThat(failed.failedAt()).isNotNull();
    assertThat(failed.sentAt()).isNull();
  }

  @Test
  void finalTransitionsIgnoreRowsLockedByAnotherWorker() {
    OutboxJob job = createReadyJob("wrong-worker-1");
    OutboxJob claimed = repository.claimBatch(
        "worker-a",
        Duration.ofMinutes(1),
        1)
        .getFirst();
    assertThat(claimed.id()).isEqualTo(job.id());

    assertThat(repository.markSent(
        claimed.id(),
        "worker-b",
        new SentOutboxResult("chat-1", "message-1", null, null, "{\"ok\":true}")))
        .isEmpty();

    OutboxJob stored = repository.findById(claimed.id()).orElseThrow();
    assertThat(stored.status()).isEqualTo(OutboxStatus.PROCESSING);
    assertThat(stored.lockedBy()).isEqualTo("worker-a");
  }

  @Test
  void staleLockRecoveryReturnsProcessingRowsToNewAndAllowsClaimAgain() {
    OutboxJob created = createReadyJob("stale-1");
    OutboxJob job = repository.claimBatch(
        "worker-a",
        Duration.ofMinutes(1),
        1)
        .getFirst();
    assertThat(job.id()).isEqualTo(created.id());

    jdbc.update("""
        update truconf_outbox
        set locked_until = now() - interval '1 minute'
        where id = ?
        """, job.id());

    List<OutboxJob> recovered = repository.recoverStaleLocks(10);

    assertThat(recovered).extracting(OutboxJob::id).containsExactly(job.id());
    assertThat(recovered.getFirst().status()).isEqualTo(OutboxStatus.NEW);
    assertThat(recovered.getFirst().lockedBy()).isNull();
    assertThat(recovered.getFirst().lockedUntil()).isNull();

    List<OutboxJob> claimedAgain =
        repository.claimBatch("worker-b", Duration.ofMinutes(1), 1);

    assertThat(claimedAgain).extracting(OutboxJob::id).containsExactly(job.id());
    assertThat(claimedAgain.getFirst().lockedBy()).isEqualTo("worker-b");
    assertThat(claimedAgain.getFirst().attemptCount()).isEqualTo(2);
  }

  private OutboxJob createReadyJob(String externalId) {
    return repository.create(new CreateOutboxJobCommand(
        externalId,
        OutboxOperation.SEND_MESSAGE,
        RecipientKind.USER,
        null,
        externalId + "@example.com",
        null,
        null,
        "{\"text\":\"" + externalId + "\"}",
        10,
        OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1)));
  }

  private OutboxJob createReadyChatJob(String externalId, String chatId) {
    return repository.create(new CreateOutboxJobCommand(
        externalId,
        OutboxOperation.SEND_MESSAGE,
        RecipientKind.CHAT,
        chatId,
        null,
        null,
        null,
        "{\"text\":\"" + externalId + "\"}",
        10,
        OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1)));
  }

  private OutboxJob createFutureJob(String externalId, Duration delay) {
    return repository.create(new CreateOutboxJobCommand(
        externalId,
        OutboxOperation.SEND_MESSAGE,
        RecipientKind.USER,
        null,
        externalId + "@example.com",
        null,
        null,
        "{\"text\":\"" + externalId + "\"}",
        10,
        OffsetDateTime.now(ZoneOffset.UTC).plus(delay)));
  }
}
