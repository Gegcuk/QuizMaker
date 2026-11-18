-- Configuration evidence only. No attempt backfill, release worker, notification or activation.
-- References must retain the captured configuration ID, never resolve a historical head again.
CREATE TABLE result_release_configurations (
    id BINARY(16) NOT NULL PRIMARY KEY,
    quiz_id BINARY(16) NOT NULL,
    revision_id BINARY(16) NOT NULL,
    publication_version BIGINT NOT NULL,
    configuration_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    canonical_configuration VARCHAR(4096) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    published_by BINARY(16) NOT NULL,
    published_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_release_configuration_version (quiz_id, revision_id, publication_version),
    UNIQUE KEY uq_release_configuration_binding (quiz_id, revision_id, id, publication_version),
    CONSTRAINT fk_release_configuration_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes(quiz_id),
    CONSTRAINT fk_release_configuration_revision FOREIGN KEY (quiz_id, revision_id) REFERENCES quiz_publications(quiz_id, revision_id),
    CONSTRAINT fk_release_configuration_actor FOREIGN KEY (published_by) REFERENCES users(user_id),
    CONSTRAINT chk_release_configuration_version CHECK (publication_version > 0),
    CONSTRAINT chk_release_configuration_json CHECK (JSON_VALID(canonical_configuration))
) ENGINE=InnoDB;

CREATE TABLE result_release_configuration_heads (
    quiz_id BINARY(16) NOT NULL,
    revision_id BINARY(16) NOT NULL,
    configuration_id BINARY(16) NOT NULL,
    publication_version BIGINT NOT NULL,
    PRIMARY KEY (quiz_id, revision_id),
    CONSTRAINT fk_release_configuration_head FOREIGN KEY (quiz_id, revision_id, configuration_id, publication_version)
        REFERENCES result_release_configurations(quiz_id, revision_id, id, publication_version)
) ENGINE=InnoDB;

-- The immutable receipt is also local publication evidence: actor, command, precondition digest,
-- exact resulting version and time. Delivery/export of domain outcome events belongs to #837/#800.
CREATE TABLE result_release_configuration_receipts (
    quiz_id BINARY(16) NOT NULL,
    revision_id BINARY(16) NOT NULL,
    actor_id BINARY(16) NOT NULL,
    command_id BINARY(16) NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    configuration_id BINARY(16) NOT NULL,
    publication_version BIGINT NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (quiz_id, revision_id, actor_id, command_id),
    CONSTRAINT fk_release_configuration_receipt FOREIGN KEY (quiz_id, revision_id, configuration_id, publication_version)
        REFERENCES result_release_configurations(quiz_id, revision_id, id, publication_version),
    CONSTRAINT fk_release_configuration_receipt_actor FOREIGN KEY (actor_id) REFERENCES users(user_id)
) ENGINE=InnoDB;

CREATE TRIGGER release_configuration_no_update BEFORE UPDATE ON result_release_configurations
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Release configuration is immutable';
CREATE TRIGGER release_configuration_receipt_no_update BEFORE UPDATE ON result_release_configuration_receipts
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Release configuration receipt is immutable';
