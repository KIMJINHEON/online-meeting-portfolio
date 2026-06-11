package com.example.hlsviewer.attendance;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

@Service
public class AttendanceVisibilityService {
  private final ConcurrentMap<String, Boolean> attendanceVisibleMap = new ConcurrentHashMap<>();

  public boolean isAttendanceVisible(String streamKey) {
    String key = normalize(streamKey);
    return Boolean.TRUE.equals(attendanceVisibleMap.get(key));
  }

  public boolean setAttendanceVisible(String streamKey, boolean visible) {
    String key = normalize(streamKey);
    attendanceVisibleMap.put(key, visible);
    return visible;
  }

  private String normalize(String value) {
    if (value == null || value.isBlank()) {
      return "stream";
    }
    return value.trim();
  }
}
