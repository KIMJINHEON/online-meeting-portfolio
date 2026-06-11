package com.example.hlsviewer.wowza;

public record WowzaStatsResponse(
    long totalConnections,
    long hlsConnections,
    long rtmpConnections,
    long dashConnections
) {}
