package ru.truconf.proxydb.outbox;

import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.truconf.proxydb.domain.FileStorageKind;
import ru.truconf.proxydb.domain.OutboxJob;
import ru.truconf.proxydb.domain.OutboxOperation;
import ru.truconf.proxydb.files.FileStorageService;
import ru.truconf.proxydb.files.StoredFile;

@Service
public class OutboxService {

  private final OutboxRepository repository;
  private final FileStorageService fileStorageService;

  public OutboxService(
      OutboxRepository repository,
      FileStorageService fileStorageService) {
    this.repository = repository;
    this.fileStorageService = fileStorageService;
  }

  @Transactional
  public EnqueuedOutboxJob enqueue(CreateOutboxJobCommand command) {
    Objects.requireNonNull(command, "command must not be null");

    if (command.externalId() != null && !command.externalId().isBlank()) {
      return repository.findByExternalId(command.externalId())
          .map(job -> new EnqueuedOutboxJob(job, false))
          .orElseGet(() -> createIdempotently(command));
    }

    return new EnqueuedOutboxJob(repository.create(command), true);
  }

  @Transactional
  public EnqueuedOutboxJob enqueueFile(
      CreateOutboxJobCommand command,
      MultipartFile file,
      MultipartFile preview) {
    Objects.requireNonNull(command, "command must not be null");
    Objects.requireNonNull(file, "file must not be null");
    if (command.operation() != OutboxOperation.SEND_FILE) {
      throw new IllegalArgumentException("operation must be SEND_FILE");
    }

    if (command.externalId() != null && !command.externalId().isBlank()) {
      return repository.findByExternalId(command.externalId())
          .map(job -> new EnqueuedOutboxJob(job, false))
          .orElseGet(() -> createFileJobIdempotently(command, file, preview));
    }

    return createFileJob(command, file, preview);
  }

  @Transactional(readOnly = true)
  public OutboxJob getById(long id) {
    return repository.findById(id)
        .orElseThrow(() -> new OutboxJobNotFoundException("Outbox job not found: " + id));
  }

  @Transactional(readOnly = true)
  public OutboxJob getByExternalId(String externalId) {
    if (externalId == null || externalId.isBlank()) {
      throw new IllegalArgumentException("externalId must not be blank");
    }
    return repository.findByExternalId(externalId)
        .orElseThrow(() -> new OutboxJobNotFoundException(
            "Outbox job not found for externalId: " + externalId));
  }

  @Transactional(readOnly = true)
  public OutboxJob getByTrueconfMessageId(String trueconfMessageId) {
    if (trueconfMessageId == null || trueconfMessageId.isBlank()) {
      throw new IllegalArgumentException("trueconfMessageId must not be blank");
    }
    return repository.findByTrueconfMessageId(trueconfMessageId)
        .orElseThrow(() -> new OutboxJobNotFoundException(
            "Outbox job not found for trueconfMessageId: " + trueconfMessageId));
  }

  private EnqueuedOutboxJob createIdempotently(CreateOutboxJobCommand command) {
    try {
      return new EnqueuedOutboxJob(repository.create(command), true);
    } catch (DataIntegrityViolationException ex) {
      return repository.findByExternalId(command.externalId())
          .map(existingJob -> new EnqueuedOutboxJob(existingJob, false))
          .orElseThrow(() -> ex);
    }
  }

  private EnqueuedOutboxJob createFileJobIdempotently(
      CreateOutboxJobCommand command,
      MultipartFile file,
      MultipartFile preview) {
    OutboxJob job;
    try {
      job = repository.create(command);
    } catch (DataIntegrityViolationException ex) {
      return repository.findByExternalId(command.externalId())
          .map(existingJob -> new EnqueuedOutboxJob(existingJob, false))
          .orElseThrow(() -> ex);
    }

    storeFileRow(job, file, preview);
    return new EnqueuedOutboxJob(job, true);
  }

  private EnqueuedOutboxJob createFileJob(
      CreateOutboxJobCommand command,
      MultipartFile file,
      MultipartFile preview) {
    OutboxJob job = repository.create(command);
    storeFileRow(job, file, preview);
    return new EnqueuedOutboxJob(job, true);
  }

  private void storeFileRow(
      OutboxJob job,
      MultipartFile file,
      MultipartFile preview) {
    StoredFile storedFile = null;
    StoredFile storedPreview = null;

    try {
      storedFile = fileStorageService.store(job.id(), file);
      storedPreview = preview == null ? null : fileStorageService.store(job.id(), preview);

      repository.createFile(new CreateOutboxFileCommand(
          job.id(),
          storedFile.originalFileName(),
          storedFile.mimeType(),
          storedFile.sizeBytes(),
          FileStorageKind.DISK,
          storedFile.filePath(),
          null,
          storedPreview == null ? null : storedPreview.originalFileName(),
          storedPreview == null ? null : storedPreview.mimeType(),
          storedPreview == null ? null : storedPreview.sizeBytes(),
          storedPreview == null ? null : storedPreview.filePath(),
          null));
    } catch (RuntimeException ex) {
      fileStorageService.delete(storedPreview);
      fileStorageService.delete(storedFile);
      throw ex;
    }
  }
}
