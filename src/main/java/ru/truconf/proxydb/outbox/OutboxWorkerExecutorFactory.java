package ru.truconf.proxydb.outbox;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class OutboxWorkerExecutorFactory {

  public ExecutorService create(int workerThreads) {
    return Executors.newFixedThreadPool(
        workerThreads,
        new NamedThreadFactory("outbox-worker-"));
  }

  private static final class NamedThreadFactory implements ThreadFactory {

    private final String prefix;
    private final AtomicInteger sequence = new AtomicInteger();

    private NamedThreadFactory(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
      thread.setDaemon(false);
      return thread;
    }
  }
}
