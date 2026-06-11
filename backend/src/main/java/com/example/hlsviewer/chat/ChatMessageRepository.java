package com.example.hlsviewer.chat;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ChatMessageRepository {
  private final JdbcTemplate jdbcTemplate;

  public ChatMessageRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public long createPending(String streamKey, long roomId, String senderName, String message) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "INSERT INTO chat_message (stream_key, room_id, sender_name, message, status) "
              + "VALUES (?, ?, ?, ?, 'pending')",
          Statement.RETURN_GENERATED_KEYS
      );
      ps.setString(1, streamKey == null || streamKey.isBlank() ? "stream" : streamKey.trim());
      ps.setLong(2, roomId);
      ps.setString(3, senderName);
      ps.setString(4, message);
      return ps;
    }, keyHolder);

    Number key = keyHolder.getKey();
    return key == null ? 0L : key.longValue();
  }

  public List<ChatMessage> listApproved(long roomId, long afterId, int limit) {
    return jdbcTemplate.query(
        "SELECT id, room_id, sender_name, message, status, created_at "
            + "FROM chat_message FORCE INDEX (idx_room_status_id) "
            + "WHERE room_id = ? AND status = 'approved' AND id > ? "
            + "ORDER BY id ASC LIMIT ?",
        (rs, rowNum) -> new ChatMessage(
            rs.getLong("id"),
            rs.getLong("room_id"),
            rs.getString("sender_name"),
            rs.getString("message"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant()
        ),
        roomId,
        afterId,
        limit
    );
  }

  public List<ChatMessage> listByStatus(long roomId, String status, int limit) {
    return jdbcTemplate.query(
        "SELECT id, room_id, sender_name, message, status, created_at "
            + "FROM chat_message FORCE INDEX (idx_room_status_id) "
            + "WHERE room_id = ? AND status = ? "
            + "ORDER BY id ASC LIMIT ?",
        (rs, rowNum) -> new ChatMessage(
            rs.getLong("id"),
            rs.getLong("room_id"),
            rs.getString("sender_name"),
            rs.getString("message"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant()
        ),
        roomId,
        status,
        limit
    );
  }

  public int updateStatus(String streamKey, long id, String status, Long adminId) {
    return jdbcTemplate.update(
        "UPDATE chat_message SET status = ?, approved_by = ?, approved_at = ? "
            + "WHERE id = ? AND stream_key = ?",
        status,
        adminId,
        Timestamp.from(Instant.now()),
        id,
        streamKey == null || streamKey.isBlank() ? "stream" : streamKey.trim()
    );
  }

  public int deleteByRoomAndId(long roomId, long id) {
    return jdbcTemplate.update(
        "DELETE FROM chat_message WHERE room_id = ? AND id = ?",
        roomId,
        id
    );
  }
}
