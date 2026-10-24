-- Dark identity storage only. Do not backfill or rebind legacy attempts.
CREATE TABLE attempt_form (
    id BINARY(16) NOT NULL PRIMARY KEY,
    schema_version SMALLINT NOT NULL,
    quiz_revision_id BINARY(16) NOT NULL,
    item_count INT NOT NULL,
    UNIQUE KEY uq_form_revision (id, quiz_revision_id),
    INDEX ix_form_quiz_revision (quiz_revision_id, id),
    CONSTRAINT fk_form_quiz_revision FOREIGN KEY (quiz_revision_id) REFERENCES quiz_revisions(id),
    CONSTRAINT chk_form_version CHECK (schema_version = 1),
    CONSTRAINT chk_form_count CHECK (item_count >= 0)
) ENGINE=InnoDB;

CREATE TABLE attempt_form_item (
    form_id BINARY(16) NOT NULL,
    id BINARY(16) NOT NULL,
    quiz_revision_id BINARY(16) NOT NULL,
    source_item_id BINARY(16) NOT NULL,
    position INT NOT NULL,
    PRIMARY KEY (form_id, id),
    UNIQUE KEY uq_form_item_source (form_id, source_item_id),
    UNIQUE KEY uq_form_item_position (form_id, position),
    INDEX ix_form_item_revision (form_id, quiz_revision_id),
    INDEX ix_form_item_source_revision (quiz_revision_id, source_item_id),
    CONSTRAINT fk_form_item_form FOREIGN KEY (form_id, quiz_revision_id)
        REFERENCES attempt_form(id, quiz_revision_id),
    CONSTRAINT fk_form_item_source FOREIGN KEY (quiz_revision_id, source_item_id)
        REFERENCES quiz_revision_items(quiz_revision_id, id),
    CONSTRAINT chk_form_item_position CHECK (position >= 0)
) ENGINE=InnoDB;
