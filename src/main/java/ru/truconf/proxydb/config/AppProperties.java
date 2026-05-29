package ru.truconf.proxydb.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
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
    @Valid @NotNull Dispatcher dispatcher,
    @Valid @NotNull Retry retry,
    @Valid @NotNull RateLimit rateLimit,
    @Valid @NotNull Websocket websocket) {

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
}
