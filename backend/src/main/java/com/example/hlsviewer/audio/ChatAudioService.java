package com.example.hlsviewer.audio;

import com.example.hlsviewer.chat.ChatRoomRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ChatAudioService {
  private static final Logger log = LoggerFactory.getLogger(ChatAudioService.class);
  private final ChatAudioRepository audioRepository;
  private final ChatRoomRepository roomRepository;
  private final Path storageRoot;
  private final long maxBytes;
  private static final int MAX_SECONDS = 120;
  private static final int TARGET_BITRATE = 96000;
  private static final int MAX_ORIGINAL_NAME_LENGTH = 200;
  private static final long FFMPEG_TIMEOUT_SECONDS = 120;
  private static final long FFPROBE_TIMEOUT_SECONDS = 15;

  public record AudioFile(ChatAudio audio, Path path) {}

  public ChatAudioService(
      ChatAudioRepository audioRepository,
      ChatRoomRepository roomRepository,
      AudioStorageProperties properties
  ) {
    this.audioRepository = audioRepository;
    this.roomRepository = roomRepository;
    this.storageRoot = Paths.get(properties.getStoragePath());
    this.maxBytes = properties.getMaxBytes();
    try {
      Files.createDirectories(this.storageRoot);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to create audio storage directory", ex);
    }
  }

  public long getMaxBytes() {
    return maxBytes;
  }

  public ChatAudio save(String streamKey, String uploaderName, MultipartFile file, boolean enforceDuration)
      throws IOException {
    String originalName = sanitizeOriginalName(file.getOriginalFilename());
    long roomId = roomRepository.getOrCreateRoomId(streamKey);

    String baseName = UUID.randomUUID().toString();
    Path tempPath = storageRoot.resolve(baseName + ".upload");
    Path targetPath = storageRoot.resolve(baseName + ".m4a");
    Files.copy(file.getInputStream(), tempPath, StandardCopyOption.REPLACE_EXISTING);

    try {
      transcodeToM4a(tempPath, targetPath);
      if (enforceDuration) {
        validateDuration(targetPath);
      }
    } catch (AudioProcessingException ex) {
      cleanupFile(tempPath);
      cleanupFile(targetPath);
      throw ex;
    } catch (IOException ex) {
      cleanupFile(tempPath);
      cleanupFile(targetPath);
      throw ex;
    } finally {
      cleanupFile(tempPath);
    }

    String storedName = targetPath.getFileName().toString();
    String contentType = "audio/mp4";
    long sizeBytes = Files.size(targetPath);

    long id;
    try {
      id = audioRepository.create(
          roomId,
          uploaderName == null ? "" : uploaderName,
          originalName,
          storedName,
          contentType,
          sizeBytes
      );
    } catch (RuntimeException ex) {
      cleanupFile(targetPath);
      throw ex;
    }

    return new ChatAudio(
        id,
        roomId,
        uploaderName == null ? "" : uploaderName,
        originalName,
        storedName,
        contentType,
        sizeBytes,
        java.time.Instant.now(),
        null
    );
  }

  public List<ChatAudio> list(String streamKey, int limit) {
    long roomId = roomRepository.getOrCreateRoomId(streamKey);
    return audioRepository.listActive(roomId, limit);
  }

  public Optional<AudioFile> load(String streamKey, long id) {
    return audioRepository.findActiveByIdAndStreamKey(id, streamKey)
        .map(audio -> new AudioFile(audio, storageRoot.resolve(audio.storedName())));
  }

  public int delete(String streamKey, long id, long adminId) {
    Optional<ChatAudio> audio = audioRepository.findActiveByIdAndStreamKey(id, streamKey);
    if (audio.isEmpty()) {
      return 0;
    }
    int updated = audioRepository.markDeleted(streamKey, id, adminId);
    if (updated > 0) {
      try {
        Files.deleteIfExists(storageRoot.resolve(audio.get().storedName()));
      } catch (IOException ex) {
        // ignore file deletion failure
      }
    }
    return updated;
  }

  public int deleteBulk(String streamKey, List<Long> ids, long adminId) {
    if (ids == null || ids.isEmpty()) {
      return 0;
    }
    List<ChatAudio> files = audioRepository.findActiveByIdsAndStreamKey(ids, streamKey);
    int updated = audioRepository.markDeletedBulk(streamKey, ids, adminId);
    if (updated > 0) {
      for (ChatAudio audio : files) {
        try {
          Files.deleteIfExists(storageRoot.resolve(audio.storedName()));
        } catch (IOException ex) {
          // ignore
        }
      }
    }
    return updated;
  }

  private String sanitizeOriginalName(String original) {
    if (original == null || original.isBlank()) {
      return "audio";
    }
    String trimmed = original.strip();
    String withoutControls = trimmed.replaceAll("[\\r\\n\\t]", "_");
    String safe = withoutControls.replaceAll("[\\\\/]", "_");
    if (safe.length() > MAX_ORIGINAL_NAME_LENGTH) {
      return safe.substring(0, MAX_ORIGINAL_NAME_LENGTH);
    }
    return safe;
  }

  private void transcodeToM4a(Path inputPath, Path outputPath) throws IOException {
    List<String> command = new ArrayList<>();
    command.add("ffmpeg");
    command.add("-nostdin");
    command.add("-loglevel");
    command.add("error");
    command.add("-y");
    command.add("-i");
    command.add(inputPath.toAbsolutePath().toString());
    command.add("-vn");
    command.add("-c:a");
    command.add("aac");
    command.add("-b:a");
    command.add(String.valueOf(TARGET_BITRATE));
    command.add("-movflags");
    command.add("+faststart");
    command.add(outputPath.toAbsolutePath().toString());

    ProcessResult result = runProcess(command, FFMPEG_TIMEOUT_SECONDS);
    if (result.timedOut) {
      throw new AudioProcessingException("process_timeout", "ffmpeg timeout");
    }
    if (result.exitCode != 0) {
      throw new AudioProcessingException("transcode_failed", "ffmpeg failed: " + result.output);
    }
    if (!Files.exists(outputPath) || Files.size(outputPath) == 0) {
      throw new AudioProcessingException("transcode_failed", "m4a output missing");
    }
  }

  private void validateDuration(Path inputPath) throws IOException {
    List<String> command = List.of(
        "ffprobe",
        "-v",
        "error",
        "-show_entries",
        "format=duration:stream=duration",
        "-of",
        "default=noprint_wrappers=1:nokey=1",
        inputPath.toAbsolutePath().toString()
    );
    ProcessResult result = runProcess(command, FFPROBE_TIMEOUT_SECONDS);
    if (result.timedOut) {
      throw new AudioProcessingException("process_timeout", "ffprobe timeout");
    }
    if (result.exitCode != 0) {
      throw new AudioProcessingException("duration_check_failed", "ffprobe failed: " + result.output);
    }
    double seconds = extractDurationSeconds(result.output);
    if (seconds <= 0) {
      // Fallback for files where container metadata omits duration.
      // We transcode to AAC with a target bitrate, so file-size-based estimation is acceptable.
      double estimatedSeconds = estimateDurationBySize(inputPath);
      if (estimatedSeconds <= 0) {
        log.warn("Audio duration parsing failed. ffprobe output: {}", result.output);
        throw new AudioProcessingException("duration_check_failed", "duration missing");
      }
      if (estimatedSeconds > MAX_SECONDS + 2.0) {
        throw new AudioProcessingException("duration_exceeded", "duration too long");
      }
      log.warn(
          "Audio duration metadata missing. Using size-based estimate: {}s, ffprobe output: {}",
          Math.round(estimatedSeconds * 100.0) / 100.0,
          result.output
      );
      return;
    }
    if (seconds > MAX_SECONDS + 0.5) {
      throw new AudioProcessingException("duration_exceeded", "duration too long");
    }
  }

  private double extractDurationSeconds(String output) {
    if (output == null || output.isBlank()) {
      return -1;
    }
    double maxDuration = -1;
    String[] lines = output.split("\\R");
    for (String line : lines) {
      String trimmed = line == null ? "" : line.trim();
      if (trimmed.isEmpty() || "N/A".equalsIgnoreCase(trimmed)) {
        continue;
      }
      if (trimmed.indexOf(',') >= 0 && trimmed.indexOf('.') < 0) {
        trimmed = trimmed.replace(',', '.');
      }
      try {
        double value = Double.parseDouble(trimmed);
        if (Double.isFinite(value) && value > maxDuration) {
          maxDuration = value;
        }
      } catch (NumberFormatException ignore) {
        // ignore unparsable lines
      }
    }
    return maxDuration;
  }

  private double estimateDurationBySize(Path inputPath) throws IOException {
    long sizeBytes = Files.size(inputPath);
    if (sizeBytes <= 0) {
      return -1;
    }
    return (sizeBytes * 8.0) / TARGET_BITRATE;
  }

  private ProcessResult runProcess(List<String> command, long timeoutSeconds) throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    Process process;
    try {
      process = builder.start();
    } catch (IOException ex) {
      String cmd = command.isEmpty() ? "" : command.get(0);
      throw new AudioProcessingException("ffmpeg_missing", "missing command: " + cmd);
    }
    StringBuilder output = new StringBuilder();
    Thread readerThread = new Thread(() -> {
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          output.append(line).append('\n');
        }
      } catch (IOException ignore) {
        // ignore read failures
      }
    });
    readerThread.setDaemon(true);
    readerThread.start();
    boolean finished;
    try {
      finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AudioProcessingException("process_interrupted", "process interrupted");
    }
    if (!finished) {
      process.destroyForcibly();
    }
    try {
      readerThread.join(1000);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
    int exit = finished ? process.exitValue() : -1;
    return new ProcessResult(exit, output.toString(), !finished);
  }

  private void cleanupFile(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignore) {
      // ignore cleanup failure
    }
  }

  private record ProcessResult(int exitCode, String output, boolean timedOut) {}
}
