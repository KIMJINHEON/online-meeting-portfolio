-- For existing voter_roster tables (MySQL 5.x compatible).
-- Run once before deploying backend code that writes the new fields.

ALTER TABLE voter_roster
  ADD COLUMN online_meeting_started_at VARCHAR(16) NULL AFTER online_meeting,
  ADD COLUMN online_meeting_ended_at VARCHAR(16) NULL AFTER online_meeting_started_at;
