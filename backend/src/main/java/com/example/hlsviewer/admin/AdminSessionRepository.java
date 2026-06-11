package com.example.hlsviewer.admin;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminSessionRepository {
  private static final HexFormat HEX = HexFormat.of();
  private final JdbcTemplate jdbcTemplate;

  public AdminSessionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public AdminSession createSession(long adminId, Duration ttl) {
    String token = UUID.randomUUID().toString();
    String tokenHash = hashToken(token);
    Instant expiresAt = Instant.now().plus(ttl);
    jdbcTemplate.update(
        "INSERT INTO admin_session (admin_id, token, expires_at) VALUES (?, ?, ?)",
        adminId,
        tokenHash,
        Timestamp.from(expiresAt)
    );
    return new AdminSession(adminId, token, expiresAt);
  }

  public Optional<AdminSession> findValidSession(String token) {
    String tokenHash = hashToken(token);
    List<AdminSession> rows = jdbcTemplate.query(
        "SELECT admin_id, token, expires_at "
            + "FROM admin_session "
            + "WHERE expires_at > NOW() "
            + "AND (token = ? OR token = ?) "
            + "LIMIT 1",
        (rs, rowNum) -> new AdminSession(
            rs.getLong("admin_id"),
            token,
            rs.getTimestamp("expires_at").toInstant()
        ),
        tokenHash,
        token
    );
    return rows.stream().findFirst();
  }

  public void deleteSession(String token) {
    String tokenHash = hashToken(token);
    jdbcTemplate.update(
        "DELETE FROM admin_session WHERE token = ? OR token = ?",
        tokenHash,
        token
    );
  }

  private String hashToken(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashed = digest.digest((token == null ? "" : token).getBytes(StandardCharsets.UTF_8));
      return HEX.formatHex(hashed, 0, 18);
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 digest unavailable", ex);
    }
  }
}
