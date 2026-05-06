package ru.truconf.proxydb.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.truconf.proxydb.config.AppProperties;
import ru.truconf.proxydb.domain.OutboxJob;
import ru.truconf.proxydb.domain.OutboxOperation;
import ru.truconf.proxydb.domain.OutboxStatus;
import ru.truconf.proxydb.domain.RecipientKind;

@Testcontainers
class OutboxDispatcherTests {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("truconf_proxydb")
          .withUsername("truconf_proxydb")
          .withPassword("truconf_proxydb");

  private DriverManagerDataSource dataSource;
  private JdbcTemplate jdbc;
  private OutboxRepository repository;
  private OutboxDispatcher dispatcher;

  @BeforeEach
  void migrateCleanDatabase() {
    dataSource = new DriverManagerDataSource(
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
  }

  @AfterEach
  void stopDispatcher() {
    if (dispatcher != null) {
      dispatcher.stop();
    }
  }

  @Test
  void pollingClaimsReadyJobsAndSkipsFutureJobs() {
    OutboxJob first = createReadyJob("dispatcher-ready-1");
    OutboxJob second = createReadyJob("dispatcher-ready-2");
    OutboxJob future = createFutureJob("dispatcher-future-1", Duration.ofMinutes(10));
    RecordingExecutor executor = new RecordingExecutor(2);

    dispatcher = newDispatcher(
        executor,
        Duration.ofMillis(100),
        Duration.ofSeconds(3),
        10,
        2);
    dispatcher.start();

    executor.awaitJobs(Duration.ofSeconds(5));

    assertThat(executor.jobIds()).containsExactlyInAnyOrder(first.id(), second.id());
    assertThat(repository.findById(first.id()))
        .isPresent()
        .get()
        .extracting(OutboxJob::status)
        .isEqualTo(OutboxStatus.PROCESSING);
    assertThat(repository.findById(second.id()))
        .isPresent()
        .get()
        .extracting(OutboxJob::status)
        .isEqualTo(OutboxStatus.PROCESSING);
    assertThat(repository.findById(future.id()))
        .isPresent()
        .get()
        .extracting(OutboxJob::status)
        .isEqualTo(OutboxStatus.NEW);
  }

  @Test
  void staleLockRecoveryReturnsExpiredProcessingJobToNewAndDispatchesItAgain() {
    OutboxJob created = createReadyJob("dispatcher-stale-1");
    OutboxJob claimed = repository.claimBatch("stale-worker", Duration.ofMillis(100), 1)
        .getFirst();
    assertThat(claimed.id()).isEqualTo(created.id());
    jdbc.update("""
        update truconf_outbox
        set locked_until = now() - interval '1 minute'
        where id = ?
        """, claimed.id());

    RecordingExecutor executor = new RecordingExecutor(1);
    dispatcher = newDispatcher(
        executor,
        Duration.ofMillis(100),
        Duration.ofSeconds(3),
        10,
        1);
    dispatcher.start();

    executor.awaitJobs(Duration.ofSeconds(5));

    OutboxJob stored = repository.findById(created.id()).orElseThrow();
    assertThat(executor.jobIds()).containsExactly(created.id());
    assertThat(stored.status()).isEqualTo(OutboxStatus.PROCESSING);
    assertThat(stored.attemptCount()).isEqualTo(2);
    assertThat(stored.lockedBy()).isEqualTo(dispatcher.workerId());
  }

  @Test
  void notifyWakesDispatcherBeforeLongPollingInterval() {
    RecordingExecutor executor = new RecordingExecutor(1);
    PostgresNotifyListener listener = new PostgresNotifyListener(dataSource, Duration.ofMillis(100));
    dispatcher = new OutboxDispatcher(
        repository,
        listener,
        executor,
        new OutboxWorkerExecutorFactory(),
        dispatcherProperties(Duration.ofSeconds(30), Duration.ofSeconds(3), 10, 1),
        "notify-worker");
    dispatcher.start();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(listener.isListening()).isTrue());

    OutboxJob job = createReadyJob("dispatcher-notify-1");

    executor.awaitJobs(Duration.ofSeconds(5));

    assertThat(executor.jobIds()).containsExactly(job.id());
    assertThat(repository.findById(job.id()))
        .isPresent()
        .get()
        .extracting(OutboxJob::lockedBy)
        .isEqualTo("notify-worker");
  }

