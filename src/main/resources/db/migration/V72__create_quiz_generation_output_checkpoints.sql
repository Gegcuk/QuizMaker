-- Generated questions must be durable before the asynchronous finalization
-- handoff. The payload contains generated question snapshots only; source
-- document content is neither copied nor hashed.
SET @quiz_generation_jobs_exists := (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'quiz_generation_jobs'
);

SET @output_checkpoints_exist := (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'quiz_generation_output_checkpoints'
);

SET @create_output_checkpoints_sql := IF(
    @quiz_generation_jobs_exists = 1 AND @output_checkpoints_exist = 0,
    'CREATE TABLE quiz_generation_output_checkpoints (
        job_id BINARY(16) NOT NULL,
        schema_version SMALLINT NOT NULL,
        payload MEDIUMTEXT NOT NULL,
        question_count INT NOT NULL,
        created_at TIMESTAMP(6) NOT NULL,
        PRIMARY KEY (job_id),
        CONSTRAINT fk_qgoc_job FOREIGN KEY (job_id)
            REFERENCES quiz_generation_jobs(id) ON DELETE CASCADE,
        CONSTRAINT chk_qgoc_schema_version CHECK (schema_version > 0),
        CONSTRAINT chk_qgoc_question_count CHECK (question_count > 0),
        INDEX idx_qgoc_created_job (created_at, job_id)
    ) ENGINE=InnoDB',
    'SELECT 1'
);

PREPARE create_output_checkpoints_statement FROM @create_output_checkpoints_sql;
EXECUTE create_output_checkpoints_statement;
DEALLOCATE PREPARE create_output_checkpoints_statement;
