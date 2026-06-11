package com.example.hlsviewer.nice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.hlsviewer.roster.RosterVerifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class NiceAuthService {
  private static final Logger log = LoggerFactory.getLogger(NiceAuthService.class);
  private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
  private static final int KEY_DERIVE_BITS = 512;
  private static final int GCM_TAG_BITS = 128;
  private static final int IV_BYTES = 16;
  private static final Duration SESSION_TTL = Duration.ofMinutes(30);
  private static final Duration RESULT_TTL = Duration.ofHours(2);
  private static final int MAX_ACTIVE_SESSIONS = 5000;
  private static final long CLEANUP_INTERVAL_MS = Duration.ofMinutes(2).toMillis();

  private final NiceProperties properties;
  private final RosterVerifier rosterVerifier;
  private final ObjectMapper objectMapper;
  private final RestTemplate restTemplate;
  private final Map<String, NiceAuthSession> sessions = new ConcurrentHashMap<>();
  private final Map<String, NiceAuthResult> results = new ConcurrentHashMap<>();
  private final AtomicLong lastCleanupEpochMs = new AtomicLong(0);
  private volatile TokenState cachedToken;

  public NiceAuthService(
      NiceProperties properties,
      RosterVerifier rosterVerifier,
      ObjectMapper objectMapper,
      RestTemplateBuilder builder) {
    this.properties = properties;
    this.rosterVerifier = rosterVerifier;
    this.objectMapper = objectMapper;
    this.restTemplate = builder
        .setConnectTimeout(java.time.Duration.ofMillis(properties.getConnectTimeoutMs()))
        .setReadTimeout(java.time.Duration.ofMillis(properties.getReadTimeoutMs()))
        .build();
  }

  public NiceStartResponse start(NiceStartRequest request) {
    ensureEnabled();
    Instant now = Instant.now();
    cleanupIfNeeded(now);
    String streamKey = safe(request.getStreamKey());
    String deviceId = normalizeDeviceId(request.getDeviceId());
    String userId = safe(request.getUserId());
    if (!userId.isBlank() && userId.length() > 9) {
      throw new IllegalStateException("user_id_invalid");
    }
    if (sessions.size() >= MAX_ACTIVE_SESSIONS) {
      throw new IllegalStateException("too_busy_try_again");
    }
    TokenState token = getTokenState();
    String requestNo = generateRequestNo();
    String returnUrl = appendQuery(properties.getReturnUrl(), "request_no", requestNo);
    String closeUrl = appendQuery(properties.getCloseUrl(), "request_no", requestNo);

    AuthUrlResponse authUrl = requestAuthUrl(token.accessToken, requestNo, returnUrl, closeUrl);
    if (!"0000".equals(authUrl.resultCode)) {
      throw new IllegalStateException("NICE auth url failed: " + authUrl.resultMessage);
    }

    NiceAuthSession session = new NiceAuthSession();
    session.requestNo = requestNo;
    session.transactionId = authUrl.transactionId;
    session.accessToken = token.accessToken;
    session.ticket = token.ticket;
    session.iterators = token.iterators;
    session.streamKey = streamKey;
    session.deviceId = deviceId;
    session.createdAt = now;
    sessions.put(requestNo, session);

    return new NiceStartResponse(requestNo, authUrl.authUrl);
  }

  public NiceAuthResult handleCallback(String requestNo, String webTransactionId, boolean success) {
    ensureEnabled();
    Instant now = Instant.now();
    cleanupIfNeeded(now);
    NiceAuthResult result = new NiceAuthResult();
    result.setReqSeq(requestNo);
    result.setVerifiedAt(now);

    NiceAuthSession session = sessions.get(requestNo);
    if (session == null) {
      result.setStatus("FAIL");
      result.setMessage("session_not_found");
      return result;
    }
    result.setStreamKey(session.streamKey);
    result.setDeviceId(session.deviceId);

    if (webTransactionId == null || webTransactionId.isBlank()) {
      result.setStatus("FAIL");
      result.setMessage("web_transaction_id_missing");
      results.put(requestNo, toStoredResult(result));
      sessions.remove(requestNo);
      return result;
    }

    ResultResponse authResult =
        requestAuthResult(session.accessToken, requestNo, session.transactionId, webTransactionId);
    if (!"0000".equals(authResult.resultCode)) {
      result.setStatus("FAIL");
      result.setMessage(authResult.resultMessage);
      results.put(requestNo, toStoredResult(result));
      sessions.remove(requestNo);
      return result;
    }

    try {
      String keyString = deriveKeyString(session.ticket, session.transactionId, session.iterators);
      if (!verifyIntegrity(authResult.encData, authResult.integrityValue, keyString)) {
        result.setStatus("FAIL");
        result.setMessage("integrity_mismatch");
        results.put(requestNo, toStoredResult(result));
        sessions.remove(requestNo);
        return result;
      }

      String json = decryptEncData(authResult.encData, keyString);
      Map<String, Object> payload = objectMapper.readValue(json, new TypeReference<>() {});
      Extracted name = extractFirstNonBlank(payload, "name", "user_name", "userName");
      Extracted birth = extractFirstNonBlank(payload, "birthdate", "birthDate", "birthday", "birth_day", "birth");
      Extracted phone = extractFirstNonBlank(payload, "mobileno", "mobileNo", "mobile_no", "phone", "phoneNo", "phone_no");

      logNicePayloadDiagnostics(payload, name.key, birth.key, phone.key, phone.value);

      result.setName(name.value);
      result.setBirthDate(birth.value);
      result.setPhone(phone.value);
      result.setMobileCo(stringValueAny(payload, "mobileco", "mobileCo", "telco", "carrier"));
      result.setDi(stringValueAny(payload, "di", "DI"));
      result.setCi(stringValueAny(payload, "ci", "CI"));
      result.setResponseNumber(stringValueAny(payload, "responseno", "responseNo", "response_no"));
      if (!success) {
        result.setStatus("FAIL");
        result.setMessage("nice_failed");
        results.put(requestNo, toStoredResult(result));
        sessions.remove(requestNo);
        return result;
      }

      RosterVerifier.Verification verification =
          rosterVerifier.verify(result.getName(), result.getBirthDate(), result.getPhone());
      if (!verification.allowed()) {
        result.setStatus("FAIL");
        result.setMessage(verification.code());
        results.put(requestNo, toStoredResult(result));
        sessions.remove(requestNo);
        return result;
      }

      result.setStatus("SUCCESS");
      result.setMessage("ok");
      results.put(requestNo, toStoredResult(result));
      sessions.remove(requestNo);
      return result;
    } catch (Exception ex) {
      result.setStatus("FAIL");
      result.setMessage("decrypt_failed");
      results.put(requestNo, toStoredResult(result));
      sessions.remove(requestNo);
      return result;
    }
  }

  public NiceAuthResult getResult(String requestNo) {
    cleanupIfNeeded(Instant.now());
    NiceAuthResult result = results.get(requestNo);
    if (result != null) {
      return result;
    }
    NiceAuthResult pending = new NiceAuthResult();
    pending.setReqSeq(requestNo);
    pending.setStatus("PENDING");
    return pending;
  }

  public void overrideResult(String requestNo, String status, String message) {
    if (requestNo == null || requestNo.isBlank()) {
      return;
    }
    NiceAuthResult current = results.get(requestNo);
    NiceAuthResult next = new NiceAuthResult();
    next.setReqSeq(requestNo);
    next.setStreamKey(current == null ? "" : current.getStreamKey());
    next.setDeviceId(current == null ? "" : current.getDeviceId());
    next.setStatus(status == null || status.isBlank() ? "FAIL" : status);
    next.setVerifiedAt(Instant.now());
    next.setMessage(message == null ? "" : message);
    results.put(requestNo, toStoredResult(next));
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
    evictExpired(now);
  }

  private void evictExpired(Instant now) {
    Instant sessionThreshold = now.minus(SESSION_TTL);
    sessions.entrySet().removeIf(entry -> {
      NiceAuthSession session = entry.getValue();
      Instant createdAt = session == null ? null : session.createdAt;
      return createdAt == null || createdAt.isBefore(sessionThreshold);
    });

    Instant resultThreshold = now.minus(RESULT_TTL);
    results.entrySet().removeIf(entry -> {
      NiceAuthResult value = entry.getValue();
      Instant verifiedAt = value == null ? null : value.getVerifiedAt();
      if (verifiedAt == null) {
        return true;
      }
      return verifiedAt.isBefore(resultThreshold);
    });
  }

  private NiceAuthResult toStoredResult(NiceAuthResult result) {
    if (result == null) {
      return null;
    }
    NiceAuthResult stored = new NiceAuthResult();
    stored.setReqSeq(result.getReqSeq());
    stored.setStreamKey(result.getStreamKey());
    stored.setDeviceId(result.getDeviceId());
    stored.setStatus(result.getStatus());
    stored.setVerifiedAt(result.getVerifiedAt());
    stored.setMessage(result.getMessage());
    return stored;
  }

  private TokenState getTokenState() {
    TokenState cached = cachedToken;
    long now = System.currentTimeMillis();
    if (cached != null && cached.expiresAtEpochMs > now + 30000) {
      return cached;
    }
    TokenResponse token = requestToken(generateRequestNo());
    long expiresAt = normalizeExpiresAt(token.expiresIn, now);
    TokenState fresh = new TokenState(
        token.accessToken,
        token.ticket,
        token.iterators,
        expiresAt
    );
    cachedToken = fresh;
    return fresh;
  }

  private TokenResponse requestToken(String requestNo) {
    String url = trimTrailingSlash(properties.getApiBaseUrl()) + "/ido/intc/v1.0/auth/token";
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Authorization", "Basic " + basicAuthHeader());
    headers.set("X-Intc-DevLang", properties.getDevLang());

    Map<String, Object> body = Map.of(
        "grant_type", "client_credentials",
        "request_no", requestNo
    );
    ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
    if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
      throw new IllegalStateException("NICE token request failed.");
    }
    try {
      Map<String, Object> payload = objectMapper.readValue(response.getBody(), new TypeReference<>() {});
      return new TokenResponse(
          stringValue(payload, "result_code"),
          stringValue(payload, "result_message"),
          stringValue(payload, "access_token"),
          longValue(payload, "expires_in"),
          intValue(payload, "iterators"),
          stringValue(payload, "ticket")
      );
    } catch (Exception ex) {
      throw new IllegalStateException("NICE token parse failed.", ex);
    }
  }

  private AuthUrlResponse requestAuthUrl(String accessToken, String requestNo, String returnUrl, String closeUrl) {
    String url = trimTrailingSlash(properties.getApiBaseUrl()) + "/ido/intc/v1.0/auth/url";
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(accessToken);

    Map<String, Object> body = Map.of(
        "request_no", requestNo,
        "return_url", returnUrl,
        "close_url", closeUrl,
        "svc_types", properties.getSvcTypes(),
        "method_type", properties.getMethodType(),
        "exp_mods", properties.getExpMods()
    );

    ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
    if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
      throw new IllegalStateException("NICE auth url request failed.");
    }
    try {
      Map<String, Object> payload = objectMapper.readValue(response.getBody(), new TypeReference<>() {});
      return new AuthUrlResponse(
          stringValue(payload, "result_code"),
          stringValue(payload, "result_message"),
          stringValue(payload, "request_no"),
          stringValue(payload, "auth_url"),
          stringValue(payload, "transaction_id")
      );
    } catch (Exception ex) {
      throw new IllegalStateException("NICE auth url parse failed.", ex);
    }
  }

  private ResultResponse requestAuthResult(
      String accessToken,
      String requestNo,
      String transactionId,
      String webTransactionId
  ) {
    String url = trimTrailingSlash(properties.getApiBaseUrl()) + "/ido/intc/v1.0/auth/result";
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(accessToken);
    Map<String, Object> body = Map.of(
        "request_no", requestNo,
        "transaction_id", transactionId,
        "web_transaction_id", webTransactionId
    );
    ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
    if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
      return new ResultResponse("9999", "http_error", "", "");
    }
    try {
      Map<String, Object> payload = objectMapper.readValue(response.getBody(), new TypeReference<>() {});
      return new ResultResponse(
          stringValue(payload, "result_code"),
          stringValue(payload, "result_message"),
          stringValue(payload, "enc_data"),
          stringValue(payload, "integrity_value")
      );
    } catch (Exception ex) {
      return new ResultResponse("9999", "parse_failed", "", "");
    }
  }

  private String deriveKeyString(String ticket, String transactionId, int iterators) throws GeneralSecurityException {
    PBEKeySpec spec = new PBEKeySpec(
        ticket.toCharArray(),
        transactionId.getBytes(StandardCharsets.UTF_8),
        Math.max(iterators, 1),
        KEY_DERIVE_BITS
    );
    SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
    byte[] keyBytes = factory.generateSecret(spec).getEncoded();
    return BASE64_URL.encodeToString(keyBytes);
  }

  private boolean verifyIntegrity(String encData, String integrityValue, String keyString) throws GeneralSecurityException {
    if (encData == null || encData.isBlank()) {
      return false;
    }
    if (integrityValue == null || integrityValue.isBlank()) {
      return false;
    }
    String hmacKey = keyString.substring(48, 80);
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] digest = mac.doFinal(encData.getBytes(StandardCharsets.UTF_8));
    String expected = BASE64_URL.encodeToString(digest);
    return expected.equals(integrityValue);
  }

  private String decryptEncData(String encData, String keyString) throws GeneralSecurityException {
    byte[] encrypted = BASE64_URL_DECODER.decode(encData);
    ByteBuffer buffer = ByteBuffer.wrap(encrypted);
    byte[] iv = new byte[IV_BYTES];
    buffer.get(iv);
    byte[] cipherText = new byte[buffer.remaining()];
    buffer.get(cipherText);
    String aesKey = keyString.substring(0, 32);
    SecretKeySpec keySpec = new SecretKeySpec(aesKey.getBytes(StandardCharsets.UTF_8), "AES");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
    byte[] decrypted = cipher.doFinal(cipherText);
    return new String(decrypted, StandardCharsets.UTF_8);
  }

  private String basicAuthHeader() {
    String auth = properties.getClientId() + ":" + properties.getClientSecret();
    return BASE64_URL.encodeToString(auth.getBytes(StandardCharsets.UTF_8));
  }

  private String appendQuery(String base, String key, String value) {
    if (base == null || base.isBlank()) {
      return "";
    }
    String separator = base.contains("?") ? "&" : "?";
    return base + separator + key + "=" + value;
  }

  private String generateRequestNo() {
    long now = System.currentTimeMillis();
    int rand = new SecureRandom().nextInt(900000) + 100000;
    return "REQ" + now + rand;
  }

  private long normalizeExpiresAt(long raw, long now) {
    if (raw > 1_000_000_000_000L) {
      return raw;
    }
    if (raw <= 0) {
      return now + 3600_000L;
    }
    return now + raw * 1000L;
  }

  private String trimTrailingSlash(String value) {
    if (value == null) {
      return "";
    }
    if (value.endsWith("/")) {
      return value.substring(0, value.length() - 1);
    }
    return value;
  }

  private String stringValue(Map<String, Object> payload, String key) {
    Object value = payload.get(key);
    return value == null ? "" : String.valueOf(value);
  }

  private long longValue(Map<String, Object> payload, String key) {
    Object value = payload.get(key);
    if (value instanceof Number number) {
      return number.longValue();
    }
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (Exception ex) {
      return 0L;
    }
  }

  private int intValue(Map<String, Object> payload, String key) {
    Object value = payload.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (Exception ex) {
      return 0;
    }
  }

  private void ensureEnabled() {
    if (!properties.isEnabled()) {
      throw new IllegalStateException("NICE auth is disabled.");
    }
    if (isBlank(properties.getClientId()) || isBlank(properties.getClientSecret())) {
      throw new IllegalStateException("NICE client id/secret is not configured.");
    }
    if (isBlank(properties.getReturnUrl()) || isBlank(properties.getCloseUrl())) {
      throw new IllegalStateException("NICE callback URLs are not configured.");
    }
  }

  private String safe(String value) {
    return value == null ? "" : value.trim();
  }

  private String normalizeDeviceId(String value) {
    String trimmed = safe(value);
    if (trimmed.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < trimmed.length() && out.length() < 64; i++) {
      char ch = trimmed.charAt(i);
      if ((ch >= 'a' && ch <= 'z')
          || (ch >= 'A' && ch <= 'Z')
          || (ch >= '0' && ch <= '9')
          || ch == '-'
          || ch == '_') {
        out.append(ch);
      }
    }
    return out.toString();
  }

  private boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private void logNicePayloadDiagnostics(
      Map<String, Object> payload,
      String extractedNameKey,
      String extractedBirthKey,
      String extractedPhoneKey,
      String extractedPhoneValue) {
    if (payload == null || payload.isEmpty()) {
      log.warn("NICE payload is empty.");
      return;
    }

    // Log keys only (no PII values) to diagnose field-name mismatches safely.
    try {
      var keys = payload.keySet().stream().sorted().toList();
      log.info("NICE payload keys: {}", keys);
    } catch (Exception ignored) {
      // best-effort logging only
    }

    boolean hasName = extractedNameKey != null && !extractedNameKey.isBlank();
    boolean hasBirth = extractedBirthKey != null && !extractedBirthKey.isBlank();
    boolean hasPhone = extractedPhoneKey != null && !extractedPhoneKey.isBlank();
    int phoneDigitCount = countDigits(extractedPhoneValue);

    log.info(
        "NICE extracted keys: nameKey={}, birthKey={}, phoneKey={} (present name={}, birth={}, phone={}, phoneDigits={})",
        safeKey(extractedNameKey),
        safeKey(extractedBirthKey),
        safeKey(extractedPhoneKey),
        hasName,
        hasBirth,
        hasPhone,
        phoneDigitCount
    );
  }

  private String safeKey(String value) {
    String key = value == null ? "" : value.trim();
    return key.isEmpty() ? "-" : key;
  }

  private int countDigits(Object value) {
    if (value == null) {
      return 0;
    }
    String s = String.valueOf(value);
    int count = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c >= '0' && c <= '9') {
        count++;
      }
    }
    return count;
  }

  private record Extracted(String key, String value) {}

  private Extracted extractFirstNonBlank(Map<String, Object> payload, String... keys) {
    if (payload == null || payload.isEmpty() || keys == null) {
      return new Extracted("", "");
    }
    for (String key : keys) {
      if (key == null || key.isBlank()) {
        continue;
      }
      Object value = payload.get(key);
      if (value == null) {
        continue;
      }
      String s = String.valueOf(value).trim();
      if (!s.isEmpty()) {
        return new Extracted(key, s);
      }
    }
    // As a last resort, try case-insensitive lookup.
    for (String key : keys) {
      if (key == null || key.isBlank()) {
        continue;
      }
      Object value = getCaseInsensitive(payload, key);
      if (value == null) {
        continue;
      }
      String s = String.valueOf(value).trim();
      if (!s.isEmpty()) {
        return new Extracted(key + "(ci)", s);
      }
    }
    return new Extracted("", "");
  }

  private Object getCaseInsensitive(Map<String, Object> payload, String key) {
    if (payload == null || key == null) {
      return null;
    }
    for (Map.Entry<String, Object> entry : payload.entrySet()) {
      if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
        return entry.getValue();
      }
    }
    return null;
  }

  private String stringValueAny(Map<String, Object> payload, String... keys) {
    Extracted extracted = extractFirstNonBlank(payload, keys);
    return extracted.value;
  }

  private record TokenResponse(
      String resultCode,
      String resultMessage,
      String accessToken,
      long expiresIn,
      int iterators,
      String ticket
  ) {}

  private record AuthUrlResponse(
      String resultCode,
      String resultMessage,
      String requestNo,
      String authUrl,
      String transactionId
  ) {}

  private record ResultResponse(
      String resultCode,
      String resultMessage,
      String encData,
      String integrityValue
  ) {}

  private record TokenState(String accessToken, String ticket, int iterators, long expiresAtEpochMs) {}

  private static class NiceAuthSession {
    private String requestNo;
    private String transactionId;
    private String accessToken;
    private String ticket;
    private int iterators;
    private String streamKey;
    private String deviceId;
    private Instant createdAt;
  }
}
