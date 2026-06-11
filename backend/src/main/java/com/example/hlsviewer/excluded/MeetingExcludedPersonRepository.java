package com.example.hlsviewer.excluded;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MeetingExcludedPersonRepository {
  private final JdbcTemplate jdbcTemplate;

  private static final RowMapper<MeetingExcludedPerson> ROW_MAPPER = (rs, rowNum) -> new MeetingExcludedPerson(
      rs.getLong("id"),
      rs.getString("stream_key"),
      rs.getString("name"),
      rs.getString("birth"),
      rs.getString("phone"),
      toLocalDateTime(rs.getTimestamp("created_at"))
  );

  public MeetingExcludedPersonRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<MeetingExcludedPerson> listByStreamKey(String streamKey) {
    return jdbcTemplate.query(
        "SELECT id, stream_key, name, birth, phone, created_at "
            + "FROM meeting_excluded_person "
            + "WHERE stream_key = ? "
            + "ORDER BY created_at DESC, id DESC",
        ROW_MAPPER,
        streamKey
    );
  }

  public Optional<MeetingExcludedPerson> findById(long id) {
    List<MeetingExcludedPerson> rows = jdbcTemplate.query(
        "SELECT id, stream_key, name, birth, phone, created_at "
            + "FROM meeting_excluded_person WHERE id = ?",
        ROW_MAPPER,
        id
    );
    return rows.stream().findFirst();
  }

  /**
   * Returns the new id. Throws {@link DuplicateKeyException} when the UNIQUE
   * (stream_key, name, birth, phone) constraint is violated, so the caller can
   * translate that to a user-friendly "already registered" message.
   */
  public long insert(String streamKey, String name, String birth, String phone) {
    jdbcTemplate.update(
        "INSERT INTO meeting_excluded_person (stream_key, name, birth, phone) VALUES (?, ?, ?, ?)",
        streamKey,
        name,
        birth,
        phone
    );
    Long id = jdbcTemplate.queryForObject(
        "SELECT id FROM meeting_excluded_person WHERE stream_key = ? AND name = ? AND birth = ? AND phone = ?",
        Long.class,
        streamKey,
        name,
        birth,
        phone
    );
    return id == null ? 0L : id;
  }

  public int delete(String streamKey, long id) {
    return jdbcTemplate.update(
        "DELETE FROM meeting_excluded_person WHERE id = ? AND stream_key = ?",
        id,
        streamKey
    );
  }

  private static java.time.LocalDateTime toLocalDateTime(Timestamp ts) {
    return ts == null ? null : ts.toLocalDateTime();
  }
}
