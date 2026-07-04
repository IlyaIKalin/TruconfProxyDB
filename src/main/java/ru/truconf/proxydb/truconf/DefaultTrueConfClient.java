package ru.truconf.proxydb.truconf;

import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class DefaultTrueConfClient implements TrueConfClient {

  private final TrueConfCommandTransport transport;
  private final TrueConfCommandFactory commandFactory;
  private final TrueConfFileUploader fileUploader;
  private final TrueConfRateLimiter rateLimiter;

  public DefaultTrueConfClient(
      TrueConfCommandTransport transport,
      TrueConfCommandFactory commandFactory,
      TrueConfFileUploader fileUploader,
      TrueConfRateLimiter rateLimiter) {
    this.transport = Objects.requireNonNull(transport, "transport must not be null");
    this.commandFactory = Objects.requireNonNull(commandFactory, "commandFactory must not be null");
    this.fileUploader = Objects.requireNonNull(fileUploader, "fileUploader must not be null");
    this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter must not be null");
  }

  @Override
  public TrueConfResponse createP2PChat(String userId) {
    return request(id -> commandFactory.createP2PChat(id, userId));
  }

  @Override
  public TrueConfResponse getChats(int count, int page) {
    return request(id -> commandFactory.getChats(id, count, page));
  }

  @Override
  public TrueConfResponse getChatById(String chatId) {
    return request(id -> commandFactory.getChatById(id, chatId));
  }

  @Override
  public TrueConfResponse createGroupChat(String title) {
    return request(id -> commandFactory.createGroupChat(id, title));
  }

  @Override
  public TrueConfResponse addChatParticipant(
      String chatId,
      String userId,
      boolean displayHistory) {
    return request(id -> commandFactory.addChatParticipant(id, chatId, userId, displayHistory));
  }

  @Override
  public TrueConfResponse getChatParticipants(String chatId, int pageSize, int pageNumber) {
    return request(id -> commandFactory.getChatParticipants(id, chatId, pageSize, pageNumber));
  }

  @Override
  public TrueConfResponse removeChatParticipant(
      String chatId,
      String userId,
      boolean clearHistory) {
    return request(id -> commandFactory.removeChatParticipant(id, chatId, userId, clearHistory));
  }

  @Override
  public TrueConfResponse sendMessage(
      String chatId,
      String text,
      String parseMode,
      String replyMessageId) {
    return request(id -> commandFactory.sendMessage(
        id,
        chatId,
        text,
        parseMode,
        replyMessageId));
  }

  @Override
  public TrueConfResponse sendFile(
      String chatId,
      TrueConfUploadFile file,
      TrueConfUploadFile preview,
      String caption,
      String parseMode,
      String replyMessageId) {
    TrueConfResponse uploadTask = request(
        id -> commandFactory.uploadFile(id, file.fileName(), file.sizeBytes()));
    String uploadTaskId = requiredField(uploadTask.uploadTaskId(), "uploadTaskId");
    rateLimiter.acquire();
    TrueConfResponse upload = fileUploader.upload(uploadTaskId, file, preview);
    String temporalFileId = requiredField(upload.temporalFileId(), "temporalFileId");
    return request(id -> commandFactory.sendFile(
        id,
        chatId,
        temporalFileId,
        caption,
        parseMode,
        replyMessageId));
  }

  @Override
  public TrueConfResponse sendSurvey(String chatId, JsonNode surveyPayload, String replyMessageId) {
    return request(id -> commandFactory.sendSurvey(id, chatId, surveyPayload, replyMessageId));
  }

  @Override
  public TrueConfResponse editMessage(String messageId, String text, String parseMode) {
    return request(id -> commandFactory.editMessage(id, messageId, text, parseMode));
  }

  @Override
  public TrueConfResponse editSurvey(String messageId, JsonNode surveyPayload) {
    return request(id -> commandFactory.editSurvey(id, messageId, surveyPayload));
  }

  @Override
  public TrueConfResponse removeMessage(String messageId, boolean forAll) {
    return request(id -> commandFactory.removeMessage(id, messageId, forAll));
  }

  @Override
  public TrueConfResponse forwardMessage(String chatId, String messageId) {
    return request(id -> commandFactory.forwardMessage(id, chatId, messageId));
  }

  private TrueConfResponse request(Function<Long, ObjectNode> commandBuilder) {
    rateLimiter.acquire();
    return transport.request(commandBuilder);
  }

  private static String requiredField(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new TrueConfException(
          "TRUECONF_RESPONSE_MISSING_" + fieldName.toUpperCase(java.util.Locale.ROOT),
          "TrueConf response does not contain " + fieldName,
          true);
    }
    return value;
  }
}
