package com.example.hlsviewer.partner;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "partner-db")
public class PartnerDbProperties {
  private boolean enabled = false;
  private String jdbcUrl = "";
  private String username = "";
  private String password = "";
  private int queryTimeoutSeconds = 3;
  private boolean requirePhoneMatch = true;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getJdbcUrl() {
    return jdbcUrl;
  }

  public void setJdbcUrl(String jdbcUrl) {
    this.jdbcUrl = jdbcUrl;
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

  public int getQueryTimeoutSeconds() {
    return queryTimeoutSeconds;
  }

  public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
    this.queryTimeoutSeconds = queryTimeoutSeconds;
  }

  public boolean isRequirePhoneMatch() {
    return requirePhoneMatch;
  }

  public void setRequirePhoneMatch(boolean requirePhoneMatch) {
    this.requirePhoneMatch = requirePhoneMatch;
  }
}

