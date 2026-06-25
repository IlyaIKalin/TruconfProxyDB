package ru.truconf.proxydb.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.truconf.proxydb.delivery.GroupChatService.AddParticipantsCommand;
import ru.truconf.proxydb.delivery.GroupChatService.CreateGroupChatCommand;
import ru.truconf.proxydb.delivery.GroupChatService.ParticipantCommand;
import ru.truconf.proxydb.delivery.GroupChatService.ParticipantStatus;
import ru.truconf.proxydb.truconf.TrueConfClient;
import ru.truconf.proxydb.truconf.TrueConfException;
import ru.truconf.proxydb.truconf.TrueConfResponse;
import ru.truconf.proxydb.truconf.TrueConfUploadFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GroupChatServiceTests {

  private final TrueConfUserIdResolver userIdResolver = mock(TrueConfUserIdResolver.class);
  private final RecordingTrueConfClient trueConfClient = new RecordingTrueConfClient();
  private final GroupChatService service = new GroupChatService(trueConfClient, userIdResolver);

  @Test
  void createsEmptyGroupChat() {
    var result = service.createGroupChat(new CreateGroupChatCommand("Support", null, null));

    assertThat(result.chatId()).isEqualTo("group-chat-1");
    assertThat(result.participants()).isEmpty();
    assertThat(trueConfClient.calls()).containsExactly("createGroupChat:Support");
  }

  @Test
  void createsGroupChatAndAddsMixedParticipantsWithDefaultDisplayHistory() {
    when(userIdResolver.resolveByEmail("employee@example.com"))
        .thenReturn("gd.rt.ru\\employee@s13.trueconf.rt.ru");

    var result = service.createGroupChat(new CreateGroupChatCommand(
        "Support",
        List.of(
            new ParticipantCommand("employee@example.com", null),
            new ParticipantCommand(null, "direct@s13.trueconf.rt.ru")),
        null));

    assertThat(result.chatId()).isEqualTo("group-chat-1");
    assertThat(result.participants()).extracting("status")
        .containsExactly(ParticipantStatus.ADDED, ParticipantStatus.ADDED);
    assertThat(result.participants().getFirst().userId())
        .isEqualTo("gd.rt.ru\\employee@s13.trueconf.rt.ru");
    assertThat(trueConfClient.calls()).containsExactly(
        "createGroupChat:Support",
        "addChatParticipant:group-chat-1:gd.rt.ru\\employee@s13.trueconf.rt.ru:true",
        "addChatParticipant:group-chat-1:direct@s13.trueconf.rt.ru:true");
  }

  @Test
  void addParticipantsReturnsPerItemFailuresAndKeepsProcessing() {
    trueConfClient.alreadyParticipantUserId("already@s13.trueconf.rt.ru");

    var result = service.addParticipants(new AddParticipantsCommand(
        "group-chat-1",
        List.of(
            new ParticipantCommand(null, "already@s13.trueconf.rt.ru"),
            new ParticipantCommand("x@example.com", "x@s13.trueconf.rt.ru"),
            new ParticipantCommand(null, "ok@s13.trueconf.rt.ru")),
        false));

    assertThat(result.participants()).extracting("status")
        .containsExactly(ParticipantStatus.IGNORED, ParticipantStatus.FAILED, ParticipantStatus.ADDED);
    assertThat(result.participants().getFirst().code()).isEqualTo("309");
    assertThat(result.participants().get(1).code()).isEqualTo("INVALID_PARTICIPANT");
    assertThat(trueConfClient.calls()).containsExactly(
        "addChatParticipant:group-chat-1:already@s13.trueconf.rt.ru:false",
        "addChatParticipant:group-chat-1:ok@s13.trueconf.rt.ru:false");
  }

  private static final class RecordingTrueConfClient implements TrueConfClient {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> calls = new ArrayList<>();
    private String alreadyParticipantUserId;

    @Override
    public TrueConfResponse createP2PChat(String userId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TrueConfResponse getChats(int count, int page) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TrueConfResponse createGroupChat(String title) {
      calls.add("createGroupChat:" + title);
      return new TrueConfResponse("group-chat-1", null, null, null, null, null, null, raw("chatId", "group-chat-1"));
    }

    @Override
    public TrueConfResponse addChatParticipant(String chatId, String userId, boolean displayHistory) {
      calls.add("addChatParticipant:" + chatId + ":" + userId + ":" + displayHistory);
      if (userId.equals(alreadyParticipantUserId)) {
        throw new TrueConfException("309", "User is already a chat participant", false, raw("errorCode", "309"));
      }
      return new TrueConfResponse(chatId, null, null, null, null, null, userId, raw("userId", userId));
    }

    @Override
    public TrueConfResponse sendMessage(
        String chatId,
        String text,
        String parseMode,
        String replyMessageId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TrueConfResponse sendFile(
        String chatId,
        TrueConfUploadFile file,
        TrueConfUploadFile preview,
        String caption,
        String parseMode,
        String replyMessageId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TrueConfResponse sendSurvey(String chatId, JsonNode surveyPayload, String replyMessageId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TrueConfResponse editMessage(String messageId, String text, String parseMode) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TrueConfResponse editSurvey(String messageId, JsonNode surveyPayload) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TrueConfResponse removeMessage(String messageId, boolean forAll) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TrueConfResponse forwardMessage(String chatId, String messageId) {
      throw new UnsupportedOperationException();
    }

    private void alreadyParticipantUserId(String userId) {
      alreadyParticipantUserId = userId;
    }

    private List<String> calls() {
      return calls;
    }

    private JsonNode raw(String fieldName, String value) {
      var payload = objectMapper.createObjectNode();
      payload.put(fieldName, value);
      var root = objectMapper.createObjectNode();
      root.put("type", 2);
      root.set("payload", payload);
      return root;
    }
  }
}
