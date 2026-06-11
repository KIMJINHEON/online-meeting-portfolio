package com.example.hlsviewer.viewer;

import com.example.hlsviewer.attendance.AttendanceSummaryService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ViewerAttendanceController {
  private final AttendanceSummaryService attendanceSummaryService;

  public ViewerAttendanceController(AttendanceSummaryService attendanceSummaryService) {
    this.attendanceSummaryService = attendanceSummaryService;
  }

  @GetMapping("/api/attendance")
  public Map<String, Long> attendance(
      @RequestParam(name = "streamKey", required = false) String streamKey) {
    return attendanceSummaryService.summarizeForAdmin(streamKey);
  }
}
