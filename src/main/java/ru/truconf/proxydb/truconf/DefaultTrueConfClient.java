package ru.truconf.proxydb.truconf;

import java.util.Objects;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class DefaultTrueConfClient implements TrueConfClient {

  private final TrueConfCommandTransport transport;
  private final TrueConfCommandFactory commandFactory;
  private final TrueConfFileUploader fileUploader;

  public DefaultTrueConfClient(
      TrueConfCommandTransport transport,
      TrueConfCommandFactory commandFactory,
      TrueConfFileUploader fileUploader) {
    this.transport = Objects.requireNonNull(transport, "transport must not be null");
    this.commandFactory = Objects.requireNonNull(commandFactory, "commandFactory must not be null");
    this.fileUploader = Objects.requireNonNull(fileUploader, "fileUploader must not be null");
  }

  @Override
  public TrueConfResponse createP2PChat(String userId) {
    return transport.request(id -> commandFactory.createP2PChat(id, userId));
  }

  @Override
  public TrueConfResponse sendMessage(
      String chatId,
      String text,
      String parseMode,
      String replyMessageId) {
    return transport.request(id -> commandFactory.sendMessage(
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
    TrueConfResponse uploadTask = transport.request(
        id -> commandFactory.uploadFile(id, file.fileName(), file.sizeBytes()));
    String uploadTaskId = requiredField(uploadTask.uploadTaskId(), "uploadTaskId");
    TrueConfResponse upload = fileUploader.upload(uploadTaskId, file, preview);
    String temporalFileId = requiredField(upload.temporalFileId(), "temporalFileId");
    return transport.request(id -> commandFactory.sendFile(
        id,
        chatId,
        temporalFileId,
        caption,
        parseMode,
        replyMessageId));
  }

  @Override
  public TrueConfResponse sendSurvey(String chatId, JsonNode surveyPayload, String replyMessageId) {
    return transport.request(id -> commandFactory.sendSurvey(id, chatId, surveyPayload, replyMessageId));
  }

  @Override
  public TrueConfResponse editMessage(String messageId, String text, String parseMode) {
    return transport.request(id -> commandFactory.editMessage(id, messageId, text, parseMode));
  }

  @Override
  public TrueConfResponse editSurvey(String messageId, JsonNode surveyPayload) {
    return transport.request(id -> commandFactory.editSurvey(id, messageId, surveyPayload));
  }

  @Override
  public TrueConfResponse removeMessage(String messageId, boolean forAll) {
    return transport.request(id -> commandFactory.removeMessage(id, messageId, forAll));
  }

  @Override
  public TrueConfResponse forwardMessage(String chatId, String messageId) {
    return transport.request(id -> commandFactory.forwardMessage(id, chatId, messageId));
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
