-- Internal #634 work only; no scheduler activation or invented legacy schedules.
CREATE TABLE attempt_scheduled_release (
    submission_id BINARY(16) NOT NULL PRIMARY KEY,
    schema_version SMALLINT NOT NULL,
    result_id BINARY(16) NOT NULL,
    configuration_id BINARY(16) NOT NULL,
    configuration_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id BINARY(16) NOT NULL,
    due_at DATETIME(6) NOT NULL,
    registered_at DATETIME(6) NOT NULL,
    work_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    failures SMALLINT NOT NULL,
    row_version BIGINT NOT NULL,
    next_at DATETIME(6) NULL,
    last_failure VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    UNIQUE KEY uq_scheduled_result (result_id),
    UNIQUE KEY uq_scheduled_event (event_id),
    KEY ix_scheduled_due (next_at,submission_id,work_state),
    CONSTRAINT fk_scheduled_submission FOREIGN KEY (submission_id) REFERENCES attempt_submission(id),
    CONSTRAINT fk_scheduled_result FOREIGN KEY (result_id) REFERENCES attempt_automatic_result(id),
    CONSTRAINT fk_scheduled_configuration FOREIGN KEY (configuration_id) REFERENCES result_release_configurations(id),
    CONSTRAINT chk_scheduled_failure CHECK (schema_version=1 AND failures BETWEEN 0 AND 4 AND row_version>=failures
        AND ((failures=0 AND last_failure IS NULL) OR (failures>0 AND last_failure IS NOT NULL))
        AND (last_failure IS NULL OR last_failure IN ('INVALID_INPUT','NOT_FOUND','NOT_READY','UNSUPPORTED','CORRUPT','BUSY','RETRYABLE','UNAVAILABLE'))),
    CONSTRAINT chk_scheduled_state CHECK (
        (work_state='PENDING' AND failures=0 AND last_failure IS NULL AND next_at IS NOT NULL AND next_at=due_at) OR
        (work_state='RETRY' AND failures BETWEEN 1 AND 3 AND last_failure IS NOT NULL AND next_at IS NOT NULL AND next_at>=due_at) OR
        (work_state='REVIEW' AND failures BETWEEN 1 AND 4 AND last_failure IS NOT NULL AND next_at IS NULL) OR
        (work_state='RELEASED' AND next_at IS NULL))
) ENGINE=InnoDB;

CREATE TRIGGER scheduled_release_binding_no_update BEFORE UPDATE ON attempt_scheduled_release
FOR EACH ROW
BEGIN
    IF NOT (OLD.submission_id <=> NEW.submission_id AND OLD.schema_version <=> NEW.schema_version AND OLD.result_id <=> NEW.result_id
        AND OLD.configuration_id <=> NEW.configuration_id AND OLD.configuration_hash <=> NEW.configuration_hash
        AND OLD.event_id <=> NEW.event_id AND OLD.due_at <=> NEW.due_at AND OLD.registered_at <=> NEW.registered_at) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Scheduled release binding is immutable';
    END IF;
END;
-- Retain work, receipts and immutable configuration on application rollback; forward fix only.
