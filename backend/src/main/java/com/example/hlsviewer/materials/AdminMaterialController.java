package com.example.hlsviewer.materials;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/materials")
public class AdminMaterialController {
  private final MeetingMaterialService materialService;
  private static final int MAX_TITLE_LENGTH = 200;
  private static final int MAX_BODY_LENGTH = 10000;

  public AdminMaterialController(MeetingMaterialService materialService) {
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

  @PostMapping(path = "/text", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> createText(@RequestBody(required = false) MaterialTextRequest request) {
    if (request == null) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "body_required"));
    }
    if (request.streamKey() == null || request.streamKey().isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "streamKey_required"));
    }
    String title = request.title() == null || request.title().isBlank()
        ? "회의자료"
        : request.title().trim();
    String body = request.body() == null ? "" : request.body().trim();
    if (title.length() > MAX_TITLE_LENGTH) {
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
          .body(Map.of("error", "title_too_long"));
    }
    if (body.length() > MAX_BODY_LENGTH) {
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
          .body(Map.of("error", "body_too_long"));
    }
    MeetingMaterial material = materialService.saveText(request.streamKey().trim(), title, body);
    return ResponseEntity.ok(toResponse(material));
  }

  @PostMapping(path = "/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<?> uploadPdf(
      @RequestParam("streamKey") String streamKey,
      @RequestParam("file") MultipartFile file
  ) {
    if (streamKey == null || streamKey.isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "streamKey_required"));
    }
    if (file == null || file.isEmpty()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "file_required"));
    }
    if (file.getSize() > materialService.getMaxBytes()) {
      return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
          .body(Map.of("error", "file_too_large"));
    }
    String contentType = file.getContentType();
    String lowerType = contentType == null ? "" : contentType.toLowerCase();
    String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
    boolean isPdf = lowerType.equals("application/pdf") || name.endsWith(".pdf");
    if (!isPdf) {
      return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
          .body(Map.of("error", "pdf_only"));
    }
    try {
      MeetingMaterial material = materialService.savePdf(streamKey.trim(), file);
      return ResponseEntity.ok(toResponse(material));
    } catch (IOException ex) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "upload_failed"));
    }
  }

  @PatchMapping("/{id}")
  public ResponseEntity<?> update(
      @PathVariable("id") long id,
      @RequestParam("streamKey") String streamKey,
      @RequestBody(required = false) MaterialUpdateRequest request,
      HttpServletRequest httpRequest
  ) {
    Long adminId = (Long) httpRequest.getAttribute("adminId");
    if (adminId == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("error", "unauthorized"));
    }
    if (streamKey == null || streamKey.isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "streamKey_required"));
    }
    if (request == null) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "body_required"));
    }
    Optional<MeetingMaterial> existing = materialService.findActive(streamKey.trim(), id);
    if (existing.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(Map.of("error", "not_found"));
    }
    MeetingMaterial material = existing.get();
    String nextTitle = request.title();
    if (nextTitle != null && nextTitle.isBlank()) {
      nextTitle = null;
    }
    if ("text".equals(material.type())) {
      String title = nextTitle == null ? material.title() : nextTitle.trim();
      String body = request.body() == null ? material.body() : request.body().trim();
      if (title.length() > MAX_TITLE_LENGTH) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(Map.of("error", "title_too_long"));
      }
      if (body.length() > MAX_BODY_LENGTH) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(Map.of("error", "body_too_long"));
      }
      MeetingMaterial updated = materialService.updateText(streamKey.trim(), id, title, body);
      return ResponseEntity.ok(toResponse(updated));
    }
    if ("pdf".equals(material.type())) {
      String title = nextTitle == null ? material.title() : nextTitle.trim();
      if (title.length() > MAX_TITLE_LENGTH) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(Map.of("error", "title_too_long"));
      }
      MeetingMaterial updated = materialService.updateTitle(streamKey.trim(), id, title);
      return ResponseEntity.ok(toResponse(updated));
    }
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("error", "invalid_type"));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<?> delete(
      @PathVariable("id") long id,
      @RequestParam("streamKey") String streamKey,
      HttpServletRequest httpRequest) {
    Long adminId = (Long) httpRequest.getAttribute("adminId");
    if (adminId == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("error", "unauthorized"));
    }
    if (streamKey == null || streamKey.isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "streamKey_required"));
    }
    int updated = materialService.delete(streamKey.trim(), id);
    if (updated == 0) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(Map.of("error", "not_found"));
    }
    return ResponseEntity.ok(Map.of("deleted", updated));
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
