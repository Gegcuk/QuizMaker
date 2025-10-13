-- Create quiz_analytics_snapshot table for caching quiz-level analytics

CREATE TABLE quiz_analytics_snapshot (
    quiz_id BINARY(16) NOT NULL,
    attempts_count BIGINT NOT NULL DEFAULT 0,
    average_score DOUBLE NOT NULL DEFAULT 0.0,
    best_score DOUBLE NOT NULL DEFAULT 0.0,
    worst_score DOUBLE NOT NULL DEFAULT 0.0,
    pass_rate DOUBLE NOT NULL DEFAULT 0.0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (quiz_id),
    FOREIGN KEY (quiz_id) REFERENCES quizzes(quiz_id) ON DELETE CASCADE
);

-- Index for faster queries by updated_at (useful for identifying stale snapshots)
CREATE INDEX idx_quiz_analytics_snapshot_updated_at ON quiz_analytics_snapshot(updated_at);

-- Note: quiz_id is the primary key, ensuring one snapshot per quiz

