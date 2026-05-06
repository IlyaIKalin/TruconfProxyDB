package ru.truconf.proxydb.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.RowMapper;
import ru.truconf.proxydb.domain.FileStorageKind;
import ru.truconf.proxydb.domain.OutboxFile;

public final class OutboxFileRowMapper implements RowMapper<OutboxFile> {

  @Override
  public OutboxFile mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new OutboxFile(
        rs.getLong("id"),
        rs.getLong("outbox_id"),
        rs.getString("file_name"),
        rs.getString("mime_type"),
        rs.getLong("size_bytes"),
        FileStorageKind.valueOf(rs.getString("storage_kind")),
        rs.getString("file_path"),
        rs.getBytes("file_data"),
        rs.getString("preview_file_name"),
        rs.getString("preview_mime_type"),
        getLong(rs, "preview_size_bytes"),
        rs.getString("preview_file_path"),
        rs.getBytes("preview_file_data"),
        rs.getObject("created_at", OffsetDateTime.class));
  }

  private static Long getLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }
}
