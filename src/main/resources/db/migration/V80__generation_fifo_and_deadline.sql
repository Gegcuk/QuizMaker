-- Preserve the deadline policy for work that ran before this migration.
ALTER TABLE quiz_generation_work
    ADD COLUMN admission_sequence BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN deadline_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN first_started_at DATETIME(6) NULL,
    ADD COLUMN deadline_at DATETIME(6) NULL;

UPDATE quiz_generation_work SET deadline_enabled = FALSE WHERE executions > 0;
UPDATE quiz_generation_work w
JOIN (SELECT job_id, ROW_NUMBER() OVER (ORDER BY created_at, job_id) AS sequence_number
      FROM quiz_generation_work) ordered_work ON ordered_work.job_id = w.job_id
SET w.admission_sequence = ordered_work.sequence_number;

CREATE INDEX ix_generation_work_fifo ON quiz_generation_work (state, admission_sequence);
CREATE TABLE quiz_generation_admission (
    id INT NOT NULL PRIMARY KEY,
    next_sequence BIGINT NOT NULL
) ENGINE=InnoDB;
INSERT INTO quiz_generation_admission (id, next_sequence)
SELECT 1, COALESCE(MAX(admission_sequence), 0) + 1 FROM quiz_generation_work;
