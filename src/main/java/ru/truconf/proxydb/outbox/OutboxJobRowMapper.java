package ru.truconf.proxydb.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.RowMapper;
import ru.truconf.proxydb.domain.OutboxJob;
import ru.truconf.proxydb.domain.OutboxOperation;
import ru.truconf.proxydb.domain.OutboxStatus;
import ru.truconf.proxydb.domain.RecipientKind;

public final class OutboxJobRowMapper implements RowMapper<OutboxJob> {

  @Override
  public OutboxJob mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new OutboxJob(
        rs.getLong("id"),
        rs.getString("external_id"),
        OutboxOperation.valueOf(rs.getString("operation")),
        RecipientKind.valueOf(rs.getString("recipient_kind")),
        rs.getString("chat_id"),
        rs.getString("user_id"),
        rs.getString("target_message_id"),
        rs.getString("reply_message_id"),
        rs.getString("payload_json"),
        OutboxStatus.valueOf(rs.getString("status")),
        rs.getInt("attempt_count"),
        rs.getInt("max_attempts"),
        getOffsetDateTime(rs, "next_attempt_at"),
        rs.getString("locked_by"),
        getOffsetDateTime(rs, "locked_until"),
        rs.getString("trueconf_chat_id"),
        rs.getString("trueconf_message_id"),
        rs.getString("trueconf_file_id"),
        getLong(rs, "trueconf_timestamp"),
        rs.getString("last_error_code"),
        rs.getString("last_error_message"),
        getBoolean(rs, "last_error_retryable"),
        rs.getString("last_response_json"),
        getOffsetDateTime(rs, "created_at"),
        getOffsetDateTime(rs, "updated_at"),
        getOffsetDateTime(rs, "sent_at"),
        getOffsetDateTime(rs, "failed_at"));
  }

  private static OffsetDateTime getOffsetDateTime(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class);
  }

  private static Long getLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private static Boolean getBoolean(ResultSet rs, String column) throws SQLException {
    boolean value = rs.getBoolean(column);
    return rs.wasNull() ? null : value;
  }
}
