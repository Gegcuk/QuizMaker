-- Create core quiz/question/attempt/answer and related tables if missing (idempotent)

-- Categories
CREATE TABLE IF NOT EXISTS categories (
  category_id BINARY(16) NOT NULL,
  category_name VARCHAR(255) NOT NULL UNIQUE,
  category_description VARCHAR(1000) NULL,
  PRIMARY KEY (category_id)
) ENGINE=InnoDB;

-- Tags
CREATE TABLE IF NOT EXISTS tags (
  tag_id BINARY(16) NOT NULL,
  tag_name VARCHAR(255) NOT NULL UNIQUE,
  tag_description VARCHAR(1000) NULL,
  PRIMARY KEY (tag_id)
) ENGINE=InnoDB;

-- Quizzes
CREATE TABLE IF NOT EXISTS quizzes (
  quiz_id BINARY(16) NOT NULL,
  creator_id BINARY(16) NOT NULL,
  category_id BINARY(16) NOT NULL,
  title VARCHAR(100) NOT NULL,
  description VARCHAR(1000) NULL,
  visibility VARCHAR(20) NOT NULL,
  difficulty VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  reviewed_at TIMESTAMP NULL,
  reviewed_by BINARY(16) NULL,
  rejection_reason TEXT NULL,
  content_hash VARCHAR(64) NULL,
  presentation_hash VARCHAR(64) NULL,
  submitted_for_review_at TIMESTAMP NULL,
  locked_for_review BOOLEAN NULL,
  publish_on_approve BOOLEAN NULL,
  version INT NULL,
  estimated_time_min INT NOT NULL,
  is_repetition_enabled BOOLEAN NOT NULL,
  is_timer_enabled BOOLEAN NOT NULL,
  timer_duration_min INT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  deleted_at TIMESTAMP NULL,
  PRIMARY KEY (quiz_id),
  UNIQUE KEY uq_quiz_creator_title (creator_id, title),
  CONSTRAINT fk_quiz_creator FOREIGN KEY (creator_id) REFERENCES users(user_id),
  CONSTRAINT fk_quiz_category FOREIGN KEY (category_id) REFERENCES categories(category_id),
  CONSTRAINT fk_quiz_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(user_id)
) ENGINE=InnoDB;

-- Questions
CREATE TABLE IF NOT EXISTS questions (
  id BINARY(16) NOT NULL,
  type VARCHAR(50) NOT NULL,
  difficulty VARCHAR(20) NOT NULL,
  question VARCHAR(1000) NOT NULL,
  content JSON NOT NULL,
  hint VARCHAR(500) NULL,
  explanation VARCHAR(2000) NULL,
  attachment_url VARCHAR(2048) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  deleted_at TIMESTAMP NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB;

-- Quiz-Questions join table
CREATE TABLE IF NOT EXISTS quiz_questions (
  quiz_id BINARY(16) NOT NULL,
  question_id BINARY(16) NOT NULL,
  PRIMARY KEY (quiz_id, question_id),
  CONSTRAINT fk_qq_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes(quiz_id) ON DELETE CASCADE,
  CONSTRAINT fk_qq_question FOREIGN KEY (question_id) REFERENCES questions(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Quiz-Tags join table
CREATE TABLE IF NOT EXISTS quiz_tags (
  quiz_id BINARY(16) NOT NULL,
  tag_id BINARY(16) NOT NULL,
  PRIMARY KEY (quiz_id, tag_id),
  CONSTRAINT fk_qt_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes(quiz_id) ON DELETE CASCADE,
  CONSTRAINT fk_qt_tag FOREIGN KEY (tag_id) REFERENCES tags(tag_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Question-Tags join table
CREATE TABLE IF NOT EXISTS question_tags (
  question_id BINARY(16) NOT NULL,
  tag_id BINARY(16) NOT NULL,
  PRIMARY KEY (question_id, tag_id),
  CONSTRAINT fk_qt_question FOREIGN KEY (question_id) REFERENCES questions(id) ON DELETE CASCADE,
  CONSTRAINT fk_qt_tag2 FOREIGN KEY (tag_id) REFERENCES tags(tag_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Share links
CREATE TABLE IF NOT EXISTS share_links (
  id BINARY(16) NOT NULL,
  quiz_id BINARY(16) NOT NULL,
  created_by BINARY(16) NOT NULL,
  token_hash VARCHAR(64) NOT NULL,
  scope VARCHAR(30) NOT NULL,
  expires_at TIMESTAMP NULL,
  one_time BOOLEAN NOT NULL,
  revoked_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT fk_sl_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes(quiz_id) ON DELETE CASCADE,
  CONSTRAINT fk_sl_creator FOREIGN KEY (created_by) REFERENCES users(user_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

-- Attempts
CREATE TABLE IF NOT EXISTS attempts (
  id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  quiz_id BINARY(16) NOT NULL,
  share_link_id BINARY(16) NULL,
  started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMP NULL,
  status VARCHAR(30) NOT NULL,
  mode VARCHAR(30) NOT NULL,
  total_score DOUBLE NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_attempt_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE RESTRICT,
  CONSTRAINT fk_attempt_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes(quiz_id) ON DELETE CASCADE,
  CONSTRAINT fk_attempt_share_link FOREIGN KEY (share_link_id) REFERENCES share_links(id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- Answers
CREATE TABLE IF NOT EXISTS answers (
  id BINARY(16) NOT NULL,
  attempt_id BINARY(16) NOT NULL,
  question_id BINARY(16) NOT NULL,
  response JSON NOT NULL,
  is_correct BOOLEAN NULL,
  score DOUBLE NULL,
  answered_at TIMESTAMP NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_answer_attempt FOREIGN KEY (attempt_id) REFERENCES attempts(id) ON DELETE CASCADE,
  CONSTRAINT fk_answer_question FOREIGN KEY (question_id) REFERENCES questions(id) ON DELETE RESTRICT
) ENGINE=InnoDB;

-- Share link analytics
CREATE TABLE IF NOT EXISTS share_link_analytics (
  id BINARY(16) NOT NULL,
  share_link_id BINARY(16) NOT NULL,
  event_type VARCHAR(30) NOT NULL,
  ip_hash VARCHAR(64) NOT NULL,
  user_agent VARCHAR(256) NULL,
  date_bucket VARCHAR(10) NOT NULL,
  country_code VARCHAR(2) NULL,
  referrer VARCHAR(512) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT fk_sla_share_link FOREIGN KEY (share_link_id) REFERENCES share_links(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Share link usage
CREATE TABLE IF NOT EXISTS share_link_usage (
  id BINARY(16) NOT NULL,
  share_link_id BINARY(16) NOT NULL,
  user_agent VARCHAR(512) NULL,
  ip_hash VARCHAR(64) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT fk_slu_share_link FOREIGN KEY (share_link_id) REFERENCES share_links(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Quiz moderation audit
CREATE TABLE IF NOT EXISTS quiz_moderation_audit (
  id BINARY(16) NOT NULL,
  quiz_id BINARY(16) NOT NULL,
  moderator_id BINARY(16) NULL,
  action VARCHAR(20) NOT NULL,
  reason TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  correlation_id VARCHAR(100) NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_qma_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes(quiz_id) ON DELETE CASCADE,
  CONSTRAINT fk_qma_moderator FOREIGN KEY (moderator_id) REFERENCES users(user_id) ON DELETE SET NULL
) ENGINE=InnoDB;
