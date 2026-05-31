package ru.truconf.proxydb.truconf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import ru.truconf.proxydb.config.AppProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class TrueConfServerApiClient {

  private static final String ACCOUNTS_PATH = "/api/v4/accounts";

  private final AppProperties properties;
  private final TrueConfServerApiTokenService tokenService;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  @Autowired
  public TrueConfServerApiClient(
      AppProperties properties,
      TrueConfServerApiTokenService tokenService,
      RestClient.Builder restClientBuilder,
      TrueConfHttpClientFactory httpClientFactory,
      ObjectMapper objectMapper) {
    this(
        properties,
        tokenService,
        httpClientFactory.configure(restClientBuilder)
            .baseUrl(stripTrailingSlash(properties.serverApi().baseUrl()))
            .build(),
        objectMapper);
  }

  TrueConfServerApiClient(
      AppProperties properties,
      TrueConfServerApiTokenService tokenService,
      RestClient restClient,
      ObjectMapper objectMapper) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.tokenService = Objects.requireNonNull(tokenService, "tokenService must not be null");
    this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  public UserSearchResponse searchAccounts(String query, int limit) {
    String normalizedQuery = normalizeQuery(query);
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive");
    }

    int pageSize = properties.serverApi().pageSize();
    int maxScanPages = properties.serverApi().maxScanPages();
    List<UserAccount> matches = new ArrayList<>();
    int scannedAccounts = 0;
    int scannedPages = 0;
    Integer page = 1;

    while (page != null && scannedPages < maxScanPages && matches.size() < limit) {
      JsonNode response = getAccountsPage(page, pageSize);
      scannedPages++;
      JsonNode accounts = accounts(response);
      if (accounts != null && accounts.isArray()) {
        for (JsonNode account : accounts) {
          scannedAccounts++;
          UserAccount user = toUserAccount(account);
          if (matches(user, normalizedQuery)) {
            matches.add(user);
            if (matches.size() == limit) {
              break;
            }
          }
        }
      }
      page = nextPage(response);
    }

    return new UserSearchResponse(
        query,
        scannedPages,
        scannedAccounts,
        matches.size() == limit,
        matches);
  }

  private JsonNode getAccountsPage(int page, int pageSize) {
    try {
      String body = restClient.get()
          .uri(uriBuilder -> uriBuilder
              .path(ACCOUNTS_PATH)
              .queryParam("page", page)
              .queryParam("page_size", pageSize)
              .queryParam("sort_field", "created_at")
              .queryParam("sort_order", 1)
              .build())
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
          .accept(MediaType.APPLICATION_JSON)
          .retrieve()
          .body(String.class);

      return objectMapper.readTree(body);
    } catch (TrueConfException ex) {
      throw ex;
    } catch (RestClientResponseException ex) {
      JsonNode errorResponse = errorResponse(ex);
      throw new TrueConfException(
          "SERVER_API_HTTP_" + ex.getStatusCode().value(),
          "TrueConf Server API returned HTTP " + ex.getStatusCode().value(),
          ex.getStatusCode().is5xxServerError(),
          errorResponse,
          ex);
    } catch (RestClientException ex) {
      throw new TrueConfException(
          "SERVER_API_REQUEST_FAILED",
          "TrueConf Server API request failed",
          true,
          ex);
    } catch (Exception ex) {
      throw new TrueConfException(
          "SERVER_API_RESPONSE_INVALID",
          "TrueConf Server API response is not valid JSON",
          true,
          ex);
    }
  }

  private String accessToken() {
    return tokenService.getAccessToken();
  }

  private JsonNode errorResponse(RestClientResponseException ex) {
    try {
      return objectMapper.readTree(ex.getResponseBodyAsString());
    } catch (Exception ignored) {
      return null;
    }
  }

  private static JsonNode accounts(JsonNode response) {
    JsonNode accounts = child(response, "accounts");
    if (accounts == null) {
      accounts = child(response, "items");
    }
    if (accounts == null) {
      accounts = child(response, "users");
    }
    return accounts;
  }

  private static Integer nextPage(JsonNode response) {
    JsonNode nextPageId = child(response, "next_page_id");
    if (nextPageId == null || nextPageId.isNull()) {
      return null;
    }
    if (nextPageId.isNumber()) {
      int value = nextPageId.asInt();
      return value > 0 ? value : null;
    }
    if (nextPageId.isTextual()) {
      try {
        int value = Integer.parseInt(nextPageId.asText());
        return value > 0 ? value : null;
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private static UserAccount toUserAccount(JsonNode account) {
    String id = text(account, "id");
    String login = text(account, "login");
    String domain = text(account, "domain");
    String displayName = text(account, "display_name", "displayName", "name");
    String type = text(account, "type");
    String trueconfId = trueconfId(login, domain);
    return new UserAccount(id, login, domain, displayName, type, trueconfId);
  }

  private static boolean matches(UserAccount user, String query) {
    return contains(user.id(), query)
        || contains(user.login(), query)
        || contains(user.domain(), query)
        || contains(user.displayName(), query)
        || contains(user.type(), query)
        || contains(user.trueconfId(), query);
  }

  private static boolean contains(String value, String query) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(query);
  }

  private static String trueconfId(String login, String domain) {
    if (login == null || login.isBlank()) {
      return null;
    }
    if (login.contains("@") || domain == null || domain.isBlank()) {
      return login;
    }
    return login + "@" + domain;
  }

  private static String normalizeQuery(String query) {
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("query must not be blank");
    }
    return query.trim().toLowerCase(Locale.ROOT);
  }

  private static JsonNode child(JsonNode node, String fieldName) {
    if (node == null || !node.isObject()) {
      return null;
    }
    JsonNode child = node.get(fieldName);
    return child == null || child.isNull() || child.isMissingNode() ? null : child;
  }

  private static String text(JsonNode node, String... fieldNames) {
    for (String fieldName : fieldNames) {
      JsonNode child = child(node, fieldName);
      if (child != null && child.isValueNode()) {
        String value = child.asText();
        if (!value.isBlank()) {
          return value;
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

  public record UserSearchResponse(
      String query,
      int scannedPages,
      int scannedAccounts,
      boolean limitReached,
      List<UserAccount> users) {
  }

  public record UserAccount(
      String id,
      String login,
      String domain,
      String displayName,
      String type,
      String trueconfId) {
  }
}
