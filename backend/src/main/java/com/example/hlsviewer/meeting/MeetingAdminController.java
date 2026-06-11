package com.example.hlsviewer.meeting;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/meetings")
public class MeetingAdminController {
  private final MeetingService meetingService;

  public MeetingAdminController(MeetingService meetingService) {
    this.meetingService = meetingService;
  }

  @GetMapping
  public List<MeetingResponse> list() {
    return meetingService.list().stream().map(MeetingResponse::from).toList();
  }

  @GetMapping("/{id}")
  public ResponseEntity<?> get(@PathVariable long id) {
    return meetingService.findById(id)
        .<ResponseEntity<?>>map(meeting -> ResponseEntity.ok(MeetingResponse.from(meeting)))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", "meeting_not_found")));
  }

  @PostMapping
  public ResponseEntity<?> create(@RequestBody CreateMeetingRequest request) {
    if (request == null) {
      return badRequest("invalid_request");
    }
    try {
      Meeting created = meetingService.create(
          request.title(), request.startAt(), request.endAt(), request.voteUrl());
      return ResponseEntity.status(HttpStatus.CREATED).body(MeetingResponse.from(created));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  @PatchMapping("/{id}")
  public ResponseEntity<?> update(@PathVariable long id, @RequestBody UpdateMeetingRequest request) {
    if (request == null) {
      return badRequest("invalid_request");
    }
    try {
      Meeting updated = meetingService.update(
          id,
          request.title(),
          request.startAt(),
          request.endAt(),
          request.voteUrl(),
          request.voteUrl() != null);
      return ResponseEntity.ok(MeetingResponse.from(updated));
    } catch (IllegalArgumentException ex) {
      if ("meeting_not_found".equals(ex.getMessage())) {
        return notFound();
      }
      return badRequest(ex.getMessage());
    }
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<?> delete(@PathVariable long id) {
    try {
      meetingService.softDelete(id);
      return ResponseEntity.ok(Map.of("ok", true));
    } catch (IllegalArgumentException ex) {
      return notFound();
    }
  }

  @PostMapping("/{id}/access")
  public ResponseEntity<?> toggleAccess(@PathVariable long id, @RequestBody AccessToggleRequest request) {
    if (request == null) {
      return badRequest("invalid_request");
    }
    try {
      Meeting updated = meetingService.setAccessOpen(id, request.open());
      return ResponseEntity.ok(MeetingResponse.from(updated));
    } catch (IllegalArgumentException ex) {
      return notFound();
    }
  }

  private static ResponseEntity<?> badRequest(String code) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", code));
  }

  private static ResponseEntity<?> notFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "meeting_not_found"));
  }

  public record CreateMeetingRequest(
      String title, LocalDateTime startAt, LocalDateTime endAt, String voteUrl) {}

  public record UpdateMeetingRequest(
      String title, LocalDateTime startAt, LocalDateTime endAt, String voteUrl) {}

  public record AccessToggleRequest(boolean open) {}
}
