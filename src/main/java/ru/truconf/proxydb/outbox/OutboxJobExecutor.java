package ru.truconf.proxydb.outbox;

import ru.truconf.proxydb.domain.OutboxJob;

public interface OutboxJobExecutor {

  void execute(OutboxJob job, String workerId) throws Exception;
}
