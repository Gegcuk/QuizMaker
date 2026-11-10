-- Prerequisite: stop and drain EVERY legacy writer, including background jobs.
-- Keep that external pause in place on failure. MySQL DDL is not transactional.
-- These drops permit an explicitly reviewed repair/retry of a failed V108 in
-- the same paused window; application startup never performs Flyway repair.
DROP TRIGGER IF EXISTS legacy_answer_membership_insert;
DROP TRIGGER IF EXISTS legacy_answer_membership_delete;
DROP TRIGGER IF EXISTS legacy_answer_membership_update;
DROP PROCEDURE IF EXISTS reconcile_legacy_answer_projection;
DROP PROCEDURE IF EXISTS change_legacy_answer_projection;
DROP FUNCTION IF EXISTS legacy_answer_projection_fact;

DELIMITER $$
CREATE FUNCTION legacy_answer_projection_fact(
    question_id BINARY(16), correct BOOLEAN, stored_score DOUBLE, answered_at TIMESTAMP(6)
) RETURNS JSON
DETERMINISTIC NO SQL SQL SECURITY INVOKER
BEGIN
    IF question_id IS NULL OR correct IS NULL OR answered_at IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Accepted answer projection is unavailable';
    END IF;
    RETURN JSON_OBJECT(
        'questionId', BIN_TO_UUID(question_id),
        'isCorrect', JSON_EXTRACT(IF(correct, 'true', 'false'), '$'),
        'score', stored_score,
        'answeredAtEpochSeconds', UNIX_TIMESTAMP(answered_at));
END$$

-- Only the parent is read/locked. Validate the previous fact before changing it,
-- so a discrepancy is not silently overwritten by a later answer update/delete.
CREATE PROCEDURE change_legacy_answer_projection(
    parent_id BINARY(16), old_id BINARY(16), expected_old JSON,
    new_id BINARY(16), new_fact JSON
)
SQL SECURITY INVOKER
BEGIN
    DECLARE projection JSON DEFAULT NULL;
    DECLARE old_path VARCHAR(60);
    DECLARE new_path VARCHAR(60);
    SELECT accepted_answer_projection INTO projection FROM attempts WHERE id = parent_id FOR UPDATE;
    IF NOT COALESCE(JSON_TYPE(projection) = 'OBJECT'
            AND JSON_LENGTH(projection) = 2
            AND JSON_TYPE(JSON_EXTRACT(projection, '$.version')) = 'INTEGER'
            AND JSON_EXTRACT(projection, '$.version') = 1
            AND JSON_TYPE(JSON_EXTRACT(projection, '$.answers')) = 'OBJECT', FALSE) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Accepted answer projection is unavailable';
    END IF;
    IF old_id IS NOT NULL THEN
        SET old_path = CONCAT('$.answers."', BIN_TO_UUID(old_id), '"');
        IF NOT COALESCE(JSON_CONTAINS_PATH(projection, 'one', old_path) = 1
                AND (JSON_EXTRACT(projection, old_path) <=> expected_old), FALSE) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Accepted answer projection is unavailable';
        END IF;
        SET projection = JSON_REMOVE(projection, old_path);
    END IF;
    IF new_id IS NOT NULL THEN
        SET new_path = CONCAT('$.answers."', BIN_TO_UUID(new_id), '"');
        IF JSON_CONTAINS_PATH(projection, 'one', new_path) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Accepted answer projection is unavailable';
        END IF;
        SET projection = JSON_SET(projection, new_path, new_fact);
    END IF;
    UPDATE attempts SET accepted_answer_projection = projection WHERE id = parent_id;
END$$

CREATE TRIGGER legacy_answer_membership_insert AFTER INSERT ON answers
FOR EACH ROW
BEGIN
    CALL change_legacy_answer_projection(NEW.attempt_id, NULL, NULL, NEW.id,
        legacy_answer_projection_fact(NEW.question_id, NEW.is_correct, NEW.score, NEW.answered_at));
END$$

CREATE TRIGGER legacy_answer_membership_delete AFTER DELETE ON answers
FOR EACH ROW
BEGIN
    CALL change_legacy_answer_projection(OLD.attempt_id, OLD.id,
        legacy_answer_projection_fact(OLD.question_id, OLD.is_correct, OLD.score, OLD.answered_at), NULL, NULL);
