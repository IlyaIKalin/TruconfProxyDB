package ru.truconf.proxydb.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.truconf.proxydb.domain.OutboxJob;

@Component
public class NoopOutboxJobExecutor implements OutboxJobExecutor {

  private static final Logger log = LoggerFactory.getLogger(NoopOutboxJobExecutor.class);

  @Override
  public void execute(OutboxJob job, String workerId) {
    log.warn(
        "Outbox job {} was claimed by {}, but TrueConf executor is not implemented yet",
        job.id(),
        workerId);
  }
}
