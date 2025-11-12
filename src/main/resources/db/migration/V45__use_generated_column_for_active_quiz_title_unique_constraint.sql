-- Ensure active quizzes keep unique titles per creator while soft deletes stay in the table

-- 1. Add generated column exposing the title only when quiz is not deleted
SET @column_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'quizzes'
      AND COLUMN_NAME = 'title_active'
);

SET @sql := IF(@column_exists = 0,
    'ALTER TABLE quizzes ADD COLUMN title_active VARCHAR(100) GENERATED ALWAYS AS (CASE WHEN is_deleted = 0 THEN title ELSE NULL END) STORED AFTER title',
    'SELECT ''Column title_active already exists, skipping'' AS message'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. Drop legacy unique constraint (creator_id, title) if it is present
SET @index_exists := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'quizzes'
      AND INDEX_NAME = 'uq_quiz_creator_title'
);

SET @sql := IF(@index_exists = 1,
    'ALTER TABLE quizzes DROP INDEX uq_quiz_creator_title',
    'SELECT ''Index uq_quiz_creator_title missing, skipping drop'' AS message'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. Add new unique index that ignores deleted quizzes (title_active is NULL when deleted)
SET @index_exists := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'quizzes'
      AND INDEX_NAME = 'uq_quiz_active_title'
);

SET @sql := IF(@index_exists = 0,
    'ALTER TABLE quizzes ADD UNIQUE INDEX uq_quiz_active_title (creator_id, title_active)',
    'SELECT ''Index uq_quiz_active_title already exists, skipping create'' AS message'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
