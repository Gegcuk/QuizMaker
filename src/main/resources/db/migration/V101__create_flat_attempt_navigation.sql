-- No cursor/backfill for historical FREE or legacy attempts. Position is independent of answers.
CREATE TABLE attempt_navigation (
    attempt_id BINARY(16) NOT NULL PRIMARY KEY,
    form_id BINARY(16) NOT NULL,
    current_item_id BINARY(16) NOT NULL,
    row_version BIGINT NOT NULL,
    CONSTRAINT fk_navigation_lifecycle FOREIGN KEY (attempt_id) REFERENCES attempt_lifecycle_state(attempt_id),
    CONSTRAINT fk_navigation_item FOREIGN KEY (form_id,current_item_id) REFERENCES attempt_form_item(form_id,id),
    CONSTRAINT chk_navigation_version CHECK (row_version > 0)
) ENGINE=InnoDB;

-- Immutable receipt extension; cleanup follows the existing receipt retention boundary.
CREATE TABLE attempt_navigation_result (
    command_id BINARY(16) NOT NULL PRIMARY KEY,
    attempt_id BINARY(16) NOT NULL,
    target_item_id BINARY(16) NOT NULL,
    form_id BINARY(16) NULL,
    current_item_id BINARY(16) NULL,
    row_version BIGINT NULL,
    CONSTRAINT fk_navigation_receipt FOREIGN KEY (command_id) REFERENCES attempt_command_receipt(id) ON DELETE CASCADE,
    CONSTRAINT chk_navigation_result CHECK (
        (form_id IS NULL AND current_item_id IS NULL AND row_version IS NULL) OR
        (form_id IS NOT NULL AND current_item_id IS NOT NULL AND row_version IS NOT NULL AND row_version > 0))
) ENGINE=InnoDB;

ALTER TABLE attempt_trace_event DROP CHECK chk_trace_event_type,
    ADD CONSTRAINT chk_trace_event_type CHECK (CAST(event_type AS BINARY) IN
        ('ATTEMPT_STARTED','FORM_CREATED','QUESTION_DELIVERED','ANSWER_SUBMITTED','ANSWER_LOCKED',
         'ATTEMPT_SUBMITTED','AUTOMATIC_GRADE_PRODUCED','RESULT_RELEASED','DRAFT_SAVED','DRAFT_DELETED','ITEM_NAVIGATED')),
    DROP CHECK chk_trace_event_reason,
    ADD CONSTRAINT chk_trace_event_reason CHECK (CAST(reason_code AS BINARY) IN
        ('ACCEPTED','MATERIALIZED','DELIVERED','LOCKED','SUBMITTED','GRADED','RELEASED','SAVED','DELETED','NAVIGATED'));
