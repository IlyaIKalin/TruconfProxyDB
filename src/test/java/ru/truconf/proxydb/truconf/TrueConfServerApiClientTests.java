package ru.truconf.proxydb.truconf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import ru.truconf.proxydb.config.AppProperties;
import tools.jackson.databind.ObjectMapper;

class TrueConfServerApiClientTests {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private MockWebServer server;

  @AfterEach
  void shutdownServer() throws Exception {
    if (server != null) {
      server.shutdown();
    }
  }

  @Test
  void searchesAccountsByDisplayNameAndBuildsTrueconfId() throws Exception {
    server = new MockWebServer();
    server.enqueue(jsonResponse("""
        {
          "accounts": [
            {
              "id": "1",
              "login": "petrov",
              "domain": "video.example.com",
              "display_name": "Петр Петров",
              "type": "user"
            },
            {
              "id": "2",
              "login": "ivanov",
              "domain": "video.example.com",
              "display_name": "Иван Иванов",
              "type": "user"
            }
          ],
          "next_page_id": 2
        }
        """));
    server.enqueue(jsonResponse("""
        {
          "accounts": [
            {
              "id": "3",
              "login": "ivanova@external.example.com",
              "domain": "",
              "display_name": "Мария Иванова",
              "type": "user"
            }
          ]
        }
        """));
    server.start();

    TrueConfServerApiTokenService tokenService = mock(TrueConfServerApiTokenService.class);
    when(tokenService.getAccessToken()).thenReturn("oauth-token");
    TrueConfServerApiClient client = client(tokenService, 2, 10);

    TrueConfServerApiClient.UserSearchResponse response = client.searchAccounts("иван", 10);

    assertThat(response.scannedPages()).isEqualTo(2);
    assertThat(response.scannedAccounts()).isEqualTo(3);
    assertThat(response.limitReached()).isFalse();
    assertThat(response.users())
        .extracting(TrueConfServerApiClient.UserAccount::trueconfId)
        .containsExactly("ivanov@video.example.com", "ivanova@external.example.com");

    RecordedRequest firstRequest = server.takeRequest();
    assertThat(firstRequest.getPath())
        .isEqualTo("/api/v4/accounts?page=1&page_size=2&sort_field=created_at&sort_order=1");
    assertThat(firstRequest.getHeader("Authorization")).isEqualTo("Bearer oauth-token");
  }

  private TrueConfServerApiClient client(
      TrueConfServerApiTokenService tokenService,
      int pageSize,
      int maxScanPages) {
    AppProperties properties = new AppProperties(
        server.url("").toString(),
        server.url("/websocket/chat_bot/").toString().replace("http://", "ws://"),
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
            server.url("").toString(),
            "server-api-client",
            "server-api-secret",
            "client_credentials",
            "",
            "",
            pageSize,
            maxScanPages));
    return new TrueConfServerApiClient(
        properties,
        tokenService,
        RestClient.builder(),
        new TrueConfHttpClientFactory(properties),
        objectMapper);
  }

  private static MockResponse jsonResponse(String body) {
    return new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body);
  }
}
