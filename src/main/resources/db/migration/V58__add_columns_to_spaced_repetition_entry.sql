ALTER TABLE spaced_repetition_entry
    ADD COLUMN last_reviewed_at TIMESTAMP NULL,
    ADD COLUMN last_grade VARCHAR(10) NULL,
    ADD COLUMN reminder_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP on update CURRENT_TIMESTAMP;

CREATE INDEX idx_spaced_repetition_user_reminder_review 
    ON spaced_repetition_entry(user_id, reminder_enabled, next_review_at);

CREATE TABLE IF NOT EXISTS repetition_review_log(
    repetition_review_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    entry_id BINARY(16) NOT NULL,
    content_type ENUM('QUESTION', 'QUIZ', 'FLASHCARD', 'DECK') NOT NULL,
    content_id BINARY(16) NOT NULL,
    grade VARCHAR(10) NOT NULL,
    reviewed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    interval_days INT NOT NULL,
    ease_factor DOUBLE NOT NULL,
    repetition_count INT NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    source_id BINARY(16) NULL,
    UNIQUE (source_type, source_id),
    attempt_id BINARY(16) NULL,
    PRIMARY KEY (repetition_review_id),
    CONSTRAINT fk_repetition_review_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_repetition_review_repetition_entry FOREIGN KEY (entry_id) REFERENCES spaced_repetition_entry(id) ON DELETE CASCADE);

CREATE INDEX idx_repetition_review_log_user_reviewed ON repetition_review_log(user_id, reviewed_at DESC);
CREATE INDEX idx_repetition_review_log_entry_reviewed ON repetition_review_log(entry_id, reviewed_at DESC);