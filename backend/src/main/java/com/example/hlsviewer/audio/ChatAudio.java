package com.example.hlsviewer.audio;

import java.time.Instant;

public record ChatAudio(
    long id,
    long roomId,
    String uploaderName,
    String originalName,
    String storedName,
    String contentType,
    long sizeBytes,
    Instant createdAt,
    Instant deletedAt
) {}
