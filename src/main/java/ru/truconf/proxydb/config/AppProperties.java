package ru.truconf.proxydb.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "truconf")
public record AppProperties(
    @NotBlank String botHttpBaseUrl,
    @NotBlank String botWsUrl,
    @NotBlank String botClientId,
    @NotBlank String botUsername,
    @NotBlank String botPassword,
    @NotBlank String proxyApiKey,
    @NotBlank String fileStorageDir,
    boolean tlsInsecureSkipVerify,
    @Valid @NotNull Dispatcher dispatcher,
    @Valid @NotNull Retry retry,
    @Valid @NotNull RateLimit rateLimit,
    @Valid @NotNull Websocket websocket,
    @Valid @NotNull ServerApi serverApi,
    @Valid @NotNull ActiveDirectory activeDirectory) {

  @ConstructorBinding
  public AppProperties {
  }

  public AppProperties(
      String httpBaseUrl,
      String wsUrl,
      String clientId,
      String username,
      String password,
      String proxyApiKey,
      String fileStorageDir,
      Dispatcher dispatcher,
      Retry retry,
      RateLimit rateLimit,
      Websocket websocket) {
    this(
        httpBaseUrl,
        wsUrl,
        clientId,
        username,
        password,
        proxyApiKey,
        fileStorageDir,
        false,
        dispatcher,
        retry,
        rateLimit,
        websocket,
        defaultServerApi(httpBaseUrl),
        defaultActiveDirectory());
  }

  public AppProperties(
      String httpBaseUrl,
      String wsUrl,
      String clientId,
      String username,
      String password,
      String proxyApiKey,
      String fileStorageDir,
      boolean tlsInsecureSkipVerify,
      Dispatcher dispatcher,
      Retry retry,
      RateLimit rateLimit,
      Websocket websocket,
      ServerApi serverApi) {
    this(
        httpBaseUrl,
        wsUrl,
        clientId,
        username,
        password,
        proxyApiKey,
        fileStorageDir,
        tlsInsecureSkipVerify,
        dispatcher,
        retry,
        rateLimit,
        websocket,
        serverApi,
        defaultActiveDirectory());
  }

  public AppProperties(
      String httpBaseUrl,
      String wsUrl,
      String clientId,
      String username,
      String password,
      String proxyApiKey,
      String fileStorageDir,
      boolean tlsInsecureSkipVerify,
      Dispatcher dispatcher,
      Retry retry,
      RateLimit rateLimit,
      Websocket websocket) {
    this(
        httpBaseUrl,
        wsUrl,
        clientId,
        username,
        password,
        proxyApiKey,
        fileStorageDir,
        tlsInsecureSkipVerify,
        dispatcher,
        retry,
        rateLimit,
        websocket,
        defaultServerApi(httpBaseUrl),
        defaultActiveDirectory());
  }

  public record Dispatcher(
      @Min(1) int batchSize,
      @NotNull Duration pollInterval,
      @NotNull Duration lockTimeout,
      @Min(1) int workerThreads) {
  }

  public record Retry(
      @Min(1) int maxAttempts,
      @NotNull Duration initialDelay,
      @NotNull Duration maxDelay,
      @Min(1) double multiplier) {
  }

  public record RateLimit(
      @Min(1) int commandsPerSecond) {
  }

  public record Websocket(
      @NotNull Duration requestTimeout,
      @NotNull Duration connectTimeout,
      @NotNull Duration reconnectDelay) {
  }

  public record ServerApi(
      @NotBlank String baseUrl,
      @NotBlank String clientId,
      @NotBlank String clientSecret,
      @NotBlank String grantType,
      String username,
      String password,
      @Min(1) int pageSize,
      @Min(1) int maxScanPages) {
  }

  public record ActiveDirectory(
      boolean enabled,
      @NotBlank String url,
      String bindDn,
      String bindPassword,
      @NotBlank String baseDn,
      @NotBlank String emailAttribute,
      @NotBlank String trueconfIdAttribute,
      @NotBlank String displayNameAttribute,
      @NotNull Duration connectTimeout,
      @NotNull Duration readTimeout) {
  }

  private static ServerApi defaultServerApi(String baseUrl) {
    return new ServerApi(baseUrl, "change-me", "change-me", "client_credentials", "", "", 100, 20);
  }

  private static ActiveDirectory defaultActiveDirectory() {
    return new ActiveDirectory(
        false,
        "ldap://ad.example.local:389",
        "",
        "",
        "DC=example,DC=local",
        "mail",
        "extensionAttribute5",
        "displayName",
        Duration.ofSeconds(3),
        Duration.ofSeconds(5));
  }
}
