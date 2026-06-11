package com.example.hlsviewer.admin;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminAuthInterceptor implements HandlerInterceptor {
  private static final String ADMIN_TOKEN_COOKIE = "adminToken";
  private final AdminAuthService authService;

  public AdminAuthInterceptor(AdminAuthService authService) {
    this.authService = authService;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
    String path = request.getRequestURI();
    if (!path.startsWith("/api/admin")) {
      return true;
    }
    if (path.equals("/api/admin/login")) {
      return true;
    }

    String token = extractToken(request);
    Optional<Long> adminId = authService.authenticate(token);
    if (adminId.isEmpty()) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      return false;
    }

    request.setAttribute("adminId", adminId.get());
    return true;
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
}
