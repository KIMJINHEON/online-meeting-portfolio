CREATE TABLE chat_room (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  stream_key VARCHAR(100) NOT NULL UNIQUE,
  title VARCHAR(200) NULL,
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE admin_user (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(50) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  display_name VARCHAR(50) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE chat_message (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  room_id BIGINT UNSIGNED NOT NULL,
  sender_name VARCHAR(50) NOT NULL,
  message TEXT NOT NULL,
  status ENUM('pending','approved','rejected') NOT NULL DEFAULT 'pending',
  approved_by BIGINT UNSIGNED NULL,
  approved_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_room_status_id (room_id, status, id),
  INDEX idx_room_status_time (room_id, status, created_at),
  INDEX idx_status_time (status, created_at),
  CONSTRAINT fk_chat_message_room
    FOREIGN KEY (room_id) REFERENCES chat_room(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_chat_message_admin
    FOREIGN KEY (approved_by) REFERENCES admin_user(id)
    ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE admin_session (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  admin_id BIGINT UNSIGNED NOT NULL,
  token CHAR(36) NOT NULL UNIQUE,
  expires_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_admin_token (admin_id, token),
  CONSTRAINT fk_admin_session_user
    FOREIGN KEY (admin_id) REFERENCES admin_user(id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chat_audio (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  room_id BIGINT UNSIGNED NOT NULL,
  uploader_name VARCHAR(50) NULL,
  original_name VARCHAR(255) NOT NULL,
  stored_name VARCHAR(255) NOT NULL UNIQUE,
  content_type VARCHAR(100) NOT NULL,
  size_bytes BIGINT UNSIGNED NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at TIMESTAMP NULL,
  deleted_by BIGINT UNSIGNED NULL,
  INDEX idx_room_created (room_id, created_at),
  INDEX idx_deleted_at (deleted_at),
  CONSTRAINT fk_chat_audio_room
    FOREIGN KEY (room_id) REFERENCES chat_room(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_chat_audio_admin
    FOREIGN KEY (deleted_by) REFERENCES admin_user(id)
    ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
