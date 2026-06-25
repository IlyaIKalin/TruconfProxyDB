package ru.truconf.proxydb.truconf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class TrueConfCommandFactoryTests {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final TrueConfCommandFactory factory = new TrueConfCommandFactory(objectMapper);

  @Test
  void buildsAuthAndAckCommands() throws Exception {
    assertJsonEquals("""
        {
          "type": 1,
          "id": 1,
          "method": "auth",
          "payload": {
            "token": "jwt-token",
            "tokenType": "JWT",
            "receiveUnread": false,
            "receiveSystemMessageEnvelopes": true
          }
        }
        """, factory.auth(1, "jwt-token"));

    assertJsonEquals("""
        {
          "type": 2,
          "id": 123
        }
        """, factory.ack(123));
  }

  @Test
  void buildsChatAndMessageCommands() throws Exception {
    assertJsonEquals("""
        {
          "type": 1,
          "id": 2,
          "method": "createP2PChat",
          "payload": {
            "userId": "user@example.com"
          }
        }
        """, factory.createP2PChat(2, "user@example.com"));

    assertJsonEquals("""
        {
          "type": 1,
          "id": 11,
          "method": "getChats",
          "payload": {
            "count": 25,
            "page": 2
          }
        }
        """, factory.getChats(11, 25, 2));

    assertJsonEquals("""
        {
          "type": 1,
          "id": 12,
          "method": "createGroupChat",
          "payload": {
            "title": "Support"
          }
        }
        """, factory.createGroupChat(12, "Support"));

    assertJsonEquals("""
        {
          "type": 1,
          "id": 13,
          "method": "addChatParticipant",
          "payload": {
            "chatId": "chat-1",
            "userId": "user@example.com",
            "displayHistory": true
          }
        }
        """, factory.addChatParticipant(13, "chat-1", "user@example.com", true));

    assertJsonEquals("""
        {
          "type": 1,
          "id": 3,
          "method": "sendMessage",
          "payload": {
            "chatId": "chat-1",
            "replyMessageId": "reply-1",
            "content": {
              "text": "Hello",
              "parseMode": "markdown"
            }
          }
        }
        """, factory.sendMessage(3, "chat-1", "Hello", "markdown", "reply-1"));

    assertJsonEquals("""
        {
          "type": 1,
          "id": 4,
          "method": "editMessage",
          "payload": {
            "messageId": "message-1",
            "content": {
              "text": "Updated",
              "parseMode": "text"
            }
          }
        }
        """, factory.editMessage(4, "message-1", "Updated", null));
  }

  @Test
  void buildsFileCommands() throws Exception {
    assertJsonEquals("""
        {
          "type": 1,
          "id": 5,
          "method": "uploadFile",
          "payload": {
            "fileSize": 1487,
            "fileName": "example.png"
          }
        }
        """, factory.uploadFile(5, "example.png", 1487));

    assertJsonEquals("""
        {
          "type": 1,
          "id": 6,
          "method": "sendFile",
          "payload": {
            "chatId": "chat-1",
            "replyMessageId": "reply-1",
            "content": {
              "temporalFileId": "tmp-file-1",
              "caption": {
                "text": "Here is the file",
                "parseMode": "html"
              }
            }
          }
        }
        """, factory.sendFile(
            6,
            "chat-1",
            "tmp-file-1",
            "Here is the file",
            "html",
            "reply-1"));
  }

  @Test
  void buildsSurveyAndModerationCommands() throws Exception {
    JsonNode survey = objectMapper.readTree("""
        {
          "url": "https://server.example/webtools/survey",
          "appVersion": 1,
          "path": "employee_testing",
          "title": "Employee survey",
          "description": "{{Survey}}",
          "buttonText": "{{Go to survey}}",
          "secret": "secret",
          "alt": " <a href=\\"https://server.example/webtools/survey?id=employee_testing\\">Employee survey</a>"
        }
        """);

    assertJsonEquals("""
        {
          "type": 1,
          "id": 7,
          "method": "sendSurvey",
          "payload": {
            "chatId": "chat-1",
            "content": {
              "url": "https://server.example/webtools/survey",
              "appVersion": 1,
              "path": "employee_testing",
              "title": "Employee survey",
              "description": "{{Survey}}",
              "buttonText": "{{Go to survey}}",
              "secret": "secret",
              "alt": " <a href=\\"https://server.example/webtools/survey?id=employee_testing\\">Employee survey</a>"
            }
          }
        }
        """, factory.sendSurvey(7, "chat-1", survey, null));

    assertJsonEquals("""
        {
          "type": 1,
          "id": 8,
          "method": "editSurvey",
          "payload": {
            "messageId": "message-1",
            "content": {
              "url": "https://server.example/webtools/survey",
              "appVersion": 1,
              "path": "employee_testing",
              "title": "Employee survey",
              "description": "{{Survey}}",
              "buttonText": "{{Go to survey}}",
              "secret": "secret",
              "alt": " <a href=\\"https://server.example/webtools/survey?id=employee_testing\\">Employee survey</a>"
            }
          }
        }
        """, factory.editSurvey(8, "message-1", survey));

    assertJsonEquals("""
        {
          "type": 1,
          "id": 9,
          "method": "removeMessage",
          "payload": {
            "messageId": "message-1",
            "forAll": true
          }
        }
        """, factory.removeMessage(9, "message-1"));

    assertJsonEquals("""
        {
          "type": 1,
          "id": 10,
          "method": "forwardMessage",
          "payload": {
            "chatId": "chat-2",
            "messageId": "message-1"
          }
        }
        """, factory.forwardMessage(10, "chat-2", "message-1"));
  }

  @Test
  void rejectsBlankRequiredFieldsAndNonObjectSurveyPayload() {
    assertThatThrownBy(() -> factory.sendMessage(1, " ", "Hello", "text", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("chatId must not be blank");

    assertThatThrownBy(() -> factory.uploadFile(1, "file.txt", -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("fileSize must not be negative");

    assertThatThrownBy(() -> factory.getChats(1, 0, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("count must be positive");

    assertThatThrownBy(() -> factory.sendSurvey(1, "chat-1", objectMapper.createArrayNode(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("surveyPayload must be a JSON object");
  }

  private void assertJsonEquals(String expectedJson, JsonNode actual) throws Exception {
    assertThat(actual.toString()).isEqualTo(objectMapper.readTree(expectedJson).toString());
  }
}
