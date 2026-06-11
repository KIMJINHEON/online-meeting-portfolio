package com.example.hlsviewer.meeting;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminMeetingContextController {
  private final AdminMeetingContextService contextService;
  private final MeetingService meetingService;

  public AdminMeetingContextController(AdminMeetingContextService contextService, MeetingService meetingService) {
    this.contextService = contextService;
    this.meetingService = meetingService;
  }

  @PostMapping("/api/admin/select-meeting")
  public ResponseEntity<?> select(@RequestBody SelectMeetingRequest request, HttpServletRequest httpRequest) {
    Long adminId = extractAdminId(httpRequest);
    if (adminId == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
    }
    if (request == null || request.streamKey() == null || request.streamKey().isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "stream_key_required"));
    }

    return meetingService.findByStreamKey(request.streamKey())
        .<ResponseEntity<?>>map(meeting -> {
          contextService.select(adminId, meeting.streamKey());
          return ResponseEntity.ok(MeetingResponse.from(meeting));
        })
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "meeting_not_found")));
  }

  @GetMapping("/api/admin/current-meeting")
  public ResponseEntity<?> current(HttpServletRequest httpRequest) {
    Long adminId = extractAdminId(httpRequest);
    if (adminId == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
    }

    return contextService.currentStreamKey(adminId)
        .flatMap(meetingService::findByStreamKey)
        .<ResponseEntity<?>>map(meeting -> ResponseEntity.ok(MeetingResponse.from(meeting)))
        .orElseGet(() -> ResponseEntity.ok(Map.of("streamKey", "", "selected", false)));
  }

  @PostMapping("/api/admin/clear-meeting")
  public ResponseEntity<?> clear(HttpServletRequest httpRequest) {
    Long adminId = extractAdminId(httpRequest);
    if (adminId == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
    }
    contextService.clear(adminId);
    return ResponseEntity.ok(Map.of("ok", true));
  }

  private Long extractAdminId(HttpServletRequest request) {
    Object value = request.getAttribute("adminId");
    if (value instanceof Long longValue) {
      return longValue;
    }
    if (value instanceof Number numberValue) {
      return numberValue.longValue();
    }
    return null;
  }

  public record SelectMeetingRequest(String streamKey) {}
}
