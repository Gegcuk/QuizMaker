-- Keep a fixed-size fingerprint of the ordered option set on the question metadata.
-- Scenario items reference that evidence without locking question or option rows to read it.
ALTER TABLE quiz_scenario_question_keys
    ADD COLUMN question_evidence BINARY(32) NULL,
    ADD COLUMN option_evidence BINARY(32) NULL,
    ADD COLUMN evidenced_option_count INT NOT NULL DEFAULT 0,
    ADD COLUMN last_option_position INT NOT NULL DEFAULT -1,
    ADD COLUMN evidence_ready BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN content_evidence BINARY(32) GENERATED ALWAYS AS (
        CASE WHEN evidence_ready AND evidenced_option_count=stored_option_count
            AND (option_count IS NULL OR option_count=stored_option_count)
        THEN UNHEX(SHA2(CONCAT(question_evidence, option_evidence), 256))
        END
    ) STORED,
    ADD UNIQUE KEY uq_scenario_question_evidence (content_evidence, question_revision_id);

-- Keep quoted tokens byte-for-byte and remove JSON whitespace at structural
-- boundaries. Do not parse numbers or join whitespace-separated literals.
CREATE FUNCTION scenario_compact_question_json(payload LONGTEXT CHARACTER SET utf8mb4)
RETURNS LONGTEXT CHARACTER SET utf8mb4 DETERMINISTIC NO SQL
BEGIN
    DECLARE slash CHAR(1) CHARACTER SET utf8mb4 DEFAULT CHAR(92 USING utf8mb4);
    DECLARE whitespace VARCHAR(8) CHARACTER SET utf8mb4 DEFAULT CONCAT('[', CHAR(32, 9, 10, 13 USING utf8mb4), ']+');
    RETURN REGEXP_REPLACE(payload,
        CONCAT('("(?:[^"', slash, slash, ']++|', slash, slash, '.)*+")|^', whitespace,
            '|([,:{', slash, '[])', whitespace, '|', whitespace, '(?=[,:}', slash, ']]|$)'),
        '$1$2', 1, 0, 'cn');
END;

-- Compare object members without relying on the native JSON numeric range.
-- Tag scalar strings and numbers before parsing so types and number spellings
-- remain distinct. Counting members detects keys discarded by the JSON parser.
CREATE FUNCTION scenario_ordered_question_json(payload LONGTEXT CHARACTER SET utf8mb4)
RETURNS LONGTEXT CHARACTER SET utf8mb4 DETERMINISTIC NO SQL
BEGIN
    DECLARE slash CHAR(1) CHARACTER SET utf8mb4 DEFAULT CHAR(92 USING utf8mb4);
    DECLARE quoted VARCHAR(80) CHARACTER SET utf8mb4 DEFAULT CONCAT('"(?:[^"', slash, slash, ']++|', slash, slash, '.)*+"');
    DECLARE tokens VARCHAR(160) CHARACTER SET utf8mb4 DEFAULT CONCAT(quoted, '|-?(?:0|[1-9][0-9]*)(?:[.][0-9]+)?(?:[eE][+-]?[0-9]+)?');
    DECLARE offset_chars INT DEFAULT 1;
    DECLARE token_start INT;
    DECLARE token_text LONGTEXT CHARACTER SET utf8mb4;
    DECLARE tagged LONGTEXT CHARACTER SET utf8mb4 DEFAULT '';
    DECLARE punctuation LONGTEXT CHARACTER SET utf8mb4;
    DECLARE member_count INT;
    DECLARE normalized LONGTEXT CHARACTER SET utf8mb4;
    -- LONGTEXT questions may exceed native JSON's size, key or nesting limits.
    DECLARE EXIT HANDLER FOR 3150,3151,3157 RETURN NULL;

    WHILE offset_chars<=CHAR_LENGTH(payload) DO
        SET token_start=REGEXP_INSTR(payload, tokens, offset_chars, 1, 0, 'cn');
        IF token_start=0 THEN
            SET tagged=CONCAT(tagged,SUBSTRING(payload,offset_chars));
            SET offset_chars=CHAR_LENGTH(payload)+1;
        ELSE
            SET token_text=REGEXP_SUBSTR(payload,tokens,token_start,1,'cn');
            SET tagged=CONCAT(tagged,SUBSTRING(payload,offset_chars,token_start-offset_chars));
            SET offset_chars=token_start+CHAR_LENGTH(token_text);
            IF LEFT(token_text,1)='"' THEN
                SET tagged=CONCAT(tagged,'"s',SUBSTRING(token_text,2));
            ELSEIF SUBSTRING(payload,offset_chars,1)=':' THEN
                RETURN NULL;
            ELSE
                SET tagged=CONCAT(tagged,'"n',token_text,'"');
            END IF;
        END IF;
    END WHILE;
    IF NOT JSON_VALID(tagged) THEN RETURN NULL; END IF;
    SET punctuation=REGEXP_REPLACE(tagged,quoted,'',1,0,'cn');
    SET member_count=CHAR_LENGTH(punctuation)-CHAR_LENGTH(REPLACE(punctuation,':',''));
    SET normalized=CAST(CAST(tagged AS JSON) AS CHAR CHARACTER SET utf8mb4);
    SET punctuation=REGEXP_REPLACE(normalized,quoted,'',1,0,'cn');
    IF member_count<>CHAR_LENGTH(punctuation)-CHAR_LENGTH(REPLACE(punctuation,':','')) THEN RETURN NULL; END IF;
    RETURN normalized;
