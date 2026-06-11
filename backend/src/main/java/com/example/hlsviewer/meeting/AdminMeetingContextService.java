package com.example.hlsviewer.meeting;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Stores the currently selected meeting (stream_key) for each admin id in memory.
 *
 * <p>Lost on application restart by design — admins simply re-select. This keeps the model
 * dependency-free (no schema change) and avoids stale rows piling up in the database.
 */
@Service
public class AdminMeetingContextService {
  private final ConcurrentHashMap<Long, String> selectedStreamKeyByAdmin = new ConcurrentHashMap<>();

  public void select(long adminId, String streamKey) {
    if (streamKey == null || streamKey.isBlank()) {
      selectedStreamKeyByAdmin.remove(adminId);
      return;
    }
    selectedStreamKeyByAdmin.put(adminId, streamKey.trim());
  }

  public Optional<String> currentStreamKey(long adminId) {
    String value = selectedStreamKeyByAdmin.get(adminId);
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(value);
  }

  public void clear(long adminId) {
    selectedStreamKeyByAdmin.remove(adminId);
  }
}
