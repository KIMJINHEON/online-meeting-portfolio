package com.example.hlsviewer.materials;

import com.example.hlsviewer.viewer.ViewerSession;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/materials")
public class MaterialController {
  private final MeetingMaterialService materialService;

  public MaterialController(MeetingMaterialService materialService) {
    this.materialService = materialService;
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
    List<MaterialResponse> responses = materialService.list(streamKey.trim(), safeLimit)
        .stream()
        .map(this::toResponse)
        .toList();
    return ResponseEntity.ok(responses);
  }

  @GetMapping("/{id}/file")
  public ResponseEntity<?> download(@PathVariable("id") long id, HttpServletRequest request) {
    String streamKey = resolveStreamKey(request);
    if (streamKey == null || streamKey.isBlank()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
    }
    return materialService.load(streamKey, id)
        .map(file -> {
          if (!Files.exists(file.path())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "file_missing"));
          }
          return ResponseEntity.ok()
              .contentType(MediaType.APPLICATION_PDF)
              .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
              .body(new FileSystemResource(file.path()));
        })
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found")));
  }

  private String resolveStreamKey(HttpServletRequest request) {
    Object attr = request.getAttribute("viewerSession");
    if (attr instanceof ViewerSession session) {
      return session.streamKey();
    }
    return null;
  }

  private MaterialResponse toResponse(MeetingMaterial material) {
    String url = "pdf".equals(material.type()) ? "/api/materials/" + material.id() + "/file" : null;
    return new MaterialResponse(
        material.id(),
        material.type(),
        material.title(),
        material.body(),
        url,
        material.sizeBytes(),
        material.createdAt().toEpochMilli()
    );
  }
}
