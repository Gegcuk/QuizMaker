-- Keep V113 and its ID index compatible. This metadata projection has no answer content
-- and changes only through source-row triggers or explicit source reconciliation.
ALTER TABLE attempt_trace_stream ADD COLUMN response_reference_index JSON NULL;
ALTER TABLE attempt_trace_stream ADD CONSTRAINT chk_trace_reference_index CHECK (
    response_reference_index IS NULL OR COALESCE(
        JSON_TYPE(response_reference_index)='OBJECT' AND JSON_LENGTH(response_reference_index)=2
        AND JSON_TYPE(JSON_EXTRACT(response_reference_index,'$.version'))='INTEGER'
        AND JSON_EXTRACT(response_reference_index,'$.version')=1
        AND JSON_TYPE(JSON_EXTRACT(response_reference_index,'$.responses'))='OBJECT',FALSE));

DELIMITER $$
CREATE FUNCTION accepted_response_reference_fact(schema_v SMALLINT, form BINARY(16), item BINARY(16),
    question BINARY(16), contract VARCHAR(128), contract_v INT, command BINARY(16), item_position INT)
RETURNS JSON DETERMINISTIC NO SQL SQL SECURITY INVOKER
RETURN JSON_OBJECT('schemaVersion',schema_v,'formId',BIN_TO_UUID(form),'formItemId',BIN_TO_UUID(item),
    'questionRevisionId',BIN_TO_UUID(question),'responseContractId',contract,'responseContractVersion',contract_v,
    'commandId',BIN_TO_UUID(command),'position',item_position)$$

CREATE PROCEDURE change_accepted_reference_index(target_attempt BINARY(16), response BINARY(16), fact JSON)
SQL SECURITY INVOKER
BEGIN
    DECLARE current_index JSON;
    DECLARE missing BOOLEAN DEFAULT FALSE;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET missing=TRUE;
    SELECT response_reference_index INTO current_index FROM attempt_trace_stream
        WHERE attempt_id=target_attempt FOR UPDATE;
    IF missing THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Accepted reference stream unavailable'; END IF;
    -- NULL streams are still on the old compatible reader until migration seeds them.
    -- Locking their parent also coordinates source metadata updates with the seed transaction.
    IF current_index IS NOT NULL THEN
        IF NOT COALESCE(JSON_EXTRACT(current_index,'$.version')=1
            AND JSON_TYPE(JSON_EXTRACT(current_index,'$.responses'))='OBJECT',FALSE) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Accepted reference index unavailable';
        END IF;
        UPDATE attempt_trace_stream SET response_reference_index=CASE WHEN fact IS NULL
            THEN JSON_REMOVE(current_index,CONCAT('$.responses."',BIN_TO_UUID(response),'"'))
            ELSE JSON_SET(current_index,CONCAT('$.responses."',BIN_TO_UUID(response),'"'),fact) END
            WHERE attempt_id=target_attempt;
    END IF;
END$$

CREATE TRIGGER accepted_reference_insert AFTER INSERT ON attempt_accepted_response FOR EACH ROW
FOLLOWS accepted_response_index_insert
BEGIN
    DECLARE item_position INT;
    SELECT position INTO item_position FROM attempt_form_item
        WHERE form_id=NEW.form_id AND id=NEW.form_item_id FOR SHARE;
    CALL change_accepted_reference_index(NEW.attempt_id,NEW.id,
        accepted_response_reference_fact(NEW.schema_version,NEW.form_id,NEW.form_item_id,NEW.question_revision_id,
            NEW.response_contract_id,NEW.response_contract_version,NEW.command_id,item_position));
END$$

CREATE TRIGGER accepted_reference_delete AFTER DELETE ON attempt_accepted_response FOR EACH ROW
FOLLOWS accepted_response_index_delete
BEGIN
    CALL change_accepted_reference_index(OLD.attempt_id,OLD.id,NULL);
END$$

