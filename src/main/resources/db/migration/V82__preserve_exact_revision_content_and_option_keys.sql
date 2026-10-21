-- MySQL's binary JSON can round numbers outside its native numeric range.
-- Convert only the representation already stored; lost precision cannot be recovered here.
-- Validation stays at the immutable domain boundary: MySQL JSON functions also reject
-- valid JSON numbers beyond the DOUBLE range, even when used only in a CHECK.
ALTER TABLE question_revisions
    DROP CHECK chk_question_revision_content,
    MODIFY COLUMN content LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

-- Unlike utf8mb4_bin (PAD SPACE), this binary NO PAD collation preserves trailing spaces.
-- The existing unique index continues to reject exact duplicates, with case significant.
ALTER TABLE question_option_revisions
    MODIFY COLUMN source_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL;
