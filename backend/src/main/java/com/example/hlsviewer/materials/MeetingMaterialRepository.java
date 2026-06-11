package com.example.hlsviewer.materials;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class MeetingMaterialRepository {
  private final JdbcTemplate jdbcTemplate;

  public MeetingMaterialRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public long createText(String streamKey, long roomId, String title, String body) {
    String normalizedKey = normalizeStreamKey(streamKey);
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "INSERT INTO meeting_material (stream_key, room_id, type, title, body) "
              + "VALUES (?, ?, 'text', ?, ?)",
          Statement.RETURN_GENERATED_KEYS
      );
      ps.setString(1, normalizedKey);
      ps.setLong(2, roomId);
      ps.setString(3, title);
      ps.setString(4, body);
      return ps;
    }, keyHolder);
    Number key = keyHolder.getKey();
    return key == null ? 0L : key.longValue();
  }

  public long createPdf(
      String streamKey,
      long roomId,
      String title,
      String storedName,
      String contentType,
      long sizeBytes
  ) {
    String normalizedKey = normalizeStreamKey(streamKey);
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "INSERT INTO meeting_material (stream_key, room_id, type, title, stored_name, content_type, size_bytes) "
              + "VALUES (?, ?, 'pdf', ?, ?, ?, ?)",
          Statement.RETURN_GENERATED_KEYS
      );
      ps.setString(1, normalizedKey);
      ps.setLong(2, roomId);
      ps.setString(3, title);
      ps.setString(4, storedName);
      ps.setString(5, contentType);
      ps.setLong(6, sizeBytes);
      return ps;
    }, keyHolder);
    Number key = keyHolder.getKey();
    return key == null ? 0L : key.longValue();
  }

  public List<MeetingMaterial> listActive(long roomId, int limit) {
    return jdbcTemplate.query(
        "SELECT id, room_id, type, title, body, stored_name, content_type, size_bytes, created_at "
            + "FROM meeting_material "
            + "WHERE room_id = ? AND deleted_at IS NULL "
            + "ORDER BY created_at DESC LIMIT ?",
        (rs, rowNum) -> new MeetingMaterial(
            rs.getLong("id"),
            rs.getLong("room_id"),
            rs.getString("type"),
            rs.getString("title"),
            rs.getString("body"),
            rs.getString("stored_name"),
            rs.getString("content_type"),
            rs.getLong("size_bytes"),
            rs.getTimestamp("created_at").toInstant()
        ),
        roomId,
        limit
    );
  }

  /**
   * Looked up by primary key but ALSO scoped to the requested streamKey so an admin viewing
   * meeting A can never read/edit/delete material that belongs to meeting B by guessing ids.
   */
  public Optional<MeetingMaterial> findActiveByIdAndStreamKey(long id, String streamKey) {
    List<MeetingMaterial> results = jdbcTemplate.query(
        "SELECT id, room_id, type, title, body, stored_name, content_type, size_bytes, created_at "
            + "FROM meeting_material "
            + "WHERE id = ? AND stream_key = ? AND deleted_at IS NULL",
        (rs, rowNum) -> new MeetingMaterial(
            rs.getLong("id"),
            rs.getLong("room_id"),
            rs.getString("type"),
            rs.getString("title"),
            rs.getString("body"),
            rs.getString("stored_name"),
            rs.getString("content_type"),
            rs.getLong("size_bytes"),
            rs.getTimestamp("created_at").toInstant()
        ),
        id,
        normalizeStreamKey(streamKey)
    );
    return results.stream().findFirst();
  }

  public int updateText(String streamKey, long id, String title, String body) {
    return jdbcTemplate.update(
        "UPDATE meeting_material SET title = ?, body = ? "
            + "WHERE id = ? AND stream_key = ? AND deleted_at IS NULL",
        title,
        body,
        id,
        normalizeStreamKey(streamKey)
    );
  }

  public int updateTitle(String streamKey, long id, String title) {
    return jdbcTemplate.update(
        "UPDATE meeting_material SET title = ? "
            + "WHERE id = ? AND stream_key = ? AND deleted_at IS NULL",
        title,
        id,
        normalizeStreamKey(streamKey)
    );
  }

  public int softDelete(String streamKey, long id) {
    return jdbcTemplate.update(
        "UPDATE meeting_material SET deleted_at = NOW() "
            + "WHERE id = ? AND stream_key = ? AND deleted_at IS NULL",
        id,
        normalizeStreamKey(streamKey)
    );
  }

  private static String normalizeStreamKey(String value) {
    return value == null || value.isBlank() ? "stream" : value.trim();
  }
}
