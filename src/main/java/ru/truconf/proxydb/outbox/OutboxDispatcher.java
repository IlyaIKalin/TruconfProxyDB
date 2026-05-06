package ru.truconf.proxydb.outbox;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import ru.truconf.proxydb.config.AppProperties;
import ru.truconf.proxydb.domain.OutboxJob;

@Component
@ConditionalOnProperty(prefix = "truconf.dispatcher", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class OutboxDispatcher implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
  private static final int PHASE = Integer.MAX_VALUE - 100;

  private final OutboxRepository repository;
  private final PostgresNotifyListener notifyListener;
  private final OutboxJobExecutor jobExecutor;
  private final OutboxWorkerExecutorFactory workerExecutorFactory;
  private final AppProperties.Dispatcher properties;
  private final String workerId;
  private final Object monitor = new Object();
  private final AtomicBoolean running = new AtomicBoolean();

  private volatile boolean notified;
  private volatile Thread dispatcherThread;
  private volatile ExecutorService workerExecutor;

  @Autowired
  public OutboxDispatcher(
      OutboxRepository repository,
      PostgresNotifyListener notifyListener,
      OutboxJobExecutor jobExecutor,
      OutboxWorkerExecutorFactory workerExecutorFactory,
      AppProperties properties) {
    this(
        repository,
        notifyListener,
        jobExecutor,
        workerExecutorFactory,
        properties.dispatcher(),
        defaultWorkerId());
  }

  OutboxDispatcher(
      OutboxRepository repository,
      PostgresNotifyListener notifyListener,
      OutboxJobExecutor jobExecutor,
      OutboxWorkerExecutorFactory workerExecutorFactory,
      AppProperties.Dispatcher properties,
      String workerId) {
    this.repository = repository;
    this.notifyListener = notifyListener;
    this.jobExecutor = jobExecutor;
    this.workerExecutorFactory = workerExecutorFactory;
    this.properties = properties;
    this.workerId = workerId;
  }

  @Override
  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }

    workerExecutor = workerExecutorFactory.create(properties.workerThreads());
    notifyListener.start(this::wakeUp);

    Thread thread = new Thread(this::runLoop, "outbox-dispatcher");
    thread.setDaemon(false);
    dispatcherThread = thread;
    thread.start();
    log.info(
        "Outbox dispatcher started with workerId={}, workerThreads={}, batchSize={}",
        workerId,
        properties.workerThreads(),
        properties.batchSize());
  }

  @Override
  public void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }

    wakeUp();
    notifyListener.stop();
    joinDispatcherThread();
    shutdownWorkers();
    log.info("Outbox dispatcher stopped");
  }

  @Override
  public void stop(Runnable callback) {
    stop();
    callback.run();
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  @Override
  public int getPhase() {
    return PHASE;
  }

  public String workerId() {
    return workerId;
  }

  public void wakeUp() {
    synchronized (monitor) {
      notified = true;
      monitor.notifyAll();
    }
  }

  private void runLoop() {
    long nextPollAt = System.nanoTime();
    while (running.get()) {
      long now = System.nanoTime();
      boolean pollDue = now >= nextPollAt;
      boolean notificationReceived = consumeNotification();

      if (pollDue) {
        recoverStaleLocks();
        nextPollAt = System.nanoTime() + properties.pollInterval().toNanos();
      }

      if (pollDue || notificationReceived) {
        claimAndDispatchBatch();
      }

      waitForSignal(nextPollAt - System.nanoTime());
    }
  }

  private void recoverStaleLocks() {
    try {
      List<OutboxJob> recovered = repository.recoverStaleLocks(properties.batchSize());
      if (!recovered.isEmpty()) {
        log.info("Recovered {} stale outbox locks", recovered.size());
      }
    } catch (Exception ex) {
      log.warn("Failed to recover stale outbox locks", ex);
    }
  }

  private void claimAndDispatchBatch() {
    List<OutboxJob> jobs;
    try {
      jobs = repository.claimBatch(workerId, properties.lockTimeout(), properties.batchSize());
    } catch (Exception ex) {
      log.warn("Failed to claim outbox batch", ex);
      return;
    }

    for (OutboxJob job : jobs) {
      try {
        workerExecutor.execute(() -> executeJob(job));
      } catch (RejectedExecutionException ex) {
        log.warn("Outbox worker pool rejected claimed job {}", job.id(), ex);
      }
    }
  }

  private void executeJob(OutboxJob job) {
    try {
      jobExecutor.execute(job, workerId);
    } catch (Exception ex) {
      log.warn("Outbox job {} failed before a final state was recorded", job.id(), ex);
    }
  }

  private boolean consumeNotification() {
    synchronized (monitor) {
      if (!notified) {
        return false;
      }
      notified = false;
      return true;
    }
  }

  private void waitForSignal(long nanosUntilNextPoll) {
    if (!running.get()) {
      return;
    }
    long millis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanosUntilNextPoll));
    synchronized (monitor) {
      if (!running.get() || notified) {
        return;
      }
      try {
        monitor.wait(millis);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void joinDispatcherThread() {
    Thread thread = dispatcherThread;
    if (thread == null || thread == Thread.currentThread()) {
      return;
    }
    try {
      thread.join(Math.max(1_000, properties.pollInterval().toMillis() + 1_000));
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  private void shutdownWorkers() {
    ExecutorService executor = workerExecutor;
    if (executor == null) {
      return;
    }
    executor.shutdown();
    try {
      if (!executor.awaitTermination(properties.lockTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
        executor.shutdownNow();
        executor.awaitTermination(1, TimeUnit.SECONDS);
      }
    } catch (InterruptedException ex) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private static String defaultWorkerId() {
    return hostname() + "-" + ManagementFactory.getRuntimeMXBean().getName() + "-"
        + UUID.randomUUID();
  }

  private static String hostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException ex) {
      return "unknown-host";
    }
  }
}
