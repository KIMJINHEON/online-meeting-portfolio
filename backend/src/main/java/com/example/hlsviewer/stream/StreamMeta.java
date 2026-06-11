package com.example.hlsviewer.stream;

import java.time.LocalDateTime;

public class StreamMeta {
  private final String streamKey;
  private final String title;
  private final LocalDateTime scheduledStart;

  public StreamMeta(String streamKey, String title, LocalDateTime scheduledStart) {
    this.streamKey = streamKey;
    this.title = title;
    this.scheduledStart = scheduledStart;
  }

  public String getStreamKey() {
    return streamKey;
  }

  public String getTitle() {
    return title;
  }

  public LocalDateTime getScheduledStart() {
    return scheduledStart;
  }
}
