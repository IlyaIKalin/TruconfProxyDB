package ru.truconf.proxydb.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.truconf.proxydb.delivery.GroupChatService;
import ru.truconf.proxydb.delivery.GroupChatService.AddParticipantsCommand;
import ru.truconf.proxydb.delivery.GroupChatService.AddParticipantsResult;
import ru.truconf.proxydb.delivery.GroupChatService.CreateGroupChatCommand;
import ru.truconf.proxydb.delivery.GroupChatService.CreateGroupChatResult;
import ru.truconf.proxydb.delivery.GroupChatService.ParticipantCommand;
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
  private final GroupChatService groupChatService;

  public TrueConfDiagnosticController(
      TrueConfClient trueConfClient,
      TrueConfServerApiClient trueConfServerApiClient,
      GroupChatService groupChatService) {
    this.trueConfClient = trueConfClient;
    this.trueConfServerApiClient = trueConfServerApiClient;
    this.groupChatService = groupChatService;
  }

  @GetMapping("/chats")
  public JsonNode getChats(
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int count,
      @RequestParam(defaultValue = "1") @Min(1) int page) {
    return trueConfClient.getChats(count, page).rawResponse();
  }

  @GetMapping("/chats/{chatId}")
  public JsonNode getChat(@PathVariable @NotBlank String chatId) {
    return trueConfClient.getChatById(chatId).rawResponse();
  }

  @PostMapping("/p2p-chats")
  public JsonNode createP2PChat(@RequestBody @Valid CreateP2PChatRequest request) {
    return trueConfClient.createP2PChat(request.userId()).rawResponse();
  }

  @PostMapping("/group-chats")
  public ResponseEntity<CreateGroupChatResult> createGroupChat(
      @RequestBody @Valid CreateGroupChatRequest request) {
    CreateGroupChatResult response = groupChatService.createGroupChat(new CreateGroupChatCommand(
        request.title(),
        participants(request.participants()),
        request.displayHistory()));
    return ResponseEntity.created(URI.create("/api/v1/trueconf/group-chats/" + response.chatId()))
        .body(response);
  }

  @PostMapping("/group-chats/{chatId}/participants")
  public AddParticipantsResult addGroupChatParticipants(
      @PathVariable String chatId,
      @RequestBody AddParticipantsRequest request) {
    return groupChatService.addParticipants(new AddParticipantsCommand(
        chatId,
        participants(request == null ? null : request.participants()),
        request == null ? null : request.displayHistory()));
  }

  @GetMapping("/group-chats/{chatId}/participants")
  public JsonNode getGroupChatParticipants(
      @PathVariable @NotBlank String chatId,
      @RequestParam(defaultValue = "100") @Min(1) @Max(100) int pageSize,
      @RequestParam(defaultValue = "1") @Min(1) int pageNumber) {
    return trueConfClient.getChatParticipants(chatId, pageSize, pageNumber).rawResponse();
  }

  @PostMapping("/group-chats/{chatId}/participants/remove")
  public JsonNode removeGroupChatParticipant(
      @PathVariable @NotBlank String chatId,
      @RequestBody @Valid RemoveParticipantRequest request) {
    return trueConfClient.removeChatParticipant(
        chatId,
        request.userId(),
        Boolean.TRUE.equals(request.clearHistory()))
        .rawResponse();
  }

  @GetMapping("/users/search")
  public UserSearchResponse searchUsers(
      @RequestParam @NotBlank String query,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
    return trueConfServerApiClient.searchAccounts(query, limit);
  }

  public record CreateP2PChatRequest(@NotBlank String userId) {
  }

  public record CreateGroupChatRequest(
      @NotBlank String title,
      List<ParticipantRequest> participants,
      Boolean displayHistory) {
  }

  public record AddParticipantsRequest(
      List<ParticipantRequest> participants,
      Boolean displayHistory) {
  }

  public record RemoveParticipantRequest(
      @NotBlank String userId,
      Boolean clearHistory) {
  }

  public record ParticipantRequest(String email, String userId) {
  }

  private static List<ParticipantCommand> participants(List<ParticipantRequest> participants) {
    if (participants == null) {
      return null;
    }
    return participants.stream()
        .map(participant -> participant == null
            ? null
            : new ParticipantCommand(participant.email(), participant.userId()))
        .toList();
  }
}
