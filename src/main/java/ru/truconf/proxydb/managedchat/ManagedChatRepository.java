package ru.truconf.proxydb.managedchat;

import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.truconf.proxydb.domain.TruconfManagedChat;

@Repository
public class ManagedChatRepository {

  private static final ManagedChatRowMapper ROW_MAPPER = new ManagedChatRowMapper();

  private final JdbcTemplate jdbc;

  public ManagedChatRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void lockOwner(String ownerSystem, String ownerKind, String ownerKey) {
    String lockKey = ownerSystem + ":" + ownerKind + ":" + ownerKey;
    jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        Object.class,
        lockKey);
  }

  public TruconfManagedChat create(
      String ownerSystem,
      String ownerKind,
      String ownerKey,
      String chatId,
      String title) {
    requireText(ownerSystem, "ownerSystem");
    requireText(ownerKind, "ownerKind");
    requireText(ownerKey, "ownerKey");
    requireText(chatId, "chatId");
    requireText(title, "title");

    return jdbc.queryForObject("""
        insert into truconf_managed_chat (
          owner_system,
          owner_kind,
          owner_key,
          chat_id,
          title
        ) values (
          ?,
          ?,
          ?,
          ?,
          ?
        )
        returning *
        """,
        ROW_MAPPER,
        ownerSystem,
        ownerKind,
        ownerKey,
        chatId,
        title);
  }

  public Optional<TruconfManagedChat> findByOwner(
      String ownerSystem,
      String ownerKind,
      String ownerKey) {
    requireText(ownerSystem, "ownerSystem");
    requireText(ownerKind, "ownerKind");
    requireText(ownerKey, "ownerKey");

    return queryOptional("""
        select *
        from truconf_managed_chat
        where owner_system = ?
          and owner_kind = ?
          and owner_key = ?
        """,
        ownerSystem,
        ownerKind,
        ownerKey);
  }

  public TruconfManagedChat register(
      String ownerSystem,
      String ownerKind,
      String ownerKey,
      String chatId,
      String title) {
    requireText(ownerSystem, "ownerSystem");
    requireText(ownerKind, "ownerKind");
    requireText(ownerKey, "ownerKey");
    requireText(chatId, "chatId");
    requireText(title, "title");

    return jdbc.queryForObject("""
        insert into truconf_managed_chat (
          owner_system,
          owner_kind,
          owner_key,
          chat_id,
          title
        ) values (
          ?,
          ?,
          ?,
          ?,
          ?
        )
        on conflict (owner_system, owner_kind, owner_key)
        do update set chat_id = excluded.chat_id,
                      title = excluded.title
        returning *
        """,
        ROW_MAPPER,
        ownerSystem,
        ownerKind,
        ownerKey,
        chatId,
        title);
  }

  public TruconfManagedChat markSynced(
      String ownerSystem,
      String ownerKind,
      String ownerKey,
      String title) {
    requireText(ownerSystem, "ownerSystem");
    requireText(ownerKind, "ownerKind");
    requireText(ownerKey, "ownerKey");
    requireText(title, "title");

    return jdbc.queryForObject("""
        update truconf_managed_chat
        set title = ?,
            last_sync_at = now()
        where owner_system = ?
          and owner_kind = ?
          and owner_key = ?
        returning *
        """,
        ROW_MAPPER,
        title,
        ownerSystem,
        ownerKind,
        ownerKey);
  }

  private Optional<TruconfManagedChat> queryOptional(String sql, Object... args) {
    return jdbc.query(sql, ROW_MAPPER, args).stream().findFirst();
  }

  private static void requireText(String value, String fieldName) {
    Objects.requireNonNull(value, fieldName + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
  }
}
