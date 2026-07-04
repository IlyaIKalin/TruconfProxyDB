package ru.truconf.proxydb.delivery;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import ru.truconf.proxydb.truconf.TrueConfClient;
import ru.truconf.proxydb.truconf.TrueConfException;
import ru.truconf.proxydb.truconf.TrueConfResponse;
import tools.jackson.databind.JsonNode;

@Service
public class GroupChatService {

  private static final boolean DEFAULT_DISPLAY_HISTORY = true;
  private static final boolean DEFAULT_CLEAR_HISTORY_ON_REMOVE = false;
  private static final int PARTICIPANT_PAGE_SIZE = 100;
  private static final int PARTICIPANT_PAGE_HARD_CAP = 100;
  private static final String ALREADY_PARTICIPANT_CODE = "309";
  private static final String INVALID_PARTICIPANT_CODE = "INVALID_PARTICIPANT";

  private final TrueConfClient trueConfClient;
  private final TrueConfUserIdResolver userIdResolver;

  public GroupChatService(
      TrueConfClient trueConfClient,
      TrueConfUserIdResolver userIdResolver) {
    this.trueConfClient = Objects.requireNonNull(trueConfClient, "trueConfClient must not be null");
    this.userIdResolver = Objects.requireNonNull(userIdResolver, "userIdResolver must not be null");
  }

  public CreateGroupChatResult createGroupChat(CreateGroupChatCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    TrueConfResponse response = trueConfClient.createGroupChat(requireText(command.title(), "title"));
    String chatId = requiredResponseField(response.chatId(), "chatId");
    List<ParticipantResult> participants = addParticipants(
        chatId,
        emptyIfNull(command.participants()),
        displayHistory(command.displayHistory()),
        false);
    return new CreateGroupChatResult(chatId, participants, response.rawResponse());
  }

  public ChatInfoResult getChatInfo(String chatId) {
    String normalizedChatId = requireText(chatId, "chatId");
    TrueConfResponse response = trueConfClient.getChatById(normalizedChatId);
    return new ChatInfoResult(
        requiredResponseField(response.chatId(), "chatId"),
        requiredResponseField(response.chatTitle(), "title"),
        response.rawResponse());
  }

  public AddParticipantsResult addParticipants(AddParticipantsCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    String chatId = requireText(command.chatId(), "chatId");
    List<ParticipantCommand> participants = emptyIfNull(command.participants());
    if (participants.isEmpty()) {
      throw new IllegalArgumentException("participants must not be empty");
    }
    return new AddParticipantsResult(
        chatId,
        addParticipants(chatId, participants, displayHistory(command.displayHistory()), true));
  }

  public SyncParticipantsResult syncParticipants(SyncParticipantsCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    String chatId = requireText(command.chatId(), "chatId");
    List<ParticipantCommand> participants = emptyIfNull(command.participants());

    List<ParticipantResult> results = new java.util.ArrayList<>(addParticipants(
        chatId,
        participants,
        displayHistory(command.displayHistory()),
        false));

    if (Boolean.TRUE.equals(command.removeStaleParticipants())) {
      Set<String> desiredUserIds = desiredUserIds(results);
      List<String> staleUserIds = currentParticipantUserIds(chatId).stream()
          .filter(userId -> !desiredUserIds.contains(userId))
          .sorted()
          .toList();
      for (int index = 0; index < staleUserIds.size(); index++) {
        results.add(removeStaleParticipant(chatId, staleUserIds.get(index), participants.size() + index));
      }
    }

    return new SyncParticipantsResult(chatId, results);
  }

  private List<ParticipantResult> addParticipants(
      String chatId,
      List<ParticipantCommand> participants,
      boolean displayHistory,
      boolean requireNotEmpty) {
    if (requireNotEmpty && participants.isEmpty()) {
      throw new IllegalArgumentException("participants must not be empty");
    }
    return java.util.stream.IntStream.range(0, participants.size())
        .mapToObj(index -> addParticipant(chatId, participants.get(index), displayHistory, index))
        .toList();
  }

  private ParticipantResult addParticipant(
      String chatId,
      ParticipantCommand participant,
      boolean displayHistory,
      int index) {
    if (participant == null) {
      return failed(index, null, null, null, "INVALID_PARTICIPANT", "participant must not be null");
    }

    String email = normalizeBlank(participant.email());
    String userId = normalizeBlank(participant.userId());
    if ((email == null && userId == null) || (email != null && userId != null)) {
      return failed(
          index,
          email,
          userId,
          null,
          "INVALID_PARTICIPANT",
          "participant must contain exactly one of email or userId");
    }

    String resolvedUserId = userId;
    try {
      resolvedUserId = userId == null ? userIdResolver.resolveByEmail(email) : userId;
      trueConfClient.addChatParticipant(chatId, resolvedUserId, displayHistory);
      return new ParticipantResult(
          index,
          ParticipantStatus.ADDED,
          email,
          resolvedUserId,
          null,
          null);
    } catch (InvalidOutboxJobException ex) {
      return failed(index, email, userId, null, ex.code(), ex.getMessage());
    } catch (TrueConfException ex) {
      ParticipantStatus status = ALREADY_PARTICIPANT_CODE.equals(ex.code())
          ? ParticipantStatus.IGNORED
          : ParticipantStatus.FAILED;
      return new ParticipantResult(index, status, email, resolvedUserId, ex.code(), ex.getMessage());
    }
  }

