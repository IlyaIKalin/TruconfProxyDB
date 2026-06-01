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
        Map.entry("truconf.bot-http-base-url", "https://bot.example.local"),
        Map.entry("truconf.bot-ws-url", "wss://bot.example.local/websocket/chat_bot/"),
        Map.entry("truconf.bot-client-id", "bot-client"),
        Map.entry("truconf.bot-username", "bot-user"),
        Map.entry("truconf.bot-password", "bot-password"),
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
        Map.entry("truconf.websocket.reconnect-delay", "100ms"),
        Map.entry("truconf.server-api.base-url", "https://server-api.example.local"),
        Map.entry("truconf.server-api.client-id", "server-api-client"),
        Map.entry("truconf.server-api.client-secret", "server-api-secret"),
        Map.entry("truconf.server-api.grant-type", "client_credentials"),
        Map.entry("truconf.server-api.username", ""),
        Map.entry("truconf.server-api.password", ""),
        Map.entry("truconf.server-api.page-size", "100"),
        Map.entry("truconf.server-api.max-scan-pages", "20"),
        Map.entry("truconf.active-directory.enabled", "true"),
        Map.entry("truconf.active-directory.url", "ldap://ad.example.local:389"),
        Map.entry("truconf.active-directory.bind-dn", "CN=svc,DC=example,DC=local"),
        Map.entry("truconf.active-directory.bind-password", "secret"),
        Map.entry("truconf.active-directory.base-dn", "DC=example,DC=local"),
        Map.entry("truconf.active-directory.email-attribute", "mail"),
        Map.entry("truconf.active-directory.trueconf-id-attribute", "extensionAttribute5"),
        Map.entry("truconf.active-directory.display-name-attribute", "displayName"),
        Map.entry("truconf.active-directory.connect-timeout", "3s"),
        Map.entry("truconf.active-directory.read-timeout", "5s"))))
        .bind("truconf", Bindable.of(AppProperties.class))
        .orElseThrow(() -> new IllegalStateException("truconf properties were not bound"));

    assertThat(properties.tlsInsecureSkipVerify()).isFalse();
    assertThat(properties.botHttpBaseUrl()).isEqualTo("https://bot.example.local");
    assertThat(properties.serverApi().baseUrl()).isEqualTo("https://server-api.example.local");
    assertThat(properties.activeDirectory().trueconfIdAttribute()).isEqualTo("extensionAttribute5");
  }
}
