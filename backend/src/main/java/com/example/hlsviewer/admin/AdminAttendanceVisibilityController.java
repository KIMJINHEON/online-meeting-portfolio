package com.example.hlsviewer.admin;

import com.example.hlsviewer.attendance.AttendanceVisibilityService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminAttendanceVisibilityController {
  private final AttendanceVisibilityService attendanceVisibilityService;

  public AdminAttendanceVisibilityController(AttendanceVisibilityService attendanceVisibilityService) {
    this.attendanceVisibilityService = attendanceVisibilityService;
  }

  @GetMapping("/api/admin/attendance-visibility/status")
  public Map<String, Object> status(
      @RequestParam(name = "streamKey", required = false) String streamKey) {
    boolean attendanceVisible = attendanceVisibilityService.isAttendanceVisible(streamKey);
    return Map.of("attendanceVisible", attendanceVisible);
  }

  @PostMapping("/api/admin/attendance-visibility/open")
  public Map<String, Object> open(
      @RequestParam(name = "streamKey", required = false) String streamKey) {
    boolean attendanceVisible = attendanceVisibilityService.setAttendanceVisible(streamKey, true);
    return Map.of("attendanceVisible", attendanceVisible);
  }

  @PostMapping("/api/admin/attendance-visibility/close")
  public Map<String, Object> close(
      @RequestParam(name = "streamKey", required = false) String streamKey) {
    boolean attendanceVisible = attendanceVisibilityService.setAttendanceVisible(streamKey, false);
    return Map.of("attendanceVisible", attendanceVisible);
  }
}
