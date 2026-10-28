-- Dark target evidence only. No legacy inference, backfill, cleanup or business-command routing.
CREATE TABLE attempt_trace_stream (
    attempt_id BINARY(16) NOT NULL PRIMARY KEY,
    schema_version SMALLINT NOT NULL,
    policy_snapshot_id BINARY(16) NOT NULL,
    form_id BINARY(16) NOT NULL,
    last_sequence BIGINT NOT NULL,
    UNIQUE KEY uq_trace_stream_form (attempt_id, form_id),
    CONSTRAINT fk_trace_lifecycle FOREIGN KEY (attempt_id) REFERENCES attempt_lifecycle_state(attempt_id),
    CONSTRAINT fk_trace_subject FOREIGN KEY (attempt_id) REFERENCES attempt_participant_subject(attempt_id),
    CONSTRAINT fk_trace_policy FOREIGN KEY (policy_snapshot_id) REFERENCES attempt_policy_snapshot(id),
    CONSTRAINT fk_trace_form FOREIGN KEY (form_id) REFERENCES attempt_form(id),
    CONSTRAINT chk_trace_stream CHECK (schema_version = 1 AND last_sequence >= 0)
) ENGINE=InnoDB;

-- Reserve an existing unique row before replay lookup: absent-key locking reads would otherwise
-- take shared gap locks and deadlock unrelated first appends under REPEATABLE_READ.
CREATE TABLE attempt_trace_identity (
    event_id BINARY(16) NOT NULL PRIMARY KEY,
    attempt_id BINARY(16) NOT NULL,
    sequence_number BIGINT NOT NULL,
    CONSTRAINT fk_trace_identity_stream FOREIGN KEY (attempt_id) REFERENCES attempt_trace_stream(attempt_id),
    CONSTRAINT chk_trace_identity CHECK (sequence_number >= 0)
) ENGINE=InnoDB;

CREATE TABLE attempt_trace_event (
    attempt_id BINARY(16) NOT NULL,
    sequence_number BIGINT NOT NULL,
    event_id BINARY(16) NOT NULL,
    schema_version SMALLINT NOT NULL,
    event_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    command_id BINARY(16) NULL,
    form_id BINARY(16) NOT NULL,
    item_id BINARY(16) NULL,
    from_execution VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    from_answers VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    to_execution VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    to_answers VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (attempt_id, sequence_number),
    UNIQUE KEY uq_trace_event_identity (event_id),
    CONSTRAINT fk_trace_event_stream FOREIGN KEY (attempt_id, form_id) REFERENCES attempt_trace_stream(attempt_id, form_id),
    CONSTRAINT fk_trace_event_item FOREIGN KEY (form_id, item_id) REFERENCES attempt_form_item(form_id, id),
    CONSTRAINT chk_trace_event_version CHECK (schema_version = 1 AND sequence_number > 0),
    CONSTRAINT chk_trace_event_type CHECK (CAST(event_type AS BINARY) IN
      ('ATTEMPT_STARTED','FORM_CREATED','QUESTION_DELIVERED','ANSWER_SUBMITTED','ANSWER_LOCKED',
       'ATTEMPT_SUBMITTED','AUTOMATIC_GRADE_PRODUCED','RESULT_RELEASED')),
    CONSTRAINT chk_trace_event_actor CHECK (CAST(actor_type AS BINARY) IN ('PARTICIPANT','SYSTEM')),
    CONSTRAINT chk_trace_event_reason CHECK (CAST(reason_code AS BINARY) IN
      ('ACCEPTED','MATERIALIZED','DELIVERED','LOCKED','SUBMITTED','GRADED','RELEASED')),
    CONSTRAINT chk_trace_event_transition CHECK (
      (from_execution IS NULL AND from_answers IS NULL AND to_execution IS NULL AND to_answers IS NULL) OR
      (from_execution IS NOT NULL AND from_answers IS NOT NULL AND to_execution IS NOT NULL AND to_answers IS NOT NULL AND
       ((CAST(from_execution AS BINARY) = 'CREATED' AND CAST(from_answers AS BINARY) = 'NOT_STARTED') OR
        (CAST(from_execution AS BINARY) = 'IN_PROGRESS' AND CAST(from_answers AS BINARY) IN ('NOT_STARTED','IN_PROGRESS')) OR
        (CAST(from_execution AS BINARY) = 'SUBMITTED' AND CAST(from_answers AS BINARY) = 'COMPLETE')) AND
       ((CAST(to_execution AS BINARY) = 'CREATED' AND CAST(to_answers AS BINARY) = 'NOT_STARTED') OR
        (CAST(to_execution AS BINARY) = 'IN_PROGRESS' AND CAST(to_answers AS BINARY) IN ('NOT_STARTED','IN_PROGRESS')) OR
        (CAST(to_execution AS BINARY) = 'SUBMITTED' AND CAST(to_answers AS BINARY) = 'COMPLETE')))
    )
) ENGINE=InnoDB;
