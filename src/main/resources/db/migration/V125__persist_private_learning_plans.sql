-- Configuration is immutable; the private head owns edit/pause concurrency independently.
CREATE TABLE learning_plan (
    id BINARY(16) NOT NULL PRIMARY KEY,
    learner_id BINARY(16) NOT NULL,
    version BIGINT NOT NULL,
    plan_version BIGINT NOT NULL,
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_learning_plan_owner (learner_id,id),
    CONSTRAINT chk_learning_plan_version CHECK (version>0 AND plan_version>0 AND plan_version<=version AND paused IN (0,1))
) ENGINE=InnoDB;
CREATE TABLE learning_plan_version (
    plan_id BINARY(16) NOT NULL,
    version BIGINT NOT NULL,
    definition TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (plan_id,version),
    CONSTRAINT fk_learning_plan_version FOREIGN KEY (plan_id) REFERENCES learning_plan(id),
    CONSTRAINT chk_learning_plan_definition CHECK (version>0 AND JSON_VALID(definition)
        AND JSON_TYPE(definition)='OBJECT' AND OCTET_LENGTH(definition)<=32768)
) ENGINE=InnoDB;
-- The service creates the head and version in one transaction; every read verifies that exact
-- version. Avoid a circular immediate FK that would make the first insert impossible in MySQL.

CREATE TABLE learning_plan_quiz_default (
    learner_id BINARY(16) NOT NULL,
    quiz_id BINARY(16) NOT NULL,
    version BIGINT NOT NULL,
    plan_id BINARY(16) NULL,
    PRIMARY KEY (learner_id,quiz_id),
    CONSTRAINT fk_learning_default_owner FOREIGN KEY (learner_id,plan_id) REFERENCES learning_plan(learner_id,id),
    CONSTRAINT chk_learning_default_version CHECK (version>0)
) ENGINE=InnoDB;
CREATE TABLE learning_plan_question_override (
    learner_id BINARY(16) NOT NULL,
    quiz_id BINARY(16) NOT NULL,
    quiz_revision_id BINARY(16) NOT NULL,
    question_revision_id BINARY(16) NOT NULL,
    version BIGINT NOT NULL,
    plan_id BINARY(16) NULL,
    plan_version BIGINT NULL,
    PRIMARY KEY (learner_id,quiz_id,quiz_revision_id,question_revision_id),
    CONSTRAINT fk_learning_override_owner FOREIGN KEY (learner_id,plan_id) REFERENCES learning_plan(learner_id,id),
    CONSTRAINT fk_learning_override_version FOREIGN KEY (plan_id,plan_version) REFERENCES learning_plan_version(plan_id,version),
    CONSTRAINT fk_learning_override_question FOREIGN KEY (question_revision_id) REFERENCES question_revisions(id),
    CONSTRAINT fk_learning_override_source FOREIGN KEY (quiz_id,quiz_revision_id) REFERENCES quiz_revisions(quiz_id,id),
    CONSTRAINT chk_learning_override_version CHECK (version>0 AND ((plan_id IS NULL AND plan_version IS NULL)
        OR (plan_id IS NOT NULL AND plan_version>0)))
) ENGINE=InnoDB;
CREATE TABLE learning_custom_state (
    cycle_id BINARY(16) NOT NULL PRIMARY KEY,
    learner_id BINARY(16) NOT NULL,
    plan_id BINARY(16) NOT NULL,
    plan_version BIGINT NOT NULL,
    sequence BIGINT NOT NULL,
    stage INT NOT NULL,
    due_at DATETIME(6) NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_learning_custom_cycle FOREIGN KEY (cycle_id) REFERENCES learning_schedule_cycle(id),
    CONSTRAINT fk_learning_custom_owner FOREIGN KEY (learner_id,plan_id) REFERENCES learning_plan(learner_id,id),
    CONSTRAINT fk_learning_custom_version FOREIGN KEY (plan_id,plan_version) REFERENCES learning_plan_version(plan_id,version),
    CONSTRAINT chk_learning_custom_state CHECK (sequence>=0 AND stage>=0 AND completed IN (0,1))
) ENGINE=InnoDB;
CREATE TABLE learning_plan_command (
    learner_id BINARY(16) NOT NULL,
    command_id BINARY(16) NOT NULL,
    input_json TEXT NOT NULL,
    result_json TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (learner_id,command_id),
    CONSTRAINT chk_learning_plan_command CHECK (JSON_VALID(input_json) AND JSON_VALID(result_json)
        AND OCTET_LENGTH(input_json)<=32768 AND OCTET_LENGTH(result_json)<=32768)
) ENGINE=InnoDB;
