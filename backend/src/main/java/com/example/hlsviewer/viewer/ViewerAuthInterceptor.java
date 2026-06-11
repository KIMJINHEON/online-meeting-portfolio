package com.example.hlsviewer.viewer;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ViewerAuthInterceptor implements HandlerInterceptor {
  private static final String VIEWER_TOKEN_COOKIE = "viewerToken";
  private final ViewerAuthService authService;

  public ViewerAuthInterceptor(ViewerAuthService authService) {
    this.authService = authService;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
    String token = extractToken(request);
    Optional<ViewerSession> session = authService.authenticate(token);
    if (session.isEmpty()) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json;charset=UTF-8");
      response.getWriter().write("{\"error\":\"unauthorized\"}");
      return false;
    }

    request.setAttribute("viewerSession", session.get());
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
}