END;

CREATE FUNCTION scenario_same_question_json(
    old_payload LONGTEXT CHARACTER SET utf8mb4, new_payload LONGTEXT CHARACTER SET utf8mb4
) RETURNS BOOLEAN DETERMINISTIC NO SQL
BEGIN
    SET old_payload=scenario_compact_question_json(old_payload),
        new_payload=scenario_compact_question_json(new_payload);
    IF CAST(old_payload AS BINARY)=CAST(new_payload AS BINARY) THEN RETURN TRUE; END IF;
    RETURN (CAST(scenario_ordered_question_json(old_payload) AS BINARY)
        = CAST(scenario_ordered_question_json(new_payload) AS BINARY)) IS TRUE;
END;

CREATE FUNCTION scenario_question_evidence(
    source_id BINARY(16), format_version SMALLINT, kind VARCHAR(50) CHARACTER SET utf8mb4,
    difficulty_name VARCHAR(20) CHARACTER SET utf8mb4, prompt VARCHAR(1000) CHARACTER SET utf8mb4,
    payload LONGTEXT CHARACTER SET utf8mb4, hint_text VARCHAR(500) CHARACTER SET utf8mb4,
    explanation_text VARCHAR(2000) CHARACTER SET utf8mb4, asset_url VARCHAR(2048) CHARACTER SET utf8mb4,
    asset_id BINARY(16), created DATETIME(6)
) RETURNS BINARY(32) DETERMINISTIC NO SQL
RETURN UNHEX(SHA2(CONCAT_WS(':', HEX(source_id), format_version,
    SHA2(kind, 256), SHA2(difficulty_name, 256), SHA2(prompt, 256), SHA2(TRIM(payload), 256),
    COALESCE(SHA2(hint_text, 256), '-'), COALESCE(SHA2(explanation_text, 256), '-'),
    COALESCE(SHA2(asset_url, 256), '-'), COALESCE(HEX(asset_id), '-'),
    DATE_FORMAT(created, '%Y-%m-%d %H:%i:%s.%f')
), 256));

