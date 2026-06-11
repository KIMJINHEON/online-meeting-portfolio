package com.example.hlsviewer.viewer;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/viewer")
public class ViewerPlaybackController {
  private static final Logger log = LoggerFactory.getLogger(ViewerPlaybackController.class);

  // Keep the accepted set tight so a malicious client can't spam arbitrary tags into logs.
  private static final Set<String> ALLOWED_EVENT_TYPES = Set.of(
      "stall",            // valueMs = stall duration
      "fatal_network",
      "fatal_media",
      "origin_fallback",
      "reload"
  );

  private final ViewerAuthService authService;

  public ViewerPlaybackController(ViewerAuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/play")
  public ResponseEntity<?> play(@RequestAttribute("viewerSession") ViewerSession viewerSession) {
    if (viewerSession != null) {
      authService.markFirstPlay(viewerSession.token());
    }
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/playback-event")
  public ResponseEntity<?> playbackEvent(
      @RequestAttribute("viewerSession") ViewerSession viewerSession,
      @RequestBody PlaybackEventRequest body) {
    if (viewerSession == null || body == null) {
      return ResponseEntity.noContent().build();
    }
    String type = body.type() == null ? "" : body.type().trim();
    if (!ALLOWED_EVENT_TYPES.contains(type)) {
      return ResponseEntity.noContent().build();
    }
    Long valueMs = body.valueMs();
    String detail = body.detail() == null ? "" : body.detail().trim();
    if (detail.length() > 80) {
      detail = detail.substring(0, 80);
    }
    log.info(
        "playback_event stream_key={} name={} phone={} type={} value_ms={} detail={}",
        viewerSession.streamKey(),
        viewerSession.name(),
        viewerSession.phone(),
        type,
        valueMs == null ? "-" : valueMs.toString(),
        detail.isEmpty() ? "-" : detail
    );
    return ResponseEntity.noContent().build();
  }

  public record PlaybackEventRequest(String type, Long valueMs, String detail) {}
}
