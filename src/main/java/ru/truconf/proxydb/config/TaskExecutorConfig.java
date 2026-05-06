package ru.truconf.proxydb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.truconf.proxydb.outbox.OutboxWorkerExecutorFactory;

@Configuration
public class TaskExecutorConfig {

  @Bean
  public OutboxWorkerExecutorFactory outboxWorkerExecutorFactory() {
    return new OutboxWorkerExecutorFactory();
  }
}
