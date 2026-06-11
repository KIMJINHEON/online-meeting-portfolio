package com.example.hlsviewer.admin;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminUserRepository {
  private final JdbcTemplate jdbcTemplate;

  public AdminUserRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<AdminUser> findByUsername(String username) {
    List<AdminUser> rows = jdbcTemplate.query(
        "SELECT id, username, password_hash, display_name FROM admin_user WHERE username = ?",
        (rs, rowNum) -> new AdminUser(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("display_name")
        ),
        username
    );
    return rows.stream().findFirst();
  }
}
