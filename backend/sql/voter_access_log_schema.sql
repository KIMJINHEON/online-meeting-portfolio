CREATE TABLE IF NOT EXISTS voter_access_log (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,

  stream_key VARCHAR(100) NOT NULL DEFAULT 'stream',
  session_token_hash CHAR(64) NOT NULL,
  name VARCHAR(100) NOT NULL,
  birth CHAR(6) NOT NULL,
  phone VARCHAR(20) NOT NULL,
  device_id VARCHAR(64) NULL,

  access_started_at DATETIME NOT NULL,
  first_played_at DATETIME NULL,
  access_ended_at DATETIME NULL,
  last_seen_at DATETIME NULL,
  end_reason VARCHAR(20) NULL,

  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  CHECK (birth REGEXP '^[0-9]{6}$'),
  CHECK (phone REGEXP '^01[0-9]-[0-9]{3,4}-[0-9]{4}$'),

  UNIQUE KEY uq_voter_access_log_session (session_token_hash),
  INDEX idx_voter_access_log_lookup (name, phone, birth),
  INDEX idx_voter_access_log_device (stream_key, device_id, access_ended_at),
  INDEX idx_voter_access_log_stream_start (stream_key, access_started_at),
  INDEX idx_voter_access_log_stream_played (stream_key, first_played_at),
  INDEX idx_voter_access_log_stream_end (stream_key, access_ended_at),
  INDEX idx_voter_access_log_last_seen (stream_key, last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
