-- Independent history: no foreign keys or cascades to mutable authoring/user rows.
-- Existing attempts and questions are intentionally not backfilled or rebound.
CREATE TABLE quiz_revisions (
    id BINARY(16) NOT NULL PRIMARY KEY,
    quiz_id BINARY(16) NOT NULL,
    owner_id BINARY(16) NOT NULL,
    schema_version SMALLINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    description VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX ix_quiz_revision_owner_quiz (owner_id, quiz_id, created_at, id),
    CONSTRAINT chk_quiz_revision_version CHECK (schema_version = 1)
) ENGINE=InnoDB;

CREATE TABLE question_revisions (
    id BINARY(16) NOT NULL PRIMARY KEY,
    question_id BINARY(16) NOT NULL,
    schema_version SMALLINT NOT NULL,
    question_type VARCHAR(50) NOT NULL,
    difficulty VARCHAR(20) NOT NULL,
    question_text VARCHAR(1000) NOT NULL,
    content JSON NOT NULL,
    hint VARCHAR(500) NULL,
    explanation VARCHAR(2000) NULL,
    attachment_url VARCHAR(2048) NULL,
    attachment_asset_id BINARY(16) NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX ix_question_revision_source (question_id, created_at, id),
    CONSTRAINT chk_question_revision_version CHECK (schema_version = 1),
    CONSTRAINT chk_question_revision_content CHECK (JSON_TYPE(content) = 'OBJECT')
) ENGINE=InnoDB;

CREATE TABLE quiz_revision_items (
    id BINARY(16) NOT NULL,
    quiz_revision_id BINARY(16) NOT NULL,
    question_revision_id BINARY(16) NOT NULL,
    authored_position INT NOT NULL,
    PRIMARY KEY (quiz_revision_id, id),
    UNIQUE KEY uq_quiz_revision_item_position (quiz_revision_id, authored_position),
    INDEX ix_revision_item_question (question_revision_id),
    CONSTRAINT fk_revision_item_quiz FOREIGN KEY (quiz_revision_id) REFERENCES quiz_revisions(id),
    CONSTRAINT fk_revision_item_question FOREIGN KEY (question_revision_id) REFERENCES question_revisions(id),
    CONSTRAINT chk_revision_item_position CHECK (authored_position >= 0)
) ENGINE=InnoDB;

CREATE TABLE question_option_revisions (
    id BINARY(16) NOT NULL,
    question_revision_id BINARY(16) NOT NULL,
    source_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    authored_position INT NOT NULL,
    PRIMARY KEY (question_revision_id, id),
    UNIQUE KEY uq_question_revision_option_key (question_revision_id, source_key),
    UNIQUE KEY uq_question_revision_option_position (question_revision_id, authored_position),
    CONSTRAINT fk_revision_option_question FOREIGN KEY (question_revision_id) REFERENCES question_revisions(id),
    CONSTRAINT chk_revision_option_position CHECK (authored_position >= 0)
) ENGINE=InnoDB;
