-- Derive evidence from stored rows so adoption can compare current content on the
-- existing metadata row. Publication references a matching digest instead of
-- reading or updating an existing question to seal it.
ALTER TABLE quiz_scenario_question_keys
    ADD COLUMN question_evidence BINARY(32) NULL,
    ADD COLUMN option_evidence JSON NULL,
    ADD COLUMN content_evidence BINARY(32) GENERATED ALWAYS AS (
        CASE WHEN JSON_LENGTH(option_evidence)=stored_option_count
            AND (option_count IS NULL OR option_count=stored_option_count)
        THEN UNHEX(SHA2(CONCAT(HEX(question_evidence), ':', CAST(option_evidence AS CHAR CHARACTER SET utf8mb4)), 256))
        END
    ) STORED,
    ADD CONSTRAINT chk_scenario_option_evidence CHECK (JSON_TYPE(option_evidence) = 'OBJECT'),
    ADD UNIQUE KEY uq_scenario_question_evidence (question_revision_id, content_evidence);

CREATE FUNCTION scenario_question_evidence(
    source_id BINARY(16), format_version SMALLINT, kind VARCHAR(50) CHARACTER SET utf8mb4,
    difficulty_name VARCHAR(20) CHARACTER SET utf8mb4, prompt VARCHAR(1000) CHARACTER SET utf8mb4,
    payload LONGTEXT CHARACTER SET utf8mb4, hint_text VARCHAR(500) CHARACTER SET utf8mb4,
    explanation_text VARCHAR(2000) CHARACTER SET utf8mb4, asset_url VARCHAR(2048) CHARACTER SET utf8mb4,
    asset_id BINARY(16), created DATETIME(6)
) RETURNS BINARY(32) DETERMINISTIC NO SQL
RETURN UNHEX(SHA2(CONCAT_WS(':', HEX(source_id), format_version,
    SHA2(kind, 256), SHA2(difficulty_name, 256), SHA2(prompt, 256), SHA2(payload, 256),
    COALESCE(SHA2(hint_text, 256), '-'), COALESCE(SHA2(explanation_text, 256), '-'),
    COALESCE(SHA2(asset_url, 256), '-'), COALESCE(HEX(asset_id), '-'),
    DATE_FORMAT(created, '%Y-%m-%d %H:%i:%s.%f')
), 256));

-- Each option hash encodes the decimal position, a colon and the exact UTF-8 key.
CREATE TRIGGER scenario_option_evidence_insert AFTER INSERT ON question_option_revisions
FOR EACH ROW
    UPDATE quiz_scenario_question_keys
    SET option_evidence=JSON_SET(option_evidence, CONCAT('$."', HEX(NEW.id), '"'),
        SHA2(CONCAT(NEW.authored_position, ':', NEW.source_key), 256))
    WHERE question_revision_id=NEW.question_revision_id AND option_evidence IS NOT NULL;

CREATE TRIGGER scenario_option_evidence_delete AFTER DELETE ON question_option_revisions
FOR EACH ROW
    UPDATE quiz_scenario_question_keys
    SET option_evidence=JSON_REMOVE(option_evidence, CONCAT('$."', HEX(OLD.id), '"'))
    WHERE question_revision_id=OLD.question_revision_id AND option_evidence IS NOT NULL;

CREATE TRIGGER scenario_option_evidence_update AFTER UPDATE ON question_option_revisions
FOR EACH ROW
BEGIN
    UPDATE quiz_scenario_question_keys
    SET option_evidence=JSON_REMOVE(option_evidence, CONCAT('$."', HEX(OLD.id), '"'))
    WHERE question_revision_id=OLD.question_revision_id AND option_evidence IS NOT NULL;

    UPDATE quiz_scenario_question_keys
    SET option_evidence=JSON_SET(option_evidence, CONCAT('$."', HEX(NEW.id), '"'),
        SHA2(CONCAT(NEW.authored_position, ':', NEW.source_key), 256))
    WHERE question_revision_id=NEW.question_revision_id AND option_evidence IS NOT NULL;
END;

