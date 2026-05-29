package ru.truconf.proxydb.truconf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import ru.truconf.proxydb.config.AppProperties;

class TrueConfHttpClientFactoryTests {

  private MockWebServer server;

  @AfterEach
  void tearDown() throws Exception {
    if (server != null) {
      server.shutdown();
    }
  }

  @Test
  void insecureFactoryTrustsSelfSignedHttpsServer() throws Exception {
    startHttpsServer();
    server.enqueue(new MockResponse().setBody("ok"));

    RestClient restClient = new TrueConfHttpClientFactory(properties(true))
        .configure(RestClient.builder())
        .baseUrl(server.url("").toString())
        .build();

    assertThat(restClient.get().uri("/health").retrieve().body(String.class)).isEqualTo("ok");
  }

  @Test
  void secureFactoryDoesNotTrustSelfSignedHttpsServer() throws Exception {
    startHttpsServer();
    server.enqueue(new MockResponse().setBody("ok"));

    RestClient restClient = new TrueConfHttpClientFactory(properties(false))
        .configure(RestClient.builder())
        .baseUrl(server.url("").toString())
        .build();

    assertThatThrownBy(() -> restClient.get().uri("/health").retrieve().body(String.class))
        .isInstanceOf(RestClientException.class);
  }

  private void startHttpsServer() throws Exception {
    HeldCertificate localhostCertificate = new HeldCertificate.Builder()
        .commonName("localhost")
        .addSubjectAlternativeName("localhost")
        .build();
    HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder()
        .heldCertificate(localhostCertificate)
        .build();
    server = new MockWebServer();
    server.useHttps(serverCertificates.sslSocketFactory(), false);
    server.start();
  }

  private static AppProperties properties(boolean tlsInsecureSkipVerify) {
    return new AppProperties(
        "https://trueconf.example.local",
        "wss://trueconf.example.local/websocket/chat_bot/",
        "bot-client",
        "bot-user",
        "bot-password",
        "api-key",
        "/tmp/truconf-proxydb-test-files",
        tlsInsecureSkipVerify,
        new AppProperties.Dispatcher(10, Duration.ofSeconds(5), Duration.ofMinutes(2), 2),
        new AppProperties.Retry(10, Duration.ofSeconds(1), Duration.ofMinutes(1), 2.0),
        new AppProperties.RateLimit(10),
        new AppProperties.Websocket(
            Duration.ofMillis(250),
            Duration.ofSeconds(2),
            Duration.ofMillis(100)));
  }
}
