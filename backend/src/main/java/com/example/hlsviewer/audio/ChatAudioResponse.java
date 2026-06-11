package com.example.hlsviewer.audio;

public record ChatAudioResponse(
    long id,
    String uploaderName,
    String originalName,
    String contentType,
    long sizeBytes,
    long createdAt
) {}
