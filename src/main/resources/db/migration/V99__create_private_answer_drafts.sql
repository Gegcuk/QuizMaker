-- Private mutable values only. No command routing, accepted-answer writes, cleanup or legacy backfill.
-- The existing five-column key also binds scoring; drafts need this exact response-only reference.
ALTER TABLE question_revision_contract_bindings ADD UNIQUE KEY uq_question_response_exact
    (question_revision_id, response_contract_id, response_contract_version);

CREATE TABLE attempt_answer_draft (
    attempt_id BINARY(16) NOT NULL,
    form_item_id BINARY(16) NOT NULL,
    schema_version SMALLINT NOT NULL,
    form_id BINARY(16) NOT NULL,
    question_revision_id BINARY(16) NOT NULL,
    response_contract_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_contract_version INT NOT NULL,
    row_version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL,
    normalized_response MEDIUMTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attempt_id, form_item_id),
    CONSTRAINT fk_draft_subject FOREIGN KEY (attempt_id) REFERENCES attempt_participant_subject(attempt_id),
    CONSTRAINT fk_draft_stream FOREIGN KEY (attempt_id, form_id) REFERENCES attempt_trace_stream(attempt_id, form_id),
    CONSTRAINT fk_draft_item FOREIGN KEY (form_id, form_item_id, question_revision_id)
        REFERENCES attempt_form_item_option_order(form_id, item_id, question_revision_id),
    CONSTRAINT fk_draft_question FOREIGN KEY (question_revision_id, response_contract_id, response_contract_version)
        REFERENCES question_revision_contract_bindings(question_revision_id, response_contract_id, response_contract_version),
    CONSTRAINT chk_draft_metadata CHECK (schema_version = 1 AND row_version > 0 AND response_contract_version > 0
        AND updated_at >= created_at),
    CONSTRAINT chk_draft_value CHECK (
        (deleted = TRUE AND normalized_response IS NULL) OR
        (deleted = FALSE AND normalized_response IS NOT NULL AND JSON_VALID(normalized_response)
         AND JSON_TYPE(normalized_response) = 'OBJECT' AND OCTET_LENGTH(normalized_response) <= 65536))
) ENGINE=InnoDB;
