package com.example.hlsviewer.viewer;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class ViewerAuthController {
  private static final String VIEWER_TOKEN_COOKIE = "viewerToken";
  private final ViewerAuthService authService;

  public ViewerAuthController(ViewerAuthService authService) {
    this.authService = authService;
  }

  @GetMapping("/me")
  public Map<String, Object> me(HttpServletRequest request) {
    String token = extractToken(request);
    return authService.authenticate(token)
        .<Map<String, Object>>map(session -> {
          // pagehide/beforeunload can fire for refresh or close; reopen the access log if the viewer returns.
          authService.recoverRecentBrowserExit(token);
          return Map.of(
              "authenticated", true,
              "expiresAtEpochMs", session.expiresAt().toEpochMilli()
          );
        })
        .orElseGet(() -> Map.of("authenticated", false));
  }

  @PostMapping("/logout")
  public ResponseEntity<?> logout(
      HttpServletRequest request,
      @RequestParam(name = "reason", required = false) String reason) {
    String token = extractToken(request);
    authService.logout(token, reason);
    ResponseCookie cookie = ResponseCookie.from(VIEWER_TOKEN_COOKIE, "")
        .httpOnly(true)
        .secure(isSecureRequest(request))
        .path("/api")
        .sameSite("Lax")
        .maxAge(Duration.ZERO)
        .build();
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .build();
  }

  @PostMapping("/disconnect")
  public ResponseEntity<?> disconnect(
      HttpServletRequest request,
      @RequestParam(name = "reason", required = false) String reason) {
    String token = extractToken(request);
    authService.disconnect(token, reason);
    return ResponseEntity.noContent().build();
  }

  private String extractToken(HttpServletRequest request) {
    String headerToken = extractBearerToken(request);
    if (headerToken != null) {
      return headerToken;
    }
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (VIEWER_TOKEN_COOKIE.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }

  private String extractBearerToken(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header == null) {
      return null;
    }
    if (header.startsWith("Bearer ")) {
      return header.substring("Bearer ".length()).trim();
    }
    return null;
  }

  private boolean isSecureRequest(HttpServletRequest request) {
    String forwardedProto = request.getHeader("X-Forwarded-Proto");
    if (forwardedProto != null && !forwardedProto.isBlank()) {
      return forwardedProto.equalsIgnoreCase("https");
    }
    return request.isSecure();
  }
}
