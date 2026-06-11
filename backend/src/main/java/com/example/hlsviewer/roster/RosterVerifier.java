package com.example.hlsviewer.roster;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RosterVerifier {
  private static final Logger log = LoggerFactory.getLogger(RosterVerifier.class);

  private final RosterRepository rosterRepository;

  public RosterVerifier(RosterRepository rosterRepository) {
    this.rosterRepository = rosterRepository;
  }

  public Verification verify(String name, String birthDate, String phone) {
    String nameKey = normalizeName(name);
    String birthKey = normalizeBirth(birthDate);
    String phoneDigits = normalizePhone(phone);
    String phoneKey = formatPhoneHyphen(phone);

    if (nameKey.isEmpty()) {
      return Verification.deny("nice_missing_name");
    }
    if (birthKey.isEmpty()) {
      return Verification.deny("nice_missing_birth");
    }
    if (birthKey.length() != 6) {
      return Verification.deny("nice_invalid_birth");
    }
    if (phoneDigits.isEmpty()) {
      return Verification.deny("nice_missing_phone");
    }
    if (phoneKey.isEmpty()) {
      // Most common reason: number is masked/partial (e.g., 010****1234).
      if (phoneDigits.length() < 10) {
        return Verification.deny("nice_phone_partial");
      }
      return Verification.deny("nice_invalid_phone");
    }

    try {
      if (rosterRepository.countAll() == 0) {
        return Verification.deny("roster_empty");
      }
      boolean ok = rosterRepository.existsMatch(nameKey, phoneKey, birthKey);
      return ok ? Verification.allow() : Verification.deny("user_not_found");
    } catch (Exception ex) {
      log.warn("Roster verify failed: {}", ex.getMessage());
      return Verification.deny("roster_db_error");
    }
  }

  static String normalizeStreamKey(String value) {
    String trimmed = value == null ? "" : value.trim();
    return trimmed.isEmpty() ? "stream" : trimmed;
  }

  public static String normalizeName(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder(trimmed.length());
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (!Character.isWhitespace(c)) {
        out.append(c);
      }
    }
    return out.toString();
  }

  public static String normalizeBirth(String value) {
    String digits = digitsOnly(value);
    if (digits.isEmpty()) {
      return "";
    }
    // Excel may store dates as a serial number (1900 date system). Try to convert common ranges.
    if (digits.length() <= 5) {
      try {
        int serial = Integer.parseInt(digits);
        if (serial >= 20000 && serial <= 80000) {
          LocalDate base = LocalDate.of(1899, 12, 31);
          int days = serial;
          if (days >= 60) {
            days -= 1; // Excel 1900 leap-year bug compensation
          }
          LocalDate date = base.plusDays(days);
          return date.format(DateTimeFormatter.ofPattern("yyMMdd"));
        }
      } catch (Exception ignored) {
        // fall back to plain digits
      }
    }
    if (digits.length() >= 6) {
      return digits.substring(digits.length() - 6);
    }
    return digits;
  }

  static String normalizePhone(String value) {
    String digits = digitsOnly(value);
    if (digits.isEmpty()) {
      return "";
    }
    return normalizeKoreanMobile(digits);
  }

  public static String formatPhoneHyphen(String value) {
    String digits = normalizePhone(value);
    if (digits.isEmpty()) {
      return "";
    }
    if (digits.length() == 11 && digits.startsWith("01")) {
      return digits.substring(0, 3) + "-" + digits.substring(3, 7) + "-" + digits.substring(7);
    }
    if (digits.length() == 10 && digits.startsWith("01")) {
      return digits.substring(0, 3) + "-" + digits.substring(3, 6) + "-" + digits.substring(6);
    }
    return "";
  }

  static String digitsOnly(String value) {
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

  static String normalizeKoreanMobile(String digits) {
    if (digits == null || digits.isBlank()) {
      return "";
    }
    String value = digits.trim();
    if (value.startsWith("82") && value.length() >= 10) {
      String rest = value.substring(2);
      if (rest.startsWith("10")) {
        return "0" + rest;
      }
      return rest;
    }
    if (value.startsWith("10") && value.length() == 10) {
      return "0" + value;
    }
    return value;
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
