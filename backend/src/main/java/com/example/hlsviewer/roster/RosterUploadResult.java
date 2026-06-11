package com.example.hlsviewer.roster;

import java.util.List;

public record RosterUploadResult(
    String streamKey,
    int inserted,
    int skipped,
    List<String> errors
) {}

