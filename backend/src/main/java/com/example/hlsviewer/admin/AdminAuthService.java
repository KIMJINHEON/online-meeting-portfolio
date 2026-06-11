package com.example.hlsviewer.admin;

import java.time.Duration;
import java.util.Optional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AdminAuthService {
  private static final Duration DEFAULT_TTL = Duration.ofHours(3);

  private final AdminUserRepository userRepository;
  private final AdminSessionRepository sessionRepository;
  private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

  public AdminAuthService(AdminUserRepository userRepository, AdminSessionRepository sessionRepository) {
    this.userRepository = userRepository;
    this.sessionRepository = sessionRepository;
  }

  public Optional<AdminLoginResponse> login(AdminLoginRequest request) {
    String username = request == null ? null : request.username();
    String password = request == null ? null : request.password();
    if (username == null || username.isBlank() || password == null || password.isBlank()) {
      return Optional.empty();
    }

    return userRepository.findByUsername(username.trim())
        .filter(user -> matchesPassword(password, user.passwordHash()))
        .map(user -> {
          AdminSession session = sessionRepository.createSession(user.id(), DEFAULT_TTL);
          return new AdminLoginResponse(
              session.token(),
              user.displayName(),
              session.expiresAt().toEpochMilli()
          );
        });
  }

  private boolean matchesPassword(String raw, String stored) {
    if (stored == null || stored.isBlank()) {
      return false;
    }
    String trimmed = stored.trim();
    if (trimmed.startsWith("$2a$") || trimmed.startsWith("$2b$") || trimmed.startsWith("$2y$")) {
      return passwordEncoder.matches(raw, trimmed);
    }
    return false;
  }

  public Optional<Long> authenticate(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    return sessionRepository.findValidSession(token).map(AdminSession::adminId);
  }

  public void logout(String token) {
    if (token == null || token.isBlank()) {
      return;
    }
    sessionRepository.deleteSession(token);
  }
}
