package com.example.hlsviewer.audio;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ChatAudioRepository {
  private final JdbcTemplate jdbcTemplate;

  public ChatAudioRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public long create(long roomId, String uploaderName, String originalName, String storedName, String contentType, long sizeBytes) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "INSERT INTO chat_audio (room_id, uploader_name, original_name, stored_name, content_type, size_bytes) "
              + "VALUES (?, ?, ?, ?, ?, ?)",
          Statement.RETURN_GENERATED_KEYS
      );
      ps.setLong(1, roomId);
      ps.setString(2, uploaderName);
      ps.setString(3, originalName);
      ps.setString(4, storedName);
      ps.setString(5, contentType);
      ps.setLong(6, sizeBytes);
      return ps;
    }, keyHolder);

    Number key = keyHolder.getKey();
    return key == null ? 0L : key.longValue();
  }

  public List<ChatAudio> listActive(long roomId, int limit) {
    return jdbcTemplate.query(
        "SELECT id, room_id, uploader_name, original_name, stored_name, content_type, size_bytes, created_at, deleted_at "
            + "FROM chat_audio "
            + "WHERE room_id = ? AND deleted_at IS NULL "
            + "ORDER BY id DESC LIMIT ?",
        (rs, rowNum) -> new ChatAudio(
            rs.getLong("id"),
            rs.getLong("room_id"),
            rs.getString("uploader_name"),
            rs.getString("original_name"),
            rs.getString("stored_name"),
            rs.getString("content_type"),
            rs.getLong("size_bytes"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toInstant()
        ),
        roomId,
        limit
    );
  }

  /**
   * Reads an active audio row by id but scopes it to a meeting via chat_room.stream_key
   * so admins/viewers in meeting A cannot fetch meeting B's audio by guessing ids.
   * chat_audio doesn't have stream_key directly; we resolve through chat_room.
   */
  public Optional<ChatAudio> findActiveByIdAndStreamKey(long id, String streamKey) {
    List<ChatAudio> results = jdbcTemplate.query(
        "SELECT ca.id, ca.room_id, ca.uploader_name, ca.original_name, ca.stored_name, "
            + "       ca.content_type, ca.size_bytes, ca.created_at, ca.deleted_at "
            + "FROM chat_audio ca "
            + "JOIN chat_room cr ON cr.id = ca.room_id "
            + "WHERE ca.id = ? AND cr.stream_key = ? AND ca.deleted_at IS NULL",
        (rs, rowNum) -> new ChatAudio(
            rs.getLong("id"),
            rs.getLong("room_id"),
            rs.getString("uploader_name"),
            rs.getString("original_name"),
            rs.getString("stored_name"),
            rs.getString("content_type"),
            rs.getLong("size_bytes"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toInstant()
        ),
        id,
        normalizeStreamKey(streamKey)
    );
    return results.stream().findFirst();
  }

  public List<ChatAudio> findActiveByIdsAndStreamKey(List<Long> ids, String streamKey) {
    if (ids == null || ids.isEmpty()) {
      return Collections.emptyList();
    }
    String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql = "SELECT ca.id, ca.room_id, ca.uploader_name, ca.original_name, ca.stored_name, "
        + "       ca.content_type, ca.size_bytes, ca.created_at, ca.deleted_at "
        + "FROM chat_audio ca "
        + "JOIN chat_room cr ON cr.id = ca.room_id "
        + "WHERE ca.deleted_at IS NULL AND cr.stream_key = ? AND ca.id IN (" + placeholders + ")";
    List<Object> params = new ArrayList<>();
    params.add(normalizeStreamKey(streamKey));
    params.addAll(ids);
    return jdbcTemplate.query(
        sql,
        (rs, rowNum) -> new ChatAudio(
            rs.getLong("id"),
            rs.getLong("room_id"),
            rs.getString("uploader_name"),
            rs.getString("original_name"),
            rs.getString("stored_name"),
            rs.getString("content_type"),
            rs.getLong("size_bytes"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toInstant()
        ),
        params.toArray()
    );
  }

  public int markDeleted(String streamKey, long id, Long adminId) {
    return jdbcTemplate.update(
        "UPDATE chat_audio ca "
            + "JOIN chat_room cr ON cr.id = ca.room_id "
            + "SET ca.deleted_at = ?, ca.deleted_by = ? "
            + "WHERE ca.id = ? AND cr.stream_key = ? AND ca.deleted_at IS NULL",
        Timestamp.from(Instant.now()),
        adminId,
        id,
        normalizeStreamKey(streamKey)
    );
  }

  public int markDeletedBulk(String streamKey, List<Long> ids, Long adminId) {
    if (ids == null || ids.isEmpty()) {
      return 0;
    }
    String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql = "UPDATE chat_audio ca "
        + "JOIN chat_room cr ON cr.id = ca.room_id "
        + "SET ca.deleted_at = ?, ca.deleted_by = ? "
        + "WHERE ca.deleted_at IS NULL AND cr.stream_key = ? AND ca.id IN (" + placeholders + ")";
    List<Object> params = new ArrayList<>();
    params.add(Timestamp.from(Instant.now()));
    params.add(adminId);
    params.add(normalizeStreamKey(streamKey));
    params.addAll(ids);
    return jdbcTemplate.update(sql, params.toArray());
  }

  private static String normalizeStreamKey(String value) {
    return value == null || value.isBlank() ? "stream" : value.trim();
  }
}
