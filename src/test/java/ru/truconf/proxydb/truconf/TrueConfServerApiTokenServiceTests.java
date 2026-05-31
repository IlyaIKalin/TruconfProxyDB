package ru.truconf.proxydb.truconf;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import ru.truconf.proxydb.config.AppProperties;
import tools.jackson.databind.ObjectMapper;

class TrueConfServerApiTokenServiceTests {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private MockWebServer server;

  @AfterEach
  void shutdownServer() throws Exception {
    if (server != null) {
      server.shutdown();
    }
  }

  @Test
  void requestsAndCachesServerApiToken() throws Exception {
    server = new MockWebServer();
    server.enqueue(jsonResponse("""
        {"access_token":"server-api-token","expires_in":3600}
        """));
    server.start();

    TrueConfServerApiTokenService service = new TrueConfServerApiTokenService(
        properties(server.url("").toString()),
        RestClient.builder().baseUrl(server.url("").toString()).build(),
        objectMapper,
        Clock.systemUTC());

    assertThat(service.getAccessToken()).isEqualTo("server-api-token");
    assertThat(service.getAccessToken()).isEqualTo("server-api-token");
    assertThat(server.getRequestCount()).isEqualTo(1);

    RecordedRequest request = server.takeRequest();
    assertThat(request.getPath()).isEqualTo("/oauth2/v1/token");
    assertThat(request.getHeader("Content-Type")).startsWith("application/json");
    var requestBody = objectMapper.readTree(request.getBody().readUtf8());
    assertThat(requestBody.get("grant_type").asText()).isEqualTo("client_credentials");
    assertThat(requestBody.get("client_id").asText()).isEqualTo("server-api-client");
    assertThat(requestBody.get("client_secret").asText()).isEqualTo("server-api-secret");
  }

  private AppProperties properties(String serverApiBaseUrl) {
    return new AppProperties(
        "https://bot.example.local",
        "wss://bot.example.local/websocket/chat_bot/",
        "bot-client",
        "bot-user",
        "bot-password",
        "api-key",
        "/tmp/truconf-proxydb-test-files",
        false,
        new AppProperties.Dispatcher(10, Duration.ofSeconds(5), Duration.ofMinutes(2), 2),
        new AppProperties.Retry(10, Duration.ofSeconds(1), Duration.ofMinutes(1), 2.0),
        new AppProperties.RateLimit(10),
        new AppProperties.Websocket(
            Duration.ofMillis(250),
            Duration.ofSeconds(2),
            Duration.ofMillis(100)),
        new AppProperties.ServerApi(
            serverApiBaseUrl,
            "server-api-client",
            "server-api-secret",
            "client_credentials",
            "",
            "",
            100,
            20));
  }

  private static MockResponse jsonResponse(String body) {
    return new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body);
  }
}
