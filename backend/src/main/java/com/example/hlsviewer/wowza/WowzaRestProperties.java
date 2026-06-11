package com.example.hlsviewer.wowza;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wowza.rest")
public class WowzaRestProperties {
  private String baseUrl = "http://127.0.0.1:8087/v2";
  private String serverName = "_defaultServer_";
  private String vhostName = "_defaultVHost_";
  private String appName = "live";
  private String username = "";
  private String password = "";
  private int connectTimeoutMs = 2000;
  private int readTimeoutMs = 2000;
  private int statsCacheTtlMs = 3000;
  private int statsStaleGraceMs = 15000;

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getServerName() {
    return serverName;
  }

  public void setServerName(String serverName) {
    this.serverName = serverName;
  }

  public String getVhostName() {
    return vhostName;
  }

  public void setVhostName(String vhostName) {
    this.vhostName = vhostName;
  }

  public String getAppName() {
    return appName;
  }

  public void setAppName(String appName) {
    this.appName = appName;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public int getConnectTimeoutMs() {
    return connectTimeoutMs;
  }

  public void setConnectTimeoutMs(int connectTimeoutMs) {
    this.connectTimeoutMs = connectTimeoutMs;
  }

  public int getReadTimeoutMs() {
    return readTimeoutMs;
  }

  public void setReadTimeoutMs(int readTimeoutMs) {
    this.readTimeoutMs = readTimeoutMs;
  }

  public int getStatsCacheTtlMs() {
    return statsCacheTtlMs;
  }

  public void setStatsCacheTtlMs(int statsCacheTtlMs) {
    this.statsCacheTtlMs = statsCacheTtlMs;
  }

  public int getStatsStaleGraceMs() {
    return statsStaleGraceMs;
  }

  public void setStatsStaleGraceMs(int statsStaleGraceMs) {
    this.statsStaleGraceMs = statsStaleGraceMs;
  }
}
