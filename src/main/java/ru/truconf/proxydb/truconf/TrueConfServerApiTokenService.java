package ru.truconf.proxydb.truconf;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import ru.truconf.proxydb.config.AppProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class TrueConfServerApiTokenService {

  private static final String TOKEN_PATH = "/oauth2/v1/token";
  private static final Duration DEFAULT_REFRESH_MARGIN = Duration.ofSeconds(30);
  private static final Duration DEFAULT_EXPIRES_IN = Duration.ofHours(1);

  private final AppProperties properties;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final Object refreshMonitor = new Object();

  private volatile CachedToken cachedToken;

  @Autowired
  public TrueConfServerApiTokenService(
      AppProperties properties,
      RestClient.Builder restClientBuilder,
      TrueConfHttpClientFactory httpClientFactory,
      ObjectMapper objectMapper) {
    this(
        properties,
        httpClientFactory.configure(restClientBuilder)
            .baseUrl(stripTrailingSlash(properties.serverApi().baseUrl()))
            .build(),
        objectMapper,
        Clock.systemUTC());
  }

  TrueConfServerApiTokenService(
      AppProperties properties,
      RestClient restClient,
      ObjectMapper objectMapper,
      Clock clock) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public String getAccessToken() {
    CachedToken current = cachedToken;
    Instant now = clock.instant();
    if (current != null && current.isUsableAt(now)) {
      return current.accessToken();
    }

    synchronized (refreshMonitor) {
      current = cachedToken;
      now = clock.instant();
      if (current != null && current.isUsableAt(now)) {
        return current.accessToken();
      }
      cachedToken = requestToken(now);
      return cachedToken.accessToken();
    }
  }

  public void invalidateToken() {
    synchronized (refreshMonitor) {
      cachedToken = null;
    }
  }

  private CachedToken requestToken(Instant requestedAt) {
    AppProperties.ServerApi serverApi = properties.serverApi();
    Map<String, String> request = new LinkedHashMap<>();
    request.put("grant_type", serverApi.grantType());
    request.put("client_id", serverApi.clientId());
    putIfNotBlank(request, "client_secret", serverApi.clientSecret());
    if ("password".equalsIgnoreCase(serverApi.grantType())) {
      putIfNotBlank(request, "username", serverApi.username());
      putIfNotBlank(request, "password", serverApi.password());
    }

    try {
      String body = restClient.post()
          .uri(TOKEN_PATH)
          .contentType(MediaType.APPLICATION_JSON)
          .accept(MediaType.APPLICATION_JSON)
          .body(request)
          .retrieve()
          .body(String.class);

      JsonNode root = objectMapper.readTree(body);
      String accessToken = firstText(root, "access_token", "token");
      if (accessToken == null) {
        throw new TrueConfException(
            "SERVER_API_OAUTH_RESPONSE_INVALID",
            "TrueConf Server API OAuth response does not contain access token",
            true,
            root);
      }

      Duration expiresIn = expiresIn(root, requestedAt);
      Instant expiresAt = requestedAt.plus(expiresIn);
      return new CachedToken(accessToken, expiresAt, refreshMargin(expiresIn));
    } catch (TrueConfException ex) {
      throw ex;
    } catch (RestClientResponseException ex) {
      JsonNode errorResponse = errorResponse(ex);
      throw new TrueConfException(
          "SERVER_API_OAUTH_HTTP_" + ex.getStatusCode().value(),
          oauthHttpMessage(ex, errorResponse),
          ex.getStatusCode().is5xxServerError(),
          errorResponse,
          ex);
    } catch (RestClientException ex) {
      throw new TrueConfException(
          "SERVER_API_OAUTH_REQUEST_FAILED",
          "TrueConf Server API OAuth request failed",
          true,
          ex);
    } catch (Exception ex) {
      throw new TrueConfException(
          "SERVER_API_OAUTH_RESPONSE_INVALID",
          "TrueConf Server API OAuth response is not valid JSON",
          true,
          ex);
    }
  }

  private static void putIfNotBlank(Map<String, String> request, String key, String value) {
    if (value != null && !value.isBlank()) {
      request.put(key, value);
    }
  }

  private static Duration expiresIn(JsonNode root, Instant requestedAt) {
    Long seconds = firstLong(root, "expires_in", "expiresIn");
    if (seconds != null && seconds > 0) {
      return Duration.ofSeconds(seconds);
    }
    Long expiresAtEpochSeconds = firstLong(root, "expires_at", "expiresAt");
    if (expiresAtEpochSeconds != null && expiresAtEpochSeconds > requestedAt.getEpochSecond()) {
      return Duration.between(requestedAt, Instant.ofEpochSecond(expiresAtEpochSeconds));
    }
    return DEFAULT_EXPIRES_IN;
  }

  private static Duration refreshMargin(Duration expiresIn) {
    Duration tenth = expiresIn.dividedBy(10);
    if (tenth.isZero()) {
      return Duration.ZERO;
    }
    return tenth.compareTo(DEFAULT_REFRESH_MARGIN) < 0 ? tenth : DEFAULT_REFRESH_MARGIN;
  }

  private String oauthHttpMessage(RestClientResponseException ex, JsonNode errorResponse) {
    String message = "TrueConf Server API OAuth endpoint returned HTTP "
        + ex.getStatusCode().value();
    String error = firstText(errorResponse, "error");
    String errorDescription = firstText(errorResponse, "error_description", "errorDescription");
    if (errorDescription != null) {
      return message + ": " + errorDescription;
    }
    if (error != null) {
      return message + ": " + error;
    }
    return message;
  }

  private JsonNode errorResponse(RestClientResponseException ex) {
    String body = ex.getResponseBodyAsString();
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readTree(body);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String firstText(JsonNode root, String... fieldNames) {
    if (root == null || !root.isObject()) {
      return null;
    }
    for (String fieldName : fieldNames) {
      JsonNode node = root.get(fieldName);
      if (node != null && node.isTextual() && !node.asText().isBlank()) {
        return node.asText();
      }
    }
    return null;
  }

  private static Long firstLong(JsonNode root, String... fieldNames) {
    if (root == null || !root.isObject()) {
      return null;
    }
    for (String fieldName : fieldNames) {
      JsonNode node = root.get(fieldName);
      if (node == null || node.isNull()) {
        continue;
      }
      if (node.isLong() || node.isInt() || node.canConvertToLong()) {
        return node.asLong();
      }
      if (node.isTextual()) {
        try {
          return Long.parseLong(node.asText());
        } catch (NumberFormatException ignored) {
          // Try the next candidate.
        }
      }
    }
    return null;
  }

  private static String stripTrailingSlash(String value) {
    if (value.endsWith("/")) {
      return value.substring(0, value.length() - 1);
    }
    return value;
  }

  private record CachedToken(String accessToken, Instant expiresAt, Duration refreshMargin) {

    private boolean isUsableAt(Instant instant) {
      return instant.plus(refreshMargin).isBefore(expiresAt);
    }
  }
}
