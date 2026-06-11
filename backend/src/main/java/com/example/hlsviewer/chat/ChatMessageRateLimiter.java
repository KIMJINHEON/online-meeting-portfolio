package com.example.hlsviewer.chat;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChatMessageRateLimiter {
  private static final long CLEANUP_INTERVAL_MS = 300_000L;

  private final long minIntervalMs;
  private final ConcurrentHashMap<String, Long> nextAllowedEpochMs = new ConcurrentHashMap<>();
  private final AtomicLong lastCleanupEpochMs = new AtomicLong(0L);

  public ChatMessageRateLimiter(
      @Value("${chat.message.min-interval-ms:10000}") long minIntervalMs) {
    this.minIntervalMs = Math.max(0L, minIntervalMs);
  }

  public Result tryAcquire(String key) {
    if (key == null || key.isBlank() || minIntervalMs <= 0L) {
      return Result.permit();
    }

    long nowMs = Instant.now().toEpochMilli();
    AtomicReference<Result> resultRef = new AtomicReference<>(Result.permit());
    nextAllowedEpochMs.compute(key, (ignored, previousNextAllowed) -> {
      long nextAllowed = previousNextAllowed == null ? 0L : previousNextAllowed;
      if (nextAllowed > nowMs) {
        resultRef.set(Result.blocked(nextAllowed - nowMs));
        return nextAllowed;
      }
      resultRef.set(Result.permit());
      return nowMs + minIntervalMs;
    });

    cleanupIfNeeded(nowMs);
    return resultRef.get();
  }

  private void cleanupIfNeeded(long nowMs) {
    long last = lastCleanupEpochMs.get();
    if (nowMs - last < CLEANUP_INTERVAL_MS) {
      return;
    }
    if (!lastCleanupEpochMs.compareAndSet(last, nowMs)) {
      return;
    }
    for (Map.Entry<String, Long> entry : nextAllowedEpochMs.entrySet()) {
      Long value = entry.getValue();
      if (value == null || value <= nowMs) {
        nextAllowedEpochMs.remove(entry.getKey(), value);
      }
    }
  }

  public record Result(boolean allowedRequest, long retryAfterMs) {
    public static Result permit() {
      return new Result(true, 0L);
    }

    public static Result blocked(long retryAfterMs) {
      return new Result(false, Math.max(1L, retryAfterMs));
    }
  }
}
