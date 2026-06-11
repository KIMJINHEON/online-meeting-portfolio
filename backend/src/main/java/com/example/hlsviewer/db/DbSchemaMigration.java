package com.example.hlsviewer.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DbSchemaMigration implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(DbSchemaMigration.class);
  private final JdbcTemplate jdbcTemplate;

  public DbSchemaMigration(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void run(ApplicationArguments args) {
    ensureAccessLogFirstPlayedAt();
    ensureAccessLogDeviceId();
    ensureChatMessageRoomStatusIdIndex();
    ensureAdminAttendanceOverrideTable();
    ensureAdminAttendanceOverrideManualFieldOnsiteColumn();
    maskLegacyAdminSessionToken();
  }

  private void ensureAccessLogFirstPlayedAt() {
    try {
      String schema = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
      if (schema == null || schema.isBlank()) {
        return;
      }
      Integer count = jdbcTemplate.queryForObject(
          "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
              + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'voter_access_log' AND COLUMN_NAME = 'first_played_at'",
          Integer.class,
          schema
      );
      if (count != null && count > 0) {
        return;
      }
      log.info("DB migration: adding voter_access_log.first_played_at");
      jdbcTemplate.execute(
          "ALTER TABLE voter_access_log "
              + "ADD COLUMN first_played_at DATETIME NULL AFTER access_started_at"
      );
      try {
        jdbcTemplate.execute(
            "CREATE INDEX idx_voter_access_log_stream_played "
                + "ON voter_access_log (stream_key, first_played_at)"
        );
      } catch (Exception ignored) {
        // Ignore (index may already exist / permissions / etc).
      }
    } catch (Exception ex) {
      log.warn("DB migration skipped/failed: {}", ex.getMessage());
    }
  }

  private void ensureAccessLogDeviceId() {
    try {
      String schema = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
      if (schema == null || schema.isBlank()) {
        return;
      }
      Integer count = jdbcTemplate.queryForObject(
          "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
              + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'voter_access_log' AND COLUMN_NAME = 'device_id'",
          Integer.class,
          schema
      );
      if (count == null || count == 0) {
        log.info("DB migration: adding voter_access_log.device_id");
        jdbcTemplate.execute(
            "ALTER TABLE voter_access_log "
                + "ADD COLUMN device_id VARCHAR(64) NULL AFTER phone"
        );
      }
      Integer indexCount = jdbcTemplate.queryForObject(
          "SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS "
              + "WHERE TABLE_SCHEMA = ? "
              + "AND TABLE_NAME = 'voter_access_log' "
              + "AND INDEX_NAME = 'idx_voter_access_log_device'",
          Integer.class,
          schema
      );
      if (indexCount == null || indexCount == 0) {
        try {
          jdbcTemplate.execute(
              "CREATE INDEX idx_voter_access_log_device "
                  + "ON voter_access_log (stream_key, device_id, access_ended_at)"
          );
        } catch (Exception ignored) {
          // Ignore index creation failures (privilege/vendor differences).
        }
      }
    } catch (Exception ex) {
      log.warn("DB migration skipped/failed for voter_access_log.device_id: {}", ex.getMessage());
    }
  }

  private void ensureChatMessageRoomStatusIdIndex() {
    try {
      String schema = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
      if (schema == null || schema.isBlank()) {
        return;
      }
      Integer count = jdbcTemplate.queryForObject(
          "SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS "
              + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'chat_message' AND INDEX_NAME = 'idx_room_status_id'",
          Integer.class,
          schema
      );
      if (count != null && count > 0) {
        return;
      }
      log.info("DB migration: adding chat_message.idx_room_status_id");
      jdbcTemplate.execute(
          "ALTER TABLE chat_message "
              + "ADD INDEX idx_room_status_id (room_id, status, id)"
      );
    } catch (Exception ex) {
      log.warn("DB migration skipped/failed for chat_message idx_room_status_id: {}", ex.getMessage());
    }
  }

  private void ensureAdminAttendanceOverrideTable() {
    try {
      log.info("DB migration: ensuring admin_attendance_override table");
      jdbcTemplate.execute(
          "CREATE TABLE IF NOT EXISTS admin_attendance_override ("
              + "stream_key VARCHAR(100) NOT NULL PRIMARY KEY, "
              + "manual_onsite BIGINT NOT NULL, "
              + "manual_field_onsite BIGINT NOT NULL DEFAULT -1, "
              + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
              + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
      );
    } catch (Exception ex) {
      log.warn("DB migration skipped/failed for admin_attendance_override: {}", ex.getMessage());
    }
  }

  private void ensureAdminAttendanceOverrideManualFieldOnsiteColumn() {
    try {
      String schema = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
      if (schema == null || schema.isBlank()) {
        return;
      }
      Integer count = jdbcTemplate.queryForObject(
          "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
              + "WHERE TABLE_SCHEMA = ? "
              + "AND TABLE_NAME = 'admin_attendance_override' "
              + "AND COLUMN_NAME = 'manual_field_onsite'",
          Integer.class,
          schema
      );
      if (count != null && count > 0) {
        return;
      }
      log.info("DB migration: adding admin_attendance_override.manual_field_onsite");
      jdbcTemplate.execute(
          "ALTER TABLE admin_attendance_override "
              + "ADD COLUMN manual_field_onsite BIGINT NOT NULL DEFAULT -1 AFTER manual_onsite"
      );
    } catch (Exception ex) {
      log.warn(
          "DB migration skipped/failed for admin_attendance_override.manual_field_onsite: {}",
          ex.getMessage()
      );
    }
  }

  private void maskLegacyAdminSessionToken() {
    try {
      int updated = jdbcTemplate.update(
          "UPDATE admin_session "
              + "SET token = LOWER(SUBSTRING(SHA2(token, 256), 1, 36)) "
              + "WHERE token IS NOT NULL AND token LIKE '%-%'"
      );
      if (updated > 0) {
        log.info("DB migration: masked {} legacy admin session tokens", updated);
      }
    } catch (Exception ex) {
      log.warn("DB migration skipped/failed for admin_session token masking: {}", ex.getMessage());
    }
  }
}
