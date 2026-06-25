package ru.truconf.proxydb.managedchat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.RowMapper;
import ru.truconf.proxydb.domain.TruconfManagedChat;

final class ManagedChatRowMapper implements RowMapper<TruconfManagedChat> {

  @Override
  public TruconfManagedChat mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new TruconfManagedChat(
        rs.getLong("id"),
        rs.getString("owner_system"),
        rs.getString("owner_kind"),
        rs.getString("owner_key"),
        rs.getString("chat_id"),
        rs.getString("title"),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("updated_at", OffsetDateTime.class),
        rs.getObject("last_sync_at", OffsetDateTime.class));
  }
}
