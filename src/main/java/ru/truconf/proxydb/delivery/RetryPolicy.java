package ru.truconf.proxydb.delivery;

import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.truconf.proxydb.config.AppProperties;
import ru.truconf.proxydb.domain.OutboxJob;

@Component
public class RetryPolicy {

  private final AppProperties.Retry properties;

  @Autowired
  public RetryPolicy(AppProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null").retry();
  }

  RetryPolicy(AppProperties.Retry properties) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
  }

  public boolean canRetry(OutboxJob job) {
    Objects.requireNonNull(job, "job must not be null");
    return job.attemptCount() < job.maxAttempts();
  }

  public Duration nextDelay(OutboxJob job) {
    Objects.requireNonNull(job, "job must not be null");
    int retryNumber = Math.max(1, job.attemptCount());
    double multiplierPower = Math.pow(properties.multiplier(), retryNumber - 1);
    double millis = properties.initialDelay().toMillis() * multiplierPower;
    long cappedMillis = (long) Math.min(millis, properties.maxDelay().toMillis());
    return Duration.ofMillis(Math.max(0, cappedMillis));
  }
}
