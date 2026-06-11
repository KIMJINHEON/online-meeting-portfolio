package com.example.hlsviewer.roster;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VoteOpenController {
  private final VoteOpenService voteOpenService;

  public VoteOpenController(VoteOpenService voteOpenService) {
    this.voteOpenService = voteOpenService;
  }

  @GetMapping("/api/vote/status")
  public Map<String, Object> status(
      @RequestParam(name = "streamKey", required = false) String streamKey) {
    boolean open = voteOpenService.isOpen(streamKey);
    return Map.of("open", open);
  }
}
