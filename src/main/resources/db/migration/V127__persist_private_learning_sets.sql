-- Private organization only. Existing question cycles, sessions and review evidence remain authoritative.
-- One owner-scoped guard serializes structural mutations, including opposite concurrent edges.
CREATE TABLE learning_set_graph (
    learner_id BINARY(16) NOT NULL PRIMARY KEY,
    version BIGINT NOT NULL,
    CONSTRAINT chk_learning_set_graph CHECK(version>=0)
) ENGINE=InnoDB;
CREATE TABLE learning_set (
    id BINARY(16) NOT NULL PRIMARY KEY,
    learner_id BINARY(16) NOT NULL,
    name VARCHAR(255) NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_learning_set_owner(learner_id,id),
    CONSTRAINT chk_learning_set CHECK(version>0 AND deleted IN (0,1) AND CHAR_LENGTH(name)>0)
) ENGINE=InnoDB;
CREATE TABLE learning_set_member (
    learner_id BINARY(16) NOT NULL,
    set_id BINARY(16) NOT NULL,
    member_key VARBINARY(160) NOT NULL,
    kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    child_set_id BINARY(16) NULL,
    quiz_id BINARY(16) NULL,
    quiz_revision_id BINARY(16) NULL,
    revision_schema_version INT NULL,
    question_revision_id BINARY(16) NULL,
    PRIMARY KEY(learner_id,set_id,member_key),
    INDEX ix_learning_set_child(learner_id,child_set_id),
    CONSTRAINT fk_learning_set_member_owner FOREIGN KEY(learner_id,set_id) REFERENCES learning_set(learner_id,id),
    CONSTRAINT fk_learning_set_child_owner FOREIGN KEY(learner_id,child_set_id) REFERENCES learning_set(learner_id,id),
    CONSTRAINT fk_learning_set_member_source FOREIGN KEY(quiz_id,quiz_revision_id) REFERENCES quiz_revisions(quiz_id,id),
    CONSTRAINT fk_learning_set_member_question FOREIGN KEY(question_revision_id) REFERENCES question_revisions(id),
    CONSTRAINT chk_learning_set_member CHECK(
        (kind='SET' AND child_set_id IS NOT NULL AND quiz_id IS NULL AND quiz_revision_id IS NULL AND revision_schema_version IS NULL AND question_revision_id IS NULL)
        OR (kind IN ('QUIZ','QUESTION') AND child_set_id IS NULL AND quiz_id IS NOT NULL AND quiz_revision_id IS NOT NULL
            AND revision_schema_version IS NOT NULL AND revision_schema_version BETWEEN 1 AND 3
            AND ((kind='QUIZ' AND question_revision_id IS NULL) OR (kind='QUESTION' AND question_revision_id IS NOT NULL))))
) ENGINE=InnoDB;
CREATE TABLE learning_set_command (
    learner_id BINARY(16) NOT NULL,
    command_id BINARY(16) NOT NULL,
    input_json TEXT NOT NULL,
    result_json TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY(learner_id,command_id),
    CONSTRAINT chk_learning_set_command CHECK(JSON_VALID(input_json) AND JSON_VALID(result_json)
        AND OCTET_LENGTH(input_json)<=32768 AND OCTET_LENGTH(result_json)<=32768)
) ENGINE=InnoDB;
-- No historical backfill, schedule cascade, age cleanup or new retention period.
-- Account/retention executors must include this personal Learning family and its retained receipts.