END$$

CREATE TRIGGER legacy_answer_membership_update AFTER UPDATE ON answers
FOR EACH ROW
BEGIN
    DECLARE old_fact JSON;
    DECLARE new_fact JSON;
    IF NOT (OLD.id <=> NEW.id) OR NOT (OLD.attempt_id <=> NEW.attempt_id)
            OR NOT (OLD.question_id <=> NEW.question_id) OR NOT (OLD.is_correct <=> NEW.is_correct)
            OR NOT (OLD.score <=> NEW.score) OR NOT (OLD.answered_at <=> NEW.answered_at) THEN
        SET old_fact = legacy_answer_projection_fact(OLD.question_id, OLD.is_correct, OLD.score, OLD.answered_at);
        SET new_fact = legacy_answer_projection_fact(NEW.question_id, NEW.is_correct, NEW.score, NEW.answered_at);
        IF OLD.attempt_id = NEW.attempt_id THEN
            CALL change_legacy_answer_projection(OLD.attempt_id, OLD.id, old_fact, NEW.id, new_fact);
        ELSEIF OLD.attempt_id < NEW.attempt_id THEN
            CALL change_legacy_answer_projection(OLD.attempt_id, OLD.id, old_fact, NULL, NULL);
            CALL change_legacy_answer_projection(NEW.attempt_id, NULL, NULL, NEW.id, new_fact);
        ELSE
            CALL change_legacy_answer_projection(NEW.attempt_id, NULL, NULL, NEW.id, new_fact);
            CALL change_legacy_answer_projection(OLD.attempt_id, OLD.id, old_fact, NULL, NULL);
        END IF;
    END IF;
END$$

-- Rebuild from stored facts, never regrade. A separate second pass compares the
-- entire projection with the source. Commit per parent to bound transaction size;
-- a partial failure remains visibly uninitialized and must not reopen writers.
CREATE PROCEDURE reconcile_legacy_answer_projection(rebuild BOOLEAN)
SQL SECURITY INVOKER
BEGIN
    DECLARE previous_id BINARY(16) DEFAULT NULL;
    DECLARE current_id BINARY(16);
    DECLARE actual_projection JSON;
    DECLARE expected_projection JSON;
    DECLARE facts JSON;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;
    parent_loop: LOOP
        SET TRANSACTION ISOLATION LEVEL READ COMMITTED;
        START TRANSACTION;
        SELECT MIN(id) INTO current_id FROM attempts WHERE previous_id IS NULL OR id > previous_id;
        IF current_id IS NULL THEN
            COMMIT;
            LEAVE parent_loop;
        END IF;
        SELECT accepted_answer_projection INTO actual_projection FROM attempts WHERE id = current_id FOR UPDATE;
        SELECT JSON_OBJECTAGG(BIN_TO_UUID(id),
            legacy_answer_projection_fact(question_id, is_correct, score, answered_at)) INTO facts
            FROM answers WHERE attempt_id = current_id;
        SET expected_projection = JSON_OBJECT('version', 1, 'answers', COALESCE(facts, JSON_OBJECT()));
        IF rebuild THEN
            UPDATE attempts SET accepted_answer_projection = expected_projection WHERE id = current_id;
        ELSEIF NOT COALESCE(JSON_TYPE(actual_projection) = 'OBJECT'
                AND JSON_TYPE(JSON_EXTRACT(actual_projection, '$.version')) = 'INTEGER'
                AND (actual_projection <=> expected_projection), FALSE) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Legacy answer projection verification failed';
        END IF;
        COMMIT;
        SET previous_id = current_id;
    END LOOP;
END$$
DELIMITER ;

CALL reconcile_legacy_answer_projection(TRUE);
CALL reconcile_legacy_answer_projection(FALSE);
DROP PROCEDURE reconcile_legacy_answer_projection;

-- Readiness is established only after BOTH passes. New parents now get a valid
-- empty projection from the DB; Hibernate must never insert/update this column.
ALTER TABLE attempts MODIFY COLUMN accepted_answer_projection JSON NOT NULL
    DEFAULT (JSON_OBJECT('version', 1, 'answers', JSON_OBJECT()));
