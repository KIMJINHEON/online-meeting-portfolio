package com.example.hlsviewer.roster;

import com.example.hlsviewer.viewer.ViewerAuthService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/roster")
public class AdminRosterController {
  private final RosterUploadService uploadService;
  private final RosterExportService exportService;
  private final ViewerAuthService viewerAuthService;

  public AdminRosterController(
      RosterUploadService uploadService,
      RosterExportService exportService,
      ViewerAuthService viewerAuthService) {
    this.uploadService = uploadService;
    this.exportService = exportService;
    this.viewerAuthService = viewerAuthService;
  }

  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public RosterUploadResult upload(
      @RequestParam(value = "streamKey", required = false) String streamKey,
      @RequestParam("file") MultipartFile file) {
    RosterUploadResult result = uploadService.upload(streamKey, file);
    if (result.inserted() > 0) {
      viewerAuthService.resetViewerStateForRosterReplace(result.streamKey());
    }
    return result;
  }

  @GetMapping(value = "/download", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
  public ResponseEntity<byte[]> download(@RequestParam(value = "streamKey", required = false) String streamKey) {
    byte[] body = exportService.exportXlsx(streamKey);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header("Content-Disposition", "attachment; filename=\"voter_roster_export.xlsx\"")
        .body(body);
  }
}
