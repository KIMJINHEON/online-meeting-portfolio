package com.example.hlsviewer.admin;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {
  private static final String ADMIN_TOKEN_COOKIE = "adminToken";
  private final AdminAuthService authService;

  public AdminAuthController(AdminAuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/login")
  public ResponseEntity<?> login(HttpServletRequest request, @RequestBody(required = false) AdminLoginRequest body) {
    return authService.login(body)
        .<ResponseEntity<?>>map(login -> {
          long maxAgeSeconds = Math.max(0, (login.expiresAtEpochMs() - System.currentTimeMillis()) / 1000);
          boolean secure = isSecureRequest(request);
          ResponseCookie cookie = ResponseCookie.from(ADMIN_TOKEN_COOKIE, login.token())
              .httpOnly(true)
              .secure(secure)
              .path("/api/admin")
              .sameSite("Lax")
              .maxAge(Duration.ofSeconds(maxAgeSeconds))
              .build();
          return ResponseEntity.ok()
              .header(HttpHeaders.SET_COOKIE, cookie.toString())
              .body(Map.of(
                  "displayName", login.displayName(),
                  "expiresAtEpochMs", login.expiresAtEpochMs()
              ));
        })
        .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("error", "invalid_credentials")));
  }

  @PostMapping("/logout")
  public ResponseEntity<?> logout(HttpServletRequest request) {
    String token = extractToken(request);
    authService.logout(token);
    ResponseCookie cookie = ResponseCookie.from(ADMIN_TOKEN_COOKIE, "")
        .httpOnly(true)
        .secure(isSecureRequest(request))
        .path("/api/admin")
        .sameSite("Lax")
        .maxAge(Duration.ZERO)
        .build();
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .build();
  }

  @GetMapping("/me")
  public ResponseEntity<?> me(HttpServletRequest request) {
    String token = extractToken(request);
    return authService.authenticate(token)
        .<ResponseEntity<?>>map(adminId -> ResponseEntity.ok(Map.of("adminId", adminId)))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("error", "unauthorized")));
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
      if (ADMIN_TOKEN_COOKIE.equals(cookie.getName())) {
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
