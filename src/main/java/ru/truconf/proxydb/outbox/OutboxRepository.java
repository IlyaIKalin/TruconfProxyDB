package ru.truconf.proxydb.outbox;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.truconf.proxydb.domain.OutboxFile;
import ru.truconf.proxydb.domain.OutboxJob;
import ru.truconf.proxydb.domain.P2pChatCacheEntry;

@Repository
public class OutboxRepository {

  private static final OutboxJobRowMapper OUTBOX_JOB_ROW_MAPPER = new OutboxJobRowMapper();
  private static final OutboxFileRowMapper OUTBOX_FILE_ROW_MAPPER = new OutboxFileRowMapper();
  private static final P2pChatCacheEntryRowMapper P2P_CHAT_CACHE_ENTRY_ROW_MAPPER =
      new P2pChatCacheEntryRowMapper();

  private final JdbcTemplate jdbc;

  public OutboxRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public OutboxJob create(CreateOutboxJobCommand command) {
    Objects.requireNonNull(command, "command must not be null");

    return jdbc.queryForObject("""
        insert into truconf_outbox (
          external_id,
          operation,
          recipient_kind,
          chat_id,
          user_id,
          recipient_email,
          target_message_id,
          reply_message_id,
          payload_json,
          max_attempts,
          next_attempt_at
        ) values (
          ?,
          ?,
          ?,
          ?,
          ?,
          ?,
          ?,
          ?,
          ?::jsonb,
          ?,
          coalesce(?::timestamptz, now())
        )
        returning *
        """,
        OUTBOX_JOB_ROW_MAPPER,
        command.externalId(),
        command.operation().name(),
        command.recipientKind().name(),
        command.chatId(),
        command.userId(),
        command.recipientEmail(),
        command.targetMessageId(),
        command.replyMessageId(),
        command.payloadJson(),
        command.maxAttempts(),
        command.nextAttemptAt());
  }

  public Optional<OutboxJob> findById(long id) {
    return queryOptional("select * from truconf_outbox where id = ?", id);
  }

  public Optional<OutboxJob> findByExternalId(String externalId) {
    Objects.requireNonNull(externalId, "externalId must not be null");
    return queryOptional("select * from truconf_outbox where external_id = ?", externalId);
  }

  public Optional<OutboxJob> findByTrueconfMessageId(String trueconfMessageId) {
    Objects.requireNonNull(trueconfMessageId, "trueconfMessageId must not be null");
    return queryOptional(
        "select * from truconf_outbox where trueconf_message_id = ?",
        trueconfMessageId);
  }

  public OutboxFile createFile(CreateOutboxFileCommand command) {
    Objects.requireNonNull(command, "command must not be null");

    return jdbc.queryForObject("""
        insert into truconf_outbox_file (
          outbox_id,
          file_name,
          mime_type,
          size_bytes,
          storage_kind,
          file_path,
          file_data,
          preview_file_name,
          preview_mime_type,
          preview_size_bytes,
          preview_file_path,
          preview_file_data
        ) values (
          ?,
          ?,
          ?,
          ?,
          ?,
          ?,
          ?,
          ?,
          ?,
          ?,
          ?,
          ?
        )
        returning *
        """,
        OUTBOX_FILE_ROW_MAPPER,
        command.outboxId(),
        command.fileName(),
        command.mimeType(),
        command.sizeBytes(),
        command.storageKind().name(),
        command.filePath(),
        command.fileData(),
        command.previewFileName(),
        command.previewMimeType(),
        command.previewSizeBytes(),
        command.previewFilePath(),
        command.previewFileData());
  }

  public Optional<OutboxFile> findFileByOutboxId(long outboxId) {
    return queryOptionalFile("select * from truconf_outbox_file where outbox_id = ?", outboxId);
  }

  public Optional<P2pChatCacheEntry> findP2pChatByUserId(String userId) {
    requireText(userId, "userId");
    return queryOptionalP2pChat("""
        update truconf_p2p_chat_cache
        set last_used_at = now()
        where user_id = ?
        returning *
        """,
        userId);
  }

  public P2pChatCacheEntry upsertP2pChat(String userId, String chatId) {
    requireText(userId, "userId");
    requireText(chatId, "chatId");

    return jdbc.queryForObject("""
        insert into truconf_p2p_chat_cache (
          user_id,
          chat_id,
          last_used_at
        ) values (
          ?,
          ?,
          now()
        )
        on conflict (user_id) do update
        set chat_id = excluded.chat_id,
            last_used_at = now()
        returning *
        """,
        P2P_CHAT_CACHE_ENTRY_ROW_MAPPER,
        userId,
        chatId);
  }

