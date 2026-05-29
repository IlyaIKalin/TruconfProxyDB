package ru.truconf.proxydb.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AppPropertiesTests {

  @Test
  void tlsInsecureSkipVerifyDefaultsToFalse() {
    AppProperties properties = new Binder(new MapConfigurationPropertySource(Map.ofEntries(
        Map.entry("truconf.http-base-url", "https://trueconf.example.local"),
        Map.entry("truconf.ws-url", "wss://trueconf.example.local/websocket/chat_bot/"),
        Map.entry("truconf.client-id", "bot-client"),
        Map.entry("truconf.username", "bot-user"),
        Map.entry("truconf.password", "bot-password"),
        Map.entry("truconf.proxy-api-key", "api-key"),
        Map.entry("truconf.file-storage-dir", "/tmp/truconf-proxydb-test-files"),
        Map.entry("truconf.dispatcher.batch-size", "10"),
        Map.entry("truconf.dispatcher.poll-interval", "5s"),
        Map.entry("truconf.dispatcher.lock-timeout", "2m"),
        Map.entry("truconf.dispatcher.worker-threads", "2"),
        Map.entry("truconf.retry.max-attempts", "10"),
        Map.entry("truconf.retry.initial-delay", "1s"),
        Map.entry("truconf.retry.max-delay", "1m"),
        Map.entry("truconf.retry.multiplier", "2.0"),
        Map.entry("truconf.rate-limit.commands-per-second", "10"),
        Map.entry("truconf.websocket.request-timeout", "250ms"),
        Map.entry("truconf.websocket.connect-timeout", "2s"),
        Map.entry("truconf.websocket.reconnect-delay", "100ms"))))
        .bind("truconf", Bindable.of(AppProperties.class))
        .orElseThrow(() -> new IllegalStateException("truconf properties were not bound"));

    assertThat(properties.tlsInsecureSkipVerify()).isFalse();
  }
}
