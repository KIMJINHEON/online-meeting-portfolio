package com.example.hlsviewer.chat;

import com.example.hlsviewer.viewer.ViewerSession;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
  private final ChatRoomRepository roomRepository;
  private final ChatMessageRepository messageRepository;
  private final ChatMessageRateLimiter chatMessageRateLimiter;
  private final ChatStreamKeyPolicy chatStreamKeyPolicy;
  private static final int MAX_STREAM_KEY_LENGTH = 100;
  private static final int MAX_SENDER_NAME_LENGTH = 50;
  private static final int MAX_MESSAGE_LENGTH = 1000;

  public ChatController(
      ChatRoomRepository roomRepository,
      ChatMessageRepository messageRepository,
      ChatMessageRateLimiter chatMessageRateLimiter,
      ChatStreamKeyPolicy chatStreamKeyPolicy) {
    this.roomRepository = roomRepository;
    this.messageRepository = messageRepository;
    this.chatMessageRateLimiter = chatMessageRateLimiter;
    this.chatStreamKeyPolicy = chatStreamKeyPolicy;
  }

  @PostMapping("/messages")
  public ResponseEntity<?> createMessage(
      HttpServletRequest httpRequest,
      @RequestBody(required = false) ChatPostRequest request) {
    if (request == null
        || request.streamKey() == null || request.streamKey().isBlank()
        || request.message() == null || request.message().isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "invalid_payload"));
    }

    ViewerSession viewerSession = resolveViewerSession(httpRequest);
    if (viewerSession == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("error", "unauthorized"));
    }
    String streamKey = chatStreamKeyPolicy.normalizeStreamKey(request.streamKey());
    if (streamKey.length() > MAX_STREAM_KEY_LENGTH) {
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
          .body(Map.of("error", "streamKey_too_long"));
    }
    if (!isViewerStreamKeyMatched(viewerSession, streamKey)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .body(Map.of("error", "streamKey_mismatch"));
    }
    if (!chatStreamKeyPolicy.isAllowed(streamKey)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .body(Map.of("error", "streamKey_not_allowed"));
    }
    String message = request.message().trim();
    if (message.length() > MAX_MESSAGE_LENGTH) {
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
          .body(Map.of("error", "message_too_long"));
    }
    String limiterKey = viewerSession == null ? "" : viewerSession.token();
    ChatMessageRateLimiter.Result limitResult = chatMessageRateLimiter.tryAcquire(limiterKey);
    if (!limitResult.allowedRequest()) {
      long retryAfterSec = Math.max(1L, (limitResult.retryAfterMs() + 999L) / 1000L);
      return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
          .header("Retry-After", String.valueOf(retryAfterSec))
          .body(Map.of(
              "error", "message_rate_limited",
              "retryAfterMs", limitResult.retryAfterMs()
          ));
    }

    String senderName = resolveSenderName(viewerSession, request);
    if (senderName.length() > MAX_SENDER_NAME_LENGTH) {
      senderName = senderName.substring(0, MAX_SENDER_NAME_LENGTH);
    }
    long roomId = roomRepository.getOrCreateRoomId(streamKey);
    long messageId = messageRepository.createPending(streamKey, roomId, senderName, message);

    return ResponseEntity.ok(Map.of("id", messageId, "status", "pending"));
  }

  @GetMapping("/messages")
  public ResponseEntity<?> listMessages(
      HttpServletRequest httpRequest,
      @RequestParam("streamKey") String streamKey,
      @RequestParam(name = "afterId", defaultValue = "0") long afterId,
      @RequestParam(name = "limit", defaultValue = "50") int limit) {
    ViewerSession viewerSession = resolveViewerSession(httpRequest);
    if (viewerSession == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("error", "unauthorized"));
    }
    String normalizedStreamKey = chatStreamKeyPolicy.normalizeStreamKey(streamKey);
    if (normalizedStreamKey.isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "streamKey_required"));
    }
    if (normalizedStreamKey.length() > MAX_STREAM_KEY_LENGTH) {
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
          .body(Map.of("error", "streamKey_too_long"));
    }
    if (!isViewerStreamKeyMatched(viewerSession, normalizedStreamKey)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .body(Map.of("error", "streamKey_mismatch"));
    }
    if (!chatStreamKeyPolicy.isAllowed(normalizedStreamKey)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .body(Map.of("error", "streamKey_not_allowed"));
    }
    int safeLimit = Math.min(Math.max(limit, 1), 200);
    long roomId = roomRepository.getOrCreateRoomId(normalizedStreamKey);
    List<ChatMessageResponse> responses = messageRepository.listApproved(roomId, afterId, safeLimit)
        .stream()
        .map(message -> new ChatMessageResponse(
            message.id(),
            message.senderName(),
            message.message(),
            message.createdAt().toEpochMilli()
        ))
        .toList();
    return ResponseEntity.ok(responses);
  }

  private ViewerSession resolveViewerSession(HttpServletRequest httpRequest) {
    if (httpRequest == null) {
      return null;
    }
    Object sessionAttr = httpRequest.getAttribute("viewerSession");
    if (sessionAttr instanceof ViewerSession session) {
      return session;
    }
    return null;
  }

  private boolean isViewerStreamKeyMatched(ViewerSession viewerSession, String requestedStreamKey) {
    String viewerStreamKey = chatStreamKeyPolicy.normalizeStreamKey(
        viewerSession == null ? null : viewerSession.streamKey());
    String requestStreamKey = chatStreamKeyPolicy.normalizeStreamKey(requestedStreamKey);
    return !viewerStreamKey.isBlank() && !requestStreamKey.isBlank() && viewerStreamKey.equals(requestStreamKey);
  }

  private String resolveSenderName(ViewerSession viewerSession, ChatPostRequest request) {
    // ViewerAuthInterceptor puts ViewerSession into request attribute; use it as the source of truth.
    if (viewerSession != null) {
      String name = viewerSession.name();
      if (name != null && !name.isBlank()) {
        return name.trim();
      }
    }
    // Fallback to the client-provided name (backward compatible).
    String fallback = request == null ? null : request.senderName();
    if (fallback != null && !fallback.isBlank()) {
      return fallback.trim();
    }
    return "익명";
  }
}
