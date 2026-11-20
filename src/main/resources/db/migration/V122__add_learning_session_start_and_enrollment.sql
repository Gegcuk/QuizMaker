ALTER TABLE quizzes ADD COLUMN shuffle_learning_questions BOOLEAN NOT NULL DEFAULT FALSE;
-- One successful full #701 preparation of this immutable publication in a Learning enrollment transaction.
CREATE TABLE learning_prepared_publication (
    revision_id BINARY(16) NOT NULL PRIMARY KEY,
    CONSTRAINT fk_learning_prepared_revision FOREIGN KEY (revision_id) REFERENCES quiz_revisions(id)
) ENGINE=InnoDB;

-- Public start is distinct from a retained internal CREATED session. Form and captures commit with it.
CREATE TABLE learning_session_start (
    session_id BINARY(16) NOT NULL PRIMARY KEY,
    form_id BINARY(16) NOT NULL UNIQUE,
    requested_size INT NOT NULL,
    shuffled BOOLEAN NOT NULL,
    CONSTRAINT fk_learning_start_session FOREIGN KEY (session_id) REFERENCES learning_session(id),
    CONSTRAINT fk_learning_start_form FOREIGN KEY (form_id) REFERENCES learning_form(id),
    CONSTRAINT chk_learning_start_size CHECK (requested_size > 0)
) ENGINE=InnoDB;

-- Store exactly what the first accepted enrollment returned, including after a cycle replacement.
CREATE TABLE learning_enrollment (
    learner_id BINARY(16) NOT NULL,
    command_id BINARY(16) NOT NULL,
    quiz_revision_id BINARY(16) NOT NULL,
    item_count INT NOT NULL,
    PRIMARY KEY (learner_id, command_id),
    CONSTRAINT fk_learning_enrollment_revision FOREIGN KEY (quiz_revision_id) REFERENCES quiz_revisions(id),
    CONSTRAINT chk_learning_enrollment_size CHECK (item_count > 0)
) ENGINE=InnoDB;

CREATE TABLE learning_enrollment_item (
    learner_id BINARY(16) NOT NULL,
    command_id BINARY(16) NOT NULL,
    position INT NOT NULL,
    question_revision_id BINARY(16) NOT NULL,
    cycle_id BINARY(16) NOT NULL,
    cycle_version BIGINT NOT NULL,
    PRIMARY KEY (learner_id, command_id, position),
    UNIQUE KEY uq_learning_enrollment_question (learner_id, command_id, question_revision_id),
    CONSTRAINT fk_learning_enrollment_item_header FOREIGN KEY (learner_id,command_id)
        REFERENCES learning_enrollment(learner_id,command_id),
    CONSTRAINT fk_learning_enrollment_item_cycle FOREIGN KEY (cycle_id) REFERENCES learning_schedule_cycle(id),
    CONSTRAINT chk_learning_enrollment_item CHECK (position >= 0 AND cycle_version > 0)
) ENGINE=InnoDB;
