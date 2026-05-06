package ru.truconf.proxydb.truconf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import ru.truconf.proxydb.config.AppProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class TrueConfSessionTests {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final TrueConfCommandFactory commandFactory = new TrueConfCommandFactory(objectMapper);
  private final TrueConfResponseMapper responseMapper = new TrueConfResponseMapper();
  private final TrueConfErrorClassifier errorClassifier = new TrueConfErrorClassifier();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  private MockWebServer server;

  @AfterEach
  void tearDown() throws Exception {
    scheduler.shutdownNow();
    if (server != null) {
      server.shutdown();
    }
  }

  @Test
  void authenticatesCorrelatesResponseAndAcksServerRequests() throws Exception {
    server = new MockWebServer();
    server.enqueue(tokenResponse());
    CountDownLatch ackReceived = new CountDownLatch(1);
    LinkedBlockingQueue<JsonNode> clientMessages = new LinkedBlockingQueue<>();
    server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
      @Override
      public void onMessage(WebSocket webSocket, String text) {
        JsonNode message = read(text);
        clientMessages.add(message);
        long id = message.get("id").asLong();
        if (message.get("type").asInt() == 2 && id == 77) {
          ackReceived.countDown();
          return;
        }
        String method = message.get("method").asText();
        if ("auth".equals(method)) {
          webSocket.send(response(id, """
              {"userId":"bot-user"}
              """));
          webSocket.send("""
              {"type":1,"id":77,"method":"incomingMessage","payload":{"messageId":"in-1"}}
              """);
          return;
        }
        if ("sendMessage".equals(method)) {
          webSocket.send(response(id, """
              {"chatId":"chat-1","messageId":"message-1","timestamp":1735134222098}
              """));
        }
      }
    }));
    server.start();

    try (TrueConfSession session = session(properties())) {
      TrueConfResponse response = session.request(
          id -> commandFactory.sendMessage(id, "chat-1", "Hello", "text", null));

      assertThat(response.chatId()).isEqualTo("chat-1");
      assertThat(response.messageId()).isEqualTo("message-1");
      assertThat(response.timestamp()).isEqualTo(1735134222098L);
      assertThat(ackReceived.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(clientMessages.stream()
          .filter(node -> node.get("type").asInt() == 1)
          .map(node -> node.get("method").asText()))
          .containsExactly("auth", "sendMessage");
    }
  }

  @Test
  void timesOutPendingRequestAndClearsPendingMap() throws Exception {
    server = new MockWebServer();
    server.enqueue(tokenResponse());
    server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
      @Override
      public void onMessage(WebSocket webSocket, String text) {
        JsonNode message = read(text);
        long id = message.get("id").asLong();
        if ("auth".equals(message.get("method").asText())) {
          webSocket.send(response(id, "{}"));
        }
      }
    }));
    server.start();

    try (TrueConfSession session = session(properties(Duration.ofMillis(80)))) {
      assertThatThrownBy(() -> session.request(
          id -> commandFactory.sendMessage(id, "chat-1", "Hello", "text", null)))
          .isInstanceOfSatisfying(TrueConfException.class, ex -> {
            assertThat(ex.code()).isEqualTo("WEBSOCKET_REQUEST_TIMEOUT");
            assertThat(ex.retryable()).isTrue();
          });

      assertThat(session.pendingRequestCount()).isZero();
    }
  }

  @Test
  void disconnectFailsPendingRequestAndNextRequestReconnects() throws Exception {
    server = new MockWebServer();
    server.enqueue(tokenResponse());
    AtomicInteger connection = new AtomicInteger();
    server.enqueue(new MockResponse().withWebSocketUpgrade(listenerThatClosesOnSend(connection)));
    server.enqueue(new MockResponse().withWebSocketUpgrade(listenerThatReplies(connection)));
    server.start();

    try (TrueConfSession session = session(properties())) {
      assertThatThrownBy(() -> session.request(
          id -> commandFactory.sendMessage(id, "chat-1", "first", "text", null)))
          .isInstanceOfSatisfying(TrueConfException.class, ex -> {
            assertThat(ex.code()).isEqualTo("WEBSOCKET_CLOSED");
            assertThat(ex.retryable()).isTrue();
          });

      TrueConfResponse response = session.request(
          id -> commandFactory.sendMessage(id, "chat-1", "second", "text", null));

      assertThat(response.messageId()).isEqualTo("message-after-reconnect");
      assertThat(connection.get()).isEqualTo(2);
    }
  }

  private WebSocketListener listenerThatClosesOnSend(AtomicInteger connection) {
    return new WebSocketListener() {
      @Override
      public void onOpen(WebSocket webSocket, okhttp3.Response response) {
        connection.incrementAndGet();
      }

      @Override
      public void onMessage(WebSocket webSocket, String text) {
        JsonNode message = read(text);
        long id = message.get("id").asLong();
        if ("auth".equals(message.get("method").asText())) {
          webSocket.send(response(id, "{}"));
          return;
        }
        webSocket.close(1001, "test disconnect");
      }
    };
  }

  private WebSocketListener listenerThatReplies(AtomicInteger connection) {
    return new WebSocketListener() {
      @Override
      public void onOpen(WebSocket webSocket, okhttp3.Response response) {
        connection.incrementAndGet();
      }

      @Override
      public void onMessage(WebSocket webSocket, String text) {
        JsonNode message = read(text);
        long id = message.get("id").asLong();
        if ("auth".equals(message.get("method").asText())) {
          webSocket.send(response(id, "{}"));
          return;
        }
        webSocket.send(response(id, """
            {"chatId":"chat-1","messageId":"message-after-reconnect"}
            """));
      }
    };
  }

  private TrueConfSession session(AppProperties properties) {
    TrueConfTokenService tokenService = new TrueConfTokenService(
        properties,
        RestClient.builder().baseUrl(properties.httpBaseUrl()).build(),
        objectMapper,
        java.time.Clock.systemUTC());
    return new TrueConfSession(
        properties,
        tokenService,
        commandFactory,
        responseMapper,
        errorClassifier,
        objectMapper,
        HttpClient.newBuilder()
            .connectTimeout(properties.websocket().connectTimeout())
            .build(),
        scheduler);
  }

  private AppProperties properties() {
    return properties(Duration.ofMillis(250));
  }

  private AppProperties properties(Duration requestTimeout) {
    return new AppProperties(
        server.url("").toString(),
        server.url("/websocket/chat_bot/").toString().replace("http://", "ws://"),
        "bot-user",
        "bot-password",
        "api-key",
        "/tmp/truconf-proxydb-test-files",
        new AppProperties.Dispatcher(10, Duration.ofSeconds(5), Duration.ofMinutes(2), 2),
        new AppProperties.Retry(10, Duration.ofSeconds(1), Duration.ofMinutes(1), 2.0),
        new AppProperties.Websocket(
            requestTimeout,
            Duration.ofSeconds(2),
            Duration.ofMillis(100)));
  }

  private MockResponse tokenResponse() {
    return new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""
            {"access_token":"token-1","expires_in":3600}
            """);
  }

  private String response(long id, String payloadJson) {
    return """
        {"type":2,"id":%d,"payload":%s}
        """.formatted(id, payloadJson);
  }

  private JsonNode read(String text) {
    try {
      return objectMapper.readTree(text);
    } catch (Exception ex) {
      throw new IllegalArgumentException(ex);
    }
  }
}