CREATE TRIGGER scenario_question_evidence_update AFTER UPDATE ON question_revisions
FOR EACH ROW
BEGIN
    DECLARE evidence BINARY(32);
    SET evidence=scenario_question_evidence(NEW.question_id, NEW.schema_version, NEW.question_type, NEW.difficulty,
        NEW.question_text, NEW.content, NEW.hint, NEW.explanation, NEW.attachment_url,
        NEW.attachment_asset_id, NEW.created_at);
    UPDATE quiz_scenario_question_keys SET question_evidence=evidence
    WHERE question_revision_id=NEW.id AND question_evidence IS NOT NULL AND question_evidence<>evidence;
END;

-- Start tracking new questions only after all change triggers are installed.
CREATE TRIGGER scenario_question_evidence_init AFTER INSERT ON question_revisions
FOR EACH ROW FOLLOWS scenario_question_track_insert
    UPDATE quiz_scenario_question_keys SET option_evidence=JSON_OBJECT(),
        question_evidence=scenario_question_evidence(NEW.question_id, NEW.schema_version, NEW.question_type, NEW.difficulty,
            NEW.question_text, NEW.content, NEW.hint, NEW.explanation, NEW.attachment_url,
            NEW.attachment_asset_id, NEW.created_at)
    WHERE question_revision_id=NEW.id;

CREATE TRIGGER scenario_question_evidence_guard BEFORE UPDATE ON quiz_scenario_question_keys
FOR EACH ROW
BEGIN
    IF OLD.option_count IS NOT NULL
        AND ((OLD.option_evidence IS NOT NULL AND NOT (OLD.option_evidence <=> NEW.option_evidence))
            OR (OLD.question_evidence IS NOT NULL AND NOT (OLD.question_evidence <=> NEW.question_evidence))) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Scenario question evidence is immutable';
    END IF;
END;

-- Existing questions and publications made during trigger installation have NULL
-- evidence. At this point every new ordinary publication maintains evidence in
-- the same transaction as its options. Preserve earlier seals and option counts.
COMMIT;

SET TRANSACTION ISOLATION LEVEL READ COMMITTED;
START TRANSACTION;

INSERT INTO quiz_scenario_question_keys (question_revision_id, question_evidence, option_evidence)
SELECT pending.question_revision_id, pending.question_proof, pending.option_proof
FROM (
    SELECT k.question_revision_id,
        scenario_question_evidence(q.question_id, q.schema_version, q.question_type, q.difficulty,
            q.question_text, q.content, q.hint, q.explanation, q.attachment_url,
            q.attachment_asset_id, q.created_at) AS question_proof,
        COALESCE(evidence.options, JSON_OBJECT()) AS option_proof
    FROM quiz_scenario_question_keys k
    JOIN question_revisions q ON q.id=k.question_revision_id
    LEFT JOIN (
        SELECT question_revision_id,
            JSON_OBJECTAGG(HEX(id), SHA2(CONCAT(authored_position, ':', source_key), 256)) AS options
        FROM question_option_revisions
        GROUP BY question_revision_id
    ) evidence ON evidence.question_revision_id=k.question_revision_id
    WHERE k.option_evidence IS NULL
) pending
ON DUPLICATE KEY UPDATE
    question_evidence=COALESCE(quiz_scenario_question_keys.question_evidence, pending.question_proof),
    option_evidence=COALESCE(quiz_scenario_question_keys.option_evidence, pending.option_proof);

COMMIT;

-- Existing items retain their direct foreign key. New scenario items refer to
-- the exact current digest, which also prevents subsequent changes to it.
ALTER TABLE quiz_revision_items
    ADD COLUMN scenario_content_evidence BINARY(32) NULL,
    ADD COLUMN legacy_question_revision_id BINARY(16) GENERATED ALWAYS AS
        (IF(scenario_content_evidence IS NULL, question_revision_id, NULL)) STORED,
    DROP FOREIGN KEY fk_revision_item_question,
    ADD CONSTRAINT fk_revision_item_legacy_question FOREIGN KEY (legacy_question_revision_id)
        REFERENCES question_revisions (id),
    ADD CONSTRAINT fk_revision_item_scenario_question FOREIGN KEY (question_revision_id, scenario_content_evidence)
        REFERENCES quiz_scenario_question_keys (question_revision_id, content_evidence);

CREATE TRIGGER scenario_item_evidence_requires_graph BEFORE INSERT ON quiz_revision_items
FOR EACH ROW
BEGIN
    IF NEW.scenario_content_evidence IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM quiz_revisions WHERE id=NEW.quiz_revision_id AND schema_version=4 FOR SHARE
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Question evidence requires a scenario revision';
    END IF;
END;
