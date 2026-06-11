package com.example.hlsviewer.excluded;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminMeetingExcludedController {
  private final MeetingExcludedPersonService service;

  public AdminMeetingExcludedController(MeetingExcludedPersonService service) {
    this.service = service;
  }

  @GetMapping("/api/admin/excluded-persons")
  public ResponseEntity<?> list(@RequestParam("streamKey") String streamKey) {
    if (streamKey == null || streamKey.isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "streamKey_required"));
    }
    List<ExcludedPersonResponse> items = service.list(streamKey)
        .stream()
        .map(ExcludedPersonResponse::from)
        .toList();
    return ResponseEntity.ok(items);
  }

  @PostMapping("/api/admin/excluded-persons")
  public ResponseEntity<?> add(@RequestBody ExcludedPersonRequest request) {
    if (request == null) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "body_required"));
    }
    if (request.streamKey() == null || request.streamKey().isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "streamKey_required"));
    }
    try {
      MeetingExcludedPerson created = service.add(
          request.streamKey(),
          request.name(),
          request.birth(),
          request.phone()
      );
      return ResponseEntity.ok(ExcludedPersonResponse.from(created));
    } catch (IllegalArgumentException ex) {
      HttpStatus status = "already_registered".equals(ex.getMessage())
          ? HttpStatus.CONFLICT
          : HttpStatus.BAD_REQUEST;
      return ResponseEntity.status(status).body(Map.of("error", ex.getMessage()));
    }
  }

  @DeleteMapping("/api/admin/excluded-persons/{id}")
  public ResponseEntity<?> delete(
      @PathVariable("id") long id,
      @RequestParam("streamKey") String streamKey) {
    if (streamKey == null || streamKey.isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "streamKey_required"));
    }
    try {
      service.delete(streamKey, id);
      return ResponseEntity.ok(Map.of("deleted", 1));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(Map.of("error", ex.getMessage()));
    }
  }

  public record ExcludedPersonRequest(String streamKey, String name, String birth, String phone) {}

  public record ExcludedPersonResponse(
      long id,
      String name,
      String birth,
      String phone,
      LocalDateTime createdAt
  ) {
    public static ExcludedPersonResponse from(MeetingExcludedPerson p) {
      return new ExcludedPersonResponse(p.id(), p.name(), p.birth(), p.phone(), p.createdAt());
    }
  }
}
