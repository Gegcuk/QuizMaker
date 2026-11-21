ALTER TABLE learning_session_start
    ADD COLUMN review_mode VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DUE',
    ADD COLUMN selected_question_id BINARY(16) NULL,
    ADD COLUMN reviewed_items INT NOT NULL DEFAULT 0,
    ADD UNIQUE KEY uq_learning_start_form_pair (session_id, form_id),
    ADD CONSTRAINT chk_learning_start_review_mode CHECK (
        (review_mode='DUE' AND selected_question_id IS NULL)
        OR (review_mode IN ('EARLY','PRACTICE') AND selected_question_id IS NOT NULL AND requested_size=1)),
    ADD CONSTRAINT chk_learning_start_reviewed CHECK (reviewed_items BETWEEN 0 AND requested_size);

-- Personal Learning answers also carry the original acknowledgement. They never reference assessment answers.
CREATE TABLE learning_session_response (
    learner_id BINARY(16) NOT NULL,
    command_id BINARY(16) NOT NULL,
    session_id BINARY(16) NOT NULL,
    form_id BINARY(16) NOT NULL,
    item_position INT NOT NULL,
    input_fingerprint BINARY(32) NOT NULL,
    response_json MEDIUMTEXT CHARACTER SET utf8mb4 NOT NULL,
    rating VARCHAR(5) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    next_item_id BINARY(16) NULL,
    next_due_at DATETIME(6) NULL,
    PRIMARY KEY (learner_id, command_id),
    UNIQUE KEY uq_learning_response_position (session_id, item_position),
    CONSTRAINT fk_learning_response_start FOREIGN KEY (session_id, form_id)
        REFERENCES learning_session_start(session_id, form_id),
    CONSTRAINT fk_learning_response_item FOREIGN KEY (form_id, item_position)
        REFERENCES learning_form_item(form_id, position),
    CONSTRAINT chk_learning_response_payload CHECK (JSON_VALID(response_json)
        AND JSON_TYPE(response_json)='OBJECT' AND OCTET_LENGTH(response_json)<=65536),
    CONSTRAINT chk_learning_response_rating CHECK (rating IN ('AGAIN','HARD','GOOD','EASY'))
) ENGINE=InnoDB;
-- Existing starts are still DUE; neither answers nor schedule history are backfilled.
