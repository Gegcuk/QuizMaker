-- Flat v1 stays unchanged. v2 explicitly requires sections; older readers reject it rather than flatten it.
-- No existing revision/attempt is backfilled or inferred from mutable authoring rows.
ALTER TABLE quiz_revisions
    ADD COLUMN section_count INT NULL,
    DROP CHECK chk_quiz_revision_version,
    ADD CONSTRAINT chk_quiz_revision_version CHECK
        ((schema_version = 1 AND section_count IS NULL)
         OR (schema_version = 2 AND section_count IS NOT NULL AND section_count > 0));

CREATE TABLE quiz_revision_sections (
    quiz_revision_id BINARY(16) NOT NULL,
    id BINARY(16) NOT NULL,
    schema_version SMALLINT NOT NULL,
    ordinal_slot INT NOT NULL,
    authored_position INT NOT NULL,
    title VARCHAR(100) NOT NULL,
    instructions VARCHAR(1000) NULL,
    item_count INT NOT NULL,
    PRIMARY KEY (quiz_revision_id, id),
    UNIQUE KEY uq_revision_section_slot (quiz_revision_id, ordinal_slot),
    UNIQUE KEY uq_revision_section_position (quiz_revision_id, authored_position),
    CONSTRAINT fk_revision_section_root FOREIGN KEY (quiz_revision_id) REFERENCES quiz_revisions(id),
    CONSTRAINT chk_revision_section_values CHECK
        (schema_version = 1 AND ordinal_slot >= 0 AND authored_position >= 0 AND item_count >= 0
         AND CHAR_LENGTH(TRIM(title)) > 0)
) ENGINE=InnoDB;

CREATE TABLE quiz_revision_section_items (
    quiz_revision_id BINARY(16) NOT NULL,
    section_id BINARY(16) NOT NULL,
    item_id BINARY(16) NOT NULL,
    ordinal_slot INT NOT NULL,
    PRIMARY KEY (quiz_revision_id, item_id),
    UNIQUE KEY uq_revision_section_item_slot (quiz_revision_id, section_id, ordinal_slot),
    CONSTRAINT fk_revision_section_item_section FOREIGN KEY (quiz_revision_id, section_id)
        REFERENCES quiz_revision_sections(quiz_revision_id, id),
    CONSTRAINT fk_revision_section_item_source FOREIGN KEY (quiz_revision_id, item_id)
        REFERENCES quiz_revision_items(quiz_revision_id, id),
    CONSTRAINT chk_revision_section_item_slot CHECK (ordinal_slot >= 0)
) ENGINE=InnoDB;

-- Fixed slots seal complete aggregates against later inserts, even when authored positions have gaps.
CREATE TRIGGER revision_section_insert_guard BEFORE INSERT ON quiz_revision_sections
FOR EACH ROW
BEGIN
    DECLARE slots INT;
    SELECT section_count INTO slots FROM quiz_revisions
        WHERE id = NEW.quiz_revision_id AND schema_version = 2;
    IF slots IS NULL OR NEW.ordinal_slot < 0 OR NEW.ordinal_slot >= slots THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid immutable section slot';
    END IF;
END;

CREATE TRIGGER revision_section_item_insert_guard BEFORE INSERT ON quiz_revision_section_items
FOR EACH ROW
BEGIN
    DECLARE slots INT;
    SELECT item_count INTO slots FROM quiz_revision_sections
        WHERE quiz_revision_id = NEW.quiz_revision_id AND id = NEW.section_id;
    IF slots IS NULL OR NEW.ordinal_slot < 0 OR NEW.ordinal_slot >= slots THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid immutable section item slot';
    END IF;
END;

CREATE TRIGGER structured_revision_no_update BEFORE UPDATE ON quiz_revisions
FOR EACH ROW
BEGIN
    IF OLD.schema_version = 2 OR NEW.schema_version = 2 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Structured revision is immutable';
    END IF;
END;

CREATE TRIGGER structured_revision_no_delete BEFORE DELETE ON quiz_revisions
FOR EACH ROW
BEGIN
    IF OLD.schema_version = 2 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Structured revision is immutable';
    END IF;
END;

CREATE TRIGGER revision_section_no_update BEFORE UPDATE ON quiz_revision_sections
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Revision section is immutable';
CREATE TRIGGER revision_section_no_delete BEFORE DELETE ON quiz_revision_sections
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Revision section is immutable';
CREATE TRIGGER revision_section_item_no_update BEFORE UPDATE ON quiz_revision_section_items
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Revision section membership is immutable';
CREATE TRIGGER revision_section_item_no_delete BEFORE DELETE ON quiz_revision_section_items
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Revision section membership is immutable';
