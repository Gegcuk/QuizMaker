-- Preserve all existing command/event values; new draft commands stay dark by default.
ALTER TABLE attempt_command_receipt DROP CHECK chk_receipt_operation,
    ADD CONSTRAINT chk_receipt_operation CHECK (CAST(operation AS BINARY) IN
        ('START','NAVIGATE_TO_ITEM','SUBMIT_ANSWER','SUBMIT_ATTEMPT','SAVE_DRAFT','DELETE_DRAFT'));
ALTER TABLE attempt_trace_event DROP CHECK chk_trace_event_type,
    ADD CONSTRAINT chk_trace_event_type CHECK (CAST(event_type AS BINARY) IN
        ('ATTEMPT_STARTED','FORM_CREATED','QUESTION_DELIVERED','ANSWER_SUBMITTED','ANSWER_LOCKED',
         'ATTEMPT_SUBMITTED','AUTOMATIC_GRADE_PRODUCED','RESULT_RELEASED','DRAFT_SAVED','DRAFT_DELETED')),
    DROP CHECK chk_trace_event_reason,
    ADD CONSTRAINT chk_trace_event_reason CHECK (CAST(reason_code AS BINARY) IN
        ('ACCEPTED','MATERIALIZED','DELIVERED','LOCKED','SUBMITTED','GRADED','RELEASED','SAVED','DELETED'));

-- Immutable, content-free receipt extension. Keep the original draft version/status on replay,
-- even after later saves/deletion. It follows receipt retention, not answer-history retention.
CREATE TABLE attempt_draft_command_result (
    command_id BINARY(16) NOT NULL PRIMARY KEY,
    attempt_id BINARY(16) NOT NULL,
    form_item_id BINARY(16) NOT NULL,
    draft_version BIGINT NOT NULL,
    draft_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    CONSTRAINT fk_draft_command_receipt FOREIGN KEY (command_id) REFERENCES attempt_command_receipt(id) ON DELETE CASCADE,
    CONSTRAINT chk_draft_command_result CHECK (draft_version > 0 AND CAST(draft_status AS BINARY) IN ('PRESENT','DELETED'))
) ENGINE=InnoDB;
