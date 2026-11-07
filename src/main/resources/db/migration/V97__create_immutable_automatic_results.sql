-- Dark #603 binary-result/v1. No legacy backfill, release, cleanup, cascade or scheduler.
CREATE TABLE attempt_automatic_result (
    id BINARY(16) NOT NULL PRIMARY KEY,
    schema_version SMALLINT NOT NULL,
    scoring_contract VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    submission_id BINARY(16) NOT NULL,
    attempt_id BINARY(16) NOT NULL,
    form_id BINARY(16) NOT NULL,
    policy_snapshot_id BINARY(16) NOT NULL,
    quiz_revision_id BINARY(16) NOT NULL,
    policy_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    earned_points BIGINT NOT NULL,
    possible_points BIGINT NOT NULL,
    basis_points INT NOT NULL,
    pass_threshold INT NOT NULL,
    passed BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_automatic_submission (submission_id),
    UNIQUE KEY uq_automatic_attempt (attempt_id),
    UNIQUE KEY uq_automatic_form (id,form_id),
    CONSTRAINT fk_automatic_submission FOREIGN KEY (submission_id,attempt_id) REFERENCES attempt_submission(id,attempt_id),
    CONSTRAINT fk_automatic_form FOREIGN KEY (attempt_id,form_id) REFERENCES attempt_trace_stream(attempt_id,form_id),
    CONSTRAINT fk_automatic_policy FOREIGN KEY (policy_snapshot_id) REFERENCES attempt_policy_snapshot(id),
    CONSTRAINT fk_automatic_revision FOREIGN KEY (quiz_revision_id) REFERENCES quiz_revisions(id),
    CONSTRAINT chk_automatic_contract CHECK (schema_version=1 AND scoring_contract='binary-result/v1'),
    CONSTRAINT chk_automatic_score CHECK (possible_points>0 AND earned_points>=0 AND earned_points<=possible_points
        AND basis_points BETWEEN 0 AND 10000 AND pass_threshold BETWEEN 0 AND 10000
        AND basis_points=(CAST(earned_points AS DECIMAL(40,0))*20000+possible_points)
            DIV (CAST(possible_points AS DECIMAL(40,0))*2)
        AND passed=(CAST(earned_points AS DECIMAL(30,0))*10000>=CAST(possible_points AS DECIMAL(30,0))*pass_threshold))
) ENGINE=InnoDB;
CREATE TABLE attempt_automatic_grade (
    result_id BINARY(16) NOT NULL,
    form_id BINARY(16) NOT NULL,
    form_item_id BINARY(16) NOT NULL,
    question_revision_id BINARY(16) NOT NULL,
    response_id BINARY(16) NULL,
    position INT NOT NULL,
    response_contract_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_contract_version INT NOT NULL,
    scoring_contract_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scoring_contract_version INT NOT NULL,
    earned_points TINYINT NOT NULL,
    PRIMARY KEY (result_id,form_item_id),
    UNIQUE KEY uq_automatic_position (result_id,position),
    UNIQUE KEY uq_automatic_response (response_id),
    CONSTRAINT fk_grade_result FOREIGN KEY (result_id,form_id) REFERENCES attempt_automatic_result(id,form_id),
    CONSTRAINT fk_grade_item FOREIGN KEY (form_id,form_item_id,question_revision_id)
        REFERENCES attempt_form_item_option_order(form_id,item_id,question_revision_id),
    CONSTRAINT fk_grade_response FOREIGN KEY (response_id) REFERENCES attempt_accepted_response(id),
    CONSTRAINT chk_automatic_grade CHECK (position>=0 AND response_contract_version>0 AND scoring_contract_version>0
        AND earned_points IN (0,1) AND (response_id IS NOT NULL OR earned_points=0))
) ENGINE=InnoDB;
-- One retained outbox fact per result. Its ID also identifies the atomic GRADED trace event.
CREATE TABLE attempt_result_created (
    result_id BINARY(16) NOT NULL PRIMARY KEY,
    schema_version SMALLINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    KEY ix_result_created (created_at,result_id),
    CONSTRAINT fk_created_result FOREIGN KEY (result_id) REFERENCES attempt_automatic_result(id),
    CONSTRAINT fk_created_trace FOREIGN KEY (result_id) REFERENCES attempt_trace_event(event_id),
    CONSTRAINT chk_result_created CHECK (schema_version=1)
) ENGINE=InnoDB;
-- The immutable result consumes its unique retained submission intent. Recovery finds intents
-- without a result; completed intents and events remain independent of receipt expiration.
-- Keep all three tables on application rollback; retained evidence requires a forward fix.
