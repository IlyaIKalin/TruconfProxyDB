package ru.truconf.proxydb.api;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.truconf.proxydb.api.SpringFlowIntegrationDtos.ProjectChatParticipantRequest;
import ru.truconf.proxydb.api.SpringFlowIntegrationDtos.ProjectChatParticipantResultResponse;
import ru.truconf.proxydb.api.SpringFlowIntegrationDtos.SendProjectNotificationRequest;
import ru.truconf.proxydb.api.SpringFlowIntegrationDtos.SendProjectNotificationResponse;
import ru.truconf.proxydb.api.SpringFlowIntegrationDtos.SendManagedChatNotificationRequest;
import ru.truconf.proxydb.api.SpringFlowIntegrationDtos.SyncProjectChatRequest;
import ru.truconf.proxydb.api.SpringFlowIntegrationDtos.SyncProjectChatResponse;
import ru.truconf.proxydb.api.SpringFlowIntegrationDtos.SyncManagedChatRequest;
import ru.truconf.proxydb.delivery.GroupChatService.ParticipantResult;
import ru.truconf.proxydb.managedchat.SpringFlowProjectChatService;
import ru.truconf.proxydb.managedchat.SpringFlowProjectChatService.ParticipantInput;
import ru.truconf.proxydb.managedchat.SpringFlowProjectChatService.SendProjectNotificationCommand;
import ru.truconf.proxydb.managedchat.SpringFlowProjectChatService.SendProjectNotificationResult;
import ru.truconf.proxydb.managedchat.SpringFlowProjectChatService.SyncProjectChatCommand;
import ru.truconf.proxydb.managedchat.SpringFlowProjectChatService.SyncProjectChatResult;
import ru.truconf.proxydb.managedchat.SpringFlowProjectChatService.SyncManagedChatCommand;
import ru.truconf.proxydb.managedchat.SpringFlowProjectChatService.SendManagedChatNotificationCommand;

@RestController
@RequestMapping("/api/v1/integrations/springflow")
public class SpringFlowIntegrationController {

  private final SpringFlowProjectChatService service;

  public SpringFlowIntegrationController(SpringFlowProjectChatService service) {
    this.service = service;
  }

  @PostMapping("/project-chats/sync")
  public ResponseEntity<SyncProjectChatResponse> syncProjectChat(
      @Valid @RequestBody SyncProjectChatRequest request) {
    SyncProjectChatResult result = service.syncProjectChat(new SyncProjectChatCommand(
        request.projectKey(),
        request.projectName(),
        request.chatTitle(),
        participants(request.participants()),
        request.displayHistory()));

    HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status)
        .location(URI.create("/api/v1/integrations/springflow/project-chats/" + result.chatId()))
        .body(toResponse(result));
  }

  @PostMapping("/managed-chats/sync")
  public ResponseEntity<SyncProjectChatResponse> syncManagedChat(
      @Valid @RequestBody SyncManagedChatRequest request) {
    SyncProjectChatResult result = service.syncManagedChat(new SyncManagedChatCommand(
        request.ownerKind(),
        request.ownerKey(),
        request.projectKey(),
        request.projectName(),
        request.chatTitle(),
        request.existingChatId(),
        participants(request.participants()),
        request.displayHistory()));

    HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status)
        .location(URI.create("/api/v1/integrations/springflow/managed-chats/" + result.chatId()))
        .body(toResponse(result));
  }

  @PostMapping("/project-notifications")
  public ResponseEntity<SendProjectNotificationResponse> sendProjectNotification(
      @Valid @RequestBody SendProjectNotificationRequest request) {
    SendProjectNotificationResult result = service.sendProjectNotification(
        new SendProjectNotificationCommand(
            request.externalId(),
            request.projectKey(),
            request.projectName(),
            request.chatTitle(),
            request.payload(),
            request.maxAttempts()));

    HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status)
        .location(URI.create("/api/v1/outbox/" + result.outboxId()))
        .body(new SendProjectNotificationResponse(
            result.outboxId(),
            result.externalId(),
            result.status(),
            result.created(),
            result.projectKey(),
            result.ownerKind(),
            result.ownerKey(),
            result.chatId(),
            result.chatTitle()));
  }

  @PostMapping("/managed-chat-notifications")
  public ResponseEntity<SendProjectNotificationResponse> sendManagedChatNotification(
      @Valid @RequestBody SendManagedChatNotificationRequest request) {
    SendProjectNotificationResult result = service.sendManagedChatNotification(
        new SendManagedChatNotificationCommand(
            request.externalId(),
            request.ownerKind(),
            request.ownerKey(),
            request.projectKey(),
            request.projectName(),
            request.chatTitle(),
            request.existingChatId(),
            request.payload(),
            request.maxAttempts()));

    HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status)
        .location(URI.create("/api/v1/outbox/" + result.outboxId()))
        .body(new SendProjectNotificationResponse(
            result.outboxId(),
            result.externalId(),
            result.status(),
            result.created(),
            result.projectKey(),
            result.ownerKind(),
            result.ownerKey(),
            result.chatId(),
            result.chatTitle()));
  }

  private SyncProjectChatResponse toResponse(SyncProjectChatResult result) {
    return new SyncProjectChatResponse(
        result.projectKey(),
        result.ownerKind(),
        result.ownerKey(),
        result.chatId(),
        result.chatTitle(),
        result.created(),
        result.addedCount(),
        result.ignoredCount(),
        result.failedCount(),
        result.lastSyncAt(),
        result.participants().stream()
            .map(this::toResponse)
            .toList());
  }

  private ProjectChatParticipantResultResponse toResponse(ParticipantResult result) {
    return new ProjectChatParticipantResultResponse(
        result.index(),
        result.status(),
        result.email(),
        result.userId(),
        result.code(),
        result.message());
  }

  private List<ParticipantInput> participants(List<ProjectChatParticipantRequest> participants) {
    if (participants == null) {
      return List.of();
    }
    return participants.stream()
        .map(participant -> participant == null
            ? null
            : new ParticipantInput(participant.email(), participant.userId()))
        .toList();
  }
}
