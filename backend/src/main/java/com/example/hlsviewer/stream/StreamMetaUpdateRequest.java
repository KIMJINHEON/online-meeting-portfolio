package com.example.hlsviewer.stream;

public class StreamMetaUpdateRequest {
  private String streamKey;
  private String title;
  private String scheduledStart;

  public String getStreamKey() {
    return streamKey;
  }

  public void setStreamKey(String streamKey) {
    this.streamKey = streamKey;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getScheduledStart() {
    return scheduledStart;
  }

  public void setScheduledStart(String scheduledStart) {
    this.scheduledStart = scheduledStart;
  }
}
