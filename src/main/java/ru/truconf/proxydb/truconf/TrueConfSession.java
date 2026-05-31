package ru.truconf.proxydb.truconf;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.truconf.proxydb.config.AppProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class TrueConfSession implements TrueConfCommandTransport, AutoCloseable {

  private static final int WEBSOCKET_RESPONSE_TYPE = 2;
  private static final int WEBSOCKET_REQUEST_TYPE = 1;

  private final AppProperties properties;
  private final TrueConfTokenService tokenService;
  private final TrueConfCommandFactory commandFactory;
  private final TrueConfResponseMapper responseMapper;
  private final TrueConfErrorClassifier errorClassifier;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final ScheduledExecutorService scheduler;
  private final boolean ownsScheduler;
  private final AtomicLong requestId = new AtomicLong();
  private final Map<Long, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
  private final Object sessionMonitor = new Object();

  private volatile WebSocket webSocket;
  private volatile boolean authenticated;

  @Autowired
  public TrueConfSession(
      AppProperties properties,
      TrueConfTokenService tokenService,
      TrueConfHttpClientFactory httpClientFactory,
      TrueConfCommandFactory commandFactory,
      TrueConfResponseMapper responseMapper,
      TrueConfErrorClassifier errorClassifier,
      ObjectMapper objectMapper) {
    this(
        properties,
        tokenService,
        commandFactory,
        responseMapper,
        errorClassifier,
        objectMapper,
        httpClientFactory.httpClient(properties.websocket().connectTimeout()),
        Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory("truconf-ws-timeout-")),
        true);
  }

  public TrueConfSession(
      AppProperties properties,
      TrueConfTokenService tokenService,
      TrueConfCommandFactory commandFactory,
      TrueConfResponseMapper responseMapper,
      TrueConfErrorClassifier errorClassifier,
      ObjectMapper objectMapper) {
    this(
        properties,
        tokenService,
        new TrueConfHttpClientFactory(properties),
        commandFactory,
        responseMapper,
        errorClassifier,
        objectMapper);
  }

  TrueConfSession(
      AppProperties properties,
      TrueConfTokenService tokenService,
      TrueConfCommandFactory commandFactory,
      TrueConfResponseMapper responseMapper,
      TrueConfErrorClassifier errorClassifier,
      ObjectMapper objectMapper,
      HttpClient httpClient,
      ScheduledExecutorService scheduler) {
    this(
        properties,
        tokenService,
        commandFactory,
        responseMapper,
        errorClassifier,
        objectMapper,
        httpClient,
        scheduler,
        false);
  }

  private TrueConfSession(
      AppProperties properties,
      TrueConfTokenService tokenService,
      TrueConfCommandFactory commandFactory,
      TrueConfResponseMapper responseMapper,
      TrueConfErrorClassifier errorClassifier,
      ObjectMapper objectMapper,
      HttpClient httpClient,
      ScheduledExecutorService scheduler,
      boolean ownsScheduler) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.tokenService = Objects.requireNonNull(tokenService, "tokenService must not be null");
    this.commandFactory = Objects.requireNonNull(commandFactory, "commandFactory must not be null");
    this.responseMapper = Objects.requireNonNull(responseMapper, "responseMapper must not be null");
    this.errorClassifier = Objects.requireNonNull(errorClassifier, "errorClassifier must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    this.ownsScheduler = ownsScheduler;
  }

  public TrueConfResponse request(Function<Long, ObjectNode> commandBuilder) {
    Objects.requireNonNull(commandBuilder, "commandBuilder must not be null");
    ensureAuthenticated();

    JsonNode response = sendRawCommand(commandBuilder);
    Optional<TrueConfError> error = responseMapper.extractError(response);
    if (error.isPresent()) {
      TrueConfError trueConfError = error.get();
      throw new TrueConfException(
          trueConfError.code(),
          trueConfError.message(),
          errorClassifier.isRetryable(trueConfError),
          trueConfError.rawResponse());
    }
    return responseMapper.mapSuccess(response);
  }

  public int pendingRequestCount() {
    return pendingRequests.size();
  }

  private void ensureAuthenticated() {
    if (webSocket != null && authenticated) {
      return;
    }

    synchronized (sessionMonitor) {
      if (webSocket != null && authenticated) {
        return;
      }
      closeCurrentSession();
      authenticate();
    }
  }

  private void openWebSocket() {
    requestId.set(0);
    String origin = websocketOrigin(properties.botWsUrl());
    try {
      webSocket = httpClient.newWebSocketBuilder()
          .header("Origin", origin)
          .header("User-Agent", "truconf-proxydb")
          .header("Accept", "*/*")
          .subprotocols("json.v1")
          .buildAsync(URI.create(properties.botWsUrl()), new SessionListener())
          .join();
      authenticated = false;
    } catch (RuntimeException ex) {
      failPendingRequests(retryableException(
          "WEBSOCKET_CONNECT_FAILED",
          websocketConnectFailedMessage(ex, origin),
          ex));
      throw retryableException(
          "WEBSOCKET_CONNECT_FAILED",
          websocketConnectFailedMessage(ex, origin),
          ex);
    }
  }

  private void authenticate() {
    for (int attempt = 0; attempt < 2; attempt++) {
      if (attempt > 0) {
        tokenService.invalidateToken();
        closeCurrentSession();
      }

      String token = tokenService.getAccessToken();
      openWebSocket();
      JsonNode response = sendRawCommand(id -> commandFactory.auth(id, token));
      Optional<TrueConfError> error = responseMapper.extractError(response);
      if (error.isEmpty()) {
        authenticated = true;
        return;
      }
      if (attempt == 0) {
        continue;
      }

      TrueConfError trueConfError = error.get();
      throw new TrueConfException(
          trueConfError.code(),
          trueConfError.message(),
          errorClassifier.isRetryable(trueConfError),
          trueConfError.rawResponse());
    }
  }

  private JsonNode sendRawCommand(Function<Long, ObjectNode> commandBuilder) {
    WebSocket current = webSocket;
    if (current == null) {
      throw retryableException(
          "WEBSOCKET_DISCONNECTED",
          "TrueConf WebSocket is not connected",
          null);
    }

    long id = requestId.incrementAndGet();
    ObjectNode command = commandBuilder.apply(id);
    CompletableFuture<JsonNode> future = new CompletableFuture<>();
    ScheduledFuture<?> timeout = scheduler.schedule(
        () -> {
          PendingRequest removed = pendingRequests.remove(id);
          if (removed != null) {
            removed.future().completeExceptionally(retryableException(
                "WEBSOCKET_REQUEST_TIMEOUT",
                "TrueConf WebSocket request timed out",
                null));
          }
        },
        properties.websocket().requestTimeout().toMillis(),
        TimeUnit.MILLISECONDS);
    pendingRequests.put(id, new PendingRequest(method(command), future, timeout));

    try {
      current.sendText(command.toString(), true)
          .whenComplete((ignored, throwable) -> {
            if (throwable != null) {
              PendingRequest removed = pendingRequests.remove(id);
              if (removed != null) {
                removed.cancelTimeout();
                removed.future().completeExceptionally(retryableException(
                    "WEBSOCKET_SEND_FAILED",
                    "TrueConf WebSocket send failed",
                    throwable));
              }
            }
          });
    } catch (RuntimeException ex) {
      PendingRequest removed = pendingRequests.remove(id);
      if (removed != null) {
        removed.cancelTimeout();
      }
      throw retryableException(
          "WEBSOCKET_SEND_FAILED",
          "TrueConf WebSocket send failed",
          ex);
    }

    try {
      return future.get();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw retryableException(
          "WEBSOCKET_REQUEST_INTERRUPTED",
          "TrueConf WebSocket request was interrupted",
          ex);
    } catch (ExecutionException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof TrueConfException trueConfException) {
        throw trueConfException;
      }
      throw retryableException(
          "WEBSOCKET_REQUEST_FAILED",
          "TrueConf WebSocket request failed",
          cause);
    }
  }

  private void handleIncomingText(String text) {
    JsonNode message;
    try {
      message = objectMapper.readTree(text);
    } catch (Exception ex) {
      failSession(retryableException(
          "WEBSOCKET_MESSAGE_INVALID",
          "TrueConf WebSocket message is not valid JSON",
          ex));
      return;
    }

    int type = intField(message, "type");
    long id = longField(message, "id");
    if (type == WEBSOCKET_RESPONSE_TYPE && id > 0) {
      PendingRequest pending = pendingRequests.remove(id);
      if (pending != null) {
        pending.cancelTimeout();
        pending.future().complete(message);
      }
      return;
    }

    if (type == WEBSOCKET_REQUEST_TYPE && id > 0) {
      WebSocket current = webSocket;
      if (current != null) {
        current.sendText(commandFactory.ack(id).toString(), true);
      }
    }
  }

  private void failSession(TrueConfException exception) {
    synchronized (sessionMonitor) {
      authenticated = false;
      webSocket = null;
      failPendingRequests(exception);
    }
  }

  private void failPendingRequests(TrueConfException exception) {
    pendingRequests.forEach((id, pending) -> {
      if (pendingRequests.remove(id, pending)) {
        pending.cancelTimeout();
        pending.future().completeExceptionally(exception);
      }
    });
  }

  private void closeCurrentSession() {
    WebSocket current = webSocket;
    authenticated = false;
    webSocket = null;
    if (current != null) {
      current.abort();
    }
  }

  @Override
  @PreDestroy
  public void close() {
    closeCurrentSession();
    failPendingRequests(retryableException(
        "WEBSOCKET_SESSION_CLOSED",
        "TrueConf WebSocket session was closed",
        null));
    if (ownsScheduler) {
      scheduler.shutdownNow();
    }
  }

  private static String method(ObjectNode command) {
    JsonNode method = command.get("method");
    return method != null && method.isTextual() ? method.asText() : "unknown";
  }

  private static int intField(JsonNode node, String fieldName) {
    JsonNode field = node == null || !node.isObject() ? null : node.get(fieldName);
    if (field == null || !field.canConvertToInt()) {
      return 0;
    }
    return field.asInt();
  }

  private static long longField(JsonNode node, String fieldName) {
    JsonNode field = node == null || !node.isObject() ? null : node.get(fieldName);
    if (field == null || !field.canConvertToLong()) {
      return 0;
    }
    return field.asLong();
  }

  private static TrueConfException retryableException(
      String code,
      String message,
      Throwable cause) {
    return new TrueConfException(code, message, true, cause);
  }

  private static String websocketConnectFailedMessage(Throwable throwable, String origin) {
    Throwable cause = unwrap(throwable);
    String message = "TrueConf WebSocket connection failed";
    if (cause instanceof WebSocketHandshakeException ex) {
      return message + ": handshake returned HTTP " + ex.getResponse().statusCode()
          + " for Origin " + origin;
    }
    if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
      return message + ": " + cause.getMessage() + " for Origin " + origin;
    }
    return message + " for Origin " + origin;
  }

  private static Throwable unwrap(Throwable throwable) {
    Throwable current = throwable;
    while ((current instanceof CompletionException || current instanceof ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static String websocketOrigin(String wsUrl) {
    URI uri = URI.create(wsUrl);
    String scheme = switch (uri.getScheme()) {
      case "wss" -> "https";
      case "ws" -> "http";
      default -> uri.getScheme();
    };
    return URI.create(scheme + "://" + uri.getAuthority()).toString();
  }

  private record PendingRequest(
      String method,
      CompletableFuture<JsonNode> future,
      ScheduledFuture<?> timeout) {

    private void cancelTimeout() {
      timeout.cancel(false);
    }
  }

  private final class SessionListener implements WebSocket.Listener {

    private final StringBuilder buffer = new StringBuilder();

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public java.util.concurrent.CompletionStage<?> onText(
        WebSocket webSocket,
        CharSequence data,
        boolean last) {
      buffer.append(data);
      if (last) {
        String text = buffer.toString();
        buffer.setLength(0);
        handleIncomingText(text);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public java.util.concurrent.CompletionStage<?> onClose(
        WebSocket webSocket,
        int statusCode,
        String reason) {
      failSession(retryableException(
          "WEBSOCKET_CLOSED",
          "TrueConf WebSocket closed: " + statusCode + " " + reason,
          null));
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      failSession(retryableException(
          "WEBSOCKET_ERROR",
          "TrueConf WebSocket failed",
          error));
    }
  }

  private static final class DaemonThreadFactory implements ThreadFactory {

    private final String prefix;
    private final AtomicLong sequence = new AtomicLong();

    private DaemonThreadFactory(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    }
  }
}
