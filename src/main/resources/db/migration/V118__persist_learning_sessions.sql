-- #575: independent, dark, pre-form Learning sessions. No enrollment, scheduling or backfill.
-- Owner identity is retained evidence, without a cascade to mutable accounts or assessment rows.
CREATE TABLE learning_session (
    id BINARY(16) NOT NULL PRIMARY KEY,
    schema_version SMALLINT NOT NULL,
    learner_id BINARY(16) NOT NULL,
    create_command_id BINARY(16) NOT NULL,
    source_kind VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version SMALLINT NOT NULL,
    quiz_id BINARY(16) NOT NULL,
    quiz_revision_id BINARY(16) NOT NULL,
    source_schema_version SMALLINT NOT NULL,
    strategy_id VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    configuration JSON NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_learning_session_create (learner_id,create_command_id),
    UNIQUE KEY uq_learning_session_receipt_binding (id,learner_id,create_command_id),
    CONSTRAINT fk_learning_session_revision FOREIGN KEY (quiz_revision_id) REFERENCES quiz_revisions(id),
    CONSTRAINT chk_learning_session_version CHECK (schema_version=1 AND version=1),
    CONSTRAINT chk_learning_session_source CHECK (source_kind='QUIZ_REVISION' AND source_version=1
        AND source_schema_version BETWEEN 1 AND 3),
    CONSTRAINT chk_learning_session_configuration CHECK (strategy_id='fsrs/v1'
        AND JSON_TYPE(configuration)='OBJECT' AND OCTET_LENGTH(configuration)<=16384),
    CONSTRAINT chk_learning_session_lifecycle CHECK (status='CREATED' AND updated_at=created_at)
) ENGINE=InnoDB;

-- Same transaction as the session; a committed session without this receipt is corruption,
-- never an invitation to synthesize missing command history during a read or retry.
CREATE TABLE learning_session_create_receipt (
    session_id BINARY(16) NOT NULL PRIMARY KEY,
    schema_version SMALLINT NOT NULL,
    learner_id BINARY(16) NOT NULL,
    command_id BINARY(16) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_learning_session_command_receipt (learner_id,command_id),
    CONSTRAINT fk_learning_session_receipt FOREIGN KEY (session_id,learner_id,command_id)
        REFERENCES learning_session(id,learner_id,create_command_id),
    CONSTRAINT chk_learning_session_receipt_version CHECK (schema_version=1)
) ENGINE=InnoDB;
-- Personal Learning history has no initial age cleanup. Retention/erasure executors remain
-- separately owned; no invented history, data reset, physical cleanup or cascade is introduced.
