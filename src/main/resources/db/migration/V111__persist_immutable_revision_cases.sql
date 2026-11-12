-- v1 flat and v2 section-only evidence is retained verbatim; cases require explicit v3.
ALTER TABLE quiz_revisions
    ADD COLUMN case_count INT NULL,
    DROP CHECK chk_quiz_revision_version,
    ADD CONSTRAINT chk_quiz_revision_version CHECK
        ((schema_version = 1 AND section_count IS NULL AND case_count IS NULL)
         OR (schema_version = 2 AND section_count > 0 AND section_count IS NOT NULL AND case_count IS NULL)
         OR (schema_version = 3 AND section_count > 0 AND section_count IS NOT NULL
             AND case_count > 0 AND case_count IS NOT NULL));

-- The complete section/item tuple is an explicit unique FK target on MySQL 8.4 too.
ALTER TABLE quiz_revision_section_items
    ADD UNIQUE KEY uq_revision_section_member (quiz_revision_id, section_id, item_id);

CREATE TABLE quiz_revision_cases (
    quiz_revision_id BINARY(16) NOT NULL,
    id BINARY(16) NOT NULL,
    section_id BINARY(16) NOT NULL,
    schema_version SMALLINT NOT NULL,
    ordinal_slot INT NOT NULL,
    authored_position INT NOT NULL,
    stimulus_revision_id BINARY(16) NOT NULL,
    atomic_selection BOOLEAN NOT NULL,
    item_count INT NOT NULL,
    PRIMARY KEY (quiz_revision_id, id),
    UNIQUE KEY uq_revision_case_slot (quiz_revision_id, ordinal_slot),
    UNIQUE KEY uq_revision_case_position (quiz_revision_id, section_id, authored_position),
    UNIQUE KEY uq_revision_case_section (quiz_revision_id, id, section_id),
    CONSTRAINT fk_revision_case_section FOREIGN KEY (quiz_revision_id, section_id)
        REFERENCES quiz_revision_sections(quiz_revision_id, id),
    CONSTRAINT chk_revision_case_values CHECK
        (schema_version = 1 AND ordinal_slot >= 0 AND authored_position >= 0
         AND atomic_selection IN (0, 1) AND item_count > 0)
) ENGINE=InnoDB;

CREATE TABLE quiz_revision_case_items (
    quiz_revision_id BINARY(16) NOT NULL,
    case_id BINARY(16) NOT NULL,
    section_id BINARY(16) NOT NULL,
    item_id BINARY(16) NOT NULL,
    ordinal_slot INT NOT NULL,
    PRIMARY KEY (quiz_revision_id, item_id),
    UNIQUE KEY uq_revision_case_item_slot (quiz_revision_id, case_id, ordinal_slot),
    CONSTRAINT fk_revision_case_item_case FOREIGN KEY (quiz_revision_id, case_id, section_id)
        REFERENCES quiz_revision_cases(quiz_revision_id, id, section_id),
    CONSTRAINT fk_revision_case_item_section FOREIGN KEY (quiz_revision_id, section_id, item_id)
        REFERENCES quiz_revision_section_items(quiz_revision_id, section_id, item_id),
    CONSTRAINT chk_revision_case_item_slot CHECK (ordinal_slot >= 0)
) ENGINE=InnoDB;

-- Count/slot guards seal complete aggregates against late inserts, not just updates/deletes.
CREATE TRIGGER revision_case_insert_guard BEFORE INSERT ON quiz_revision_cases
FOR EACH ROW
BEGIN
    DECLARE slots INT;
    SELECT case_count INTO slots FROM quiz_revisions
        WHERE id = NEW.quiz_revision_id AND schema_version = 3;
    IF slots IS NULL OR NEW.ordinal_slot < 0 OR NEW.ordinal_slot >= slots THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid immutable case slot';
    END IF;
END;

CREATE TRIGGER revision_case_item_insert_guard BEFORE INSERT ON quiz_revision_case_items
FOR EACH ROW
BEGIN
    DECLARE slots INT;
    SELECT item_count INTO slots FROM quiz_revision_cases
        WHERE quiz_revision_id = NEW.quiz_revision_id AND id = NEW.case_id;
    IF slots IS NULL OR NEW.ordinal_slot < 0 OR NEW.ordinal_slot >= slots THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid immutable case member slot';
    END IF;
END;

DROP TRIGGER revision_section_insert_guard;
CREATE TRIGGER revision_section_insert_guard BEFORE INSERT ON quiz_revision_sections
FOR EACH ROW
BEGIN
    DECLARE slots INT;
    SELECT section_count INTO slots FROM quiz_revisions
        WHERE id = NEW.quiz_revision_id AND schema_version IN (2, 3);
    IF slots IS NULL OR NEW.ordinal_slot < 0 OR NEW.ordinal_slot >= slots THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid immutable section slot';
    END IF;
END;

DROP TRIGGER structured_revision_no_update;
CREATE TRIGGER structured_revision_no_update BEFORE UPDATE ON quiz_revisions
FOR EACH ROW
BEGIN
    IF OLD.schema_version >= 2 OR NEW.schema_version >= 2 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Structured revision is immutable';
    END IF;
END;

DROP TRIGGER structured_revision_no_delete;
CREATE TRIGGER structured_revision_no_delete BEFORE DELETE ON quiz_revisions
FOR EACH ROW
BEGIN
    IF OLD.schema_version >= 2 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Structured revision is immutable';
    END IF;
END;

CREATE TRIGGER revision_case_no_update BEFORE UPDATE ON quiz_revision_cases
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Revision case is immutable';
CREATE TRIGGER revision_case_no_delete BEFORE DELETE ON quiz_revision_cases
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Revision case is immutable';
CREATE TRIGGER revision_case_item_no_update BEFORE UPDATE ON quiz_revision_case_items
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Revision case membership is immutable';
CREATE TRIGGER revision_case_item_no_delete BEFORE DELETE ON quiz_revision_case_items
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Revision case membership is immutable';
