-- Replay reads the commitment to the immutable graph on the per-revision publication key.
ALTER TABLE quiz_scenario_publication_keys
    ADD COLUMN replay_sha256 BINARY(32) NULL,
    ADD CONSTRAINT chk_scenario_replay_published CHECK (replay_sha256 IS NULL OR published_revision_id IS NOT NULL);

CREATE TRIGGER scenario_publication_guard_replay BEFORE UPDATE ON quiz_scenario_publication_keys
FOR EACH ROW
BEGIN
    IF OLD.quiz_revision_id <> NEW.quiz_revision_id
        OR (OLD.published_revision_id IS NOT NULL AND NOT (OLD.published_revision_id <=> NEW.published_revision_id))
        OR (OLD.replay_sha256 IS NOT NULL AND NOT (OLD.replay_sha256 <=> NEW.replay_sha256)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Scenario publication evidence is immutable';
    END IF;
END;

CREATE TRIGGER scenario_publication_guard_delete BEFORE DELETE ON quiz_scenario_publication_keys
FOR EACH ROW
BEGIN
    IF OLD.published_revision_id IS NOT NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Scenario publication evidence is immutable';
    END IF;
END;

-- Like the existing option guards, protect the question once it is part of a scenario.
-- Storage equality is bytewise, including on columns with case-insensitive collations.
-- No-op upserts used by concurrent publishers skip the lookup entirely.
CREATE TRIGGER scenario_question_guard_content BEFORE UPDATE ON question_revisions
FOR EACH ROW
BEGIN
    IF NOT (
        OLD.id <=> NEW.id AND OLD.question_id <=> NEW.question_id AND OLD.schema_version <=> NEW.schema_version
        AND CAST(OLD.question_type AS BINARY) <=> CAST(NEW.question_type AS BINARY)
        AND CAST(OLD.difficulty AS BINARY) <=> CAST(NEW.difficulty AS BINARY)
        AND CAST(OLD.question_text AS BINARY) <=> CAST(NEW.question_text AS BINARY)
        AND CAST(OLD.content AS BINARY) <=> CAST(NEW.content AS BINARY)
        AND CAST(OLD.hint AS BINARY) <=> CAST(NEW.hint AS BINARY)
        AND CAST(OLD.explanation AS BINARY) <=> CAST(NEW.explanation AS BINARY)
        AND CAST(OLD.attachment_url AS BINARY) <=> CAST(NEW.attachment_url AS BINARY)
        AND OLD.attachment_asset_id <=> NEW.attachment_asset_id AND OLD.created_at <=> NEW.created_at
        AND OLD.creating_scenario_revision_id <=> NEW.creating_scenario_revision_id
    ) THEN
        IF EXISTS (SELECT 1 FROM quiz_scenario_question_keys WHERE question_revision_id=OLD.id
            AND option_count IS NOT NULL FOR SHARE) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Scenario question content is immutable';
        END IF;
    END IF;
END;

-- V131's initial backfill ran before the tracking triggers were installed. An older
-- publisher can commit a question and its options during that interval. After the
-- trigger DDL, new publications track their options in the same transaction.
-- DDL is already committed. Give this repair its own transaction on the Flyway connection
-- so the source scan is a consistent read and only rows being repaired are locked.
COMMIT;
SET TRANSACTION ISOLATION LEVEL READ COMMITTED;
START TRANSACTION;

INSERT INTO quiz_scenario_question_keys (question_revision_id, stored_option_count)
SELECT counts.id, counts.option_total
FROM (
    SELECT q.id, COUNT(o.id) AS option_total
    FROM question_revisions q
    LEFT JOIN quiz_scenario_question_keys k ON k.question_revision_id=q.id
    LEFT JOIN question_option_revisions o ON o.question_revision_id=q.id
    WHERE k.option_count IS NULL
    GROUP BY q.id, k.question_revision_id, k.stored_option_count
    HAVING k.question_revision_id IS NULL OR COUNT(o.id) <> k.stored_option_count
) counts
ON DUPLICATE KEY UPDATE stored_option_count=IF(option_count IS NULL, counts.option_total, stored_option_count);
COMMIT;
