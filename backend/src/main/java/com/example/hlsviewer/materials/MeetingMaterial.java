package com.example.hlsviewer.materials;

import java.time.Instant;

public record MeetingMaterial(
    long id,
    long roomId,
    String type,
    String title,
    String body,
    String storedName,
    String contentType,
    long sizeBytes,
    Instant createdAt
) {}
