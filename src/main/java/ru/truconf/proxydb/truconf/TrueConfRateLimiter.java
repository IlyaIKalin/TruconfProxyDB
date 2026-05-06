package ru.truconf.proxydb.truconf;

import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.truconf.proxydb.config.AppProperties;

@Component
public class TrueConfRateLimiter {

  private static final long NANOS_PER_SECOND = 1_000_000_000L;

  private final long intervalNanos;
  private final LongSupplier nanoTime;
  private final LongConsumer sleeper;

  private long nextPermitAtNanos;

  @Autowired
  public TrueConfRateLimiter(AppProperties properties) {
    this(
        properties.rateLimit().commandsPerSecond(),
        System::nanoTime,
        LockSupport::parkNanos);
  }

  TrueConfRateLimiter(
      int commandsPerSecond,
      LongSupplier nanoTime,
      LongConsumer sleeper) {
    if (commandsPerSecond < 1) {
      throw new IllegalArgumentException("commandsPerSecond must be positive");
    }
    this.intervalNanos = Math.max(1, NANOS_PER_SECOND / commandsPerSecond);
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
  }

  public void acquire() {
    throwIfInterrupted();

    long waitNanos;
    synchronized (this) {
      long now = nanoTime.getAsLong();
      long permitAt = Math.max(now, nextPermitAtNanos);
      nextPermitAtNanos = permitAt + intervalNanos;
      waitNanos = permitAt - now;
    }

    sleep(waitNanos);
  }

  private void sleep(long waitNanos) {
    if (waitNanos <= 0) {
      return;
    }

    long deadline = nanoTime.getAsLong() + waitNanos;
    long remaining = waitNanos;
    while (remaining > 0) {
      sleeper.accept(remaining);
      throwIfInterrupted();
      remaining = deadline - nanoTime.getAsLong();
    }
  }

  private static void throwIfInterrupted() {
    if (Thread.currentThread().isInterrupted()) {
      throw new TrueConfException(
          "TRUECONF_RATE_LIMIT_INTERRUPTED",
          "Interrupted while waiting for TrueConf rate limit permit",
          true);
    }
  }
}
