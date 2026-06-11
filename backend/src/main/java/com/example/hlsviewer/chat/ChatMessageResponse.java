package com.example.hlsviewer.chat;

public record ChatMessageResponse(
    long id,
    String senderName,
    String message,
    long createdAtEpochMs
) {}
