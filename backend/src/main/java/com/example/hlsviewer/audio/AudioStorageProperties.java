package com.example.hlsviewer.audio;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "audio")
public class AudioStorageProperties {
  private String storagePath = "/opt/meeting/storage/audio";
  private long maxBytes = 52428800L;

  public String getStoragePath() {
    return storagePath;
  }

  public void setStoragePath(String storagePath) {
    this.storagePath = storagePath;
  }

  public long getMaxBytes() {
    return maxBytes;
  }

  public void setMaxBytes(long maxBytes) {
    this.maxBytes = maxBytes;
  }
}
