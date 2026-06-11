package com.example.hlsviewer.roster;

import com.example.hlsviewer.viewer.ViewerSession;
import java.util.Locale;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VoteEligibilityController {
  private final RosterRepository rosterRepository;
  private final VoteOpenService voteOpenService;

  public VoteEligibilityController(RosterRepository rosterRepository, VoteOpenService voteOpenService) {
    this.rosterRepository = rosterRepository;
    this.voteOpenService = voteOpenService;
  }

  @GetMapping("/api/vote/onsite")
  public VoteEligibilityResponse onsite(
      @RequestParam(name = "streamKey", required = false) String streamKey,
      @RequestAttribute(value = "viewerSession", required = false) ViewerSession viewerSession) {
    if (!voteOpenService.isOpen(streamKey)) {
      return new VoteEligibilityResponse(false, "vote_not_open");
    }
    if (viewerSession == null) {
      return new VoteEligibilityResponse(false, "unauthorized");
    }
    try {
      if (rosterRepository.countAll() == 0) {
        return new VoteEligibilityResponse(false, "roster_empty");
      }
    } catch (Exception ex) {
      return new VoteEligibilityResponse(false, "roster_db_error");
    }

    String nameKey = RosterVerifier.normalizeName(viewerSession.name());
    String birthKey = RosterVerifier.normalizeBirth(viewerSession.birthDate());
    String phoneKey = RosterVerifier.formatPhoneHyphen(viewerSession.phone());
    String resolvedStreamKey =
        (streamKey == null || streamKey.isBlank()) ? viewerSession.streamKey() : streamKey;

    Optional<RosterRepository.VoteContext> voteContext =
        rosterRepository.findVoteContext(resolvedStreamKey, nameKey, phoneKey, birthKey);
    if (voteContext.isEmpty()) {
      return new VoteEligibilityResponse(false, "user_not_found");
    }

    RosterRepository.VoteContext context = voteContext.get();
    if (hasText(context.paperSubmitConfirm())) {
      return new VoteEligibilityResponse(false, "paper_vote_already_used");
    }
    if (hasText(context.mailSubmitConfirm())) {
      return new VoteEligibilityResponse(false, "mail_vote_already_used");
    }
    if (hasText(context.electronicVote())) {
      return new VoteEligibilityResponse(false, "electronic_vote_already_used");
    }

    boolean allowed = isAllowed(context.onsiteVoteAllowed());
    if (!allowed) {
      return new VoteEligibilityResponse(false, "onsite_vote_not_allowed");
    }
    return new VoteEligibilityResponse(true, "");
  }

  private boolean isAllowed(String value) {
    if (value == null) {
      return false;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return false;
    }
    String lower = trimmed.toLowerCase(Locale.ROOT);

    if ("가능".equals(trimmed)) {
      return true;
    }
    if ("불가".equals(trimmed)) {
      return false;
    }

    if ("y".equals(lower) || "yes".equals(lower) || "true".equals(lower) || "1".equals(lower) || "o".equals(lower)) {
      return true;
    }
    if ("n".equals(lower) || "no".equals(lower) || "false".equals(lower) || "0".equals(lower) || "x".equals(lower)) {
      return false;
    }

    if (lower.contains("불가") || lower.contains("no") || lower.contains("false")) {
      return false;
    }
    if (lower.contains("가능") || lower.contains("yes") || lower.contains("true")) {
      return true;
    }

    return false;
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }

  public record VoteEligibilityResponse(boolean allowed, String reason) {}
}