CREATE TRIGGER accepted_reference_update AFTER UPDATE ON attempt_accepted_response FOR EACH ROW
FOLLOWS accepted_response_index_update
BEGIN
    DECLARE item_position INT;
    DECLARE fact JSON;
    SELECT position INTO item_position FROM attempt_form_item
        WHERE form_id=NEW.form_id AND id=NEW.form_item_id FOR SHARE;
    SET fact=accepted_response_reference_fact(NEW.schema_version,NEW.form_id,NEW.form_item_id,NEW.question_revision_id,
        NEW.response_contract_id,NEW.response_contract_version,NEW.command_id,item_position);
    IF OLD.attempt_id=NEW.attempt_id AND OLD.id=NEW.id THEN
        CALL change_accepted_reference_index(NEW.attempt_id,NEW.id,fact);
    ELSEIF OLD.attempt_id<=NEW.attempt_id THEN
        CALL change_accepted_reference_index(OLD.attempt_id,OLD.id,NULL);
        CALL change_accepted_reference_index(NEW.attempt_id,NEW.id,fact);
    ELSE
        CALL change_accepted_reference_index(NEW.attempt_id,NEW.id,fact);
        CALL change_accepted_reference_index(OLD.attempt_id,OLD.id,NULL);
    END IF;
END$$

-- Exact source comparison is an explicit maintenance/migration boundary, not a request-time
-- locking scan. The parent lock precedes a current nonlocking RC source read per stream.
CREATE PROCEDURE reconcile_accepted_reference_index(rebuild BOOLEAN)
SQL SECURITY INVOKER
BEGIN
    DECLARE previous_attempt BINARY(16) DEFAULT NULL;
    DECLARE current_attempt BINARY(16);
    DECLARE current_index JSON;
    DECLARE identity_index JSON;
    DECLARE source_ids JSON;
    DECLARE source_references JSON;
    DECLARE expected_index JSON;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET current_attempt=NULL;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
    reconcile_loop: LOOP
        SET TRANSACTION ISOLATION LEVEL READ COMMITTED;
        START TRANSACTION;
        SET current_attempt=NULL;
        SELECT attempt_id,response_index,response_reference_index INTO current_attempt,identity_index,current_index
            FROM attempt_trace_stream FORCE INDEX(PRIMARY)
            WHERE previous_attempt IS NULL OR attempt_id>previous_attempt ORDER BY attempt_id LIMIT 1 FOR UPDATE;
        IF current_attempt IS NULL THEN COMMIT; LEAVE reconcile_loop; END IF;
        SELECT JSON_OBJECTAGG(BIN_TO_UUID(r.id),1),
            JSON_OBJECTAGG(BIN_TO_UUID(r.id),accepted_response_reference_fact(r.schema_version,r.form_id,r.form_item_id,
                r.question_revision_id,r.response_contract_id,r.response_contract_version,r.command_id,f.position))
            INTO source_ids,source_references
            FROM attempt_accepted_response r JOIN attempt_form_item f ON f.form_id=r.form_id AND f.id=r.form_item_id
            WHERE r.attempt_id=current_attempt;
        IF NOT COALESCE(JSON_EXTRACT(identity_index,'$.version')=1
            AND (JSON_EXTRACT(identity_index,'$.ids') <=> COALESCE(source_ids,JSON_OBJECT())),FALSE) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Accepted reference membership mismatch';
        END IF;
        SET expected_index=JSON_OBJECT('version',1,'responses',COALESCE(source_references,JSON_OBJECT()));
        IF rebuild THEN
            UPDATE attempt_trace_stream SET response_reference_index=expected_index WHERE attempt_id=current_attempt;
        ELSEIF NOT (current_index <=> expected_index) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Accepted reference index mismatch';
        END IF;
        SET previous_attempt=current_attempt;
        COMMIT;
    END LOOP;
END$$
DELIMITER ;

-- New initialized streams are enabled only once all source triggers exist.
ALTER TABLE attempt_trace_stream ALTER COLUMN response_reference_index
    SET DEFAULT (JSON_OBJECT('version',1,'responses',JSON_OBJECT()));
CALL reconcile_accepted_reference_index(TRUE);
CALL reconcile_accepted_reference_index(FALSE);
ALTER TABLE attempt_trace_stream MODIFY COLUMN response_reference_index JSON NOT NULL
    DEFAULT (JSON_OBJECT('version',1,'responses',JSON_OBJECT()));
