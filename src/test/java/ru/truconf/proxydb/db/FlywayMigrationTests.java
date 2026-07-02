package ru.truconf.proxydb.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.truconf.proxydb.domain.OutboxOperation;
import ru.truconf.proxydb.domain.OutboxStatus;
import ru.truconf.proxydb.domain.RecipientKind;
import ru.truconf.proxydb.outbox.OutboxJobRowMapper;

@Testcontainers
class FlywayMigrationTests {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("truconf_proxydb")
          .withUsername("truconf_proxydb")
          .withPassword("truconf_proxydb");

  private JdbcTemplate jdbc;

  @BeforeEach
  void migrateCleanDatabase() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(),
        POSTGRES.getUsername(),
        POSTGRES.getPassword());

    Flyway flyway = Flyway.configure()
        .dataSource(dataSource)
        .cleanDisabled(false)
        .load();

    flyway.clean();
    flyway.migrate();
    jdbc = new JdbcTemplate(dataSource);
  }

  @Test
  void migrationAppliesCleanlyToEmptyPostgres() {
    Integer tableCount = jdbc.queryForObject(
        """
        select count(*)
        from information_schema.tables
        where table_schema = 'public'
          and table_name in (
            'truconf_outbox',
            'truconf_outbox_file',
            'truconf_p2p_chat_cache',
            'truconf_user_email_cache',
            'truconf_managed_chat',
            'flyway_schema_history'
          )
        """,
        Integer.class);

    Integer appliedMigrations = jdbc.queryForObject(
        """
        select count(*)
        from flyway_schema_history
        where success = true and version in ('1', '2', '3', '4')
        """,
        Integer.class);

    assertThat(tableCount).isEqualTo(6);
    assertThat(appliedMigrations).isEqualTo(4);
  }

  @Test
  void constraintsRejectInvalidOutboxRows() {
    assertDataAccessFailure("""
        insert into truconf_outbox (operation, recipient_kind, user_id)
        values ('BAD_OPERATION', 'USER', 'user@example.com')
        """);

    assertDataAccessFailure("""
        insert into truconf_outbox (operation, recipient_kind, user_id, status)
        values ('SEND_MESSAGE', 'USER', 'user@example.com', 'BAD_STATUS')
        """);

    assertDataAccessFailure("""
        insert into truconf_outbox (operation, recipient_kind, user_id)
        values ('SEND_MESSAGE', 'BAD_RECIPIENT', 'user@example.com')
        """);

    assertDataAccessFailure("""
        insert into truconf_outbox (operation, recipient_kind, payload_json)
        values ('SEND_MESSAGE', 'USER', '{}'::jsonb)
        """);

    assertDataAccessFailure("""
        insert into truconf_outbox (operation, recipient_kind, payload_json)
        values ('SEND_MESSAGE', 'CHAT', '{}'::jsonb)
        """);

    assertDataAccessFailure("""
        insert into truconf_outbox (operation, recipient_kind, payload_json)
        values ('SEND_MESSAGE', 'USER_EMAIL', '{}'::jsonb)
        """);

    assertDataAccessFailure("""
        insert into truconf_outbox (operation, recipient_kind, user_id, payload_json)
        values ('SEND_MESSAGE', 'USER', 'user@example.com', '[]'::jsonb)
        """);

    assertDataAccessFailure("""
        insert into truconf_outbox (operation, recipient_kind, user_id, attempt_count)
        values ('SEND_MESSAGE', 'USER', 'user@example.com', -1)
        """);

    assertDataAccessFailure("""
        insert into truconf_outbox (operation, recipient_kind, user_id, max_attempts)
        values ('SEND_MESSAGE', 'USER', 'user@example.com', 0)
        """);
  }

  @Test
  void constraintsRejectInvalidFileRows() {
    Long outboxId = insertMinimalOutbox("file-constraints");

    assertDataAccessFailure("""
        insert into truconf_outbox_file (outbox_id, file_name, size_bytes, storage_kind, file_path)
        values (%d, 'report.txt', -1, 'DISK', '/tmp/report.txt')
        """.formatted(outboxId));

    assertDataAccessFailure("""
        insert into truconf_outbox_file (outbox_id, file_name, size_bytes, storage_kind)
        values (%d, 'report.txt', 10, 'BAD_STORAGE')
        """.formatted(outboxId));

    assertDataAccessFailure("""
        insert into truconf_outbox_file (outbox_id, file_name, size_bytes, storage_kind)
        values (%d, 'report.txt', 10, 'DISK')
        """.formatted(outboxId));

    assertDataAccessFailure("""
        insert into truconf_outbox_file (outbox_id, file_name, size_bytes, storage_kind)
        values (%d, 'report.txt', 10, 'DB')
        """.formatted(outboxId));
  }

  @Test
  void directInsertMinimalExamplePassesAndMapsToDomain() {
    jdbc.update("""
        insert into truconf_outbox (
          external_id,
          operation,
          recipient_kind,
          user_id,
          payload_json
        ) values (
          'crm-123',
          'SEND_MESSAGE',
          'USER',
          'user@example.com',
          '{"text":"Hello","parseMode":"text"}'::jsonb
        )
        """);

    var job = jdbc.queryForObject(
        "select * from truconf_outbox where external_id = 'crm-123'",
        new OutboxJobRowMapper());

    assertThat(job).isNotNull();
    assertThat(job.externalId()).isEqualTo("crm-123");
    assertThat(job.operation()).isEqualTo(OutboxOperation.SEND_MESSAGE);
    assertThat(job.recipientKind()).isEqualTo(RecipientKind.USER);
    assertThat(job.userId()).isEqualTo("user@example.com");
    assertThat(job.status()).isEqualTo(OutboxStatus.NEW);
    assertThat(job.attemptCount()).isZero();
    assertThat(job.maxAttempts()).isEqualTo(10);
    assertThat(job.nextAttemptAt()).isNotNull();
    assertThat(job.payloadJson()).contains("\"text\": \"Hello\"");
  }

  @Test
  void directInsertUserEmailRecipientPassesAndMapsToDomain() {
    jdbc.update("""
        insert into truconf_outbox (
          external_id,
          operation,
          recipient_kind,
          recipient_email,
          payload_json
        ) values (
          'crm-email-123',
          'SEND_MESSAGE',
          'USER_EMAIL',
          'user@example.com',
          '{"text":"Hello"}'::jsonb
        )
        """);

    var job = jdbc.queryForObject(
        "select * from truconf_outbox where external_id = 'crm-email-123'",
        new OutboxJobRowMapper());

    assertThat(job).isNotNull();
    assertThat(job.recipientKind()).isEqualTo(RecipientKind.USER_EMAIL);
    assertThat(job.recipientEmail()).isEqualTo("user@example.com");
    assertThat(job.userId()).isNull();
  }

  @Test
  void deliveryKeyIsGeneratedForAllRecipientKinds() {
    jdbc.update("""
        insert into truconf_outbox (
          external_id,
          operation,
          recipient_kind,
          chat_id,
          payload_json
        ) values (
          'delivery-chat',
          'SEND_MESSAGE',
          'CHAT',
          'chat-123',
          '{"text":"Hello"}'::jsonb
        )
        """);

    jdbc.update("""
        insert into truconf_outbox (
          external_id,
          operation,
          recipient_kind,
          user_id,
          payload_json
        ) values (
          'delivery-user',
          'SEND_MESSAGE',
          'USER',
          'user@example.com',
          '{"text":"Hello"}'::jsonb
        )
        """);

    jdbc.update("""
        insert into truconf_outbox (
          external_id,
          operation,
          recipient_kind,
          recipient_email,
          payload_json
        ) values (
          'delivery-email',
          'SEND_MESSAGE',
          'USER_EMAIL',
          'User@Example.COM',
          '{"text":"Hello"}'::jsonb
        )
        """);

    assertThat(deliveryKey("delivery-chat")).isEqualTo("CHAT:chat-123");
    assertThat(deliveryKey("delivery-user")).isEqualTo("USER:user@example.com");
    assertThat(deliveryKey("delivery-email")).isEqualTo("USER_EMAIL:user@example.com");
  }

  @Test
  void validFileInsertPassesForDiskAndDbStorage() {
    Long diskOutboxId = insertMinimalOutbox("disk-file");
    Long dbOutboxId = insertMinimalOutbox("db-file");

    jdbc.update("""
        insert into truconf_outbox_file (
          outbox_id, file_name, mime_type, size_bytes, storage_kind, file_path
        ) values (?, 'report.txt', 'text/plain', 10, 'DISK', '/var/lib/files/report.txt')
        """, diskOutboxId);

    jdbc.update("""
        insert into truconf_outbox_file (
          outbox_id, file_name, mime_type, size_bytes, storage_kind, file_data
        ) values (?, 'report.txt', 'text/plain', 3, 'DB', ?)
        """, dbOutboxId, new byte[] {1, 2, 3});

    Integer fileCount = jdbc.queryForObject(
        "select count(*) from truconf_outbox_file",
        Integer.class);

    assertThat(fileCount).isEqualTo(2);
  }

  @Test
  void managedChatRegistryConstraintsAndUniquenessWork() {
    jdbc.update("""
        insert into truconf_managed_chat (
          owner_system,
          owner_kind,
          owner_key,
          chat_id,
          title
        ) values (
          'SPRINGFLOW',
          'PROJECT',
          'demo',
          'chat-1',
          'SpringFlow: Demo'
        )
        """);

    assertDataAccessFailure("""
        insert into truconf_managed_chat (
          owner_system,
          owner_kind,
          owner_key,
          chat_id,
          title
        ) values (
          'SPRINGFLOW',
          'PROJECT',
          'demo',
          'chat-2',
          'Duplicate'
        )
        """);

    assertDataAccessFailure("""
        insert into truconf_managed_chat (
          owner_system,
          owner_kind,
          owner_key,
          chat_id,
          title
        ) values (
          '',
          'PROJECT',
          'bad',
          'chat-bad',
          'Bad'
        )
        """);
  }

  private Long insertMinimalOutbox(String externalId) {
    return jdbc.queryForObject("""
        insert into truconf_outbox (
          external_id,
          operation,
          recipient_kind,
          user_id,
          payload_json
        ) values (?, 'SEND_MESSAGE', 'USER', 'user@example.com', '{}'::jsonb)
        returning id
        """, Long.class, externalId);
  }

  private String deliveryKey(String externalId) {
    return jdbc.queryForObject(
        "select delivery_key from truconf_outbox where external_id = ?",
        String.class,
        externalId);
  }

  private void assertDataAccessFailure(String sql) {
    assertThatThrownBy(() -> jdbc.update(sql))
        .isInstanceOf(DataAccessException.class);
  }
}
