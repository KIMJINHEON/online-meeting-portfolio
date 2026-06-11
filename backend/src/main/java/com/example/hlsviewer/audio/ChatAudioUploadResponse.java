package com.example.hlsviewer.audio;

public record ChatAudioUploadResponse(
    long id,
    String originalName,
    long sizeBytes
) {}