  private List<String> currentParticipantUserIds(String chatId) {
    List<String> userIds = new java.util.ArrayList<>();
    for (int pageNumber = 1; pageNumber <= PARTICIPANT_PAGE_HARD_CAP; pageNumber++) {
      TrueConfResponse response = trueConfClient.getChatParticipants(
          chatId,
          PARTICIPANT_PAGE_SIZE,
          pageNumber);
      List<String> page = participantUserIds(response.rawResponse());
      userIds.addAll(page);
      if (page.size() < PARTICIPANT_PAGE_SIZE) {
        break;
      }
    }
    return userIds;
  }

  private List<String> participantUserIds(JsonNode rawResponse) {
    JsonNode participants = child(payload(rawResponse), "participants");
    if (participants == null || !participants.isArray()) {
      return List.of();
    }
    List<String> userIds = new java.util.ArrayList<>();
    for (JsonNode participant : participants) {
      String userId = normalizeBlank(textValue(child(participant, "userId")));
      if (userId != null) {
        userIds.add(userId);
      }
    }
    return userIds;
  }

  private ParticipantResult removeStaleParticipant(String chatId, String userId, int index) {
    try {
      trueConfClient.removeChatParticipant(
          chatId,
          userId,
          DEFAULT_CLEAR_HISTORY_ON_REMOVE);
      return new ParticipantResult(
          index,
          ParticipantStatus.REMOVED,
          null,
          userId,
          null,
          null);
    } catch (TrueConfException ex) {
      return new ParticipantResult(
          index,
          ParticipantStatus.FAILED,
          null,
          userId,
          ex.code(),
          ex.getMessage());
    }
  }

  private static Set<String> desiredUserIds(List<ParticipantResult> results) {
    return results.stream()
        .filter(result -> result.userId() != null)
        .filter(result -> !INVALID_PARTICIPANT_CODE.equals(result.code()))
        .map(ParticipantResult::userId)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private static ParticipantResult failed(
      int index,
      String email,
      String userId,
      String resolvedUserId,
      String code,
      String message) {
    return new ParticipantResult(
        index,
        ParticipantStatus.FAILED,
        email,
        firstText(resolvedUserId, userId),
        code,
        message);
  }

  private static List<ParticipantCommand> emptyIfNull(List<ParticipantCommand> participants) {
    return participants == null ? List.of() : participants;
  }

  private static boolean displayHistory(Boolean value) {
    return value == null ? DEFAULT_DISPLAY_HISTORY : value;
  }

  private static String requiredResponseField(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new TrueConfException(
          "TRUECONF_RESPONSE_MISSING_" + fieldName.toUpperCase(java.util.Locale.ROOT),
          "TrueConf response does not contain " + fieldName,
          true);
    }
    return value;
  }

  private static String requireText(String value, String fieldName) {
    String normalized = normalizeBlank(value);
    if (normalized == null) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return normalized;
  }

  private static String normalizeBlank(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String firstText(String first, String second) {
    return first == null || first.isBlank() ? second : first;
  }

  public record CreateGroupChatCommand(
      String title,
      List<ParticipantCommand> participants,
      Boolean displayHistory) {
  }

  public record AddParticipantsCommand(
      String chatId,
      List<ParticipantCommand> participants,
      Boolean displayHistory) {
  }

  public record SyncParticipantsCommand(
      String chatId,
      List<ParticipantCommand> participants,
      Boolean displayHistory,
      Boolean removeStaleParticipants) {
  }

  public record ParticipantCommand(String email, String userId) {
  }

  public record CreateGroupChatResult(
      String chatId,
      List<ParticipantResult> participants,
      JsonNode rawResponse) {
  }

  public record AddParticipantsResult(
      String chatId,
      List<ParticipantResult> participants) {
  }

  public record SyncParticipantsResult(
      String chatId,
      List<ParticipantResult> participants) {
  }

  public record ChatInfoResult(
      String chatId,
      String title,
      JsonNode rawResponse) {
  }

  public record ParticipantResult(
      int index,
      ParticipantStatus status,
      String email,
      String userId,
      String code,
      String message) {
  }

  public enum ParticipantStatus {
    ADDED,
    IGNORED,
    REMOVED,
    FAILED
  }

  private static JsonNode payload(JsonNode response) {
    JsonNode payload = child(response, "payload");
    return payload == null || !payload.isObject() ? response : payload;
  }

  private static JsonNode child(JsonNode node, String fieldName) {
    if (node == null || !node.isObject()) {
      return null;
    }
    JsonNode child = node.get(fieldName);
    return child == null || child.isNull() || child.isMissingNode() ? null : child;
  }

  private static String textValue(JsonNode node) {
    if (node == null) {
      return null;
    }
    if (node.isTextual() || node.isNumber() || node.isBoolean()) {
      return node.asText();
    }
    return null;
  }
}