-- Claim sorted candidates inside the caller's transaction. A duplicate belongs
-- to another writer and will be checked against current evidence on item insert.
CREATE PROCEDURE scenario_insert_questions(IN scenario_id BINARY(16), IN candidates JSON)
MODIFIES SQL DATA
BEGIN
    DECLARE candidate_index INT DEFAULT 0;
    DECLARE candidate JSON;
    DECLARE inserted_ids JSON DEFAULT (JSON_ARRAY());
    DECLARE inserted BOOLEAN;
    DECLARE CONTINUE HANDLER FOR 1062 SET inserted=FALSE;

    WHILE candidate_index<JSON_LENGTH(candidates) DO
        SET candidate=JSON_EXTRACT(candidates, CONCAT('$[',candidate_index,']')), inserted=TRUE;
        INSERT INTO question_revisions (id,question_id,schema_version,question_type,difficulty,
            question_text,content,hint,explanation,attachment_url,attachment_asset_id,created_at,creating_scenario_revision_id)
        SELECT UNHEX(revision_hex),UNHEX(source_hex),format_version,kind,difficulty_name,prompt,payload,
            hint_text,explanation_text,asset_url,UNHEX(asset_hex),created,scenario_id
        FROM JSON_TABLE(candidate, '$' COLUMNS (
            revision_hex CHAR(32) PATH '$[0]', source_hex CHAR(32) PATH '$[1]', format_version SMALLINT PATH '$[2]',
            kind VARCHAR(50) PATH '$[3]', difficulty_name VARCHAR(20) PATH '$[4]', prompt VARCHAR(1000) PATH '$[5]',
            payload LONGTEXT PATH '$[6]', hint_text VARCHAR(500) PATH '$[7]', explanation_text VARCHAR(2000) PATH '$[8]',
            asset_url VARCHAR(2048) PATH '$[9]', asset_hex CHAR(32) PATH '$[10]', created DATETIME(6) PATH '$[11]'
        )) AS fields;
        IF inserted THEN
            SET inserted_ids=JSON_ARRAY_APPEND(inserted_ids,'$',JSON_UNQUOTE(JSON_EXTRACT(candidate,'$[0]')));
        END IF;
        SET candidate_index=candidate_index+1;
    END WHILE;
    SELECT UNHEX(id_hex) AS id FROM JSON_TABLE(inserted_ids, '$[*]' COLUMNS (id_hex CHAR(32) PATH '$')) AS written;
END;

-- Hash one option as its hex ID, decimal position, and exact UTF-8 key.
CREATE FUNCTION scenario_option_evidence(
    option_id BINARY(16), position_number INT, exact_key VARCHAR(255) CHARACTER SET utf8mb4
) RETURNS BINARY(32) DETERMINISTIC NO SQL
RETURN UNHEX(SHA2(CONCAT(HEX(option_id), ':', position_number, ':', exact_key), 256));

-- The metadata lock serializes option changes for this question. Migration callers
-- use READ COMMITTED and take their consistent read after acquiring that lock.
-- Exceptional edits in an existing caller transaction need a current read.
CREATE PROCEDURE scenario_rebuild_option_evidence(IN revision_id BINARY(16), IN during_backfill BOOLEAN)
MODIFIES SQL DATA
rebuild: BEGIN
    DECLARE tracked BOOLEAN DEFAULT FALSE;
    DECLARE done BOOLEAN DEFAULT FALSE;
    DECLARE fingerprint BINARY(32) DEFAULT UNHEX(SHA2('', 256));
    DECLARE next_hash BINARY(32);
    DECLARE next_position INT;
    DECLARE last_position INT DEFAULT -1;
    DECLARE option_total INT DEFAULT 0;
    DECLARE current_options CURSOR FOR
        SELECT scenario_option_evidence(id, authored_position, source_key), authored_position
        FROM question_option_revisions WHERE question_revision_id=revision_id
        ORDER BY authored_position FOR SHARE;
    DECLARE committed_options CURSOR FOR
        SELECT scenario_option_evidence(id, authored_position, source_key), authored_position
        FROM question_option_revisions WHERE question_revision_id=revision_id
        ORDER BY authored_position;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done=TRUE;

    SELECT option_evidence IS NOT NULL INTO tracked FROM quiz_scenario_question_keys
    WHERE question_revision_id=revision_id FOR UPDATE;
    IF done OR (during_backfill AND tracked) OR (NOT during_backfill AND NOT tracked) THEN
        LEAVE rebuild;
    END IF;

    IF during_backfill THEN OPEN committed_options;
    ELSE OPEN current_options;
    END IF;
    collect: LOOP
        IF during_backfill THEN FETCH committed_options INTO next_hash, next_position;
        ELSE FETCH current_options INTO next_hash, next_position;
        END IF;
        IF done THEN LEAVE collect;
        END IF;
        SET fingerprint=UNHEX(SHA2(CONCAT(fingerprint, next_hash), 256)),
            option_total=option_total+1, last_position=next_position;
    END LOOP;
    IF during_backfill THEN CLOSE committed_options;
    ELSE CLOSE current_options;
    END IF;

    UPDATE quiz_scenario_question_keys
    SET option_evidence=fingerprint, evidenced_option_count=option_total, last_option_position=last_position
    WHERE question_revision_id=revision_id;
