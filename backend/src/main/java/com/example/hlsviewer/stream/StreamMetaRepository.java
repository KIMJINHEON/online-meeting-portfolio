package com.example.hlsviewer.stream;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StreamMetaRepository {
  private final JdbcTemplate jdbcTemplate;

  public StreamMetaRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<StreamMeta> findByStreamKey(String streamKey) {
    List<StreamMeta> results =
        jdbcTemplate.query(
            "SELECT stream_key, title, scheduled_start FROM stream_schedule WHERE stream_key = ?",
            (rs, rowNum) -> {
              Timestamp scheduled = rs.getTimestamp("scheduled_start");
              LocalDateTime scheduledStart = scheduled == null ? null : scheduled.toLocalDateTime();
              return new StreamMeta(
                  rs.getString("stream_key"),
                  rs.getString("title"),
                  scheduledStart
              );
            },
            streamKey);
    return results.stream().findFirst();
  }

  public void upsert(String streamKey, String title, LocalDateTime scheduledStart) {
    jdbcTemplate.update(
        "INSERT INTO stream_schedule (stream_key, title, scheduled_start) VALUES (?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE title = VALUES(title), scheduled_start = VALUES(scheduled_start)",
        streamKey,
        title,
        scheduledStart == null ? null : Timestamp.valueOf(scheduledStart)
    );
  }
}
