package com.example.hlsviewer;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hls")
public class HlsProperties {
  private String baseUrl = "";
  private String defaultStreamKey = "stream";
  private String pathTemplate = "/live/{streamKey}/playlist.m3u8";
  private String playbackQuery = "";
  private String defaultUrl = "";

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getDefaultStreamKey() {
    return defaultStreamKey;
  }

  public void setDefaultStreamKey(String defaultStreamKey) {
    this.defaultStreamKey = defaultStreamKey;
  }

  public String getPathTemplate() {
    return pathTemplate;
  }

  public void setPathTemplate(String pathTemplate) {
    this.pathTemplate = pathTemplate;
  }

  public String getPlaybackQuery() {
    return playbackQuery;
  }

  public void setPlaybackQuery(String playbackQuery) {
    this.playbackQuery = playbackQuery;
  }

  public String getDefaultUrl() {
    return defaultUrl;
  }

  public void setDefaultUrl(String defaultUrl) {
    this.defaultUrl = defaultUrl;
  }
}
