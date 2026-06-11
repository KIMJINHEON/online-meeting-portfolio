package com.example.hlsviewer.admin;

import java.time.Instant;

public record AdminSession(long adminId, String token, Instant expiresAt) {}
