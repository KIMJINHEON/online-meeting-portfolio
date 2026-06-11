package com.example.hlsviewer.admin;

import com.example.hlsviewer.attendance.AttendanceSummaryService;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AdminAttendanceController {
  private final AttendanceSummaryService attendanceSummaryService;

  public AdminAttendanceController(AttendanceSummaryService attendanceSummaryService) {
    this.attendanceSummaryService = attendanceSummaryService;
  }

  @GetMapping("/api/admin/attendance")
  public Map<String, Object> attendance(
      @RequestParam(name = "streamKey", required = false) String streamKey) {
    Map<String, Object> response = new HashMap<>(attendanceSummaryService.summarizeForAdmin(streamKey));
    response.put("manualOnsite", attendanceSummaryService.findManualOnsite(streamKey).orElse(null));
    response.put("manualFieldOnsite", attendanceSummaryService.findManualFieldOnsite(streamKey).orElse(null));
    return response;
  }

  @PostMapping("/api/admin/attendance/manual-onsite")
  public Map<String, Object> updateManualOnsite(
      @RequestParam(name = "streamKey", required = false) String streamKey,
      @RequestBody(required = false) ManualOnsiteRequest request) {
    Long value = request == null ? null : request.onsite();
    if (value == null) {
      attendanceSummaryService.clearManualOnsite(streamKey);
    } else {
      if (value < 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "manual_onsite_must_be_non_negative");
      }
      if (value > 1_000_000L) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "manual_onsite_too_large");
      }
      attendanceSummaryService.setManualOnsite(streamKey, value);
    }
    Map<String, Object> response = new HashMap<>(attendanceSummaryService.summarizeForAdmin(streamKey));
    response.put("manualOnsite", attendanceSummaryService.findManualOnsite(streamKey).orElse(null));
    response.put("manualFieldOnsite", attendanceSummaryService.findManualFieldOnsite(streamKey).orElse(null));
    return response;
  }

  @PostMapping("/api/admin/attendance/manual-field-onsite")
  public Map<String, Object> updateManualFieldOnsite(
      @RequestParam(name = "streamKey", required = false) String streamKey,
      @RequestBody(required = false) ManualOnsiteRequest request) {
    Long value = request == null ? null : request.onsite();
    if (value == null) {
      attendanceSummaryService.clearManualFieldOnsite(streamKey);
    } else {
      if (value < 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "manual_field_onsite_must_be_non_negative");
      }
      if (value > 1_000_000L) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "manual_field_onsite_too_large");
      }
      attendanceSummaryService.setManualFieldOnsite(streamKey, value);
    }
    Map<String, Object> response = new HashMap<>(attendanceSummaryService.summarizeForAdmin(streamKey));
    response.put("manualOnsite", attendanceSummaryService.findManualOnsite(streamKey).orElse(null));
    response.put("manualFieldOnsite", attendanceSummaryService.findManualFieldOnsite(streamKey).orElse(null));
    return response;
  }

  public record ManualOnsiteRequest(Long onsite) {}
}
