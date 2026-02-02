-- Deduplicate entries before enforcing uniqueness on (user_id, question_id).
DELETE e1
FROM spaced_repetition_entry e1
JOIN spaced_repetition_entry e2
  ON e1.user_id = e2.user_id
 AND e1.question_id = e2.question_id
 AND (
     COALESCE(e1.last_reviewed_at, '1970-01-01 00:00:00') < COALESCE(e2.last_reviewed_at, '1970-01-01 00:00:00')
  OR (
        COALESCE(e1.last_reviewed_at, '1970-01-01 00:00:00') = COALESCE(e2.last_reviewed_at, '1970-01-01 00:00:00')
    AND e1.updated_at < e2.updated_at
     )
  OR (
        COALESCE(e1.last_reviewed_at, '1970-01-01 00:00:00') = COALESCE(e2.last_reviewed_at, '1970-01-01 00:00:00')
    AND e1.updated_at = e2.updated_at
    AND e1.id > e2.id
     )
 );

-- Add unique constraint if a matching unique index does not already exist.
SET @existing_unique := (
    SELECT INDEX_NAME
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'spaced_repetition_entry'
      AND NON_UNIQUE = 0
    GROUP BY INDEX_NAME
    HAVING GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) IN ('user_id,question_id', 'question_id,user_id')
    LIMIT 1
);

SET @sql := IF(
    @existing_unique IS NULL,
    'ALTER TABLE spaced_repetition_entry ADD CONSTRAINT uq_spaced_repetition_user_question UNIQUE (user_id, question_id);',
    'SELECT 1;'
);

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
