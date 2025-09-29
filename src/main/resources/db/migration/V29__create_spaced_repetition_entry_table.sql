-- Create spaced_repetition_entry table for spaced repetition algorithm

CREATE TABLE IF NOT EXISTS spaced_repetition_entry (
    id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    question_id BINARY(16) NOT NULL,
    next_review_at TIMESTAMP NOT NULL,
    interval_days INT NOT NULL,
    repetition_count INT NOT NULL,
    ease_factor DOUBLE NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_spaced_repetition_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    CONSTRAINT fk_spaced_repetition_question FOREIGN KEY (question_id) REFERENCES questions(id),
    CONSTRAINT uq_spaced_repetition_user_question UNIQUE (user_id, question_id)
) ENGINE=InnoDB;

-- Create indexes
CREATE INDEX idx_spaced_repetition_user_id ON spaced_repetition_entry(user_id);
CREATE INDEX idx_spaced_repetition_next_review_at ON spaced_repetition_entry(next_review_at);
CREATE INDEX idx_spaced_repetition_question_id ON spaced_repetition_entry(question_id);
