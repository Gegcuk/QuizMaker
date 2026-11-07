-- Dark single-response evidence. No legacy backfill, result changes or cascading deletion.
CREATE TABLE attempt_accepted_response (
    id BINARY(16) NOT NULL PRIMARY KEY,
    schema_version SMALLINT NOT NULL,
    attempt_id BINARY(16) NOT NULL,
    form_id BINARY(16) NOT NULL,
    form_item_id BINARY(16) NOT NULL,
    question_revision_id BINARY(16) NOT NULL,
    response_contract_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_contract_version INT NOT NULL,
    normalized_response MEDIUMTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    command_id BINARY(16) NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_accepted_attempt_item (attempt_id, form_item_id),
    UNIQUE KEY uq_accepted_command (command_id),
    CONSTRAINT fk_accepted_stream FOREIGN KEY (attempt_id, form_id)
        REFERENCES attempt_trace_stream(attempt_id, form_id),
    CONSTRAINT fk_accepted_item_question FOREIGN KEY (form_id, form_item_id, question_revision_id)
        REFERENCES attempt_form_item_option_order(form_id, item_id, question_revision_id),
    CONSTRAINT fk_accepted_question_contract FOREIGN KEY (question_revision_id)
        REFERENCES question_revision_contract_bindings(question_revision_id),
    CONSTRAINT chk_accepted_contract CHECK (schema_version = 1 AND response_contract_version > 0),
    CONSTRAINT chk_accepted_payload CHECK (JSON_VALID(normalized_response) AND JSON_TYPE(normalized_response) = 'OBJECT'
        AND OCTET_LENGTH(normalized_response) <= 65536)
) ENGINE=InnoDB;
-- command_id intentionally has no receipt FK: receipt expiry must not erase/block retained answers.
