package com.example.hlsviewer.viewer;

import java.time.Instant;

public record ViewerSession(
    String token,
    Instant expiresAt,
    String streamKey,
    String name,
    String birthDate,
    String phone
) {}
