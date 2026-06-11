package com.example.hlsviewer.wowza;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stats")
public class WowzaStatsController {
  private final WowzaStatsService statsService;

  public WowzaStatsController(WowzaStatsService statsService) {
    this.statsService = statsService;
  }

  @GetMapping
  public ResponseEntity<?> getStats() {
    return statsService.fetchCurrentStats()
        .<ResponseEntity<?>>map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("error", "wowza_unavailable")));
  }
}
