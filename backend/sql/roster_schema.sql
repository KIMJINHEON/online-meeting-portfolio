DROP TABLE IF EXISTS voter_roster;

CREATE TABLE voter_roster (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,

  seq_no INT NULL,
  name VARCHAR(100) NOT NULL,
  dong VARCHAR(50) NULL,
  jibun VARCHAR(100) NULL,
  birth CHAR(6) NOT NULL,
  phone VARCHAR(20) NOT NULL,
  paper_submit_confirm VARCHAR(20) NULL,
  mail_submit_confirm VARCHAR(20) NULL,
  electronic_vote VARCHAR(20) NULL,
  phone_accessed_at VARCHAR(16) NULL,
  entry_time VARCHAR(16) NULL,
  online_meeting VARCHAR(20) NULL,
  online_meeting_started_at VARCHAR(16) NULL,
  online_meeting_ended_at VARCHAR(16) NULL,
  ip_address VARCHAR(64) NULL,
  roster_registered_at VARCHAR(16) NULL,
  onsite_vote_allowed VARCHAR(20) NULL,
  proxy_name VARCHAR(100) NULL,
  proxy_phone VARCHAR(20) NULL,
  sign_image VARCHAR(255) NULL,

  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  CHECK (birth REGEXP '^[0-9]{6}$'),
  CHECK (phone REGEXP '^01[0-9]-[0-9]{3,4}-[0-9]{4}$'),
  CHECK (proxy_phone IS NULL OR proxy_phone = '' OR proxy_phone REGEXP '^01[0-9]-[0-9]{3,4}-[0-9]{4}$'),
  CHECK (phone_accessed_at IS NULL OR phone_accessed_at = '' OR phone_accessed_at REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}$'),
  CHECK (entry_time IS NULL OR entry_time = '' OR entry_time REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}$'),
  CHECK (online_meeting_started_at IS NULL OR online_meeting_started_at = '' OR online_meeting_started_at REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}$'),
  CHECK (online_meeting_ended_at IS NULL OR online_meeting_ended_at = '' OR online_meeting_ended_at REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}$'),
  CHECK (roster_registered_at IS NULL OR roster_registered_at = '' OR roster_registered_at REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}$'),

  INDEX idx_voter_roster_lookup (name, phone, birth),
  INDEX idx_voter_roster_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
