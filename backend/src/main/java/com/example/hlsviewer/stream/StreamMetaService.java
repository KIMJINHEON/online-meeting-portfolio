package com.example.hlsviewer.stream;

import com.example.hlsviewer.wowza.WowzaStatsService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class StreamMetaService {
  private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Seoul");
  private static final DateTimeFormatter OUTPUT_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
  private static final DateTimeFormatter INPUT_SPACE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final long LIVE_OFF_GRACE_MS = 15000L;
  private static final long INCOMING_LIVE_CACHE_TTL_MS = 5000L;

  private final StreamMetaRepository repository;
  private final WowzaStatsService statsService;
  private final ConcurrentHashMap<String, LiveStateEntry> liveStateByStreamKey = new ConcurrentHashMap<>();

  public StreamMetaService(StreamMetaRepository repository, WowzaStatsService statsService) {
    this.repository = repository;
    this.statsService = statsService;
  }

  private static final class LiveStateEntry {
    volatile long lastDetectedAtMs;
    volatile boolean lastResolved;
    volatile long lastIncomingCheckedAtMs;
    volatile boolean lastIncomingLive;
  }

  public StreamMetaResponse getMeta(String streamKey) {
    return getMeta(streamKey, false);
  }

  public StreamMetaResponse getAdminMeta(String streamKey) {
    return getMeta(streamKey, true);
  }

  public StreamMetaResponse update(StreamMetaUpdateRequest request) {
    String key = normalizeStreamKey(request.getStreamKey());
    String title = request.getTitle() == null ? "" : request.getTitle().trim();
    LocalDateTime scheduled = parseDate(request.getScheduledStart());
    repository.upsert(key, title, scheduled);
    StreamMeta meta = new StreamMeta(key, title, scheduled);
    boolean live = isLive(key);
    Long obsUptimeSeconds = resolveObsUptimeSeconds(key, live, true);
    return buildResponse(meta, live, obsUptimeSeconds);
  }

  private StreamMetaResponse getMeta(String streamKey, boolean includeObsUptime) {
    String key = normalizeStreamKey(streamKey);
    StreamMeta meta = repository.findByStreamKey(key).orElse(new StreamMeta(key, "", null));
    boolean live = isLive(key);
    Long obsUptimeSeconds = resolveObsUptimeSeconds(key, live, includeObsUptime);
    return buildResponse(meta, live, obsUptimeSeconds);
  }

  private StreamMetaResponse buildResponse(StreamMeta meta, boolean live, Long obsUptimeSeconds) {
    ZonedDateTime now = ZonedDateTime.now(DEFAULT_ZONE);
    ZonedDateTime scheduled = null;
    if (meta.getScheduledStart() != null) {
      scheduled = meta.getScheduledStart().atZone(DEFAULT_ZONE);
    }

    String status;
    String statusLabel;
    Long minutesToStart = null;
    if (live) {
      status = "LIVE";
      statusLabel = "라이브중";
    } else if (scheduled != null) {
      if (now.isBefore(scheduled)) {
        long minutes = Math.max(0, Duration.between(now, scheduled).toMinutes());
        minutesToStart = minutes;
        if (minutes >= 720) {
          status = "PREPARING";
          statusLabel = "준비중";
        } else if (minutes >= 60) {
          long hours = Math.max(1, minutes / 60);
          status = "COUNTDOWN";
          statusLabel = "시작 " + hours + "시간 전";
        } else if (minutes > 0) {
          status = "COUNTDOWN";
          statusLabel = "시작 " + minutes + "분 전";
        } else {
          status = "SOON";
          statusLabel = "곧 시작";
        }
      } else {
        status = "PREPARING";
        statusLabel = "준비중";
      }
    } else {
      status = "PREPARING";
      statusLabel = "준비중";
    }

    String scheduledStart =
        meta.getScheduledStart() == null ? "" : meta.getScheduledStart().format(OUTPUT_FORMAT);
    return new StreamMetaResponse(
        meta.getStreamKey(),
        meta.getTitle(),
        scheduledStart,
        status,
        statusLabel,
        minutesToStart,
        obsUptimeSeconds,
        live);
  }

  private Long resolveObsUptimeSeconds(String streamKey, boolean live, boolean includeObsUptime) {
    if (!includeObsUptime || !live) {
      return null;
    }
    return statsService.fetchIncomingStreamUptimeSeconds(streamKey).orElse(null);
  }

  /**
   * Live state is decided per streamKey by looking only at that stream's incoming uptime in Wowza.
   * The previous implementation looked at the server-wide RTMP connection count, which caused
   * meeting B to appear "live" whenever any other meeting was being broadcast.
   */
  private boolean isLive(String streamKey) {
    String key = normalizeStreamKey(streamKey);
    long now = System.currentTimeMillis();
    LiveStateEntry state = liveStateByStreamKey.computeIfAbsent(key, k -> new LiveStateEntry());
    synchronized (state) {
      if (hasIncomingLiveSignal(state, key, now)) {
        state.lastDetectedAtMs = now;
        state.lastResolved = true;
        return true;
      }
      if (state.lastResolved && now - state.lastDetectedAtMs < LIVE_OFF_GRACE_MS) {
        return true;
      }
      state.lastResolved = false;
      return false;
    }
  }

  private boolean hasIncomingLiveSignal(LiveStateEntry state, String streamKey, long nowMs) {
    if (nowMs - state.lastIncomingCheckedAtMs <= INCOMING_LIVE_CACHE_TTL_MS) {
      return state.lastIncomingLive;
    }
    boolean resolved = statsService
        .fetchIncomingStreamUptimeSeconds(streamKey)
        .map((uptime) -> uptime > 0)
        .orElse(false);
    state.lastIncomingCheckedAtMs = nowMs;
    state.lastIncomingLive = resolved;
    return resolved;
  }

  private String normalizeStreamKey(String value) {
    if (value == null || value.isBlank()) {
      return "stream";
    }
    return value.trim();
  }

  private LocalDateTime parseDate(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String trimmed = value.trim();
    try {
      return LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    } catch (DateTimeParseException ex) {
      // fall through
    }
    try {
      return LocalDateTime.parse(trimmed, INPUT_SPACE_FORMAT);
    } catch (DateTimeParseException ex) {
      return null;
    }
  }
}
