package com.example.hlsviewer.roster;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RosterRepository {
  // Attendance-excluded persons (관제 직원) are now managed dynamically per meeting in
  // the meeting_excluded_person table. See queryForCountWithExclusion below.

  private final JdbcTemplate jdbcTemplate;

  public RosterRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public int replaceAll(String streamKey, List<RosterRow> rows) {
    String key = normalizeStreamKey(streamKey);
    jdbcTemplate.update("DELETE FROM voter_roster WHERE stream_key = ?", key);
    if (rows == null || rows.isEmpty()) {
      return 0;
    }

    String sql =
        "INSERT INTO voter_roster ("
            + "stream_key, seq_no, name, dong, jibun, birth, phone, "
            + "paper_submit_confirm, mail_submit_confirm, electronic_vote, "
            + "phone_accessed_at, entry_time, online_meeting, online_meeting_started_at, online_meeting_ended_at, "
            + "ip_address, roster_registered_at, onsite_vote_allowed, "
            + "proxy_name, proxy_phone, sign_image"
            + ") VALUES ("
            + "?, ?, ?, ?, ?, ?, ?, "
            + "?, ?, ?, "
            + "?, ?, ?, ?, ?, ?, ?, ?, "
            + "?, ?, ?"
            + ")";

    jdbcTemplate.batchUpdate(
        sql,
        rows,
        1000,
        (ps, row) -> {
          ps.setString(1, key);
          if (row.seqNo() == null) {
            ps.setNull(2, java.sql.Types.INTEGER);
          } else {
            ps.setInt(2, row.seqNo());
          }
          ps.setString(3, row.name());
          ps.setString(4, row.dong());
          ps.setString(5, row.jibun());
          ps.setString(6, row.birth());
          ps.setString(7, row.phone());
          ps.setString(8, row.paperSubmitConfirm());
          ps.setString(9, row.mailSubmitConfirm());
          ps.setString(10, row.electronicVote());
          ps.setString(11, row.phoneAccessedAt());
          ps.setString(12, row.entryTime());
          ps.setString(13, row.onlineMeeting());
          ps.setString(14, row.onlineMeetingStartedAt());
          ps.setString(15, row.onlineMeetingEndedAt());
          ps.setString(16, row.ipAddress());
          ps.setString(17, row.rosterRegisteredAt());
          ps.setString(18, row.onsiteVoteAllowed());
          ps.setString(19, row.proxyName());
          ps.setString(20, row.proxyPhone());
          ps.setString(21, row.signImage());
        }
    );
    return rows.size();
  }

  public boolean existsMatch(String name, String phone, String birth) {
    Integer found = jdbcTemplate.query(
        "SELECT 1 FROM voter_roster "
            + "WHERE name = ? AND phone = ? AND birth = ? "
            + "LIMIT 1",
        rs -> rs.next() ? 1 : null,
        name,
        phone,
        birth
    );
    return found != null;
  }

  /**
   * Returns the distinct stream_keys of every meeting whose roster contains the given person.
   * Used by the viewer flow to decide which meeting(s) a user can enter after NICE authentication.
   */
  public List<String> findStreamKeysByPerson(String name, String phone, String birth) {
    return jdbcTemplate.query(
        "SELECT DISTINCT stream_key FROM voter_roster "
            + "WHERE name = ? AND phone = ? AND birth = ? "
            + "  AND stream_key IS NOT NULL AND stream_key <> ''",
        (rs, rowNum) -> rs.getString("stream_key"),
        name,
        phone,
        birth
    );
  }

  public int countAll() {
    Integer value = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM voter_roster",
        Integer.class
    );
    return value == null ? 0 : value;
  }

  public Optional<String> findOnsiteVoteAllowed(String streamKey, String name, String phone, String birth) {
    List<String> rows = jdbcTemplate.query(
        "SELECT onsite_vote_allowed FROM voter_roster "
            + "WHERE stream_key = ? AND name = ? AND phone = ? AND birth = ? "
            + "LIMIT 1",
        (rs, rowNum) -> rs.getString("onsite_vote_allowed"),
        normalizeStreamKey(streamKey),
        name,
        phone,
        birth
    );
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(rows.get(0));
  }

  /**
   * Returns the vote-related cells for ONE roster row in the given meeting.
   * Without the stream_key filter this used to return any meeting's row for the same person,
   * which caused users in meeting B to be told "이미 전자투표 참여" because meeting A's row
   * for that name+phone+birth had electronic_vote='1'.
   */
  public Optional<VoteContext> findVoteContext(String streamKey, String name, String phone, String birth) {
    List<VoteContext> rows = jdbcTemplate.query(
        "SELECT onsite_vote_allowed, paper_submit_confirm, mail_submit_confirm, electronic_vote FROM voter_roster "
            + "WHERE stream_key = ? AND name = ? AND phone = ? AND birth = ? "
            + "LIMIT 1",
        (rs, rowNum) ->
            new VoteContext(
                rs.getString("onsite_vote_allowed"),
                rs.getString("paper_submit_confirm"),
                rs.getString("mail_submit_confirm"),
                rs.getString("electronic_vote")),
        normalizeStreamKey(streamKey),
        name,
        phone,
        birth
    );
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(rows.get(0));
  }

  public Optional<AudioUploaderInfo> findAudioUploaderInfo(String streamKey, String name, String phone, String birth) {
    List<AudioUploaderInfo> rows = jdbcTemplate.query(
        "SELECT name, jibun FROM voter_roster "
            + "WHERE stream_key = ? AND name = ? AND phone = ? AND birth = ? "
            + "LIMIT 1",
        (rs, rowNum) -> new AudioUploaderInfo(
            rs.getString("name"),
            rs.getString("jibun")),
        normalizeStreamKey(streamKey),
        name,
        phone,
        birth
    );
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(rows.get(0));
  }

  public int countOnsiteAllowed() {
    Integer value = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM voter_roster WHERE TRIM(COALESCE(onsite_vote_allowed, '')) = '가능'",
        Integer.class
    );
    return value == null ? 0 : value;
  }

  public int countOnsiteDisallowed() {
    Integer value = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM voter_roster "
            + "WHERE TRIM(COALESCE(onsite_vote_allowed, '')) IN ('불가', '불가능')",
        Integer.class
    );
    return value == null ? 0 : value;
  }

  public int countElectronicVotePresent(String streamKey) {
    String key = normalizeStreamKey(streamKey);
    return queryForCountWithExclusion(
        key,
        "SELECT COUNT(*) FROM voter_roster "
            + "WHERE stream_key = ? "
            + "  AND TRIM(COALESCE(electronic_vote, '')) <> ''",
        null,
        key
    );
  }

  public int countElectronicVotePresentDistinctPhone(String streamKey) {
    String key = normalizeStreamKey(streamKey);
    Integer value = jdbcTemplate.queryForObject(
        "SELECT COUNT(DISTINCT REPLACE(REPLACE(TRIM(phone), '-', ''), ' ', '')) "
            + "FROM voter_roster "
            + "WHERE stream_key = ? "
            + "  AND TRIM(COALESCE(electronic_vote, '')) <> '' "
            + "  AND TRIM(COALESCE(phone, '')) <> ''",
        Integer.class,
        key
    );
    return value == null ? 0 : value;
  }

  public int countPaperSubmitPresent(String streamKey) {
    String key = normalizeStreamKey(streamKey);
    return queryForCountWithExclusion(
        key,
        "SELECT COUNT(*) FROM voter_roster "
            + "WHERE stream_key = ? "
            + "  AND TRIM(COALESCE(paper_submit_confirm, '')) <> ''",
        null,
        key
    );
  }

  public int countMailSubmitPresent(String streamKey) {
    String key = normalizeStreamKey(streamKey);
    return queryForCountWithExclusion(
        key,
        "SELECT COUNT(*) FROM voter_roster "
            + "WHERE stream_key = ? "
            + "  AND TRIM(COALESCE(mail_submit_confirm, '')) <> ''",
        null,
        key
    );
  }

  public int countEntryTimePresent(String streamKey) {
    String key = normalizeStreamKey(streamKey);
    return queryForCountWithExclusion(
        key,
        "SELECT COUNT(*) FROM voter_roster "
            + "WHERE stream_key = ? "
            + "  AND TRIM(COALESCE(entry_time, '')) <> ''",
        null,
        key
    );
  }

  public int updateIpAddress(String name, String birth, String phone, String ipAddress) {
    String ip = normalizeIp(ipAddress);
    if (ip.isEmpty()) {
      return 0;
    }
    return jdbcTemplate.update(
        "UPDATE voter_roster "
            + "SET ip_address = ? "
            + "WHERE name = ? AND birth = ? AND phone = ?",
        ip,
        safe(name),
        safe(birth),
        safe(phone)
    );
  }

  public int countOnlineStarted(String streamKey) {
    String key = normalizeStreamKey(streamKey);
    return queryForCountWithExclusion(
        key,
        "SELECT COUNT(DISTINCT r.id) "
            + "FROM voter_roster r "
            + "LEFT JOIN voter_access_log l "
            + "  ON l.stream_key = ? "
            + " AND l.name = r.name "
            + " AND l.birth = r.birth "
            + " AND l.phone = r.phone "
            + "WHERE r.stream_key = ? "
            + "  AND (TRIM(COALESCE(r.online_meeting_started_at, '')) <> '' "
            + "   OR l.id IS NOT NULL)",
        "r",
        key,
        key
    );
  }

  public int countOnlineAuthenticatedDistinctPhone(String streamKey) {
    String key = normalizeStreamKey(streamKey);
    return queryForCountWithExclusion(
        key,
        "SELECT COUNT(DISTINCT REPLACE(TRIM(phone), '-', '')) "
            + "FROM voter_access_log "
            + "WHERE stream_key = ? "
            + "  AND access_started_at IS NOT NULL "
            + "  AND TRIM(COALESCE(phone, '')) <> ''",
        null,
        key
    );
  }

  public int countOnlineAuthenticatedNoSubmissionDistinctPhone(String streamKey) {
    String key = normalizeStreamKey(streamKey);
    return queryForCountWithExclusion(
        key,
        "SELECT COUNT(DISTINCT REPLACE(REPLACE(TRIM(r.phone), '-', ''), ' ', '')) "
            + "FROM voter_roster r "
            + "JOIN voter_access_log l "
            + "  ON l.stream_key = ? "
            + " AND REPLACE(REPLACE(TRIM(l.phone), '-', ''), ' ', '') = REPLACE(REPLACE(TRIM(r.phone), '-', ''), ' ', '') "
            + "WHERE r.stream_key = ? "
            + "  AND l.access_started_at IS NOT NULL "
            + "  AND TRIM(COALESCE(r.phone, '')) <> '' "
            + "  AND TRIM(COALESCE(r.paper_submit_confirm, '')) = '' "
            + "  AND TRIM(COALESCE(r.mail_submit_confirm, '')) = '' "
            + "  AND TRIM(COALESCE(r.electronic_vote, '')) = ''",
        "r",
        key,
        key
    );
  }

  public int countOnlineActiveDistinctPhone(String streamKey) {
    String key = normalizeStreamKey(streamKey);
    return queryForCountWithExclusion(
        key,
        "SELECT COUNT(DISTINCT REPLACE(TRIM(phone), '-', '')) "
            + "FROM voter_access_log "
            + "WHERE stream_key = ? "
            + "  AND access_started_at IS NOT NULL "
            + "  AND access_ended_at IS NULL "
            + "  AND TRIM(COALESCE(phone, '')) <> ''",
        null,
        key
    );
  }

  public int countOnlinePaperDistinctPhone(String streamKey) {
    return countOnlineByRosterFieldDistinctPhone(streamKey, "paper_submit_confirm");
  }

  public int countOnlineMailDistinctPhone(String streamKey) {
    return countOnlineByRosterFieldDistinctPhone(streamKey, "mail_submit_confirm");
  }

  public int countOnlineElectronicDistinctPhone(String streamKey) {
    return countOnlineByRosterFieldDistinctPhone(streamKey, "electronic_vote");
  }

  public int countOnlineEntryTimeDistinctPhone(String streamKey) {
    return countOnlineByRosterFieldDistinctPhone(streamKey, "entry_time");
  }

  private int countOnlineByRosterFieldDistinctPhone(String streamKey, String fieldName) {
    String key = normalizeStreamKey(streamKey);
    String normalizedField =
        switch (fieldName) {
          case "paper_submit_confirm", "mail_submit_confirm", "electronic_vote", "entry_time" -> fieldName;
          default -> throw new IllegalArgumentException("unsupported_field");
        };
    String sql =
        "SELECT COUNT(DISTINCT REPLACE(REPLACE(TRIM(r.phone), '-', ''), ' ', '')) "
            + "FROM voter_roster r "
            + "JOIN voter_access_log l "
            + "  ON l.stream_key = ? "
            + " AND REPLACE(REPLACE(TRIM(l.phone), '-', ''), ' ', '') = REPLACE(REPLACE(TRIM(r.phone), '-', ''), ' ', '') "
            + "WHERE r.stream_key = ? "
            + "  AND l.access_started_at IS NOT NULL "
            + "  AND TRIM(COALESCE(r."
            + normalizedField
            + ", '')) <> '' "
            + "  AND TRIM(COALESCE(r.phone, '')) <> ''";
    return queryForCountWithExclusion(key, sql, "r", key, key);
  }

  public List<RosterRow> findAllForExport(String streamKey) {
    String key = normalizeStreamKey(streamKey);
    String sql =
        "SELECT "
            + "r.seq_no, r.name, r.dong, r.jibun, r.birth, r.phone, "
            + "r.paper_submit_confirm, r.mail_submit_confirm, r.electronic_vote, "
            + "r.phone_accessed_at, r.entry_time, r.online_meeting, "
            + "COALESCE(log.first_played_at_fmt, r.online_meeting_started_at) AS online_meeting_started_at, "
            + "COALESCE(log.access_ended_at_fmt, r.online_meeting_ended_at) AS online_meeting_ended_at, "
            + "r.ip_address, r.roster_registered_at, r.onsite_vote_allowed, "
            + "r.proxy_name, r.proxy_phone, r.sign_image "
            + "FROM voter_roster r "
            + "LEFT JOIN ("
            + "  SELECT name, birth, phone, "
            + "         DATE_FORMAT(MIN(first_played_at), '%y/%m/%d %H:%i') AS first_played_at_fmt, "
            + "         DATE_FORMAT(MAX(access_ended_at), '%y/%m/%d %H:%i') AS access_ended_at_fmt "
            + "  FROM voter_access_log "
            + "  WHERE stream_key = ? "
            + "  GROUP BY name, birth, phone"
            + ") log ON log.name = r.name AND log.birth = r.birth AND log.phone = r.phone "
            + "WHERE r.stream_key = ? "
            + "ORDER BY (r.seq_no IS NULL), r.seq_no, r.id";

    return jdbcTemplate.query(
        sql,
        (rs, rowNum) -> new RosterRow(
            (Integer) rs.getObject("seq_no"),
            rs.getString("name"),
            rs.getString("dong"),
            rs.getString("jibun"),
            rs.getString("birth"),
            rs.getString("phone"),
            rs.getString("paper_submit_confirm"),
            rs.getString("mail_submit_confirm"),
            rs.getString("electronic_vote"),
            rs.getString("phone_accessed_at"),
            rs.getString("entry_time"),
            rs.getString("online_meeting"),
            rs.getString("online_meeting_started_at"),
            rs.getString("online_meeting_ended_at"),
            rs.getString("ip_address"),
            rs.getString("roster_registered_at"),
            rs.getString("onsite_vote_allowed"),
            rs.getString("proxy_name"),
            rs.getString("proxy_phone"),
            rs.getString("sign_image")),
        key,
        key
    );
  }

  private String normalizeStreamKey(String value) {
    String trimmed = value == null ? "" : value.trim();
    return trimmed.isEmpty() ? "stream" : trimmed;
  }

  /**
   * Wraps an attendance/roster count query with a NOT EXISTS subquery against
   * meeting_excluded_person, so anyone an admin registered as "관제 직원" for that
   * streamKey is removed from the count.
   *
   * <p>The outer query's row must expose name/birth/phone columns. The alias param
   * is the alias of the table holding those columns (e.g. "r" for voter_roster, or
   * null/blank when the outer query is bare voter_access_log).
   *
   * <p>This is called from queries that already filter by stream_key, and the same
   * stream_key is passed in here so the exclusion list is scoped to that meeting.
   */
  private int queryForCountWithExclusion(String streamKey, String baseSql, String alias, Object... baseParams) {
    String sql = baseSql + attendanceExclusionSql(alias);
    List<Object> params = new ArrayList<>();
    if (baseParams != null) {
      for (Object param : baseParams) {
        params.add(param);
      }
    }
    params.add(normalizeStreamKey(streamKey));
    Integer value = jdbcTemplate.queryForObject(sql, Integer.class, params.toArray());
    return value == null ? 0 : value;
  }

  private String attendanceExclusionSql(String alias) {
    String prefix = (alias == null || alias.isBlank()) ? "" : alias.trim() + ".";
    return " AND NOT EXISTS ("
        + "SELECT 1 FROM meeting_excluded_person mep "
        + "WHERE mep.stream_key = ? "
        + "AND mep.name = REPLACE(TRIM(COALESCE(" + prefix + "name, '')), ' ', '') "
        + "AND mep.birth = TRIM(COALESCE(" + prefix + "birth, '')) "
        + "AND mep.phone = TRIM(COALESCE(" + prefix + "phone, ''))"
        + ")";
  }

  private String normalizeIp(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty() || "unknown".equalsIgnoreCase(trimmed)) {
      return "";
    }
    if (trimmed.length() <= 64) {
      return trimmed;
    }
    return trimmed.substring(0, 64);
  }

  private String safe(String value) {
    return value == null ? "" : value.trim();
  }

  public record VoteContext(
      String onsiteVoteAllowed,
      String paperSubmitConfirm,
      String mailSubmitConfirm,
      String electronicVote) {}

  public record AudioUploaderInfo(String name, String jibun) {}
}
