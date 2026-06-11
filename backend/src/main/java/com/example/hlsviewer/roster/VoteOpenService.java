package com.example.hlsviewer.roster;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

@Service
public class VoteOpenService {
  private final ConcurrentMap<String, Boolean> openMap = new ConcurrentHashMap<>();

  public boolean isOpen(String streamKey) {
    String key = normalize(streamKey);
    return Boolean.TRUE.equals(openMap.get(key));
  }

  public boolean setOpen(String streamKey, boolean open) {
    String key = normalize(streamKey);
    openMap.put(key, open);
    return open;
  }

  private String normalize(String value) {
    if (value == null || value.isBlank()) {
      return "stream";
    }
    return value.trim();
  }
}
