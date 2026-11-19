-- #647: immutable, Learning-owned forms. No lifecycle/schedule change or invented history.
CREATE TABLE learning_form (
    id BINARY(16) NOT NULL PRIMARY KEY,
    session_id BINARY(16) NOT NULL,
    quiz_revision_id BINARY(16) NOT NULL,
    schema_version SMALLINT NOT NULL,
    outcome VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_count INT NOT NULL,
    fingerprint_version SMALLINT NOT NULL,
    fingerprint BINARY(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_learning_form_session (session_id),
    UNIQUE KEY uq_learning_form_source (id,quiz_revision_id),
    CONSTRAINT fk_learning_form_session FOREIGN KEY (session_id) REFERENCES learning_session(id),
    CONSTRAINT fk_learning_form_revision FOREIGN KEY (quiz_revision_id) REFERENCES quiz_revisions(id),
    CONSTRAINT chk_learning_form_version CHECK (schema_version=1 AND fingerprint_version=1),
    CONSTRAINT chk_learning_form_outcome CHECK ((outcome='EMPTY_COMPLETE' AND item_count=0)
        OR (outcome='READY' AND item_count>0))
) ENGINE=InnoDB;

CREATE TABLE learning_form_item (
    form_id BINARY(16) NOT NULL,
    position INT NOT NULL,
    quiz_revision_id BINARY(16) NOT NULL,
    source_item_id BINARY(16) NOT NULL,
    question_revision_id BINARY(16) NOT NULL,
    question_schema_version SMALLINT NOT NULL,
    response_contract_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_contract_version INT NOT NULL,
    option_count INT NOT NULL,
    PRIMARY KEY (form_id,position),
    UNIQUE KEY uq_learning_form_item (form_id,source_item_id),
    CONSTRAINT fk_learning_form_item_form FOREIGN KEY (form_id,quiz_revision_id) REFERENCES learning_form(id,quiz_revision_id),
    CONSTRAINT fk_learning_form_item_source FOREIGN KEY (quiz_revision_id,source_item_id) REFERENCES quiz_revision_items(quiz_revision_id,id),
    CONSTRAINT fk_learning_form_item_question FOREIGN KEY (question_revision_id) REFERENCES question_revisions(id),
    CONSTRAINT chk_learning_form_item CHECK (position>=0 AND question_schema_version=1 AND response_contract_version>0 AND option_count>=0)
) ENGINE=InnoDB;

CREATE TABLE learning_form_option (
    form_id BINARY(16) NOT NULL,
    item_position INT NOT NULL,
    position INT NOT NULL,
    option_id BINARY(16) NOT NULL,
    PRIMARY KEY (form_id,item_position,position),
    UNIQUE KEY uq_learning_form_option (form_id,item_position,option_id),
    CONSTRAINT fk_learning_form_option_item FOREIGN KEY (form_id,item_position) REFERENCES learning_form_item(form_id,position),
    CONSTRAINT chk_learning_form_option CHECK (item_position>=0 AND position>=0)
) ENGINE=InnoDB;
-- No cascades to mutable accounts, no content/answers, no backfill or age cleanup.
-- Missing/inconsistent evidence fails closed; forward-fix rather than read-time repair.
