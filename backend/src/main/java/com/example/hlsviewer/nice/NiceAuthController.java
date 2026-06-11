package com.example.hlsviewer.nice;

import com.example.hlsviewer.roster.RosterRepository;
import com.example.hlsviewer.viewer.ViewerAuthService;
import com.example.hlsviewer.viewer.ViewerSession;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/nice")
public class NiceAuthController {
  private static final String VIEWER_TOKEN_COOKIE = "viewerToken";
  private static final Logger log = LoggerFactory.getLogger(NiceAuthController.class);
  private final NiceAuthService authService;
  private final ViewerAuthService viewerAuthService;
  private final RosterRepository rosterRepository;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public NiceAuthController(
      NiceAuthService authService,
      ViewerAuthService viewerAuthService,
      RosterRepository rosterRepository) {
    this.authService = authService;
    this.viewerAuthService = viewerAuthService;
    this.rosterRepository = rosterRepository;
  }

  @PostMapping("/start")
  public ResponseEntity<?> start(@RequestBody(required = false) NiceStartRequest request) {
    try {
      NiceStartRequest safeRequest = request == null ? new NiceStartRequest() : request;
      return ResponseEntity.ok(authService.start(safeRequest));
    } catch (IllegalStateException ex) {
      return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
  }

  @GetMapping("/result/{requestNo}")
  public NiceAuthPublicResult result(@PathVariable String requestNo) {
    return NiceAuthPublicResult.from(authService.getResult(requestNo));
  }

  @RequestMapping(value = "/callback/success", method = {RequestMethod.GET, RequestMethod.POST})
  public ResponseEntity<String> success(
      HttpServletRequest request,
      @RequestParam(name = "web_transaction_id", required = false) String webTransactionId,
      @RequestParam(name = "request_no", required = false) String requestNo) {
    return buildCallbackResponse(request, requestNo, webTransactionId, true);
  }

  @RequestMapping(value = "/callback/fail", method = {RequestMethod.GET, RequestMethod.POST})
  public ResponseEntity<String> fail(
      HttpServletRequest request,
      @RequestParam(name = "web_transaction_id", required = false) String webTransactionId,
      @RequestParam(name = "request_no", required = false) String requestNo) {
    return buildCallbackResponse(request, requestNo, webTransactionId, false);
  }

  private ResponseEntity<String> buildCallbackResponse(
      HttpServletRequest request,
      String requestNo,
      String webTransactionId,
      boolean success) {
    if (requestNo == null || requestNo.isBlank()) {
      return ResponseEntity.badRequest().contentType(MediaType.TEXT_HTML).body("<html>Invalid request</html>");
    }
    NiceAuthResult result = authService.handleCallback(requestNo, webTransactionId, success);
    Optional<ViewerSession> viewerSession = Optional.empty();
    if ("SUCCESS".equalsIgnoreCase(result.getStatus())) {
      try {
        viewerSession = Optional.of(viewerAuthService.createSession(result));
        String clientIp = resolveClientIp(request);
        viewerSession.ifPresent(session -> {
          try {
            rosterRepository.updateIpAddress(session.name(), session.birthDate(), session.phone(), clientIp);
          } catch (Exception ex) {
            log.warn("Failed to update roster IP address: {}", ex.getMessage());
          }
        });
      } catch (IllegalStateException ex) {
        result.setStatus("FAIL");
        result.setMessage(ex.getMessage());
        authService.overrideResult(requestNo, "FAIL", ex.getMessage());
      }
    }
    String payload = toSafeJson(Map.of(
        "type", "nice_auth",
        "requestNo", result.getReqSeq(),
        "status", result.getStatus()
    ));
    String html =
        "<!doctype html><html><head><meta charset=\"utf-8\"></head><body>" +
            "<script>" +
            "const payload = " + payload + ";" +
            // Mobile often runs the flow in the same tab (no opener), and window.close() is blocked.
            // In that case, redirect back to the app with the requestNo so the SPA can resume.
            "const qs = new URLSearchParams({ niceRequestNo: payload.requestNo || '', niceStatus: payload.status || '' });" +
            "const redirectUrl = window.location.origin + '/?' + qs.toString();" +
            "try { if (window.opener && !window.opener.closed) { window.opener.postMessage(payload, window.location.origin); } } catch (e) {}" +
            "try { window.close(); } catch (e) {}" +
            "setTimeout(() => { try { window.location.replace(redirectUrl); } catch (e) {} }, 200);" +
            "</script>" +
            "<noscript>" +
            "<p>Authentication complete. <a href=\"/\">Return to the app</a>.</p>" +
            "</noscript>" +
            "</body></html>";
    ResponseEntity.BodyBuilder builder = ResponseEntity.ok().contentType(MediaType.TEXT_HTML);
    if (viewerSession.isPresent()) {
      boolean secure = isSecureRequest(request);
      long maxAgeSeconds = Math.max(
          0,
          (viewerSession.get().expiresAt().toEpochMilli() - System.currentTimeMillis()) / 1000);
      ResponseCookie cookie = ResponseCookie.from(VIEWER_TOKEN_COOKIE, viewerSession.get().token())
          .httpOnly(true)
          .secure(secure)
          .path("/api")
          .sameSite("Lax")
          .maxAge(Duration.ofSeconds(maxAgeSeconds))
          .build();
      builder.header(HttpHeaders.SET_COOKIE, cookie.toString());
    }
    return builder.body(html);
  }

  private String toSafeJson(Map<String, String> payload) {
    try {
      String json = objectMapper.writeValueAsString(payload);
      // prevent breaking out of the script tag
      return json.replace("</", "<\\/");
    } catch (JsonProcessingException ex) {
      return "{}";
    }
  }

  private boolean isSecureRequest(HttpServletRequest request) {
    String forwardedProto = request.getHeader("X-Forwarded-Proto");
    if (forwardedProto != null && !forwardedProto.isBlank()) {
      return forwardedProto.equalsIgnoreCase("https");
    }
    return request.isSecure();
  }

  private String resolveClientIp(HttpServletRequest request) {
    String forwardedFor = normalizeIpToken(request.getHeader("X-Forwarded-For"));
    if (!forwardedFor.isEmpty()) {
      return forwardedFor;
    }

    String realIp = normalizeIpToken(request.getHeader("X-Real-IP"));
    if (!realIp.isEmpty()) {
      return realIp;
    }

    return normalizeIpToken(request.getRemoteAddr());
  }

  private String normalizeIpToken(String value) {
    if (value == null) {
      return "";
    }
    String token = value;
    int comma = token.indexOf(',');
    if (comma >= 0) {
      token = token.substring(0, comma);
    }
    String trimmed = token.trim();
    if (trimmed.isEmpty() || "unknown".equalsIgnoreCase(trimmed)) {
      return "";
    }
    // [IPv6]:port -> IPv6
    if (trimmed.startsWith("[") && trimmed.contains("]")) {
      int end = trimmed.indexOf(']');
      trimmed = trimmed.substring(1, end).trim();
    } else {
      // IPv4:port -> IPv4
      int lastColon = trimmed.lastIndexOf(':');
      int lastDot = trimmed.lastIndexOf('.');
      if (lastColon > lastDot && lastDot >= 0) {
        String port = trimmed.substring(lastColon + 1);
        if (port.matches("^[0-9]{1,5}$")) {
          trimmed = trimmed.substring(0, lastColon);
        }
      }
    }

    String lower = trimmed.toLowerCase(Locale.ROOT);
    if (lower.startsWith("::ffff:") && trimmed.substring(7).contains(".")) {
      trimmed = trimmed.substring(7);
    }

    if (trimmed.length() <= 64) {
      return trimmed;
    }
    return trimmed.substring(0, 64);
  }
}
