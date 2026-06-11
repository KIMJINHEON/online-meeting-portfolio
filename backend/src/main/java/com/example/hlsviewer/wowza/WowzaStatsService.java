package com.example.hlsviewer.wowza;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class WowzaStatsService {
  private static final long MIN_CACHE_TTL_MS = 250L;
  private static final long MIN_STALE_GRACE_MS = 1000L;

  private final WowzaRestProperties props;
  private final RestTemplate restTemplate;
  private final ObjectMapper objectMapper;
  private final Object statsCacheLock = new Object();

  private volatile WowzaStatsResponse cachedStats;
  private volatile long cachedStatsAtMs = 0L;
  private volatile long cachedStatsSuccessAtMs = 0L;

  private record IncomingStreamTarget(String instanceName, String streamName, int score) {}

  public WowzaStatsService(RestTemplateBuilder builder, WowzaRestProperties props, ObjectMapper objectMapper) {
    this.props = props;
    this.objectMapper = objectMapper;
    this.restTemplate = builder
        .setConnectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
        .setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()))
        .build();
  }

  public Optional<WowzaStatsResponse> fetchCurrentStats() {
    if (props.getBaseUrl() == null || props.getBaseUrl().isBlank()) {
      return Optional.empty();
    }

    long now = System.currentTimeMillis();
    long cacheTtlMs = Math.max(MIN_CACHE_TTL_MS, (long) props.getStatsCacheTtlMs());
    long staleGraceMs = Math.max(MIN_STALE_GRACE_MS, (long) props.getStatsStaleGraceMs());

    WowzaStatsResponse snapshot = cachedStats;
    if (snapshot != null && now - cachedStatsAtMs <= cacheTtlMs) {
      return Optional.of(snapshot);
    }

    synchronized (statsCacheLock) {
      now = System.currentTimeMillis();
      snapshot = cachedStats;
      if (snapshot != null && now - cachedStatsAtMs <= cacheTtlMs) {
        return Optional.of(snapshot);
      }

      Optional<WowzaStatsResponse> fetched = fetchCurrentStatsFromWowza();
      if (fetched.isPresent()) {
        cachedStats = fetched.get();
        cachedStatsAtMs = now;
        cachedStatsSuccessAtMs = now;
        return fetched;
      }

      // Throttle repeated upstream failures for a short period.
      cachedStatsAtMs = now;

      if (snapshot != null && now - cachedStatsSuccessAtMs <= staleGraceMs) {
        return Optional.of(snapshot);
      }
      return Optional.empty();
    }
  }

  private Optional<WowzaStatsResponse> fetchCurrentStatsFromWowza() {
    String url = String.format(
        "%s/servers/%s/vhosts/%s/applications/%s/monitoring/current",
        trimTrailingSlash(props.getBaseUrl()),
        props.getServerName(),
        props.getVhostName(),
        props.getAppName()
    );

    try {
      ResponseEntity<String> response = restTemplate.exchange(
          url,
          HttpMethod.GET,
          new HttpEntity<>(buildHeaders()),
          String.class
      );
      if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
        return Optional.empty();
      }

      JsonNode root = objectMapper.readTree(response.getBody());
      long totalConnections = root.path("totalConnections").asLong(0);
      JsonNode connectionCount = root.path("connectionCount");
      long hlsConnections = connectionCount.path("CUPERTINO").asLong(0);
      long rtmpConnections = connectionCount.path("RTMP").asLong(0);
      long dashConnections = connectionCount.path("MPEGDASH").asLong(0);

      return Optional.of(new WowzaStatsResponse(
          totalConnections,
          hlsConnections,
          rtmpConnections,
          dashConnections
      ));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  public Optional<Long> fetchIncomingStreamUptimeSeconds(String preferredStreamKey) {
    if (props.getBaseUrl() == null || props.getBaseUrl().isBlank()) {
      return Optional.empty();
    }

    String instancesUrl = String.format(
        "%s/servers/%s/vhosts/%s/applications/%s/instances",
        trimTrailingSlash(props.getBaseUrl()),
        props.getServerName(),
        props.getVhostName(),
        props.getAppName()
    );

    try {
      ResponseEntity<String> instancesResponse = restTemplate.exchange(
          instancesUrl,
          HttpMethod.GET,
          new HttpEntity<>(buildHeaders()),
          String.class
      );
      if (!instancesResponse.getStatusCode().is2xxSuccessful() || instancesResponse.getBody() == null) {
        return Optional.empty();
      }

      JsonNode root = objectMapper.readTree(instancesResponse.getBody());
      JsonNode instanceList = root.path("instanceList");
      if (!instanceList.isArray()) {
        return Optional.empty();
      }

      IncomingStreamTarget target = selectIncomingStreamTarget(instanceList, preferredStreamKey);
      if (target == null) {
        return Optional.empty();
      }

      String monitoringUrl = String.format(
          "%s/servers/%s/vhosts/%s/applications/%s/instances/%s/incomingstreams/%s/monitoring/current",
          trimTrailingSlash(props.getBaseUrl()),
          encodePathSegment(props.getServerName()),
          encodePathSegment(props.getVhostName()),
          encodePathSegment(props.getAppName()),
          encodePathSegment(target.instanceName()),
          encodePathSegment(target.streamName())
      );

      ResponseEntity<String> monitoringResponse = restTemplate.exchange(
          monitoringUrl,
          HttpMethod.GET,
          new HttpEntity<>(buildHeaders()),
          String.class
      );
      if (!monitoringResponse.getStatusCode().is2xxSuccessful() || monitoringResponse.getBody() == null) {
        return Optional.empty();
      }

      JsonNode monitoring = objectMapper.readTree(monitoringResponse.getBody());
      long uptime = monitoring.path("uptime").asLong(-1);
      if (uptime < 0) {
        return Optional.empty();
      }
      return Optional.of(uptime);
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  private IncomingStreamTarget selectIncomingStreamTarget(JsonNode instanceList, String preferredStreamKey) {
    Set<String> preferredNames = buildPreferredNames(preferredStreamKey);
    IncomingStreamTarget best = null;

    for (JsonNode instance : instanceList) {
      String instanceName = instance.path("name").asText("");
      JsonNode incomingStreams = instance.path("incomingStreams");
      if (!incomingStreams.isArray()) {
        continue;
      }
      for (JsonNode incomingStream : incomingStreams) {
        int score = scoreIncomingStream(incomingStream, preferredNames);
        if (score == Integer.MIN_VALUE) {
          continue;
        }
        if (best == null || score > best.score()) {
          best = new IncomingStreamTarget(instanceName, incomingStream.path("name").asText(""), score);
        }
      }
    }

    return best;
  }

  private int scoreIncomingStream(JsonNode incomingStream, Set<String> preferredNames) {
    String streamName = incomingStream.path("name").asText("");
    if (streamName.isBlank()) {
      return Integer.MIN_VALUE;
    }
    // Reject any stream that isn't the requested streamKey (or one of its known suffix variants).
    // Without this, a meeting that isn't broadcasting would inherit another meeting's uptime and
    // appear "live", because connected+externalSource alone scored ~90 even without a name match.
    if (!preferredNames.contains(streamName)) {
      return Integer.MIN_VALUE;
    }

    boolean connected = incomingStream.path("isConnected").asBoolean(false);
    String sourceIp = incomingStream.path("sourceIp").asText("");
    boolean externalSource = isExternalSource(sourceIp);

    int score = 100;
    if (connected) {
      score += 40;
    }
    if (externalSource) {
      score += 30;
    }
    if (connected && externalSource) {
      score += 20;
    }
    return score;
  }

  private Set<String> buildPreferredNames(String preferredStreamKey) {
    Set<String> names = new LinkedHashSet<>();
    addPreferredName(names, preferredStreamKey);

    if (preferredStreamKey != null) {
      String trimmed = preferredStreamKey.trim();
      int colonIndex = trimmed.lastIndexOf(':');
      if (colonIndex >= 0 && colonIndex + 1 < trimmed.length()) {
        addPreferredName(names, trimmed.substring(colonIndex + 1));
      }
    }

    Set<String> expanded = new LinkedHashSet<>(names);
    for (String name : names) {
      if (name.endsWith("_all") && name.length() > 4) {
        expanded.add(name.substring(0, name.length() - 4));
      }
      if (name.endsWith("_mobile") && name.length() > 7) {
        expanded.add(name.substring(0, name.length() - 7));
      }
    }
    return expanded;
  }

  private void addPreferredName(Set<String> names, String value) {
    if (value == null) {
      return;
    }
    String trimmed = value.trim();
    if (!trimmed.isBlank()) {
      names.add(trimmed);
    }
  }

  private boolean isExternalSource(String sourceIp) {
    String normalized = sourceIp == null ? "" : sourceIp.trim().toLowerCase(Locale.ROOT);
    return !normalized.isBlank() && !normalized.contains("local");
  }

  private HttpHeaders buildHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
    if (props.getUsername() != null && !props.getUsername().isBlank()) {
      headers.setBasicAuth(props.getUsername(), props.getPassword() == null ? "" : props.getPassword());
    }
    return headers;
  }

  private String encodePathSegment(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private String trimTrailingSlash(String value) {
    if (value.endsWith("/")) {
      return value.substring(0, value.length() - 1);
    }
    return value;
  }
}
