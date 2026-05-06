package ru.truconf.proxydb.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.RowMapper;
import ru.truconf.proxydb.domain.P2pChatCacheEntry;

public final class P2pChatCacheEntryRowMapper implements RowMapper<P2pChatCacheEntry> {

  @Override
  public P2pChatCacheEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new P2pChatCacheEntry(
        rs.getString("user_id"),
        rs.getString("chat_id"),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("updated_at", OffsetDateTime.class),
        rs.getObject("last_used_at", OffsetDateTime.class));
  }
}
