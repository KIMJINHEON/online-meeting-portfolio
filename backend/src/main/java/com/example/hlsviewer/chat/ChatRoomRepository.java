package com.example.hlsviewer.chat;

import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
public class ChatRoomRepository {
  private final JdbcTemplate jdbcTemplate;
  private final Map<String, Long> roomIdCache = new ConcurrentHashMap<>();

  public ChatRoomRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public long getOrCreateRoomId(String streamKey) {
    String key = normalizeStreamKey(streamKey);
    return roomIdCache.computeIfAbsent(key, this::findOrCreateRoomId);
  }

  private long findOrCreateRoomId(String streamKey) {
    List<Long> rows = jdbcTemplate.query(
        "SELECT id FROM chat_room WHERE stream_key = ?",
        (rs, rowNum) -> rs.getLong("id"),
        streamKey
    );
    if (!rows.isEmpty()) {
      return rows.get(0);
    }

    try {
      jdbcTemplate.update(
          "INSERT INTO chat_room (stream_key, is_active) VALUES (?, 1)",
          streamKey
      );
    } catch (DuplicateKeyException ignore) {
      // Another request created this room key first.
    }

    Long id = jdbcTemplate.queryForObject(
        "SELECT id FROM chat_room WHERE stream_key = ?",
        Long.class,
        streamKey
    );
    if (id == null) {
      throw new IllegalStateException("chat_room_not_found_after_create");
    }
    return id;
  }

  private String normalizeStreamKey(String value) {
    if (value == null || value.isBlank()) {
      return "stream";
    }
    return value.trim();
  }
}
