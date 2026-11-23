-- Existing custom cycles retain their immediate first review and captured plan version.
ALTER TABLE learning_custom_state
    ADD COLUMN schedule_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN reviewed_at DATETIME(6) NULL,
    ADD COLUMN time_zone VARCHAR(255) NULL,
    ADD CONSTRAINT chk_custom_schedule_version CHECK (schedule_version>=0);

CREATE TABLE learning_custom_stage (
    cycle_id BINARY(16) NOT NULL,
    stage INT NOT NULL,
    interval_json VARCHAR(512) NOT NULL,
    PRIMARY KEY(cycle_id,stage),
    CONSTRAINT fk_custom_stage_cycle FOREIGN KEY(cycle_id) REFERENCES learning_custom_state(cycle_id),
    CONSTRAINT chk_custom_stage CHECK(stage>=0 AND JSON_VALID(interval_json))
) ENGINE=InnoDB;

CREATE TABLE learning_custom_change (
    cycle_id BINARY(16) NOT NULL,
    version BIGINT NOT NULL,
    learner_id BINARY(16) NOT NULL,
    command_id BINARY(16) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    before_json TEXT NOT NULL,
    after_json TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY(cycle_id,version),
    UNIQUE KEY uq_custom_change_command(learner_id,command_id),
    CONSTRAINT fk_custom_change_cycle FOREIGN KEY(cycle_id) REFERENCES learning_custom_state(cycle_id),
    CONSTRAINT chk_custom_change CHECK(version>0 AND kind IN ('AGAIN','HARD','GOOD','EASY','TIMEZONE')
        AND JSON_VALID(before_json) AND JSON_VALID(after_json)
        AND OCTET_LENGTH(before_json)<=32768 AND OCTET_LENGTH(after_json)<=32768)
) ENGINE=InnoDB;
