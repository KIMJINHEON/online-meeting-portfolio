package com.example.hlsviewer.meeting;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MeetingRepository {
  private final JdbcTemplate jdbcTemplate;

  private static final String SELECT_COLS =
      "id, stream_key, title, start_at, end_at, access_open, vote_url, "
          + "deleted_at, created_at, updated_at";

  private static final RowMapper<Meeting> ROW_MAPPER = (rs, rowNum) -> new Meeting(
      rs.getLong("id"),
      rs.getString("stream_key"),
      rs.getString("title"),
      toLocalDateTime(rs.getTimestamp("start_at")),
      toLocalDateTime(rs.getTimestamp("end_at")),
      rs.getBoolean("access_open"),
      rs.getString("vote_url"),
      toLocalDateTime(rs.getTimestamp("deleted_at")),
      toLocalDateTime(rs.getTimestamp("created_at")),
      toLocalDateTime(rs.getTimestamp("updated_at"))
  );

  public MeetingRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<Meeting> findById(long id) {
    List<Meeting> rows = jdbcTemplate.query(
        "SELECT " + SELECT_COLS + " FROM meeting WHERE id = ? AND deleted_at IS NULL LIMIT 1",
        ROW_MAPPER,
        id
    );
    return rows.stream().findFirst();
  }

  public Optional<Meeting> findByStreamKey(String streamKey) {
    if (streamKey == null || streamKey.isBlank()) {
      return Optional.empty();
    }
    List<Meeting> rows = jdbcTemplate.query(
        "SELECT " + SELECT_COLS + " FROM meeting WHERE stream_key = ? AND deleted_at IS NULL LIMIT 1",
        ROW_MAPPER,
        streamKey
    );
    return rows.stream().findFirst();
  }

  public List<Meeting> findActiveOrderedByStart() {
    return jdbcTemplate.query(
        "SELECT " + SELECT_COLS + " FROM meeting WHERE deleted_at IS NULL "
            + "ORDER BY start_at ASC, id ASC",
        ROW_MAPPER
    );
  }

  public List<Meeting> findActiveByStreamKeys(List<String> streamKeys) {
    if (streamKeys == null || streamKeys.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", streamKeys.stream().map(k -> "?").toList());
    String sql = "SELECT " + SELECT_COLS + " FROM meeting WHERE deleted_at IS NULL "
        + "AND stream_key IN (" + placeholders + ") "
        + "ORDER BY start_at ASC, id ASC";
    return jdbcTemplate.query(sql, ROW_MAPPER, streamKeys.toArray());
  }

  /**
   * Inserts a new meeting. Throws DuplicateKeyException on stream_key collision so the caller can retry.
   */
  public long insert(
      String streamKey,
      String title,
      LocalDateTime startAt,
      LocalDateTime endAt,
      String voteUrl) {
    return jdbcTemplate.update(
        "INSERT INTO meeting (stream_key, title, start_at, end_at, access_open, vote_url) "
            + "VALUES (?, ?, ?, ?, FALSE, ?)",
        streamKey,
        title,
        Timestamp.valueOf(startAt),
        Timestamp.valueOf(endAt),
        voteUrl
    );
  }

  public boolean streamKeyExists(String streamKey) {
    Integer found = jdbcTemplate.query(
        "SELECT 1 FROM meeting WHERE stream_key = ? LIMIT 1",
        rs -> rs.next() ? 1 : null,
        streamKey
    );
    return found != null;
  }

  public int update(
      long id,
      String title,
      LocalDateTime startAt,
      LocalDateTime endAt,
      String voteUrl) {
    return jdbcTemplate.update(
        "UPDATE meeting SET title = ?, start_at = ?, end_at = ?, vote_url = ? "
            + "WHERE id = ? AND deleted_at IS NULL",
        title,
        Timestamp.valueOf(startAt),
        Timestamp.valueOf(endAt),
        voteUrl,
        id
    );
  }

  public int updateAccessOpen(long id, boolean accessOpen) {
    return jdbcTemplate.update(
        "UPDATE meeting SET access_open = ? WHERE id = ? AND deleted_at IS NULL",
        accessOpen,
        id
    );
  }

  public int softDelete(long id) {
    return jdbcTemplate.update(
        "UPDATE meeting SET deleted_at = CURRENT_TIMESTAMP WHERE id = ? AND deleted_at IS NULL",
        id
    );
  }

  private static LocalDateTime toLocalDateTime(Timestamp ts) {
    return ts == null ? null : ts.toLocalDateTime();
  }
}
