package com.example.hlsviewer.attendance;

import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AttendanceOverrideRepository {
  private final JdbcTemplate jdbcTemplate;
  private volatile Boolean manualFieldColumnAvailable = null;

  public AttendanceOverrideRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<Long> findManualOnsite(String streamKey) {
    String key = normalizeStreamKey(streamKey);
    return findManualOnsiteByKey(key);
  }

  public Optional<Long> findManualFieldOnsite(String streamKey) {
    String key = normalizeStreamKey(streamKey);
    String fallbackKey = fieldOnsiteFallbackKey(key);

    if (!isManualFieldColumnAvailable()) {
      return findManualOnsiteByKey(fallbackKey);
    }

    try {
      Optional<Long> columnValue = findManualFieldOnsiteByColumn(key);
      if (columnValue.isPresent()) {
        return columnValue;
      }
      // 컬럼이 있어도 값이 없으면(기존 fallback 데이터가 있던 경우) fallback 키를 한번 더 조회.
      return findManualOnsiteByKey(fallbackKey);
    } catch (DataAccessException ex) {
      manualFieldColumnAvailable = false;
      return findManualOnsiteByKey(fallbackKey);
    }
  }

  public void upsertManualOnsite(String streamKey, long manualOnsite) {
    String key = normalizeStreamKey(streamKey);
    upsertManualOnsiteByKey(key, manualOnsite);
  }

  public void upsertManualFieldOnsite(String streamKey, long manualFieldOnsite) {
    String key = normalizeStreamKey(streamKey);
    String fallbackKey = fieldOnsiteFallbackKey(key);
    long normalized = Math.max(0, manualFieldOnsite);

    if (!isManualFieldColumnAvailable()) {
      upsertManualOnsiteByKey(fallbackKey, normalized);
      return;
    }

    try {
      jdbcTemplate.update(
          "INSERT INTO admin_attendance_override (stream_key, manual_onsite, manual_field_onsite) VALUES (?, -1, ?) "
              + "ON DUPLICATE KEY UPDATE manual_field_onsite = VALUES(manual_field_onsite)",
          key,
          normalized
      );
      // 컬럼 저장 성공 시 fallback 키는 비움.
      clearManualOnsiteByKey(fallbackKey);
    } catch (DataAccessException ex) {
      manualFieldColumnAvailable = false;
      upsertManualOnsiteByKey(fallbackKey, normalized);
    }
  }

  public void clearManualOnsite(String streamKey) {
    String key = normalizeStreamKey(streamKey);
    clearManualOnsiteByKey(key);
  }

  public void clearManualFieldOnsite(String streamKey) {
    String key = normalizeStreamKey(streamKey);
    String fallbackKey = fieldOnsiteFallbackKey(key);

    if (!isManualFieldColumnAvailable()) {
      clearManualOnsiteByKey(fallbackKey);
      return;
    }

    try {
      jdbcTemplate.update(
          "UPDATE admin_attendance_override SET manual_field_onsite = -1 WHERE stream_key = ?",
          key
      );
      clearManualOnsiteByKey(fallbackKey);
    } catch (DataAccessException ex) {
      manualFieldColumnAvailable = false;
      clearManualOnsiteByKey(fallbackKey);
    }
  }

  private Optional<Long> findManualOnsiteByKey(String key) {
    try {
      List<Long> rows = jdbcTemplate.query(
          "SELECT manual_onsite FROM admin_attendance_override WHERE stream_key = ? LIMIT 1",
          (rs, rowNum) -> rs.getLong("manual_onsite"),
          key
      );
      if (rows.isEmpty()) {
        return Optional.empty();
      }
      Long value = rows.get(0);
      if (value == null || value < 0) {
        return Optional.empty();
      }
      return Optional.of(value);
    } catch (DataAccessException ex) {
      return Optional.empty();
    }
  }

  private Optional<Long> findManualFieldOnsiteByColumn(String key) {
    List<Long> rows = jdbcTemplate.query(
        "SELECT manual_field_onsite FROM admin_attendance_override WHERE stream_key = ? LIMIT 1",
        (rs, rowNum) -> rs.getLong("manual_field_onsite"),
        key
    );
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Long value = rows.get(0);
    if (value == null || value < 0) {
      return Optional.empty();
    }
    return Optional.of(value);
  }

  private void upsertManualOnsiteByKey(String key, long manualOnsite) {
    jdbcTemplate.update(
        "INSERT INTO admin_attendance_override (stream_key, manual_onsite) VALUES (?, ?) "
            + "ON DUPLICATE KEY UPDATE manual_onsite = VALUES(manual_onsite)",
        key,
        Math.max(0, manualOnsite)
    );
  }

  private void clearManualOnsiteByKey(String key) {
    jdbcTemplate.update(
        "UPDATE admin_attendance_override SET manual_onsite = -1 WHERE stream_key = ?",
        key
    );
  }

  private boolean isManualFieldColumnAvailable() {
    Boolean cached = manualFieldColumnAvailable;
    if (cached != null) {
      return cached;
    }
    try {
      jdbcTemplate.queryForObject(
          "SELECT manual_field_onsite FROM admin_attendance_override LIMIT 1",
          Long.class
      );
      manualFieldColumnAvailable = true;
    } catch (DataAccessException ex) {
      manualFieldColumnAvailable = false;
    }
    return Boolean.TRUE.equals(manualFieldColumnAvailable);
  }

  private String fieldOnsiteFallbackKey(String streamKey) {
    String suffix = "#field";
    int maxBaseLength = Math.max(1, 100 - suffix.length());
    String base = streamKey.length() > maxBaseLength ? streamKey.substring(0, maxBaseLength) : streamKey;
    return base + suffix;
  }

  private String normalizeStreamKey(String value) {
    String trimmed = value == null ? "" : value.trim();
    return trimmed.isEmpty() ? "stream" : trimmed;
  }
}
