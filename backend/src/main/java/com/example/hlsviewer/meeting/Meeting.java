package com.example.hlsviewer.meeting;

import java.time.LocalDateTime;

public record Meeting(
    long id,
    String streamKey,
    String title,
    LocalDateTime startAt,
    LocalDateTime endAt,
    boolean accessOpen,
    String voteUrl,
    LocalDateTime deletedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
