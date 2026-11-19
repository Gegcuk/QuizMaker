-- #649: separate, dark Learning authority. No import, history reset, cascade or age cleanup.
-- This strategy-neutral slot is shared by future FSRS/custom writers, never one slot per strategy.
CREATE TABLE learning_schedule_slot (
    learner_id BINARY(16) NOT NULL,
    quiz_id BINARY(16) NOT NULL,
    quiz_revision_id BINARY(16) NOT NULL,
    question_revision_id BINARY(16) NOT NULL,
    active_cycle_id BINARY(16) NULL,
    cycle_version BIGINT NOT NULL,
    seed_id BINARY(16) NOT NULL,
    PRIMARY KEY (learner_id,quiz_id,quiz_revision_id,question_revision_id),
    UNIQUE KEY uq_learning_active_cycle (active_cycle_id),
    CONSTRAINT fk_learning_slot_quiz FOREIGN KEY (quiz_revision_id) REFERENCES quiz_revisions(id),
    CONSTRAINT fk_learning_slot_question FOREIGN KEY (question_revision_id) REFERENCES question_revisions(id),
    CONSTRAINT chk_learning_slot_version CHECK ((active_cycle_id IS NULL AND cycle_version=0)
        OR (active_cycle_id IS NOT NULL AND cycle_version>0))
) ENGINE=InnoDB;

CREATE TABLE learning_schedule_cycle (
    id BINARY(16) NOT NULL PRIMARY KEY,
    learner_id BINARY(16) NOT NULL,
    quiz_id BINARY(16) NOT NULL,
    quiz_revision_id BINARY(16) NOT NULL,
    question_revision_id BINARY(16) NOT NULL,
    cycle_version BIGINT NOT NULL,
    strategy_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    create_command_id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_learning_cycle_version (learner_id,quiz_id,quiz_revision_id,question_revision_id,cycle_version),
    UNIQUE KEY uq_learning_cycle_binding (id,learner_id,quiz_id,quiz_revision_id,question_revision_id,cycle_version),
    CONSTRAINT fk_learning_cycle_slot FOREIGN KEY (learner_id,quiz_id,quiz_revision_id,question_revision_id)
        REFERENCES learning_schedule_slot(learner_id,quiz_id,quiz_revision_id,question_revision_id),
    CONSTRAINT chk_learning_cycle_version CHECK (cycle_version>0 AND LENGTH(strategy_id)>0)
) ENGINE=InnoDB;
ALTER TABLE learning_schedule_slot ADD CONSTRAINT fk_learning_slot_active
    FOREIGN KEY (active_cycle_id,learner_id,quiz_id,quiz_revision_id,question_revision_id,cycle_version)
    REFERENCES learning_schedule_cycle(id,learner_id,quiz_id,quiz_revision_id,question_revision_id,cycle_version);

CREATE TABLE learning_fsrs_state (
    cycle_id BINARY(16) NOT NULL PRIMARY KEY,
    sequence BIGINT NOT NULL,
    -- MySQL binary JSON can change the last bit of a double when rendering it back to text.
    -- Retain the exact project-owned encoding; still validate JSON shape and bounded size.
    state TEXT CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    CONSTRAINT fk_learning_fsrs_cycle FOREIGN KEY (cycle_id) REFERENCES learning_schedule_cycle(id),
    CONSTRAINT chk_learning_fsrs_state CHECK (sequence>=0 AND JSON_VALID(state) AND JSON_TYPE(state)='OBJECT' AND OCTET_LENGTH(state)<=16384)
) ENGINE=InnoDB;

-- Semantic receipt is reserved first; incomplete reservations may exist only inside the transaction.
-- Result state is retained even after later reviews/cycle changes for exact historical replay.
CREATE TABLE learning_schedule_receipt (
    learner_id BINARY(16) NOT NULL,
    command_id BINARY(16) NOT NULL,
    reservation_id BINARY(16) NOT NULL,
    semantic_input TEXT CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_cycle_version BIGINT NULL,
    result_state TEXT CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (learner_id,command_id),
    CONSTRAINT chk_learning_receipt CHECK (OCTET_LENGTH(semantic_input)<=20000
        AND ((result_cycle_version IS NULL AND result_state IS NULL)
            OR (result_cycle_version IS NOT NULL AND result_cycle_version>0 AND result_state IS NOT NULL
                AND JSON_VALID(result_state) AND JSON_TYPE(result_state)='OBJECT' AND OCTET_LENGTH(result_state)<=16384)))
) ENGINE=InnoDB;

CREATE TABLE learning_review_event (
    cycle_id BINARY(16) NOT NULL,
    sequence BIGINT NOT NULL,
    learner_id BINARY(16) NOT NULL,
    command_id BINARY(16) NOT NULL,
    transition TEXT CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (cycle_id,sequence),
    UNIQUE KEY uq_learning_review_command (learner_id,command_id),
    CONSTRAINT fk_learning_review_cycle FOREIGN KEY (cycle_id) REFERENCES learning_schedule_cycle(id),
    CONSTRAINT fk_learning_review_receipt FOREIGN KEY (learner_id,command_id) REFERENCES learning_schedule_receipt(learner_id,command_id),
    CONSTRAINT chk_learning_review CHECK (sequence>0 AND JSON_VALID(transition) AND JSON_TYPE(transition)='OBJECT' AND OCTET_LENGTH(transition)<=16384)
) ENGINE=InnoDB;

CREATE TABLE learning_cycle_session (
    cycle_id BINARY(16) NOT NULL,
    session_id BINARY(16) NOT NULL,
    captured_sequence BIGINT NOT NULL,
    released BOOLEAN NOT NULL,
    PRIMARY KEY (cycle_id,session_id),
    INDEX ix_learning_unreleased_session (cycle_id,released),
    CONSTRAINT fk_learning_capture_cycle FOREIGN KEY (cycle_id) REFERENCES learning_schedule_cycle(id),
    CONSTRAINT fk_learning_capture_session FOREIGN KEY (session_id) REFERENCES learning_session(id),
    CONSTRAINT chk_learning_capture CHECK (captured_sequence>=0 AND released IN (0,1))
) ENGINE=InnoDB;
-- Retention/erasure owners must include these personal Learning records and retained receipts.
-- Reads never initialize missing state; forward-fix corrupt/missing evidence explicitly.
