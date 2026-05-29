package ru.truconf.proxydb.truconf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import ru.truconf.proxydb.config.AppProperties;
import tools.jackson.databind.ObjectMapper;

class TrueConfTokenServiceTests {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private MockWebServer server;

  @AfterEach
  void shutdownServer() throws Exception {
    if (server != null) {
      server.shutdown();
    }
  }

  @Test
  void cachesTokenUntilInvalidated() throws Exception {
    server = new MockWebServer();
    server.enqueue(jsonResponse("""
        {"access_token":"token-1","expires_in":3600}
        """));
    server.enqueue(jsonResponse("""
        {"access_token":"token-2","expires_in":3600}
        """));
    server.start();

    TrueConfTokenService service = tokenService(server);

    assertThat(service.getAccessToken()).isEqualTo("token-1");
    assertThat(service.getAccessToken()).isEqualTo("token-1");
    assertThat(server.getRequestCount()).isEqualTo(1);

    service.invalidateToken();

    assertThat(service.getAccessToken()).isEqualTo("token-2");
    assertThat(server.getRequestCount()).isEqualTo(2);
    RecordedRequest request = server.takeRequest();
    assertThat(request.getPath()).isEqualTo("/bridge/api/client/v1/oauth/token");
    assertThat(request.getBody().readUtf8())
        .contains("grant_type=password")
        .contains("client_id=bot-client")
        .contains("username=bot-user")
        .contains("password=bot-password");
  }

  @Test
  void reportsTerminalHttpAuthError() throws Exception {
    server = new MockWebServer();
    server.enqueue(new MockResponse()
        .setResponseCode(401)
        .setBody("""
            {"error":"invalid_grant"}
            """));
    server.start();

    TrueConfTokenService service = tokenService(server);

    assertThatThrownBy(service::getAccessToken)
        .isInstanceOfSatisfying(TrueConfException.class, ex -> {
          assertThat(ex.code()).isEqualTo("OAUTH_HTTP_401");
          assertThat(ex.retryable()).isFalse();
        });
  }

  private TrueConfTokenService tokenService(MockWebServer mockWebServer) {
    return new TrueConfTokenService(
        properties(
            mockWebServer.url("").toString(),
            mockWebServer.url("/websocket/chat_bot/").toString().replace("http://", "ws://")),
        RestClient.builder().baseUrl(mockWebServer.url("").toString()).build(),
        objectMapper,
        java.time.Clock.systemUTC());
  }

  private static MockResponse jsonResponse(String body) {
    return new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body);
  }

  static AppProperties properties(String httpBaseUrl, String wsUrl) {
    return new AppProperties(
        httpBaseUrl,
        wsUrl,
        "bot-client",
        "bot-user",
        "bot-password",
        "api-key",
        "/tmp/truconf-proxydb-test-files",
        new AppProperties.Dispatcher(10, Duration.ofSeconds(5), Duration.ofMinutes(2), 2),
        new AppProperties.Retry(10, Duration.ofSeconds(1), Duration.ofMinutes(1), 2.0),
        new AppProperties.RateLimit(10),
        new AppProperties.Websocket(
            Duration.ofMillis(250),
            Duration.ofSeconds(2),
            Duration.ofMillis(100)));
  }
}
