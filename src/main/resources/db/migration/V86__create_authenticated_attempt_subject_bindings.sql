-- Dark target identity only: do not infer bindings from legacy attempts/user/sentinel rows.
-- Target start (#600) owns the atomic attempt/binding write; no FK to the legacy attempts table.
CREATE TABLE attempt_participant_subject (
    attempt_id BINARY(16) NOT NULL PRIMARY KEY,
    schema_version SMALLINT NOT NULL,
    subject_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BINARY(16) NOT NULL,
    INDEX ix_attempt_subject_user (user_id, attempt_id),
    CONSTRAINT fk_attempt_subject_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE RESTRICT,
    CONSTRAINT chk_attempt_subject_version CHECK (schema_version = 1),
    CONSTRAINT chk_attempt_subject_type CHECK (CAST(subject_type AS BINARY) = CAST('AUTHENTICATED_USER' AS BINARY))
) ENGINE=InnoDB;
