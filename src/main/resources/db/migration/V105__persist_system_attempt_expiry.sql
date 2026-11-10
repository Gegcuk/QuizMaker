-- Additive/dark #702. VOLUNTARY identifies the only pre-existing terminal writer.
ALTER TABLE attempt_submission
    ADD COLUMN completion_cause VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'VOLUNTARY',
    ADD CONSTRAINT ck_submission_cause CHECK (CAST(completion_cause AS BINARY) IN ('VOLUNTARY','EXPIRY'));

-- Derived pending work only: immutable timing remains the source. Transaction-scoped claims
-- disappear on rollback/disconnect; there is no lease to expire or new retry/retention policy.
CREATE TABLE attempt_expiry_work (
    attempt_id BINARY(16) NOT NULL PRIMARY KEY,
    effective_deadline DATETIME(6) NOT NULL,
    KEY ix_expiry_due (effective_deadline,attempt_id),
    CONSTRAINT fk_expiry_timing FOREIGN KEY (attempt_id) REFERENCES attempt_timing(attempt_id) ON DELETE CASCADE
) ENGINE=InnoDB;
INSERT INTO attempt_expiry_work(attempt_id,effective_deadline)
SELECT t.attempt_id,t.effective_deadline FROM attempt_timing t
JOIN attempt_lifecycle_state l ON l.attempt_id=t.attempt_id
WHERE l.execution_state='IN_PROGRESS';

-- Stable system identity, independent of participant transport keys and their cleanup.
CREATE TABLE attempt_expiry_receipt (
    attempt_id BINARY(16) NOT NULL PRIMARY KEY,
    schema_version SMALLINT NOT NULL,
    command_id BINARY(16) NOT NULL,
    submission_id BINARY(16) NOT NULL,
    state_version BIGINT NOT NULL,
    effective_deadline DATETIME(6) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_expiry_command (command_id),
    UNIQUE KEY uq_expiry_submission (submission_id),
    CONSTRAINT fk_expiry_receipt_timing FOREIGN KEY (attempt_id) REFERENCES attempt_timing(attempt_id),
    CONSTRAINT fk_expiry_receipt_submission FOREIGN KEY (submission_id,attempt_id) REFERENCES attempt_submission(id,attempt_id),
    CONSTRAINT ck_expiry_receipt CHECK (schema_version=1 AND state_version>0 AND recorded_at>=effective_deadline)
) ENGINE=InnoDB;

ALTER TABLE attempt_trace_event DROP CHECK chk_trace_event_type,
    DROP CHECK chk_trace_event_reason,
    ADD CONSTRAINT chk_trace_event_type CHECK (CAST(event_type AS BINARY) IN
      ('ATTEMPT_STARTED','FORM_CREATED','QUESTION_DELIVERED','ANSWER_SUBMITTED','ANSWER_LOCKED',
       'ATTEMPT_SUBMITTED','AUTOMATIC_GRADE_PRODUCED','RESULT_RELEASED','DRAFT_SAVED','DRAFT_DELETED','ITEM_NAVIGATED','ATTEMPT_EXPIRED')),
    ADD CONSTRAINT chk_trace_event_reason CHECK (CAST(reason_code AS BINARY) IN
      ('ACCEPTED','MATERIALIZED','DELIVERED','LOCKED','SUBMITTED','GRADED','RELEASED','SAVED','DELETED','NAVIGATED','EXPIRED'));
-- Keep evidence after writes. Roll back activation, then forward-fix; never erase terminal history.
