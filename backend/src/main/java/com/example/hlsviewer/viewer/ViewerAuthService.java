package com.example.hlsviewer.viewer;

import com.example.hlsviewer.nice.NiceAuthResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ViewerAuthService {
  private static final Logger log = LoggerFactory.getLogger(ViewerAuthService.class);
  private static final Duration DEFAULT_TTL = Duration.ofHours(5);
  private static final long CLEANUP_INTERVAL_MS = Duration.ofMinutes(5).toMillis();
  private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
  private static final HexFormat HEX = HexFormat.of();

  private final SecureRandom random = new SecureRandom();
  private final ViewerAccessLogRepository accessLogRepository;
  private final Duration staleSessionTimeout;
  private final long lastSeenWriteIntervalMs;
  private final ConcurrentHashMap<String, ViewerSession> sessions = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Long> lastSeenWriteEpochMs = new ConcurrentHashMap<>();
  private final AtomicLong lastCleanupEpochMs = new AtomicLong(0);

  public ViewerAuthService(
      ViewerAccessLogRepository accessLogRepository,
      @Value("${viewer.session.stale-timeout-ms:900000}") long staleTimeoutMs,
      @Value("${viewer.session.last-seen-write-interval-ms:30000}") long lastSeenWriteIntervalMs) {
    this.accessLogRepository = accessLogRepository;
    this.staleSessionTimeout = Duration.ofMillis(Math.max(60_000L, staleTimeoutMs));
    this.lastSeenWriteIntervalMs = Math.max(1_000L, lastSeenWriteIntervalMs);
  }

  public synchronized ViewerSession createSession(NiceAuthResult result) {
    Instant now = Instant.now();
    cleanupIfNeeded(now);
    String token = generateToken();
    String streamKey = normalizeStreamKey(result == null ? null : result.getStreamKey());
    String name = normalizeName(safe(result == null ? null : result.getName()));
    String birth = normalizeBirth(safe(result == null ? null : result.getBirthDate()));
    String phone = normalizePhone(safe(result == null ? null : result.getPhone()));
    String deviceId = normalizeDeviceId(safe(result == null ? null : result.getDeviceId()));
    if (!name.isEmpty() && !birth.isEmpty() && !phone.isEmpty()) {
      try {
        if (accessLogRepository.existsOpenSessionOnOtherDevice(name, birth, phone, deviceId)) {
          throw new IllegalStateException("already_connected");
        }
      } catch (IllegalStateException ex) {
        throw ex;
      } catch (Exception ex) {
        log.warn("Failed to validate concurrent session: {}", ex.getMessage());
        throw new IllegalStateException("session_check_failed");
      }
    }
    ViewerSession session = new ViewerSession(
        token,
        now.plus(DEFAULT_TTL),
        streamKey,
        name,
        birth,
        phone
    );
    sessions.put(token, session);
    lastSeenWriteEpochMs.put(token, now.toEpochMilli());
    try {
      accessLogRepository.startSession(streamKey, hashToken(token), name, birth, phone, deviceId, now);
    } catch (Exception ex) {
      log.warn("Failed to start access log: {}", ex.getMessage());
    }
    return session;
  }

  public Optional<ViewerSession> authenticate(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    Instant now = Instant.now();
    cleanupIfNeeded(now);
    ViewerSession session = sessions.get(token);
    if (session == null) {
      return Optional.empty();
    }
    if (session.expiresAt() == null || session.expiresAt().isBefore(now)) {
      sessions.remove(token);
      lastSeenWriteEpochMs.remove(token);
      closeAccessLog(token, now, "expired");
      return Optional.empty();
    }
    touchSessionIfNeeded(token, now);
    return Optional.of(session);
  }

  public void logout(String token, String reason) {
    if (token == null || token.isBlank()) {
      return;
    }
    sessions.remove(token);
    lastSeenWriteEpochMs.remove(token);
    String trimmed = reason == null ? "" : reason.trim();
    closeAccessLog(token, Instant.now(), trimmed.isEmpty() ? "logout" : trimmed);
  }

  /**
   * Replaces the streamKey on an existing viewer session in place, after the viewer chooses a meeting.
   * Returns the updated session, or empty if the token is unknown or expired.
   */
  public synchronized Optional<ViewerSession> updateStreamKey(String token, String streamKey) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    ViewerSession current = sessions.get(token);
    if (current == null) {
      return Optional.empty();
    }
    Instant now = Instant.now();
    if (current.expiresAt() == null || current.expiresAt().isBefore(now)) {
      sessions.remove(token);
      lastSeenWriteEpochMs.remove(token);
      closeAccessLog(token, now, "expired");
      return Optional.empty();
    }
    String cleanKey = normalizeStreamKey(streamKey);
    if (cleanKey.equals(current.streamKey())) {
      return Optional.of(current);
    }
    ViewerSession updated = new ViewerSession(
        current.token(),
        current.expiresAt(),
        cleanKey,
        current.name(),
        current.birthDate(),
        current.phone()
    );
    sessions.put(token, updated);
    try {
      accessLogRepository.updateStreamKey(hashToken(token), cleanKey);
    } catch (Exception ex) {
      log.warn("Failed to rewrite access_log stream_key for session: {}", ex.getMessage());
    }
    return Optional.of(updated);
  }

  public void disconnect(String token, String reason) {
    if (token == null || token.isBlank()) {
      return;
    }
    closeAccessLog(token, Instant.now(), normalizeReason(reason));
  }

  public synchronized int invalidateSessionsByStreamKey(String streamKey, String reason) {
    String key = normalizeStreamKey(streamKey);
    String normalizedReason = normalizeReason(reason);
    Instant now = Instant.now();
    List<String> targetTokens = new ArrayList<>();
    sessions.forEach((token, session) -> {
      if (session == null) {
        return;
      }
      String sessionKey = normalizeStreamKey(session.streamKey());
      if (key.equals(sessionKey)) {
        targetTokens.add(token);
      }
    });
    int closed = 0;
    for (String token : targetTokens) {
      ViewerSession removed = sessions.remove(token);
      if (removed == null) {
        continue;
      }
      lastSeenWriteEpochMs.remove(token);
      closeAccessLog(token, now, normalizedReason);
      closed++;
    }
    if (closed > 0) {
      log.info("Invalidated {} viewer sessions for streamKey={} ({})", closed, key, normalizedReason);
    }
    return closed;
  }

  public synchronized void resetViewerStateForRosterReplace(String streamKey) {
    String key = normalizeStreamKey(streamKey);
    int invalidated = invalidateSessionsByStreamKey(key, "roster_replaced");
    int deletedLogs = 0;
    try {
      deletedLogs = accessLogRepository.deleteByStreamKey(key);
    } catch (Exception ex) {
      log.warn("Failed to clear access logs after roster replace: {}", ex.getMessage());
    }
    log.info(
        "Roster replaced: streamKey={}, invalidatedSessions={}, deletedAccessLogs={}",
        key,
        invalidated,
        Math.max(0, deletedLogs));
  }

  public void recoverRecentBrowserExit(String token) {
    if (token == null || token.isBlank()) {
      return;
    }
    Instant now = Instant.now();
    try {
      accessLogRepository.reopenBrowserExit(hashToken(token), now);
    } catch (Exception ex) {
      log.warn("Failed to reopen browser-exit access log: {}", ex.getMessage());
    }
  }

  public void markFirstPlay(String token) {
    if (token == null || token.isBlank()) {
      return;
    }
    Instant now = Instant.now();
    try {
      accessLogRepository.markFirstPlay(hashToken(token), now);
    } catch (Exception ex) {
      log.warn("Failed to mark first-play access log: {}", ex.getMessage());
    }
  }

  public long countActiveSessions() {
    Instant now = Instant.now();
    cleanupIfNeeded(now);
    evictExpiredSessions(now);
    return sessions.size();
  }

  public long countUniqueViewers(String streamKey) {
    Instant now = Instant.now();
    cleanupIfNeeded(now);
    evictExpiredSessions(now);
    String targetKey = normalizeStreamKey(streamKey);
    Set<String> phones = new HashSet<>();
    for (ViewerSession session : sessions.values()) {
      if (session == null) {
        continue;
      }
      String sessionKey = normalizeStreamKey(session.streamKey());
      if (!sessionKey.equals(targetKey)) {
        continue;
      }
      String key = normalizePhoneKey(session.phone());
      if (key.isEmpty()) {
        key = session.token(); // fallback to keep count stable
      }
      phones.add(key);
    }
    return phones.size();
  }

  private void cleanupIfNeeded(Instant now) {
    long nowMs = now.toEpochMilli();
    long last = lastCleanupEpochMs.get();
    if (nowMs - last < CLEANUP_INTERVAL_MS) {
      return;
    }
    if (!lastCleanupEpochMs.compareAndSet(last, nowMs)) {
      return;
    }
    evictExpiredSessions(now);
    closeStaleAccessLogs(now);
  }

  @Scheduled(fixedDelayString = "${viewer.session.stale-close-interval-ms:300000}")
  public void scheduledCloseStaleAccessLogs() {
    closeStaleAccessLogs(Instant.now());
  }

  private String generateToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return BASE64_URL.encodeToString(bytes);
  }

  private void evictExpiredSessions(Instant now) {
    sessions.forEach((token, session) -> {
      Instant expiresAt = session == null ? null : session.expiresAt();
      if (expiresAt == null || expiresAt.isBefore(now)) {
        if (sessions.remove(token, session)) {
          lastSeenWriteEpochMs.remove(token);
          closeAccessLog(token, now, "expired");
        }
      }
    });
  }

  private void touchSessionIfNeeded(String token, Instant now) {
    long nowMs = now.toEpochMilli();
    Long lastMs = lastSeenWriteEpochMs.get(token);
    if (lastMs != null && nowMs - lastMs < lastSeenWriteIntervalMs) {
      return;
    }
    lastSeenWriteEpochMs.put(token, nowMs);
    try {
      accessLogRepository.touchSession(hashToken(token), now);
    } catch (Exception ex) {
      log.warn("Failed to touch access log: {}", ex.getMessage());
    }
  }

  private void closeStaleAccessLogs(Instant now) {
    Instant staleBefore = now.minus(staleSessionTimeout);
    try {
      int closed = accessLogRepository.closeStaleSessions(staleBefore, "timeout");
      if (closed > 0) {
        log.info("Closed {} stale access sessions (before {})", closed, staleBefore);
      }
    } catch (Exception ex) {
      log.warn("Failed to close stale access logs: {}", ex.getMessage());
    }
  }

  private void closeAccessLog(String token, Instant endedAt, String reason) {
    try {
      accessLogRepository.endSession(hashToken(token), endedAt, reason);
    } catch (Exception ex) {
      log.warn("Failed to end access log: {}", ex.getMessage());
    }
  }

  private String hashToken(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashed = digest.digest((token == null ? "" : token).getBytes(StandardCharsets.UTF_8));
      return HEX.formatHex(hashed);
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 digest unavailable", ex);
    }
  }

  private String normalizePhoneKey(String value) {
    String digits = digitsOnly(value);
    if (digits.isEmpty()) {
      return "";
    }
    if (digits.startsWith("82") && digits.length() >= 10) {
      String rest = digits.substring(2);
      if (rest.startsWith("10")) {
        return "0" + rest;
      }
      return rest;
    }
    if (digits.startsWith("10") && digits.length() == 10) {
      return "0" + digits;
    }
    return digits;
  }

  private String normalizeStreamKey(String value) {
    String trimmed = value == null ? "" : value.trim();
    return trimmed.isEmpty() ? "stream" : trimmed;
  }

  private String normalizeDeviceId(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    if (trimmed.length() <= 64) {
      return trimmed;
    }
    return trimmed.substring(0, 64);
  }

  private String normalizeName(String value) {
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

  private String normalizeBirth(String value) {
    String digits = digitsOnly(value);
    if (digits.isEmpty()) {
      return "";
    }
    if (digits.length() >= 6) {
      return digits.substring(digits.length() - 6);
    }
    return digits;
  }

  private String normalizePhone(String value) {
    String digits = normalizePhoneKey(value);
    if (digits.length() == 11 && digits.startsWith("01")) {
      return digits.substring(0, 3) + "-" + digits.substring(3, 7) + "-" + digits.substring(7);
    }
    if (digits.length() == 10 && digits.startsWith("01")) {
      return digits.substring(0, 3) + "-" + digits.substring(3, 6) + "-" + digits.substring(6);
    }
    return "";
  }

  private String digitsOnly(String value) {
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

  private String safe(String value) {
    return value == null ? "" : value;
  }

  private String normalizeReason(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      return "disconnect";
    }
    if (trimmed.length() <= 20) {
      return trimmed;
    }
    return trimmed.substring(0, 20);
  }
}
