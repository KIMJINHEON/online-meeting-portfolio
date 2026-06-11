package com.example.hlsviewer.audio;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/audio")
public class AdminAudioController {
  private final ChatAudioService audioService;

  public AdminAudioController(ChatAudioService audioService) {
    this.audioService = audioService;
  }

  @GetMapping
  public ResponseEntity<?> list(
      @RequestParam("streamKey") String streamKey,
      @RequestParam(name = "limit", defaultValue = "100") int limit
  ) {
    if (streamKey == null || streamKey.isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "streamKey_required"));
    }
    int safeLimit = Math.min(Math.max(limit, 1), 200);
    List<ChatAudioResponse> responses = audioService.list(streamKey.trim(), safeLimit)
        .stream()
        .map(audio -> new ChatAudioResponse(
            audio.id(),
            audio.uploaderName(),
            audio.originalName(),
            audio.contentType(),
            audio.sizeBytes(),
            audio.createdAt().toEpochMilli()
        ))
        .toList();
    return ResponseEntity.ok(responses);
  }

  @GetMapping("/{id}/download")
  public ResponseEntity<?> download(
      @PathVariable("id") long id,
      @RequestParam("streamKey") String streamKey) {
    if (streamKey == null || streamKey.isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "streamKey_required"));
    }
    return audioService.load(streamKey.trim(), id)
        .map(file -> {
          if (!Files.exists(file.path())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "file_missing"));
          }
          String contentType = file.audio().contentType();
          MediaType mediaType = (contentType == null || contentType.isBlank())
              ? MediaType.APPLICATION_OCTET_STREAM
              : MediaType.parseMediaType(contentType);
          String filename = file.audio().originalName();
          return ResponseEntity.ok()
              .contentType(mediaType)
              .header(
                  HttpHeaders.CONTENT_DISPOSITION,
                  "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8)
              )
              .body(new FileSystemResource(file.path()));
        })
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found")));
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
    int updated = audioService.delete(streamKey.trim(), id, adminId);
    return ResponseEntity.ok(Map.of("deleted", updated, "timestamp", Instant.now().toEpochMilli()));
  }

  @PostMapping("/delete")
  public ResponseEntity<?> deleteBulk(@RequestBody(required = false) ChatAudioDeleteRequest request,
                                      HttpServletRequest httpRequest) {
    Long adminId = (Long) httpRequest.getAttribute("adminId");
    if (adminId == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("error", "unauthorized"));
    }
    if (request == null || request.ids() == null || request.ids().isEmpty()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "ids_required"));
    }
    if (request.streamKey() == null || request.streamKey().isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "streamKey_required"));
    }
    int updated = audioService.deleteBulk(request.streamKey().trim(), request.ids(), adminId);
    return ResponseEntity.ok(Map.of("deleted", updated, "timestamp", Instant.now().toEpochMilli()));
  }
}
