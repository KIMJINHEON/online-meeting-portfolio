package com.example.hlsviewer.viewer;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ViewerAccessLogRepository {
  private final JdbcTemplate jdbcTemplate;

  public ViewerAccessLogRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void startSession(
      String streamKey,
      String sessionTokenHash,
      String name,
      String birth,
      String phone,
      String deviceId,
      Instant startedAt) {
    Timestamp at = Timestamp.from(startedAt == null ? Instant.now() : startedAt);
    try {
      jdbcTemplate.update(
          "INSERT INTO voter_access_log ("
              + "stream_key, session_token_hash, name, birth, phone, device_id, access_started_at, last_seen_at"
              + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
          normalizeStreamKey(streamKey),
          safe(sessionTokenHash),
          safe(name),
          safe(birth),
          safe(phone),
          normalizeDeviceId(deviceId),
          at,
          at);
    } catch (Exception ex) {
      // Fallback for environments where device_id column is not applied yet.
      jdbcTemplate.update(
          "INSERT INTO voter_access_log ("
              + "stream_key, session_token_hash, name, birth, phone, access_started_at, last_seen_at"
              + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
          normalizeStreamKey(streamKey),
          safe(sessionTokenHash),
          safe(name),
          safe(birth),
          safe(phone),
          at,
          at);
    }
  }

  // Stream-key agnostic: a viewer can only have ONE open session at a time across the
  // whole system, regardless of which meeting it belongs to. This prevents a PC session
  // (which gets its access_log row rewritten to a meeting key after enter-meeting) from
  // hiding behind the old 'stream' key when a second device tries to authenticate.
  public boolean existsOpenSession(String name, String birth, String phone) {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(1) "
            + "FROM voter_access_log "
            + "WHERE name = ? "
            + "AND birth = ? "
            + "AND phone = ? "
            + "AND access_ended_at IS NULL",
        Integer.class,
        safe(name),
        safe(birth),
        safe(phone));
    return count != null && count > 0;
  }

  public boolean existsOpenSessionOnOtherDevice(
      String name,
      String birth,
      String phone,
      String deviceId) {
    String normalizedDeviceId = normalizeDeviceId(deviceId);
    if (normalizedDeviceId.isEmpty()) {
      return existsOpenSession(name, birth, phone);
    }
    try {
      Integer count = jdbcTemplate.queryForObject(
          "SELECT COUNT(1) "
              + "FROM voter_access_log "
              + "WHERE name = ? "
              + "AND birth = ? "
              + "AND phone = ? "
              + "AND access_ended_at IS NULL "
              + "AND TRIM(COALESCE(device_id, '')) <> '' "
              + "AND device_id <> ?",
          Integer.class,
          safe(name),
          safe(birth),
          safe(phone),
          normalizedDeviceId);
      return count != null && count > 0;
    } catch (Exception ex) {
      return existsOpenSession(name, birth, phone);
    }
  }

  public int endSession(String sessionTokenHash, Instant endedAt, String reason) {
    Timestamp at = Timestamp.from(endedAt == null ? Instant.now() : endedAt);
    return jdbcTemplate.update(
        "UPDATE voter_access_log "
            + "SET access_ended_at = ?, last_seen_at = ?, end_reason = ? "
            + "WHERE session_token_hash = ? AND access_ended_at IS NULL",
        at,
        at,
        normalizeReason(reason),
        safe(sessionTokenHash));
  }

  public int touchSession(String sessionTokenHash, Instant seenAt) {
    Timestamp at = Timestamp.from(seenAt == null ? Instant.now() : seenAt);
    return jdbcTemplate.update(
        "UPDATE voter_access_log "
            + "SET last_seen_at = ? "
            + "WHERE session_token_hash = ? AND access_ended_at IS NULL",
        at,
        safe(sessionTokenHash));
  }

  public int closeStaleSessions(Instant staleBefore, String reason) {
    Timestamp cutoff = Timestamp.from(staleBefore == null ? Instant.now() : staleBefore);
    return jdbcTemplate.update(
        "UPDATE voter_access_log "
            + "SET access_ended_at = COALESCE(last_seen_at, access_started_at, ?), "
            + "last_seen_at = COALESCE(last_seen_at, access_started_at, ?), "
            + "end_reason = ? "
            + "WHERE access_ended_at IS NULL "
            + "AND COALESCE(last_seen_at, access_started_at) < ?",
        cutoff,
        cutoff,
        normalizeReason(reason),
        cutoff);
  }

  public int reopenBrowserExit(String sessionTokenHash, Instant reopenedAt) {
    Timestamp reopenTs = Timestamp.from(reopenedAt == null ? Instant.now() : reopenedAt);
    return jdbcTemplate.update(
        "UPDATE voter_access_log "
            + "SET access_ended_at = NULL, last_seen_at = ?, end_reason = NULL "
            + "WHERE session_token_hash = ? "
            + "AND access_ended_at IS NOT NULL "
            + "AND (end_reason = 'browser_exit' OR end_reason = 'unknown')",
        reopenTs,
        safe(sessionTokenHash));
  }

  public int markFirstPlay(String sessionTokenHash, Instant playedAt) {
    Timestamp at = Timestamp.from(playedAt == null ? Instant.now() : playedAt);
    // first_played_at is optional; mark only once for an idempotent attendance signal.
    return jdbcTemplate.update(
        "UPDATE voter_access_log "
            + "SET first_played_at = COALESCE(first_played_at, ?), last_seen_at = ? "
            + "WHERE session_token_hash = ?",
        at,
        at,
        safe(sessionTokenHash));
  }

  public int deleteByStreamKey(String streamKey) {
    return jdbcTemplate.update(
        "DELETE FROM voter_access_log WHERE stream_key = ?",
        normalizeStreamKey(streamKey));
  }

  /**
   * Rewrite the stream_key on the viewer's currently-open access_log row.
   * Called when a viewer enters a specific meeting after the initial NICE auth,
   * so that the per-meeting attendance count (WHERE stream_key = meeting_key)
   * picks them up.
   */
  public int updateStreamKey(String sessionTokenHash, String newStreamKey) {
    return jdbcTemplate.update(
        "UPDATE voter_access_log "
            + "SET stream_key = ? "
            + "WHERE session_token_hash = ? AND access_ended_at IS NULL",
        normalizeStreamKey(newStreamKey),
        safe(sessionTokenHash));
  }

  private String normalizeStreamKey(String value) {
    String trimmed = value == null ? "" : value.trim();
    return trimmed.isEmpty() ? "stream" : trimmed;
  }

  private String normalizeReason(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      return "unknown";
    }
    if (trimmed.length() <= 20) {
      return trimmed;
    }
    return trimmed.substring(0, 20);
  }

  private String safe(String value) {
    return value == null ? "" : value.trim();
  }

  private String normalizeDeviceId(String value) {
    String trimmed = safe(value);
    if (trimmed.isEmpty()) {
      return "";
    }
    if (trimmed.length() <= 64) {
      return trimmed;
    }
    return trimmed.substring(0, 64);
  }
}
