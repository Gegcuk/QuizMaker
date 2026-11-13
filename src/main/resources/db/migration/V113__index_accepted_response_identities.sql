-- A current-read anchor for accepted IDs, not an answer/result authority. Readers still
-- validate the actual accepted rows by PK. Keep updates in the source writer's transaction.
ALTER TABLE attempt_trace_stream ADD COLUMN response_index JSON NULL;
ALTER TABLE attempt_trace_stream ADD CONSTRAINT chk_trace_response_index CHECK (
    response_index IS NULL OR (JSON_TYPE(response_index)='OBJECT' AND JSON_LENGTH(response_index)=2
        AND JSON_TYPE(JSON_EXTRACT(response_index,'$.version'))='INTEGER'
        AND JSON_EXTRACT(response_index,'$.version')=1
        AND JSON_TYPE(JSON_EXTRACT(response_index,'$.ids'))='OBJECT'));

DELIMITER $$
CREATE PROCEDURE change_accepted_response_index(current_attempt BINARY(16), old_response BINARY(16), new_response BINARY(16))
SQL SECURITY INVOKER
BEGIN
    DECLARE old_path VARCHAR(48);
    DECLARE new_path VARCHAR(48);
    SET old_path=CONCAT('$.ids."',BIN_TO_UUID(old_response),'"');
    SET new_path=CONCAT('$.ids."',BIN_TO_UUID(new_response),'"');
    UPDATE attempt_trace_stream
        SET response_index=CASE WHEN new_response IS NULL THEN JSON_REMOVE(response_index,old_path)
            ELSE JSON_SET(CASE WHEN old_response IS NULL THEN response_index
                ELSE JSON_REMOVE(response_index,old_path) END,new_path,1) END
        WHERE attempt_id=current_attempt AND JSON_EXTRACT(response_index,'$.version')=1
            AND (old_response IS NULL OR JSON_EXTRACT(response_index,old_path)=1)
            AND (new_response IS NULL OR JSON_CONTAINS_PATH(response_index,'one',new_path)=0);
    IF ROW_COUNT()<>1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Accepted response index unavailable';
    END IF;
END$$

CREATE TRIGGER accepted_response_index_insert AFTER INSERT ON attempt_accepted_response FOR EACH ROW
BEGIN
    CALL change_accepted_response_index(NEW.attempt_id,NULL,NEW.id);
END$$

CREATE TRIGGER accepted_response_index_delete AFTER DELETE ON attempt_accepted_response FOR EACH ROW
BEGIN
    CALL change_accepted_response_index(OLD.attempt_id,OLD.id,NULL);
END$$

CREATE TRIGGER accepted_response_index_update AFTER UPDATE ON attempt_accepted_response FOR EACH ROW
BEGIN
    IF OLD.id<>NEW.id OR OLD.attempt_id<>NEW.attempt_id THEN
        IF OLD.attempt_id=NEW.attempt_id THEN
            CALL change_accepted_response_index(NEW.attempt_id,OLD.id,NEW.id);
        ELSEIF OLD.attempt_id<NEW.attempt_id THEN
            CALL change_accepted_response_index(OLD.attempt_id,OLD.id,NULL);
            CALL change_accepted_response_index(NEW.attempt_id,NULL,NEW.id);
        ELSE
            CALL change_accepted_response_index(NEW.attempt_id,NULL,NEW.id);
            CALL change_accepted_response_index(OLD.attempt_id,OLD.id,NULL);
        END IF;
    END IF;
END$$

-- Seed one existing stream at a time. Lock the parent before reading its source;
-- RC here belongs only to this migration's own transactions, never application callers.
CREATE PROCEDURE initialize_accepted_response_indexes()
SQL SECURITY INVOKER
BEGIN
    DECLARE previous_attempt BINARY(16) DEFAULT NULL;
    DECLARE current_attempt BINARY(16);
    DECLARE current_index JSON;
    DECLARE current_ids JSON;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET current_attempt=NULL;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;
    seed_loop: LOOP
        SET TRANSACTION ISOLATION LEVEL READ COMMITTED;
        START TRANSACTION;
        SET current_attempt=NULL;
        SELECT attempt_id,response_index INTO current_attempt,current_index
            FROM attempt_trace_stream FORCE INDEX (PRIMARY)
            WHERE previous_attempt IS NULL OR attempt_id>previous_attempt
            ORDER BY attempt_id LIMIT 1 FOR UPDATE;
        IF current_attempt IS NULL THEN
            COMMIT;
            LEAVE seed_loop;
        END IF;
        IF current_index IS NULL THEN
            SELECT JSON_OBJECTAGG(BIN_TO_UUID(id),1) INTO current_ids
                FROM attempt_accepted_response WHERE attempt_id=current_attempt;
            UPDATE attempt_trace_stream
                SET response_index=JSON_OBJECT('version',1,'ids',COALESCE(current_ids,JSON_OBJECT()))
                WHERE attempt_id=current_attempt AND response_index IS NULL;
        END IF;
        SET previous_attempt=current_attempt;
        COMMIT;
    END LOOP;
END$$
DELIMITER ;

-- Enable initialized new streams only AFTER every source trigger is installed.
-- Earlier streams remain NULL and fail closed for identity changes until seeded.
-- Seeding never replaces an initialized stream or its concurrent committed writes.
ALTER TABLE attempt_trace_stream ALTER COLUMN response_index
    SET DEFAULT (JSON_OBJECT('version',1,'ids',JSON_OBJECT()));
CALL initialize_accepted_response_indexes();
DROP PROCEDURE initialize_accepted_response_indexes;

ALTER TABLE attempt_trace_stream MODIFY COLUMN response_index JSON NOT NULL
    DEFAULT (JSON_OBJECT('version',1,'ids',JSON_OBJECT()));