END;

-- The normal writers insert options in ascending authored order. A single
-- fixed-width update extends the digest. Direct edits or out-of-order inserts
-- can rebuild the snapshot for that question.
CREATE TRIGGER scenario_option_evidence_insert AFTER INSERT ON question_option_revisions
FOR EACH ROW
BEGIN
    UPDATE quiz_scenario_question_keys
    SET option_evidence=UNHEX(SHA2(CONCAT(option_evidence,
            scenario_option_evidence(NEW.id, NEW.authored_position, NEW.source_key)), 256)),
        evidenced_option_count=evidenced_option_count+1, last_option_position=NEW.authored_position
    WHERE question_revision_id=NEW.question_revision_id
        AND option_evidence IS NOT NULL AND last_option_position<NEW.authored_position;
    IF ROW_COUNT()=0 THEN
        CALL scenario_rebuild_option_evidence(NEW.question_revision_id, FALSE);
    END IF;
END;

CREATE TRIGGER scenario_option_evidence_delete AFTER DELETE ON question_option_revisions
FOR EACH ROW CALL scenario_rebuild_option_evidence(OLD.question_revision_id, FALSE);

CREATE TRIGGER scenario_option_evidence_update AFTER UPDATE ON question_option_revisions
FOR EACH ROW
BEGIN
    IF OLD.question_revision_id<>NEW.question_revision_id THEN
        CALL scenario_rebuild_option_evidence(OLD.question_revision_id, FALSE);
    END IF;
    CALL scenario_rebuild_option_evidence(NEW.question_revision_id, FALSE);
END;

CREATE TRIGGER scenario_question_evidence_update AFTER UPDATE ON question_revisions
FOR EACH ROW
BEGIN
    -- Preserve the recorded proof across whitespace and object-order edits.
    IF scenario_question_evidence(OLD.question_id, OLD.schema_version, OLD.question_type, OLD.difficulty,
            OLD.question_text, OLD.content, OLD.hint, OLD.explanation, OLD.attachment_url,
            OLD.attachment_asset_id, OLD.created_at)
        <> scenario_question_evidence(NEW.question_id, NEW.schema_version, NEW.question_type, NEW.difficulty,
            NEW.question_text, IF(scenario_same_question_json(OLD.content, NEW.content), OLD.content, NEW.content), NEW.hint,
            NEW.explanation, NEW.attachment_url, NEW.attachment_asset_id, NEW.created_at) THEN
        UPDATE quiz_scenario_question_keys
        SET question_evidence=scenario_question_evidence(NEW.question_id, NEW.schema_version,
            NEW.question_type, NEW.difficulty, NEW.question_text, NEW.content, NEW.hint,
            NEW.explanation, NEW.attachment_url, NEW.attachment_asset_id, NEW.created_at)
        WHERE question_revision_id=NEW.id AND question_evidence IS NOT NULL;
    END IF;
END;

-- Existing items retain their direct foreign key. New scenario items refer to
-- the exact current digest, which also prevents subsequent changes to it.
ALTER TABLE quiz_revision_items
    ADD COLUMN scenario_content_evidence BINARY(32) NULL,
    ADD COLUMN legacy_question_revision_id BINARY(16) GENERATED ALWAYS AS
        (IF(scenario_content_evidence IS NULL, question_revision_id, NULL)) STORED,
    DROP FOREIGN KEY fk_revision_item_question,
    ADD CONSTRAINT fk_revision_item_legacy_question FOREIGN KEY (legacy_question_revision_id)
        REFERENCES question_revisions (id),
    ADD CONSTRAINT fk_revision_item_scenario_question FOREIGN KEY (scenario_content_evidence, question_revision_id)
        REFERENCES quiz_scenario_question_keys (content_evidence, question_revision_id);