  @Test
  void gracefulStopStopsDispatcherListenerAndWorkerThreads() {
    createReadyJob("dispatcher-stop-1");
    BlockingExecutor executor = new BlockingExecutor();
    PostgresNotifyListener listener = new PostgresNotifyListener(dataSource, Duration.ofMillis(100));
    dispatcher = new OutboxDispatcher(
        repository,
        listener,
        executor,
        new OutboxWorkerExecutorFactory(),
        dispatcherProperties(Duration.ofMillis(100), Duration.ofMillis(100), 10, 1),
        "stop-worker");
    dispatcher.start();

    executor.awaitStarted(Duration.ofSeconds(5));
    dispatcher.stop();

    assertThat(dispatcher.isRunning()).isFalse();
    assertThat(listener.isRunning()).isFalse();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> {
          assertThat(liveThreadNames("outbox-dispatcher")).isEmpty();
          assertThat(liveThreadNames("outbox-postgres-listener")).isEmpty();
          assertThat(liveThreadNames("outbox-worker-")).isEmpty();
        });
    assertThat(executor.interrupted()).isTrue();
  }

  private OutboxDispatcher newDispatcher(
      OutboxJobExecutor executor,
      Duration pollInterval,
      Duration lockTimeout,
      int batchSize,
      int workerThreads) {
    return new OutboxDispatcher(
        repository,
        new PostgresNotifyListener(dataSource, Duration.ofMillis(100)),
        executor,
        new OutboxWorkerExecutorFactory(),
        dispatcherProperties(pollInterval, lockTimeout, batchSize, workerThreads),
        "test-worker");
  }

  private static AppProperties.Dispatcher dispatcherProperties(
      Duration pollInterval,
      Duration lockTimeout,
      int batchSize,
      int workerThreads) {
    return new AppProperties.Dispatcher(batchSize, pollInterval, lockTimeout, workerThreads);
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

  private static List<String> liveThreadNames(String prefix) {
    return Thread.getAllStackTraces()
        .keySet()
        .stream()
        .filter(Thread::isAlive)
        .map(Thread::getName)
        .filter(name -> name.startsWith(prefix))
        .toList();
  }

  private static final class RecordingExecutor implements OutboxJobExecutor {

    private final CountDownLatch latch;
    private final List<Long> jobIds = new CopyOnWriteArrayList<>();

    private RecordingExecutor(int expectedJobs) {
      latch = new CountDownLatch(expectedJobs);
    }

    @Override
    public void execute(OutboxJob job, String workerId) {
      jobIds.add(job.id());
      latch.countDown();
    }

    private List<Long> jobIds() {
      return jobIds;
    }

    private void awaitJobs(Duration timeout) {
      try {
        assertThat(latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while waiting for dispatcher jobs", ex);
      }
    }
  }

  private static final class BlockingExecutor implements OutboxJobExecutor {

    private final CountDownLatch started = new CountDownLatch(1);
    private final AtomicBoolean interrupted = new AtomicBoolean();

    @Override
    public void execute(OutboxJob job, String workerId) {
      started.countDown();
      try {
        Thread.sleep(TimeUnit.SECONDS.toMillis(30));
      } catch (InterruptedException ex) {
        interrupted.set(true);
        Thread.currentThread().interrupt();
      }
    }

    private void awaitStarted(Duration timeout) {
      try {
        assertThat(started.await(timeout.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while waiting for blocking executor", ex);
      }
    }

    private boolean interrupted() {
      return interrupted.get();
    }
  }
}
