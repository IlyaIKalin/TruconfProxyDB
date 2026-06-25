package ru.truconf.proxydb.truconf;

import tools.jackson.databind.JsonNode;

public interface TrueConfClient {

  TrueConfResponse createP2PChat(String userId);

  TrueConfResponse getChats(int count, int page);

  TrueConfResponse getChatById(String chatId);

  TrueConfResponse createGroupChat(String title);

  TrueConfResponse addChatParticipant(String chatId, String userId, boolean displayHistory);

  TrueConfResponse sendMessage(
      String chatId,
      String text,
      String parseMode,
      String replyMessageId);

  TrueConfResponse sendFile(
      String chatId,
      TrueConfUploadFile file,
      TrueConfUploadFile preview,
      String caption,
      String parseMode,
      String replyMessageId);

  TrueConfResponse sendSurvey(String chatId, JsonNode surveyPayload, String replyMessageId);

  TrueConfResponse editMessage(String messageId, String text, String parseMode);

  TrueConfResponse editSurvey(String messageId, JsonNode surveyPayload);

  TrueConfResponse removeMessage(String messageId, boolean forAll);

  TrueConfResponse forwardMessage(String chatId, String messageId);
}
