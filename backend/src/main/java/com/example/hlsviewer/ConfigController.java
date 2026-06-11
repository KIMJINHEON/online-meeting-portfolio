package com.example.hlsviewer;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ConfigController {
  private final HlsProperties props;

  public ConfigController(HlsProperties props) {
    this.props = props;
  }

  @GetMapping("/config")
  public HlsConfigResponse getConfig() {
    String resolvedDefaultUrl = resolveDefaultUrl();
    return new HlsConfigResponse(
        safe(props.getBaseUrl()),
        safe(props.getDefaultStreamKey()),
        safe(props.getPathTemplate()),
        safe(props.getPlaybackQuery()),
        resolvedDefaultUrl
    );
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    Map<String, String> response = new LinkedHashMap<>();
    response.put("status", "ok");
    return response;
  }

  private String resolveDefaultUrl() {
    String explicit = trimToEmpty(props.getDefaultUrl());
    if (!explicit.isEmpty()) {
      return explicit;
    }

    String baseUrl = trimToEmpty(props.getBaseUrl());
    if (baseUrl.isEmpty()) {
      return "";
    }

    String streamKey = trimToEmpty(props.getDefaultStreamKey());
    if (streamKey.isEmpty()) {
      streamKey = "stream";
    }

    String pathTemplate = trimToEmpty(props.getPathTemplate());
    if (pathTemplate.isEmpty()) {
      pathTemplate = "/live/{streamKey}/playlist.m3u8";
    }

    String resolvedPath = pathTemplate.replace("{streamKey}", streamKey);
    String joined = joinUrl(baseUrl, resolvedPath);

    String query = trimToEmpty(props.getPlaybackQuery());
    if (!query.isEmpty()) {
      if (query.startsWith("?")) {
        return joined + query;
      }
      return joined + "?" + query;
    }

    return joined;
  }

  private String joinUrl(String baseUrl, String path) {
    String base = baseUrl;
    if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }

    String suffix = path;
    if (!suffix.startsWith("/")) {
      suffix = "/" + suffix;
    }

    return base + suffix;
  }

  private String safe(String value) {
    return value == null ? "" : value;
  }

  private String trimToEmpty(String value) {
    if (value == null) {
      return "";
    }
    return value.trim();
  }
}