  public Optional<String> findTrueconfIdByEmail(String email) {
    requireText(email, "email");
    List<String> trueconfIds = jdbc.queryForList("""
        update truconf_user_email_cache
        set last_used_at = now()
        where email = ?
        returning trueconf_id
        """,
        String.class,
        email);
    if (trueconfIds.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(trueconfIds.getFirst());
  }

  public void upsertUserEmailCache(String email, String trueconfId, String displayName) {
    requireText(email, "email");
    requireText(trueconfId, "trueconfId");

    jdbc.update("""
        insert into truconf_user_email_cache (
          email,
          trueconf_id,
          display_name,
          last_used_at
        ) values (
          ?,
          ?,
          ?,
          now()
        )
        on conflict (email) do update
        set trueconf_id = excluded.trueconf_id,
            display_name = excluded.display_name,
            last_used_at = now()
        """,
        email,
        trueconfId,
        displayName);
  }

  public List<OutboxJob> claimBatch(String workerId, Duration lockTimeout, int batchSize) {
    requireWorkerId(workerId);
    requirePositiveDuration(lockTimeout, "lockTimeout");
    requirePositive(batchSize, "batchSize");

    return jdbc.query("""
        with candidate as (
          select id
          from truconf_outbox
          where status in ('NEW', 'RETRY_WAIT')
            and next_attempt_at <= now()
          order by next_attempt_at, id
          for update skip locked
          limit ?
        )
        update truconf_outbox o
        set status = 'PROCESSING',
            locked_by = ?,
            locked_until = now() + (?::double precision * interval '1 millisecond'),
            attempt_count = attempt_count + 1
        from candidate
        where o.id = candidate.id
        returning o.*
        """,
        OUTBOX_JOB_ROW_MAPPER,
        batchSize,
        workerId,
        lockTimeout.toMillis());
  }

  public Optional<OutboxJob> markSent(long id, String workerId, SentOutboxResult result) {
    requireWorkerId(workerId);
    Objects.requireNonNull(result, "result must not be null");

    return queryOptional("""
        update truconf_outbox
        set status = 'SENT',
            locked_by = null,
            locked_until = null,
            trueconf_chat_id = ?,
            trueconf_message_id = ?,
            trueconf_file_id = ?,
            trueconf_timestamp = ?,
            last_error_code = null,
            last_error_message = null,
            last_error_retryable = null,
            last_response_json = ?::jsonb,
            sent_at = now(),
            failed_at = null
        where id = ?
          and status = 'PROCESSING'
          and locked_by = ?
        returning *
        """,
        result.trueconfChatId(),
        result.trueconfMessageId(),
        result.trueconfFileId(),
        result.trueconfTimestamp(),
        result.responseJson(),
        id,
        workerId);
  }

  public Optional<OutboxJob> markRetry(
      long id,
      String workerId,
      Duration retryDelay,
      OutboxError error) {
    requireWorkerId(workerId);
    requirePositiveOrZeroDuration(retryDelay, "retryDelay");
    Objects.requireNonNull(error, "error must not be null");

    return queryOptional("""
        update truconf_outbox
        set status = 'RETRY_WAIT',
            locked_by = null,
            locked_until = null,
            next_attempt_at = now() + (?::double precision * interval '1 millisecond'),
            last_error_code = ?,
            last_error_message = ?,
            last_error_retryable = ?,
            last_response_json = ?::jsonb,
            failed_at = null
        where id = ?
          and status = 'PROCESSING'
          and locked_by = ?
        returning *
        """,
        retryDelay.toMillis(),
        error.code(),
        error.message(),
        error.retryable(),
        error.responseJson(),
        id,
        workerId);
  }

  public Optional<OutboxJob> markFailed(long id, String workerId, OutboxError error) {
    requireWorkerId(workerId);
    Objects.requireNonNull(error, "error must not be null");

    return queryOptional("""
        update truconf_outbox
        set status = 'FAILED',
            locked_by = null,
            locked_until = null,
            last_error_code = ?,
            last_error_message = ?,
            last_error_retryable = ?,
            last_response_json = ?::jsonb,
            sent_at = null,
            failed_at = now()
        where id = ?
          and status = 'PROCESSING'
          and locked_by = ?
        returning *
        """,
        error.code(),
        error.message(),
        error.retryable(),
        error.responseJson(),
        id,
        workerId);
  }

  public List<OutboxJob> recoverStaleLocks(int batchSize) {
    requirePositive(batchSize, "batchSize");

    return jdbc.query("""
        with stale as (
          select id
          from truconf_outbox
          where status = 'PROCESSING'
            and locked_until < now()
          order by locked_until, id
          for update skip locked
          limit ?
        )
        update truconf_outbox o
        set status = 'NEW',
            locked_by = null,
            locked_until = null
        from stale
        where o.id = stale.id
        returning o.*
        """,
        OUTBOX_JOB_ROW_MAPPER,
        batchSize);
  }

  private Optional<OutboxJob> queryOptional(String sql, Object... args) {
    List<OutboxJob> jobs = jdbc.query(sql, OUTBOX_JOB_ROW_MAPPER, args);
    if (jobs.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(jobs.getFirst());
  }

  private Optional<OutboxFile> queryOptionalFile(String sql, Object... args) {
    List<OutboxFile> files = jdbc.query(sql, OUTBOX_FILE_ROW_MAPPER, args);
    if (files.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(files.getFirst());
  }

  private Optional<P2pChatCacheEntry> queryOptionalP2pChat(String sql, Object... args) {
    List<P2pChatCacheEntry> entries = jdbc.query(sql, P2P_CHAT_CACHE_ENTRY_ROW_MAPPER, args);
    if (entries.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(entries.getFirst());
  }

  private static void requireWorkerId(String workerId) {
    if (workerId == null || workerId.isBlank()) {
      throw new IllegalArgumentException("workerId must not be blank");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }

  private static void requirePositive(int value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requirePositiveDuration(Duration duration, String name) {
    requirePositiveOrZeroDuration(duration, name);
    if (duration.isZero()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requirePositiveOrZeroDuration(Duration duration, String name) {
    Objects.requireNonNull(duration, name + " must not be null");
    if (duration.isNegative()) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }
}
