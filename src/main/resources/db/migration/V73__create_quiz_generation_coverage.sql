-- Persist only bounded reconciliation facts. Generated questions remain in the
-- versioned output checkpoint; source documents are neither copied nor hashed.
SET @quiz_generation_jobs_exists := (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'quiz_generation_jobs'
);

SET @generation_coverage_exists := (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'quiz_generation_coverage'
);

SET @create_generation_coverage_sql := IF(
    @quiz_generation_jobs_exists = 1 AND @generation_coverage_exists = 0,
    'CREATE TABLE quiz_generation_coverage (
        job_id BINARY(16) NOT NULL,
        outcome VARCHAR(24) NOT NULL,
        threshold_percent INT NOT NULL,
        requested_count INT NOT NULL,
        accepted_count INT NOT NULL,
        missing_count INT NOT NULL,
        discarded_count INT NOT NULL,
        created_at TIMESTAMP(6) NOT NULL,
        PRIMARY KEY (job_id),
        CONSTRAINT fk_qgc_job FOREIGN KEY (job_id)
            REFERENCES quiz_generation_jobs(id) ON DELETE CASCADE,
        CONSTRAINT chk_qgc_outcome CHECK (outcome IN (''COMPLETE'', ''PARTIAL'', ''FAILED_THRESHOLD'')),
        CONSTRAINT chk_qgc_threshold CHECK (threshold_percent BETWEEN 0 AND 100),
        CONSTRAINT chk_qgc_requested CHECK (requested_count > 0),
        CONSTRAINT chk_qgc_accepted CHECK (accepted_count BETWEEN 0 AND requested_count),
        CONSTRAINT chk_qgc_missing CHECK (missing_count = requested_count - accepted_count),
        CONSTRAINT chk_qgc_discarded CHECK (discarded_count >= 0)
    ) ENGINE=InnoDB',
    'SELECT 1'
);

PREPARE create_generation_coverage_statement FROM @create_generation_coverage_sql;
EXECUTE create_generation_coverage_statement;
DEALLOCATE PREPARE create_generation_coverage_statement;

SET @generation_type_coverage_exists := (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'quiz_generation_type_coverage'
);

SET @create_generation_type_coverage_sql := IF(
    @quiz_generation_jobs_exists = 1
        AND (SELECT COUNT(*) FROM information_schema.tables
             WHERE table_schema = DATABASE() AND table_name = 'quiz_generation_coverage') = 1
        AND @generation_type_coverage_exists = 0,
    'CREATE TABLE quiz_generation_type_coverage (
        job_id BINARY(16) NOT NULL,
        question_type VARCHAR(32) NOT NULL,
        requested_count INT NOT NULL,
        accepted_count INT NOT NULL,
        missing_count INT NOT NULL,
        PRIMARY KEY (job_id, question_type),
        CONSTRAINT fk_qgtc_coverage FOREIGN KEY (job_id)
            REFERENCES quiz_generation_coverage(job_id) ON DELETE CASCADE,
        CONSTRAINT chk_qgtc_requested CHECK (requested_count > 0),
        CONSTRAINT chk_qgtc_accepted CHECK (accepted_count BETWEEN 0 AND requested_count),
        CONSTRAINT chk_qgtc_missing CHECK (missing_count = requested_count - accepted_count)
    ) ENGINE=InnoDB',
    'SELECT 1'
);

PREPARE create_generation_type_coverage_statement FROM @create_generation_type_coverage_sql;
EXECUTE create_generation_type_coverage_statement;
DEALLOCATE PREPARE create_generation_type_coverage_statement;
