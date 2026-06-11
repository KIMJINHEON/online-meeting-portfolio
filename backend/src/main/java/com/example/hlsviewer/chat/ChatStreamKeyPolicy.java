package com.example.hlsviewer.chat;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Single-stream era ops control: an admin can pin a fixed list of stream_keys to allow chat for
 * (via `chat.allowed-stream-keys` env). In multi-meeting mode this list is typically left blank,
 * meaning "any non-blank streamKey is permitted" — meeting validation (viewer session match,
 * meeting row existence) is handled elsewhere.
 */
@Service
public class ChatStreamKeyPolicy {
  private final Set<String> allowedStreamKeys;

  public ChatStreamKeyPolicy(
      @Value("${chat.allowed-stream-keys:}") String configuredAllowedKeys) {
    this.allowedStreamKeys = parseConfiguredKeys(configuredAllowedKeys);
  }

  public boolean isAllowed(String streamKey) {
    String normalized = normalizeStreamKey(streamKey);
    if (normalized.isBlank()) {
      return false;
    }
    // No explicit allowlist configured → multi-meeting mode → any non-blank streamKey passes.
    if (allowedStreamKeys.isEmpty()) {
      return true;
    }
    return allowedStreamKeys.contains(normalized);
  }

  public String normalizeStreamKey(String value) {
    return value == null ? "" : value.trim();
  }

  private Set<String> parseConfiguredKeys(String raw) {
    if (raw == null || raw.isBlank()) {
      return Set.of();
    }
    Set<String> result = new LinkedHashSet<>();
    Arrays.stream(raw.split(","))
        .map(this::normalizeStreamKey)
        .filter(v -> !v.isBlank())
        .forEach(result::add);
    return result;
  }
}
