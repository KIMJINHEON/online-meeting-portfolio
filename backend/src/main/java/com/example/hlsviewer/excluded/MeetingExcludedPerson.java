package com.example.hlsviewer.excluded;

import java.time.LocalDateTime;

public record MeetingExcludedPerson(
    long id,
    String streamKey,
    String name,
    String birth,
    String phone,
    LocalDateTime createdAt
) {}
