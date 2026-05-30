package ru.truconf.proxydb.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.truconf.proxydb.truconf.TrueConfClient;
import ru.truconf.proxydb.truconf.TrueConfServerApiClient;
import ru.truconf.proxydb.truconf.TrueConfServerApiClient.UserSearchResponse;
import tools.jackson.databind.JsonNode;

@Validated
@RestController
@RequestMapping("/api/v1/trueconf")
public class TrueConfDiagnosticController {

  private final TrueConfClient trueConfClient;
  private final TrueConfServerApiClient trueConfServerApiClient;

  public TrueConfDiagnosticController(
      TrueConfClient trueConfClient,
      TrueConfServerApiClient trueConfServerApiClient) {
    this.trueConfClient = trueConfClient;
    this.trueConfServerApiClient = trueConfServerApiClient;
  }

  @GetMapping("/chats")
  public JsonNode getChats(
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int count,
      @RequestParam(defaultValue = "1") @Min(1) int page) {
    return trueConfClient.getChats(count, page).rawResponse();
  }

  @PostMapping("/p2p-chats")
  public JsonNode createP2PChat(@RequestBody @Valid CreateP2PChatRequest request) {
    return trueConfClient.createP2PChat(request.userId()).rawResponse();
  }

  @GetMapping("/users/search")
  public UserSearchResponse searchUsers(
      @RequestParam @NotBlank String query,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
    return trueConfServerApiClient.searchAccounts(query, limit);
  }

  public record CreateP2PChatRequest(@NotBlank String userId) {
  }
}
