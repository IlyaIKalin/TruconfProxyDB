package ru.truconf.proxydb.outbox;

import ru.truconf.proxydb.domain.OutboxJob;

public record EnqueuedOutboxJob(OutboxJob job, boolean created) {
}
