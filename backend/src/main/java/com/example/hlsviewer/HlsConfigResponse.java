package com.example.hlsviewer;

public record HlsConfigResponse(
    String baseUrl,
    String defaultStreamKey,
    String pathTemplate,
    String playbackQuery,
    String defaultUrl
) {}
