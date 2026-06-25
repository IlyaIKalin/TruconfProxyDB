package ru.truconf.proxydb.delivery;

import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import ru.truconf.proxydb.truconf.TrueConfClient;
import ru.truconf.proxydb.truconf.TrueConfException;
import ru.truconf.proxydb.truconf.TrueConfResponse;
import tools.jackson.databind.JsonNode;

@Service
public class GroupChatService {

  private static final boolean DEFAULT_DISPLAY_HISTORY = true;
  private static final String ALREADY_PARTICIPANT_CODE = "309";

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
    FAILED
  }
}
