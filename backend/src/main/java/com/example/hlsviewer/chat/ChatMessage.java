package com.example.hlsviewer.chat;

import java.time.Instant;

public record ChatMessage(
    long id,
    long roomId,
    String senderName,
    String message,
    String status,
    Instant createdAt
) {}