-- Older ordinary writers insert all options before attaching an item. Publish
-- the proof here, so partial sequences never churn the fingerprint index.
CREATE TRIGGER scenario_ordinary_item_publish_evidence AFTER INSERT ON quiz_revision_items
FOR EACH ROW
BEGIN
    IF NEW.scenario_content_evidence IS NULL AND EXISTS (
        SELECT 1 FROM quiz_scenario_question_keys
        WHERE question_revision_id=NEW.question_revision_id AND option_evidence IS NOT NULL AND NOT evidence_ready
    ) THEN
        UPDATE quiz_scenario_question_keys SET evidence_ready=TRUE
        WHERE question_revision_id=NEW.question_revision_id AND NOT evidence_ready;
    END IF;
END;

CREATE TRIGGER scenario_item_evidence_requires_graph BEFORE INSERT ON quiz_revision_items
FOR EACH ROW
BEGIN
    IF NEW.scenario_content_evidence IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM quiz_revisions WHERE id=NEW.quiz_revision_id AND schema_version=4 FOR SHARE
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Question evidence requires a scenario revision';
    END IF;
END;

-- Start tracking new questions only after all option change triggers are installed.
CREATE TRIGGER scenario_question_evidence_init AFTER INSERT ON question_revisions
FOR EACH ROW FOLLOWS scenario_question_track_insert
    UPDATE quiz_scenario_question_keys SET option_evidence=UNHEX(SHA2('', 256)),
        question_evidence=scenario_question_evidence(NEW.question_id, NEW.schema_version, NEW.question_type, NEW.difficulty,
            NEW.question_text, NEW.content, NEW.hint, NEW.explanation, NEW.attachment_url,
            NEW.attachment_asset_id, NEW.created_at)
    WHERE question_revision_id=NEW.id;

CREATE TRIGGER scenario_question_evidence_guard BEFORE UPDATE ON quiz_scenario_question_keys
FOR EACH ROW
BEGIN
    IF OLD.option_count IS NOT NULL
        AND ((OLD.option_evidence IS NOT NULL AND
                (NOT (OLD.option_evidence <=> NEW.option_evidence)
                    OR OLD.evidenced_option_count<>NEW.evidenced_option_count
                    OR OLD.last_option_position<>NEW.last_option_position))
            OR (OLD.question_evidence IS NOT NULL AND NOT (OLD.question_evidence <=> NEW.question_evidence))) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Scenario question evidence is immutable';
    END IF;
END;

CREATE PROCEDURE scenario_backfill_question_evidence()
MODIFIES SQL DATA
BEGIN
    DECLARE done BOOLEAN DEFAULT FALSE;
    DECLARE revision_id BINARY(16);
    DECLARE question_hash BINARY(32);
    DECLARE pending CURSOR FOR
        SELECT question_revision_id FROM quiz_scenario_question_keys
        WHERE option_evidence IS NULL ORDER BY question_revision_id;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done=TRUE;

    OPEN pending;
    fill_evidence: LOOP
        FETCH pending INTO revision_id;
        IF done THEN LEAVE fill_evidence;
        END IF;
        -- This acquires the metadata lock before opening the options cursor.
        CALL scenario_rebuild_option_evidence(revision_id, TRUE);
        SET question_hash=(
            SELECT scenario_question_evidence(question_id, schema_version, question_type, difficulty,
                question_text, content, hint, explanation, attachment_url, attachment_asset_id, created_at)
            FROM question_revisions WHERE id=revision_id
        );
        UPDATE quiz_scenario_question_keys SET question_evidence=question_hash, evidence_ready=TRUE
        WHERE question_revision_id=revision_id AND question_evidence IS NULL;
    END LOOP;
    CLOSE pending;
END;

-- Once the tracking triggers are installed, only retained rows and publications
-- made during installation are missing evidence.
COMMIT;
SET TRANSACTION ISOLATION LEVEL READ COMMITTED;
START TRANSACTION;
CALL scenario_backfill_question_evidence();
COMMIT;
DROP PROCEDURE scenario_backfill_question_evidence;
