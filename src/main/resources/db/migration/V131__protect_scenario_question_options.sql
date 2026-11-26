-- Every question gets current option cardinality. Retain any earlier seal so that a mismatch
-- left by an out-of-band write is detected instead of being accepted as a different snapshot.
ALTER TABLE question_revisions ADD COLUMN creating_scenario_revision_id BINARY(16) NULL;
ALTER TABLE quiz_scenario_question_keys ADD COLUMN stored_option_count INT NOT NULL DEFAULT 0;

INSERT INTO quiz_scenario_question_keys (question_revision_id, stored_option_count)
SELECT counts.id, counts.option_total
FROM (
    SELECT q.id, COUNT(o.id) AS option_total
    FROM question_revisions q LEFT JOIN question_option_revisions o ON o.question_revision_id=q.id
    GROUP BY q.id
) counts
ON DUPLICATE KEY UPDATE stored_option_count=counts.option_total;

ALTER TABLE quiz_scenario_question_keys
    ADD CONSTRAINT fk_scenario_question_key_revision FOREIGN KEY (question_revision_id)
        REFERENCES question_revisions(id) ON DELETE CASCADE,
    ADD CONSTRAINT chk_scenario_question_stored_option_count CHECK (stored_option_count >= 0);

CREATE TRIGGER scenario_question_track_insert AFTER INSERT ON question_revisions
FOR EACH ROW INSERT INTO quiz_scenario_question_keys (question_revision_id) VALUES (NEW.id);

CREATE TRIGGER scenario_question_guard_seal BEFORE UPDATE ON quiz_scenario_question_keys
FOR EACH ROW
BEGIN
    IF NEW.question_revision_id <> OLD.question_revision_id
        OR (OLD.option_count IS NOT NULL AND
            (NOT (NEW.option_count <=> OLD.option_count) OR NEW.stored_option_count <> OLD.stored_option_count))
        OR (OLD.option_count IS NULL AND NEW.option_count IS NOT NULL
            AND NEW.option_count <> NEW.stored_option_count) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid scenario question seal';
    END IF;
END;

CREATE TRIGGER scenario_question_guard_key_delete BEFORE DELETE ON quiz_scenario_question_keys
FOR EACH ROW
BEGIN
    IF OLD.option_count IS NOT NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Scenario question seal is immutable';
    END IF;
END;

CREATE TRIGGER scenario_question_option_insert BEFORE INSERT ON question_option_revisions
FOR EACH ROW
BEGIN
    UPDATE quiz_scenario_question_keys SET stored_option_count=stored_option_count+1
    WHERE question_revision_id=NEW.question_revision_id AND option_count IS NULL;
    IF ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Scenario question options are sealed or unavailable';
    END IF;
END;

CREATE TRIGGER scenario_question_option_delete BEFORE DELETE ON question_option_revisions
FOR EACH ROW
BEGIN
    UPDATE quiz_scenario_question_keys SET stored_option_count=stored_option_count-1
    WHERE question_revision_id=OLD.question_revision_id AND option_count IS NULL;
    IF ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Scenario question options are sealed or unavailable';
    END IF;
END;

CREATE TRIGGER scenario_question_option_update BEFORE UPDATE ON question_option_revisions
FOR EACH ROW
BEGIN
    IF OLD.question_revision_id = NEW.question_revision_id THEN
        IF NOT EXISTS (SELECT 1 FROM quiz_scenario_question_keys
            WHERE question_revision_id=OLD.question_revision_id AND option_count IS NULL FOR SHARE) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Scenario question options are sealed or unavailable';
        END IF;
    ELSE
        UPDATE quiz_scenario_question_keys SET stored_option_count=stored_option_count-1
        WHERE question_revision_id=OLD.question_revision_id AND option_count IS NULL;
        IF ROW_COUNT() <> 1 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Scenario question options are sealed or unavailable';
        END IF;
        UPDATE quiz_scenario_question_keys SET stored_option_count=stored_option_count+1
        WHERE question_revision_id=NEW.question_revision_id AND option_count IS NULL;
        IF ROW_COUNT() <> 1 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Scenario question options are sealed or unavailable';
        END IF;
    END IF;
END;
