package com.example.hlsviewer.partner;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PartnerNiceCertVerifier {
  private static final Logger log = LoggerFactory.getLogger(PartnerNiceCertVerifier.class);

  private final PartnerDbProperties properties;

  public PartnerNiceCertVerifier(PartnerDbProperties properties) {
    this.properties = properties;
  }

  public boolean isEnabled() {
    return properties.isEnabled();
  }

  public Verification verify(String userId, String nicePhone) {
    if (!properties.isEnabled()) {
      return Verification.allow();
    }

    String normalizedUserId = safeTrim(userId);
    String niceDigits = normalizeKoreanMobile(digitsOnly(nicePhone));
    if (!normalizedUserId.isEmpty() && normalizedUserId.length() > 9) {
      return Verification.deny("user_id_invalid");
    }

    Optional<String> partnerPhone;
    try {
      if (!normalizedUserId.isEmpty()) {
        partnerPhone = findPartnerPhoneByUserId(normalizedUserId);
      } else if (!niceDigits.isEmpty()) {
        partnerPhone = findPartnerPhoneByPhone(niceDigits);
      } else {
        partnerPhone = Optional.empty();
      }
    } catch (Exception ex) {
      log.warn("Partner DB verify failed for userId={}: {}", normalizedUserId, ex.getMessage());
      return Verification.deny("partner_db_error");
    }

    if (partnerPhone.isEmpty()) {
      return Verification.deny("user_not_found");
    }

    if (!properties.isRequirePhoneMatch()) {
      return Verification.allow();
    }

    String partnerDigits = normalizeKoreanMobile(digitsOnly(partnerPhone.get()));
    if (!niceDigits.isEmpty() && !partnerDigits.isEmpty() && niceDigits.equals(partnerDigits)) {
      return Verification.allow();
    }
    return Verification.deny("phone_mismatch");
  }

  private Optional<String> findPartnerPhoneByUserId(String userId) throws Exception {
    String jdbcUrl = safeTrim(properties.getJdbcUrl());
    String username = safeTrim(properties.getUsername());
    String password = properties.getPassword() == null ? "" : properties.getPassword();

    if (jdbcUrl.isEmpty() || username.isEmpty()) {
      throw new IllegalStateException("partner_db_not_configured");
    }

    String sql = "SELECT result_code FROM nice_cert WHERE userid = ? ORDER BY id DESC LIMIT 1";

    DriverManager.setLoginTimeout(Math.max(1, properties.getQueryTimeoutSeconds()));
    try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, userId);
      ps.setQueryTimeout(Math.max(1, properties.getQueryTimeoutSeconds()));
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        String value = rs.getString(1);
        if (value == null || value.isBlank()) {
          return Optional.empty();
        }
        return Optional.of(value.trim());
      }
    }
  }

  private Optional<String> findPartnerPhoneByPhone(String digitsPhone) throws Exception {
    String jdbcUrl = safeTrim(properties.getJdbcUrl());
    String username = safeTrim(properties.getUsername());
    String password = properties.getPassword() == null ? "" : properties.getPassword();

    if (jdbcUrl.isEmpty() || username.isEmpty()) {
      throw new IllegalStateException("partner_db_not_configured");
    }

    // Partner DB may store phone with hyphens/spaces. Normalize in query.
    String sql =
        "SELECT result_code FROM nice_cert " +
            "WHERE REPLACE(REPLACE(result_code, '-', ''), ' ', '') = ? " +
            "ORDER BY id DESC LIMIT 1";

    DriverManager.setLoginTimeout(Math.max(1, properties.getQueryTimeoutSeconds()));
    try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, digitsPhone);
      ps.setQueryTimeout(Math.max(1, properties.getQueryTimeoutSeconds()));
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        String value = rs.getString(1);
        if (value == null || value.isBlank()) {
          return Optional.empty();
        }
        return Optional.of(value.trim());
      }
    }
  }

  private static String digitsOnly(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    StringBuilder out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c >= '0' && c <= '9') {
        out.append(c);
      }
    }
    return out.toString();
  }

  private static String normalizeKoreanMobile(String digits) {
    if (digits == null || digits.isBlank()) {
      return "";
    }
    String value = digits.trim();
    // Normalize "+82 10..." to "010..."
    if (value.startsWith("82") && value.length() >= 10) {
      String rest = value.substring(2);
      if (rest.startsWith("10")) {
        return "0" + rest;
      }
      return rest;
    }
    // Normalize "10..." to "010..." (missing leading 0)
    if (value.startsWith("10") && value.length() == 10) {
      return "0" + value;
    }
    return value;
  }

  private static String safeTrim(String value) {
    return value == null ? "" : value.trim();
  }

  public record Verification(boolean allowed, String code) {
    public static Verification allow() {
      return new Verification(true, "");
    }

    public static Verification deny(String code) {
      return new Verification(false, code == null ? "denied" : code);
    }
  }
}
