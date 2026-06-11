package com.example.hlsviewer.materials;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "materials")
public class MaterialStorageProperties {
  private String storagePath = "/opt/meeting/storage/materials";
  private long maxBytes = 104857600L;

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
