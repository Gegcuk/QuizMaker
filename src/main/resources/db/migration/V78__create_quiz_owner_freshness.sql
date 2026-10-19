-- Missing metadata means already seen, including every pre-feature quiz.
-- Only the new application writes rows while committing successful AI output.
CREATE TABLE quiz_owner_freshness (
    quiz_id BINARY(16) NOT NULL,
    is_new BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (quiz_id),
    CONSTRAINT chk_quiz_owner_freshness_value CHECK (is_new IN (0, 1)),
    CONSTRAINT fk_quiz_owner_freshness_quiz FOREIGN KEY (quiz_id)
        REFERENCES quizzes(quiz_id) ON DELETE CASCADE
) ENGINE=InnoDB;
