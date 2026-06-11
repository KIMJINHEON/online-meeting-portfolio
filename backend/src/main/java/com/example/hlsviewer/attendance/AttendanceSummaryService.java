package com.example.hlsviewer.attendance;

import com.example.hlsviewer.roster.RosterRepository;
import com.example.hlsviewer.viewer.ViewerAuthService;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AttendanceSummaryService {
  private final ViewerAuthService viewerAuthService;
  private final RosterRepository rosterRepository;
  private final AttendanceOverrideRepository attendanceOverrideRepository;

  public AttendanceSummaryService(
      ViewerAuthService viewerAuthService,
      RosterRepository rosterRepository,
      AttendanceOverrideRepository attendanceOverrideRepository) {
    this.viewerAuthService = viewerAuthService;
    this.rosterRepository = rosterRepository;
    this.attendanceOverrideRepository = attendanceOverrideRepository;
  }

  public Map<String, Long> summarize(String streamKey) {
    // "온라인 참석"은 access log에서 종료되지 않은(access_ended_at IS NULL) 세션의 휴대폰(unique) 기준 집계
    long online;
    try {
      online = rosterRepository.countOnlineActiveDistinctPhone(streamKey);
    } catch (Exception primaryEx) {
      try {
        // Fallback 1: in-memory active session count.
        online = viewerAuthService.countUniqueViewers(streamKey);
      } catch (Exception secondaryEx) {
        // Fallback 2: legacy count logic.
        online = rosterRepository.countOnlineStarted(streamKey);
      }
    }

    // "서면 결의"는 명부의 서면제출확인 컬럼 값 존재 건수로 집계 (해당 회의 명부 한정)
    long paper = rosterRepository.countPaperSubmitPresent(streamKey);
    // "우편 제출"은 명부의 우편제출확인 컬럼 값 존재 건수로 집계 (해당 회의 명부 한정)
    long mail = rosterRepository.countMailSubmitPresent(streamKey);
    // "직접 참석" 기본값은 출입시간(entry_time) 데이터가 있는 명부 인원 중
    // 본인인증 후 접속(access_started_at) 이력이 있는 사람(휴대폰 중복 제거) 수.
    long onsite = rosterRepository.countOnlineEntryTimeDistinctPhone(streamKey);

    return Map.of(
        "online", online,
        "paper", paper,
        "mail", mail,
        "onsite", onsite,
        // "합계"는 본인인증 완료 후 접속(access_started_at)한 인원(휴대폰 중복 제거) 기준으로만 제공
        "total", online
    );
  }

  public Map<String, Long> summarizeForAdmin(String streamKey) {
    Map<String, Long> base = summarize(streamKey);
    long onlineForAdmin = base.getOrDefault("online", 0L);
    try {
      // Admin의 "온라인 참석"은 접속 시작(access_started_at) 이력이 있고
      // 서면/우편/전자투표가 모두 비어 있는 인원(전화번호 중복 제거) 기준.
      onlineForAdmin = rosterRepository.countOnlineAuthenticatedNoSubmissionDistinctPhone(streamKey);
    } catch (Exception ignored) {
      // Fallback: base summarize()의 online 값 사용
    }

    long paperSubmitted = base.getOrDefault("paper", 0L);
    long mailSubmitted = base.getOrDefault("mail", 0L);
    long fieldOnsiteAttending = findManualFieldOnsite(streamKey).orElse(0L);
    long fieldOnsiteSubmitted = 0L;
    long paperAttending = 0L;
    long mailAttending = 0L;
    long electronicAttending = 0L;
    try {
      paperAttending = rosterRepository.countOnlinePaperDistinctPhone(streamKey);
      mailAttending = rosterRepository.countOnlineMailDistinctPhone(streamKey);
      electronicAttending = rosterRepository.countOnlineElectronicDistinctPhone(streamKey);
    } catch (Exception ignored) {
      // Fallback 0 유지
    }

    long onsiteSubmitted = rosterRepository.countEntryTimePresent(streamKey);
    long electronicSubmitted = rosterRepository.countElectronicVotePresent(streamKey);

    Map<String, Long> out = new HashMap<>(base);
    out.put("online", onlineForAdmin);
    out.put("paper", paperAttending);
    out.put("mail", mailAttending);
    out.put("electronic", electronicAttending);
    out.put("fieldOnsite", fieldOnsiteAttending);
    out.put("paperSubmitted", paperSubmitted);
    out.put("mailSubmitted", mailSubmitted);
    out.put("onsiteSubmitted", onsiteSubmitted);
    out.put("fieldOnsiteSubmitted", fieldOnsiteSubmitted);
    out.put("electronicSubmitted", electronicSubmitted);
    out.put(
        "attendingTotal",
        onlineForAdmin + paperAttending + mailAttending + fieldOnsiteAttending + electronicAttending
    );
    // 제출 합계는 제출 라인의 항목 합계(서면/우편/직접참석/전자투표).
    out.put("total", paperSubmitted + mailSubmitted + onsiteSubmitted + electronicSubmitted);
    return out;
  }

  public Optional<Long> findManualOnsite(String streamKey) {
    return attendanceOverrideRepository.findManualOnsite(streamKey);
  }

  public void setManualOnsite(String streamKey, long value) {
    attendanceOverrideRepository.upsertManualOnsite(streamKey, Math.max(0, value));
  }

  public void clearManualOnsite(String streamKey) {
    attendanceOverrideRepository.clearManualOnsite(streamKey);
  }

  public Optional<Long> findManualFieldOnsite(String streamKey) {
    return attendanceOverrideRepository.findManualFieldOnsite(streamKey);
  }

  public void setManualFieldOnsite(String streamKey, long value) {
    attendanceOverrideRepository.upsertManualFieldOnsite(streamKey, Math.max(0, value));
  }

  public void clearManualFieldOnsite(String streamKey) {
    attendanceOverrideRepository.clearManualFieldOnsite(streamKey);
  }
}
