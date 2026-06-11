package com.example.hlsviewer.stream;

public class StreamMetaResponse {
  private final String streamKey;
  private final String title;
  private final String scheduledStart;
  private final String status;
  private final String statusLabel;
  private final Long minutesToStart;
  private final Long obsUptimeSeconds;
  private final boolean live;

  public StreamMetaResponse(
      String streamKey,
      String title,
      String scheduledStart,
      String status,
      String statusLabel,
      Long minutesToStart,
      Long obsUptimeSeconds,
      boolean live) {
    this.streamKey = streamKey;
    this.title = title;
    this.scheduledStart = scheduledStart;
    this.status = status;
    this.statusLabel = statusLabel;
    this.minutesToStart = minutesToStart;
    this.obsUptimeSeconds = obsUptimeSeconds;
    this.live = live;
  }

  public String getStreamKey() {
    return streamKey;
  }

  public String getTitle() {
    return title;
  }

  public String getScheduledStart() {
    return scheduledStart;
  }

  public String getStatus() {
    return status;
  }

  public String getStatusLabel() {
    return statusLabel;
  }

  public Long getMinutesToStart() {
    return minutesToStart;
  }

  public Long getObsUptimeSeconds() {
    return obsUptimeSeconds;
  }

  public boolean isLive() {
    return live;
  }
}
