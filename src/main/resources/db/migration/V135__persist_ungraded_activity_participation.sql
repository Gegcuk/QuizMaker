-- Internal ungraded activity bindings. Existing attempts have no activity row.
CREATE TABLE attempt_ungraded_activity (
    attempt_id BINARY(16) NOT NULL PRIMARY KEY,
    form_id BINARY(16) NOT NULL,
    response_retention VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    privacy_notice_presented BOOLEAN NOT NULL,
    activity_policy MEDIUMTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    UNIQUE KEY uq_ungraded_activity_form (attempt_id, form_id, response_retention),
    CONSTRAINT fk_ungraded_activity_stream FOREIGN KEY (attempt_id, form_id)
        REFERENCES attempt_trace_stream(attempt_id, form_id),
    CONSTRAINT chk_ungraded_policy CHECK (JSON_VALID(activity_policy) AND JSON_TYPE(activity_policy) = 'OBJECT'),
    CONSTRAINT chk_ungraded_notice CHECK (privacy_notice_presented IN (0, 1)
        AND (CAST(response_retention AS BINARY) = 'RETAIN_IMMUTABLE_RESPONSES'
          OR (CAST(response_retention AS BINARY) = 'COMPLETION_ONLY_NO_RESPONSE_RETENTION'
            AND privacy_notice_presented = 1)))
) ENGINE=InnoDB;

CREATE TABLE attempt_ungraded_participation (
    id BINARY(16) NOT NULL PRIMARY KEY,
    attempt_id BINARY(16) NOT NULL,
    form_id BINARY(16) NOT NULL,
    form_item_id BINARY(16) NOT NULL,
    question_revision_id BINARY(16) NOT NULL,
    response_contract_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_contract_version INT NOT NULL,
    response_retention VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    normalized_response MEDIUMTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    command_id BINARY(16) NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_ungraded_participation_item (attempt_id, form_item_id),
    UNIQUE KEY uq_ungraded_participation_command (command_id),
    CONSTRAINT fk_ungraded_participation_activity FOREIGN KEY (attempt_id, form_id, response_retention)
        REFERENCES attempt_ungraded_activity(attempt_id, form_id, response_retention),
    CONSTRAINT fk_ungraded_participation_question FOREIGN KEY (form_id, form_item_id, question_revision_id)
        REFERENCES attempt_form_item_option_order(form_id, item_id, question_revision_id),
    CONSTRAINT fk_ungraded_participation_contract FOREIGN KEY (question_revision_id)
        REFERENCES question_revision_contract_bindings(question_revision_id),
    CONSTRAINT chk_ungraded_participation_contract CHECK (response_contract_version > 0),
    CONSTRAINT chk_ungraded_participation_payload CHECK (
        (CAST(response_retention AS BINARY) = 'COMPLETION_ONLY_NO_RESPONSE_RETENTION' AND normalized_response IS NULL)
        OR (CAST(response_retention AS BINARY) = 'RETAIN_IMMUTABLE_RESPONSES' AND normalized_response IS NOT NULL
            AND JSON_VALID(normalized_response) AND JSON_TYPE(normalized_response) = 'OBJECT'
            AND OCTET_LENGTH(normalized_response) <= 65536))
) ENGINE=InnoDB;
-- No receipt FK: normal receipt expiry must not delete or block retained participation.
