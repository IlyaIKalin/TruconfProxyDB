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
    @NotBlank String httpBaseUrl,
    @NotBlank String wsUrl,
    @NotBlank String clientId,
    @NotBlank String username,
    @NotBlank String password,
    @NotBlank String proxyApiKey,
    @NotBlank String fileStorageDir,
    boolean tlsInsecureSkipVerify,
    @Valid @NotNull Dispatcher dispatcher,
    @Valid @NotNull Retry retry,
    @Valid @NotNull RateLimit rateLimit,
    @Valid @NotNull Websocket websocket,
    @Valid @NotNull ServerApi serverApi) {

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
        defaultServerApi());
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
        defaultServerApi());
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
      @Min(1) int pageSize,
      @Min(1) int maxScanPages) {
  }

  private static ServerApi defaultServerApi() {
    return new ServerApi(100, 20);
  }
}
