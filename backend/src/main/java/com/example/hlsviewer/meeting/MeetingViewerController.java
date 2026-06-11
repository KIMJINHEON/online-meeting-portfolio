package com.example.hlsviewer.meeting;

import com.example.hlsviewer.roster.RosterRepository;
import com.example.hlsviewer.viewer.ViewerAuthService;
import com.example.hlsviewer.viewer.ViewerSession;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeetingViewerController {
  private final MeetingService meetingService;
  private final RosterRepository rosterRepository;
  private final ViewerAuthService viewerAuthService;

  public MeetingViewerController(
      MeetingService meetingService,
      RosterRepository rosterRepository,
      ViewerAuthService viewerAuthService) {
    this.meetingService = meetingService;
    this.rosterRepository = rosterRepository;
    this.viewerAuthService = viewerAuthService;
  }

  /**
   * Returns the list of meetings whose roster contains the authenticated viewer.
   * Window/time enforcement is not applied here — the viewer can see every meeting
   * they are rostered for, regardless of whether access is currently open. The actual
   * gate is applied at {@link #enterMeeting}.
   */
  @GetMapping("/api/viewer/my-meetings")
  public ResponseEntity<?> myMeetings(HttpServletRequest request) {
    ViewerSession session = extractSession(request);
    if (session == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
    }

    List<String> streamKeys = rosterRepository.findStreamKeysByPerson(
        session.name(), session.phone(), session.birthDate());
    if (streamKeys.isEmpty()) {
      return ResponseEntity.ok(Map.of("meetings", List.of()));
    }

    List<MeetingResponse> meetings = meetingService.findByStreamKeys(streamKeys).stream()
        .map(MeetingResponse::from)
        .toList();
    return ResponseEntity.ok(Map.of("meetings", meetings));
  }

  /**
   * Final gate before a viewer joins a specific meeting.
   *
   * <p>Verifies (1) the viewer is on that meeting's roster, (2) the meeting still exists, and
   * (3) the admin has flipped access_open=true. Only on success is the viewer's session
   * streamKey rewritten so downstream APIs route to this meeting.
   */
  @PostMapping("/api/viewer/enter-meeting")
  public ResponseEntity<?> enterMeeting(@RequestBody EnterMeetingRequest body, HttpServletRequest request) {
    ViewerSession session = extractSession(request);
    if (session == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
    }
    if (body == null || body.streamKey() == null || body.streamKey().isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "stream_key_required"));
    }

    List<String> rosteredKeys = rosterRepository.findStreamKeysByPerson(
        session.name(), session.phone(), session.birthDate());
    String target = body.streamKey().trim();
    if (!rosteredKeys.contains(target)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "not_in_roster"));
    }

    return meetingService.findByStreamKey(target)
        .<ResponseEntity<?>>map(meeting -> {
          if (!meeting.accessOpen()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "access_closed", "message", "접속 가능한 시간이 아닙니다."));
          }
          viewerAuthService.updateStreamKey(session.token(), meeting.streamKey());
          return ResponseEntity.ok(MeetingResponse.from(meeting));
        })
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "meeting_not_found")));
  }

  private ViewerSession extractSession(HttpServletRequest request) {
    Object attr = request.getAttribute("viewerSession");
    return attr instanceof ViewerSession session ? session : null;
  }

  public record EnterMeetingRequest(String streamKey) {}
}
