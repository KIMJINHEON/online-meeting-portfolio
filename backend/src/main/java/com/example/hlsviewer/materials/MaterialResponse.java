package com.example.hlsviewer.materials;

public record MaterialResponse(
    long id,
    String type,
    String title,
    String body,
    String url,
    long sizeBytes,
    long createdAt
) {}
