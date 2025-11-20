-- Create quiz groups and memberships tables for organizing quizzes into collections

-- Quiz Groups
CREATE TABLE IF NOT EXISTS quiz_groups (
  group_id BINARY(16) NOT NULL,
  owner_id BINARY(16) NOT NULL,
  name VARCHAR(100) NOT NULL,
  description VARCHAR(500) NULL,
  color VARCHAR(20) NULL,
  icon VARCHAR(50) NULL,
  document_id BINARY(16) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  deleted_at TIMESTAMP NULL,
  version INT NULL,
  PRIMARY KEY (group_id),
  CONSTRAINT fk_quiz_groups_owner FOREIGN KEY (owner_id) REFERENCES users(user_id),
  CONSTRAINT fk_quiz_groups_document FOREIGN KEY (document_id) REFERENCES documents(id)
) ENGINE=InnoDB;

-- Quiz Group Memberships (many-to-many with ordering)
CREATE TABLE IF NOT EXISTS quiz_group_memberships (
  group_id BINARY(16) NOT NULL,
  quiz_id BINARY(16) NOT NULL,
  position INT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (group_id, quiz_id),
  UNIQUE KEY uq_quiz_group_members_position (group_id, position),
  CONSTRAINT fk_quiz_group_memberships_group FOREIGN KEY (group_id) REFERENCES quiz_groups(group_id) ON DELETE CASCADE,
  CONSTRAINT fk_quiz_group_memberships_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes(quiz_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Indexes for quiz_groups
CREATE INDEX idx_quiz_groups_owner_id ON quiz_groups(owner_id);
CREATE INDEX idx_quiz_groups_document_id ON quiz_groups(document_id);

-- Indexes for quiz_group_memberships
CREATE INDEX idx_quiz_group_memberships_quiz_id ON quiz_group_memberships(quiz_id);

-- Index for archived quizzes queries (status = ARCHIVED combined with creator_id)
CREATE INDEX idx_quizzes_creator_status ON quizzes(creator_id, status);

