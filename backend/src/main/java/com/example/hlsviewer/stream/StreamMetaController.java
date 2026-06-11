package com.example.hlsviewer.stream;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StreamMetaController {
  private final StreamMetaService streamMetaService;

  public StreamMetaController(StreamMetaService streamMetaService) {
    this.streamMetaService = streamMetaService;
  }

  @GetMapping("/api/stream-meta")
  public StreamMetaResponse getMeta(@RequestParam(name = "streamKey", required = false) String streamKey) {
    return streamMetaService.getMeta(streamKey);
  }

  @GetMapping("/api/admin/stream-meta")
  public StreamMetaResponse getAdminMeta(@RequestParam(name = "streamKey", required = false) String streamKey) {
    return streamMetaService.getAdminMeta(streamKey);
  }

  @PostMapping("/api/admin/stream-meta")
  public ResponseEntity<?> update(@RequestBody(required = false) StreamMetaUpdateRequest request) {
    if (request == null) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "invalid_request"));
    }
    return ResponseEntity.ok(streamMetaService.update(request));
  }
}
