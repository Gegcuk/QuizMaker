-- Dark #529 terminal evidence. No legacy backfill, score calculation, cleanup or cascade.
CREATE TABLE attempt_submission (
    id BINARY(16) NOT NULL PRIMARY KEY,
    schema_version SMALLINT NOT NULL,
    attempt_id BINARY(16) NOT NULL,
    form_id BINARY(16) NOT NULL,
    policy_snapshot_id BINARY(16) NOT NULL,
    quiz_revision_id BINARY(16) NOT NULL,
    command_id BINARY(16) NOT NULL,
    state_version BIGINT NOT NULL,
    accepted_count INT NOT NULL,
    unanswered_count INT NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_submission_attempt (attempt_id),
    UNIQUE KEY uq_submission_command (command_id),
    UNIQUE KEY uq_submission_identity (id, attempt_id),
    CONSTRAINT fk_submission_lifecycle FOREIGN KEY (attempt_id) REFERENCES attempt_lifecycle_state(attempt_id),
    CONSTRAINT fk_submission_form FOREIGN KEY (attempt_id,form_id) REFERENCES attempt_trace_stream(attempt_id,form_id),
    CONSTRAINT fk_submission_policy FOREIGN KEY (policy_snapshot_id) REFERENCES attempt_policy_snapshot(id),
    CONSTRAINT fk_submission_revision FOREIGN KEY (quiz_revision_id) REFERENCES quiz_revisions(id),
    CONSTRAINT chk_submission_v1 CHECK (schema_version=1 AND state_version>0
        AND accepted_count>=0 AND unanswered_count>=0 AND accepted_count+unanswered_count>0)
) ENGINE=InnoDB;
-- The retained intent is visible only after the terminal transaction commits. #603 owns
-- consumption/result creation and its restart/acknowledgement protocol, not this migration.
CREATE TABLE attempt_finalization_intent (
    submission_id BINARY(16) NOT NULL PRIMARY KEY,
    attempt_id BINARY(16) NOT NULL,
    schema_version SMALLINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_finalization_attempt (attempt_id),
    KEY ix_finalization_created (created_at,submission_id),
    CONSTRAINT fk_finalization_submission FOREIGN KEY (submission_id,attempt_id)
        REFERENCES attempt_submission(id,attempt_id),
    CONSTRAINT chk_finalization_v1 CHECK (schema_version=1)
) ENGINE=InnoDB;
-- No receipt FK: expiry of transport receipts must not erase terminal evidence or intents.
-- Keep additive tables on rollback; use a forward fix rather than deleting submitted history.
