package ru.truconf.proxydb.truconf;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import ru.truconf.proxydb.config.AppProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class TrueConfTokenService {

  private static final String TOKEN_PATH = "/bridge/api/client/v1/oauth/token";
  private static final Duration DEFAULT_REFRESH_MARGIN = Duration.ofSeconds(30);
  private static final Duration DEFAULT_EXPIRES_IN = Duration.ofHours(1);

  private final AppProperties properties;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final Object refreshMonitor = new Object();

  private volatile CachedToken cachedToken;

  @Autowired
  public TrueConfTokenService(
      AppProperties properties,
      RestClient.Builder restClientBuilder,
      ObjectMapper objectMapper) {
    this(
        properties,
        restClientBuilder.baseUrl(stripTrailingSlash(properties.httpBaseUrl())).build(),
        objectMapper,
        Clock.systemUTC());
  }

  TrueConfTokenService(
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
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "password");
    form.add("username", properties.username());
    form.add("password", properties.password());

    try {
      String body = restClient.post()
          .uri(TOKEN_PATH)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .accept(MediaType.APPLICATION_JSON)
          .body(form)
          .retrieve()
          .body(String.class);

      JsonNode root = objectMapper.readTree(body);
      String accessToken = firstText(root, "access_token", "token", "id_token");
      if (accessToken == null) {
        throw new TrueConfException(
            "OAUTH_RESPONSE_INVALID",
            "TrueConf OAuth response does not contain access token",
            true,
            root);
      }

      Duration expiresIn = expiresIn(root);
      Instant expiresAt = requestedAt.plus(expiresIn);
      return new CachedToken(accessToken, expiresAt, refreshMargin(expiresIn));
    } catch (TrueConfException ex) {
      throw ex;
    } catch (RestClientResponseException ex) {
      boolean retryable = ex.getStatusCode().is5xxServerError();
      throw new TrueConfException(
          "OAUTH_HTTP_" + ex.getStatusCode().value(),
          "TrueConf OAuth endpoint returned HTTP " + ex.getStatusCode().value(),
          retryable,
          ex);
    } catch (RestClientException ex) {
      throw new TrueConfException(
          "OAUTH_REQUEST_FAILED",
          "TrueConf OAuth request failed",
          true,
          ex);
    } catch (Exception ex) {
      throw new TrueConfException(
          "OAUTH_RESPONSE_INVALID",
          "TrueConf OAuth response is not valid JSON",
          true,
          ex);
    }
  }

  private static Duration expiresIn(JsonNode root) {
    Long seconds = firstLong(root, "expires_in", "expiresIn");
    if (seconds == null || seconds <= 0) {
      return DEFAULT_EXPIRES_IN;
    }
    return Duration.ofSeconds(seconds);
  }

  private static Duration refreshMargin(Duration expiresIn) {
    Duration tenth = expiresIn.dividedBy(10);
    if (tenth.isZero()) {
      return Duration.ZERO;
    }
    return tenth.compareTo(DEFAULT_REFRESH_MARGIN) < 0 ? tenth : DEFAULT_REFRESH_MARGIN;
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
