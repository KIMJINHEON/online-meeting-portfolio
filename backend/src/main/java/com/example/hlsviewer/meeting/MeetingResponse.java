package com.example.hlsviewer.meeting;

import java.time.LocalDateTime;

public record MeetingResponse(
    long id,
    String streamKey,
    String title,
    LocalDateTime startAt,
    LocalDateTime endAt,
    boolean accessOpen,
    String voteUrl,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
  public static MeetingResponse from(Meeting meeting) {
    return new MeetingResponse(
        meeting.id(),
        meeting.streamKey(),
        meeting.title(),
        meeting.startAt(),
        meeting.endAt(),
        meeting.accessOpen(),
        meeting.voteUrl(),
        meeting.createdAt(),
        meeting.updatedAt()
    );
  }
}
