package com.example.hlsviewer.audio;

import com.example.hlsviewer.roster.RosterRepository;
import com.example.hlsviewer.viewer.ViewerSession;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/chat/audio")
public class ChatAudioController {
  private static final Logger log = LoggerFactory.getLogger(ChatAudioController.class);
  private final ChatAudioService audioService;
  private final RosterRepository rosterRepository;

  public ChatAudioController(ChatAudioService audioService, RosterRepository rosterRepository) {
    this.audioService = audioService;
    this.rosterRepository = rosterRepository;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<?> upload(
      @RequestParam("streamKey") String streamKey,
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "recorded", required = false) Boolean recorded,
      @RequestAttribute(value = "viewerSession", required = false) ViewerSession viewerSession
  ) {
    if (streamKey == null || streamKey.isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "streamKey_required"));
    }
    if (file == null || file.isEmpty()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "file_required"));
    }
    if (file.getSize() > audioService.getMaxBytes()) {
      return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
          .body(Map.of("error", "file_too_large"));
    }
    String contentType = file.getContentType();
    boolean contentTypeAllowed = false;
    if (contentType != null && !contentType.isBlank()) {
      String lower = contentType.toLowerCase();
      contentTypeAllowed = lower.startsWith("audio/") || "video/webm".equals(lower);
    }
    String originalName = file.getOriginalFilename();
    String name = originalName == null ? "" : originalName.toLowerCase();
    boolean extensionAllowed = name.endsWith(".webm")
        || name.endsWith(".m4a")
        || name.endsWith(".mp3")
        || name.endsWith(".wav")
        || name.endsWith(".aac")
        || name.endsWith(".ogg");
    if (!contentTypeAllowed && !extensionAllowed) {
      return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
          .body(Map.of("error", "audio_only"));
    }

    boolean enforceDuration = recorded != null && recorded;
    try {
      ChatAudio audio =
          audioService.save(
              streamKey.trim(),
              resolveUploaderName(viewerSession),
              file,
              enforceDuration);
      return ResponseEntity.ok(new ChatAudioUploadResponse(
          audio.id(),
          audio.originalName(),
          audio.sizeBytes()
      ));
    } catch (AudioProcessingException ex) {
      log.warn("Audio upload rejected: code={}, message={}", ex.getCode(), ex.getMessage());
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
          .body(Map.of("error", ex.getCode()));
    } catch (IOException ex) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "upload_failed"));
    }
  }

  private String resolveUploaderName(ViewerSession viewerSession) {
    if (viewerSession == null) {
      return "익명";
    }
    String name = normalizeName(viewerSession.name());
    String birth = normalizeBirth(viewerSession.birthDate());
    String phone = formatPhoneHyphen(viewerSession.phone());
    if (name.isEmpty() || birth.isEmpty() || phone.isEmpty()) {
      return name.isEmpty() ? "익명" : name;
    }
    return rosterRepository
        .findAudioUploaderInfo(viewerSession.streamKey(), name, phone, birth)
        .map(info -> {
          String labelName = safeText(info.name(), name);
          String jibun = safeText(info.jibun(), "");
          return jibun.isEmpty() ? labelName : "[" + jibun + "]_" + labelName;
        })
        .orElse(name);
  }

  private String safeText(String value, String fallback) {
    if (value == null) {
      return fallback == null ? "" : fallback;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return fallback == null ? "" : fallback;
    }
    return trimmed;
  }

  private String normalizeName(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder(trimmed.length());
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (!Character.isWhitespace(c)) {
        out.append(c);
      }
    }
    return out.toString();
  }

  private String normalizeBirth(String value) {
    String digits = digitsOnly(value);
    if (digits.isEmpty()) {
      return "";
    }
    return digits.length() >= 6 ? digits.substring(digits.length() - 6) : digits;
  }

  private String formatPhoneHyphen(String value) {
    String digits = normalizePhone(value);
    if (digits.length() == 11 && digits.startsWith("01")) {
      return digits.substring(0, 3) + "-" + digits.substring(3, 7) + "-" + digits.substring(7);
    }
    if (digits.length() == 10 && digits.startsWith("01")) {
      return digits.substring(0, 3) + "-" + digits.substring(3, 6) + "-" + digits.substring(6);
    }
    return "";
  }

  private String normalizePhone(String value) {
    String digits = digitsOnly(value);
    if (digits.isEmpty()) {
      return "";
    }
    if (digits.startsWith("82") && digits.length() >= 10) {
      String rest = digits.substring(2);
      if (rest.startsWith("10")) {
        return "0" + rest;
      }
      return rest;
    }
    if (digits.startsWith("10") && digits.length() == 10) {
      return "0" + digits;
    }
    return digits;
  }

  private String digitsOnly(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    StringBuilder out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c >= '0' && c <= '9') {
        out.append(c);
      }
    }
    return out.toString();
  }
}
