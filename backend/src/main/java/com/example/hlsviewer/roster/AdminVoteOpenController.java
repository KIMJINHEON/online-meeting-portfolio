package com.example.hlsviewer.roster;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminVoteOpenController {
  private final VoteOpenService voteOpenService;

  public AdminVoteOpenController(VoteOpenService voteOpenService) {
    this.voteOpenService = voteOpenService;
  }

  @GetMapping("/api/admin/vote/status")
  public ResponseEntity<?> status(
      @RequestParam(name = "streamKey", required = false) String streamKey,
      HttpServletRequest request) {
    Long adminId = (Long) request.getAttribute("adminId");
    if (adminId == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("error", "unauthorized"));
    }
    boolean open = voteOpenService.isOpen(streamKey);
    return ResponseEntity.ok(Map.of("open", open));
  }

  @PostMapping("/api/admin/vote/open")
  public ResponseEntity<?> open(
      @RequestParam(name = "streamKey", required = false) String streamKey,
      HttpServletRequest request) {
    Long adminId = (Long) request.getAttribute("adminId");
    if (adminId == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("error", "unauthorized"));
    }
    boolean open = voteOpenService.setOpen(streamKey, true);
    return ResponseEntity.ok(Map.of("open", open));
  }

  @PostMapping("/api/admin/vote/close")
  public ResponseEntity<?> close(
      @RequestParam(name = "streamKey", required = false) String streamKey,
      HttpServletRequest request) {
    Long adminId = (Long) request.getAttribute("adminId");
    if (adminId == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("error", "unauthorized"));
    }
    boolean open = voteOpenService.setOpen(streamKey, false);
    return ResponseEntity.ok(Map.of("open", open));
  }
}
