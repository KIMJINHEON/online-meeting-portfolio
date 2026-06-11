package com.example.hlsviewer.chat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/messages")
public class AdminMessageController {
  private final ChatRoomRepository roomRepository;
  private final ChatMessageRepository messageRepository;

  public AdminMessageController(ChatRoomRepository roomRepository, ChatMessageRepository messageRepository) {
    this.roomRepository = roomRepository;
    this.messageRepository = messageRepository;
  }

  @GetMapping
  public ResponseEntity<?> listMessages(
      @RequestParam("streamKey") String streamKey,
      @RequestParam(name = "status", defaultValue = "pending") String status,
      @RequestParam(name = "limit", defaultValue = "100") int limit) {
    if (streamKey == null || streamKey.isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "streamKey_required"));
    }
    String normalizedStatus = status == null ? "pending" : status.trim().toLowerCase();
    if (!normalizedStatus.equals("pending") && !normalizedStatus.equals("approved") && !normalizedStatus.equals("rejected")) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "invalid_status"));
    }
    int safeLimit = Math.min(Math.max(limit, 1), 200);
    long roomId = roomRepository.getOrCreateRoomId(streamKey.trim());
    List<ChatAdminMessageResponse> responses = messageRepository.listByStatus(roomId, normalizedStatus, safeLimit)
        .stream()
        .map(message -> new ChatAdminMessageResponse(
            message.id(),
            message.senderName(),
            message.message(),
            message.status(),
            message.createdAt().toEpochMilli()
        ))
        .toList();
    return ResponseEntity.ok(responses);
  }

  @PostMapping("/{id}/approve")
  public ResponseEntity<?> approve(
      @PathVariable("id") long id,
      @RequestParam("streamKey") String streamKey,
      HttpServletRequest request) {
    Long adminId = (Long) request.getAttribute("adminId");
    if (adminId == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("error", "unauthorized"));
    }
    if (streamKey == null || streamKey.isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "streamKey_required"));
    }
    int updated = messageRepository.updateStatus(streamKey, id, "approved", adminId);
    return ResponseEntity.ok(Map.of("updated", updated));
  }

  @PostMapping("/{id}/reject")
  public ResponseEntity<?> reject(
      @PathVariable("id") long id,
      @RequestParam("streamKey") String streamKey,
      HttpServletRequest request) {
    Long adminId = (Long) request.getAttribute("adminId");
    if (adminId == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("error", "unauthorized"));
    }
    if (streamKey == null || streamKey.isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "streamKey_required"));
    }
    int updated = messageRepository.updateStatus(streamKey, id, "rejected", adminId);
    return ResponseEntity.ok(Map.of("updated", updated));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<?> delete(
      @PathVariable("id") long id,
      @RequestParam("streamKey") String streamKey,
      HttpServletRequest request) {
    Long adminId = (Long) request.getAttribute("adminId");
    if (adminId == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("error", "unauthorized"));
    }
    if (streamKey == null || streamKey.isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "streamKey_required"));
    }

    long roomId = roomRepository.getOrCreateRoomId(streamKey.trim());
    int deleted = messageRepository.deleteByRoomAndId(roomId, id);
    return ResponseEntity.ok(Map.of("deleted", deleted));
  }
}
