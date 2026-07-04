package ru.truconf.proxydb.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.truconf.proxydb.delivery.GroupChatService;
import ru.truconf.proxydb.delivery.GroupChatService.AddParticipantsCommand;
import ru.truconf.proxydb.delivery.GroupChatService.AddParticipantsResult;
import ru.truconf.proxydb.delivery.GroupChatService.ParticipantResult;
import ru.truconf.proxydb.delivery.GroupChatService.ParticipantStatus;
import ru.truconf.proxydb.truconf.TrueConfClient;
import ru.truconf.proxydb.truconf.TrueConfResponse;
import ru.truconf.proxydb.truconf.TrueConfServerApiClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class TrueConfDiagnosticControllerTests {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final TrueConfClient trueConfClient = mock(TrueConfClient.class);
  private final TrueConfServerApiClient serverApiClient = mock(TrueConfServerApiClient.class);
  private final GroupChatService groupChatService = mock(GroupChatService.class);
  private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TrueConfDiagnosticController(
      trueConfClient,
      serverApiClient,
      groupChatService))
      .build();

  @Test
  void getChatReturnsRawTrueconfResponse() throws Exception {
    when(trueConfClient.getChatById("chat-1")).thenReturn(response(rawChat()));

    mockMvc.perform(get("/api/v1/trueconf/chats/chat-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.payload.chatId").value("chat-1"))
        .andExpect(jsonPath("$.payload.title").value("Support"));

    verify(trueConfClient).getChatById("chat-1");
  }

  @Test
  void listGroupChatParticipantsReturnsRawTrueconfResponse() throws Exception {
    when(trueConfClient.getChatParticipants("chat-1", 50, 2))
        .thenReturn(response(rawParticipants()));

    mockMvc.perform(get("/api/v1/trueconf/group-chats/chat-1/participants")
            .param("pageSize", "50")
            .param("pageNumber", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.payload.participants[0].userId").value("user@example.test"));

    verify(trueConfClient).getChatParticipants("chat-1", 50, 2);
  }

  @Test
  void removeGroupChatParticipantCallsNativeTrueconfMethod() throws Exception {
    when(trueConfClient.removeChatParticipant("chat-1", "user@example.test", false))
        .thenReturn(response(emptyPayload()));

    mockMvc.perform(post("/api/v1/trueconf/group-chats/chat-1/participants/remove")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "userId": "user@example.test",
                  "clearHistory": false
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value(2));

    verify(trueConfClient).removeChatParticipant("chat-1", "user@example.test", false);
  }

  @Test
  void addGroupChatParticipantsStillUsesGenericAddEndpoint() throws Exception {
    when(groupChatService.addParticipants(any(AddParticipantsCommand.class)))
        .thenReturn(new AddParticipantsResult(
            "chat-1",
            List.of(new ParticipantResult(
                0,
                ParticipantStatus.ADDED,
                "user@example.test",
                "tc:user@example.test",
                null,
                null))));

    mockMvc.perform(post("/api/v1/trueconf/group-chats/chat-1/participants")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "participants": [
                    { "email": "user@example.test" }
                  ],
                  "displayHistory": true
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.chatId").value("chat-1"))
        .andExpect(jsonPath("$.participants[0].status").value("ADDED"));
  }

  @Test
  void springFlowIntegrationEndpointsAreNotMapped() throws Exception {
    mockMvc.perform(post("/api/v1/integrations/" + "springflow/project-chats/sync")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isNotFound());
  }

  private TrueConfResponse response(ObjectNode rawResponse) {
    return new TrueConfResponse(null, null, null, null, null, null, null, null, rawResponse);
  }

  private ObjectNode rawChat() {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("chatId", "chat-1");
    payload.put("title", "Support");
    ObjectNode root = objectMapper.createObjectNode();
    root.put("type", 2);
    root.set("payload", payload);
    return root;
  }

  private ObjectNode rawParticipants() {
    ObjectNode participant = objectMapper.createObjectNode();
    participant.put("userId", "user@example.test");
    var participants = objectMapper.createArrayNode();
    participants.add(participant);
    ObjectNode payload = objectMapper.createObjectNode();
    payload.set("participants", participants);
    ObjectNode root = objectMapper.createObjectNode();
    root.put("type", 2);
    root.set("payload", payload);
    return root;
  }

  private ObjectNode emptyPayload() {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("type", 2);
    root.set("payload", objectMapper.createObjectNode());
    return root;
  }
}
