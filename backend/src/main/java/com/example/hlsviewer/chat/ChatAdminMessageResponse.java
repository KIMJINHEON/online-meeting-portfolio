package com.example.hlsviewer.chat;

public record ChatAdminMessageResponse(
    long id,
    String senderName,
    String message,
    String status,
    long createdAtEpochMs
) {}
