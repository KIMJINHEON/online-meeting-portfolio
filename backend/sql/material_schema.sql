-- 회의자료
CREATE TABLE IF NOT EXISTS meeting_material (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  room_id BIGINT UNSIGNED NOT NULL,
  type ENUM('text','pdf') NOT NULL,
  title VARCHAR(200) NOT NULL,
  body MEDIUMTEXT NULL,
  stored_name VARCHAR(255) NULL,
  content_type VARCHAR(100) NULL,
  size_bytes BIGINT UNSIGNED NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at TIMESTAMP NULL,
  INDEX idx_room_created (room_id, created_at),
  CONSTRAINT fk_meeting_material_room
    FOREIGN KEY (room_id) REFERENCES chat_room(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
