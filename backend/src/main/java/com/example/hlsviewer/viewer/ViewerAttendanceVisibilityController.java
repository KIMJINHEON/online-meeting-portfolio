package com.example.hlsviewer.viewer;

import com.example.hlsviewer.attendance.AttendanceVisibilityService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ViewerAttendanceVisibilityController {
  private final AttendanceVisibilityService attendanceVisibilityService;

  public ViewerAttendanceVisibilityController(AttendanceVisibilityService attendanceVisibilityService) {
    this.attendanceVisibilityService = attendanceVisibilityService;
  }

  @GetMapping("/api/attendance-visibility/status")
  public Map<String, Object> status(
      @RequestParam(name = "streamKey", required = false) String streamKey) {
    boolean attendanceVisible = attendanceVisibilityService.isAttendanceVisible(streamKey);
    return Map.of("attendanceVisible", attendanceVisible);
  }
}
