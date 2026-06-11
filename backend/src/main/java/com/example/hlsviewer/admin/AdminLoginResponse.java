package com.example.hlsviewer.admin;

public record AdminLoginResponse(String token, String displayName, long expiresAtEpochMs) {}
