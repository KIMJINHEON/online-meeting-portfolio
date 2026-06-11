package com.example.hlsviewer.meeting;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class MeetingService {
  private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
  private static final String STREAM_KEY_PREFIX = "m-";
  private static final int STREAM_KEY_RANDOM_BYTES = 12;
  private static final int STREAM_KEY_MAX_RETRIES = 5;
  private static final int VOTE_URL_MAX_LENGTH = 500;

  private final MeetingRepository repository;
  private final SecureRandom random = new SecureRandom();

  public MeetingService(MeetingRepository repository) {
    this.repository = repository;
  }

  public List<Meeting> list() {
    return repository.findActiveOrderedByStart();
  }

  public Optional<Meeting> findById(long id) {
    return repository.findById(id);
  }

  public Optional<Meeting> findByStreamKey(String streamKey) {
    return repository.findByStreamKey(streamKey);
  }

  public List<Meeting> findByStreamKeys(List<String> streamKeys) {
    return repository.findActiveByStreamKeys(streamKeys);
  }

  public Meeting create(String title, LocalDateTime startAt, LocalDateTime endAt, String voteUrl) {
    String cleanTitle = requireNonBlank(title, "title_required");
    requireOrder(startAt, endAt);
    String cleanVoteUrl = normalizeVoteUrl(voteUrl);

    String streamKey = generateUniqueStreamKey();
    repository.insert(streamKey, cleanTitle, startAt, endAt, cleanVoteUrl);
    return repository.findByStreamKey(streamKey)
        .orElseThrow(() -> new IllegalStateException("meeting_insert_failed"));
  }

  public Meeting update(
      long id,
      String title,
      LocalDateTime startAt,
      LocalDateTime endAt,
      String voteUrl,
      boolean voteUrlProvided) {
    Meeting current = repository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("meeting_not_found"));

    String nextTitle = title == null || title.isBlank() ? current.title() : title.trim();
    LocalDateTime nextStart = startAt == null ? current.startAt() : startAt;
    LocalDateTime nextEnd = endAt == null ? current.endAt() : endAt;
    requireOrder(nextStart, nextEnd);
    String nextVoteUrl = voteUrlProvided ? normalizeVoteUrl(voteUrl) : current.voteUrl();

    int updated = repository.update(id, nextTitle, nextStart, nextEnd, nextVoteUrl);
    if (updated == 0) {
      throw new IllegalStateException("meeting_update_failed");
    }
    return repository.findById(id)
        .orElseThrow(() -> new IllegalStateException("meeting_update_failed"));
  }

  public Meeting setAccessOpen(long id, boolean accessOpen) {
    int updated = repository.updateAccessOpen(id, accessOpen);
    if (updated == 0) {
      throw new IllegalArgumentException("meeting_not_found");
    }
    return repository.findById(id)
        .orElseThrow(() -> new IllegalStateException("meeting_update_failed"));
  }

  public void softDelete(long id) {
    int updated = repository.softDelete(id);
    if (updated == 0) {
      throw new IllegalArgumentException("meeting_not_found");
    }
  }

  private String generateUniqueStreamKey() {
    for (int attempt = 0; attempt < STREAM_KEY_MAX_RETRIES; attempt++) {
      byte[] bytes = new byte[STREAM_KEY_RANDOM_BYTES];
      random.nextBytes(bytes);
      String candidate = STREAM_KEY_PREFIX + BASE64_URL.encodeToString(bytes);
      if (!repository.streamKeyExists(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("stream_key_generation_failed");
  }

  private String requireNonBlank(String value, String errorCode) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(errorCode);
    }
    return value.trim();
  }

  private void requireOrder(LocalDateTime startAt, LocalDateTime endAt) {
    if (startAt == null) {
      throw new IllegalArgumentException("start_at_required");
    }
    if (endAt == null) {
      throw new IllegalArgumentException("end_at_required");
    }
    if (!endAt.isAfter(startAt)) {
      throw new IllegalArgumentException("end_at_must_be_after_start_at");
    }
  }

  private String normalizeVoteUrl(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > VOTE_URL_MAX_LENGTH) {
      throw new IllegalArgumentException("vote_url_too_long");
    }
    String lower = trimmed.toLowerCase();
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
      throw new IllegalArgumentException("vote_url_invalid_scheme");
    }
    return trimmed;
  }
}
