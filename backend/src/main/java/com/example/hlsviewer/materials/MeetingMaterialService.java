package com.example.hlsviewer.materials;

import com.example.hlsviewer.chat.ChatRoomRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MeetingMaterialService {
  private final MeetingMaterialRepository materialRepository;
  private final ChatRoomRepository roomRepository;
  private final Path storageRoot;
  private final long maxBytes;
  private static final int MAX_TITLE_LENGTH = 200;
  private static final int MAX_ORIGINAL_NAME_LENGTH = 200;

  public record MaterialFile(MeetingMaterial material, Path path) {}

  public MeetingMaterialService(
      MeetingMaterialRepository materialRepository,
      ChatRoomRepository roomRepository,
      MaterialStorageProperties properties
  ) {
    this.materialRepository = materialRepository;
    this.roomRepository = roomRepository;
    this.storageRoot = Paths.get(properties.getStoragePath());
    this.maxBytes = properties.getMaxBytes();
    try {
      Files.createDirectories(this.storageRoot);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to create material storage directory", ex);
    }
  }

  public long getMaxBytes() {
    return maxBytes;
  }

  public MeetingMaterial saveText(String streamKey, String title, String body) {
    long roomId = roomRepository.getOrCreateRoomId(streamKey);
    long id = materialRepository.createText(streamKey, roomId, title, body);
    return materialRepository.findActiveByIdAndStreamKey(id, streamKey).orElseThrow();
  }

  public MeetingMaterial savePdf(String streamKey, MultipartFile file) throws IOException {
    String originalName = sanitizeOriginalName(file.getOriginalFilename());
    long roomId = roomRepository.getOrCreateRoomId(streamKey);
    String storedName = generateStoredName(originalName);
    Path targetPath = storageRoot.resolve(storedName);
    Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

    long id;
    try {
      id = materialRepository.createPdf(
          streamKey,
          roomId,
          truncateTitle(originalName),
          storedName,
          "application/pdf",
          file.getSize()
      );
    } catch (RuntimeException ex) {
      try {
        Files.deleteIfExists(targetPath);
      } catch (IOException ignore) {
        // ignore cleanup failure
      }
      throw ex;
    }
    return materialRepository.findActiveByIdAndStreamKey(id, streamKey).orElseThrow();
  }

  public List<MeetingMaterial> list(String streamKey, int limit) {
    long roomId = roomRepository.getOrCreateRoomId(streamKey);
    return materialRepository.listActive(roomId, limit);
  }

  public Optional<MeetingMaterial> findActive(String streamKey, long id) {
    return materialRepository.findActiveByIdAndStreamKey(id, streamKey);
  }

  public MeetingMaterial updateText(String streamKey, long id, String title, String body) {
    materialRepository.updateText(streamKey, id, title, body);
    return materialRepository.findActiveByIdAndStreamKey(id, streamKey).orElseThrow();
  }

  public MeetingMaterial updateTitle(String streamKey, long id, String title) {
    materialRepository.updateTitle(streamKey, id, title);
    return materialRepository.findActiveByIdAndStreamKey(id, streamKey).orElseThrow();
  }

  public int delete(String streamKey, long id) {
    Optional<MeetingMaterial> existing = materialRepository.findActiveByIdAndStreamKey(id, streamKey);
    int updated = materialRepository.softDelete(streamKey, id);
    if (updated > 0) {
      existing.ifPresent(material -> {
        if ("pdf".equals(material.type()) && material.storedName() != null) {
          try {
            Files.deleteIfExists(storageRoot.resolve(material.storedName()));
          } catch (IOException ignore) {
            // ignore cleanup failure
          }
        }
      });
    }
    return updated;
  }

  public Optional<MaterialFile> load(String streamKey, long id) {
    return materialRepository.findActiveByIdAndStreamKey(id, streamKey)
        .map(material -> new MaterialFile(material, storageRoot.resolve(material.storedName())));
  }

  private String sanitizeOriginalName(String original) {
    if (original == null || original.isBlank()) {
      return "meeting-material.pdf";
    }
    String trimmed = original.strip();
    String withoutControls = trimmed.replaceAll("[\\r\\n\\t]", "_");
    String safe = withoutControls.replaceAll("[\\\\/]", "_");
    if (safe.length() > MAX_ORIGINAL_NAME_LENGTH) {
      safe = safe.substring(0, MAX_ORIGINAL_NAME_LENGTH);
    }
    return safe.toLowerCase().endsWith(".pdf") ? safe : safe + ".pdf";
  }

  private String generateStoredName(String originalName) {
    String safeName = sanitizeOriginalName(originalName);
    String prefix = UUID.randomUUID().toString();
    return prefix + "_" + safeName;
  }

  private String truncateTitle(String title) {
    if (title == null) {
      return "";
    }
    String trimmed = title.trim();
    if (trimmed.length() <= MAX_TITLE_LENGTH) {
      return trimmed;
    }
    return trimmed.substring(0, MAX_TITLE_LENGTH);
  }
}
