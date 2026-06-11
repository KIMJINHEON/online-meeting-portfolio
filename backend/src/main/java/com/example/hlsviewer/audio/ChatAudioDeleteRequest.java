package com.example.hlsviewer.audio;

import java.util.List;

public record ChatAudioDeleteRequest(String streamKey, List<Long> ids) {}
