ALTER TABLE quizzes
    ADD COLUMN import_content_hash VARCHAR(64) NULL;

CREATE INDEX idx_quiz_import_content_hash_creator
    ON quizzes(creator_id, import_content_hash);
