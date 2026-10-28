-- Additive/dark. Do not invent option order for existing forms or legacy attempts.
-- Retained rows are recovered by forward-fix; no destructive downgrade/cascade.
ALTER TABLE quiz_revision_items ADD UNIQUE KEY uq_revision_item_question (quiz_revision_id,id,question_revision_id);
ALTER TABLE attempt_form_item ADD UNIQUE KEY uq_form_item_source_binding (form_id,id,quiz_revision_id,source_item_id);
ALTER TABLE question_revision_contract_bindings ADD UNIQUE KEY uq_question_contract_exact
    (question_revision_id,response_contract_id,response_contract_version,scoring_contract_id,scoring_contract_version);

CREATE TABLE attempt_form_option_order (
    form_id BINARY(16) NOT NULL PRIMARY KEY,
    schema_version SMALLINT NOT NULL,
    strategy VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    algorithm_version INT NOT NULL,
    item_count INT NOT NULL,
    CONSTRAINT fk_option_order_form FOREIGN KEY (form_id) REFERENCES attempt_form(id),
    CONSTRAINT chk_option_order_schema CHECK (schema_version=1),
    CONSTRAINT chk_option_order_strategy CHECK (strategy IN ('AUTHORED','SHUFFLED_PER_ATTEMPT')),
    CONSTRAINT chk_option_order_algorithm CHECK (algorithm_version=1),
    CONSTRAINT chk_option_order_count CHECK (item_count>=0)
) ENGINE=InnoDB;

CREATE TABLE attempt_form_item_option_order (
    form_id BINARY(16) NOT NULL,
    item_id BINARY(16) NOT NULL,
    quiz_revision_id BINARY(16) NOT NULL,
    source_item_id BINARY(16) NOT NULL,
    question_revision_id BINARY(16) NOT NULL,
    response_contract_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_contract_version INT NOT NULL,
    scoring_contract_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scoring_contract_version INT NOT NULL,
    option_count INT NOT NULL,
    PRIMARY KEY (form_id,item_id),
    UNIQUE KEY uq_form_option_question (form_id,item_id,question_revision_id),
    CONSTRAINT fk_item_option_order_root FOREIGN KEY (form_id) REFERENCES attempt_form_option_order(form_id),
    CONSTRAINT fk_item_option_order_source FOREIGN KEY (form_id,item_id,quiz_revision_id,source_item_id)
        REFERENCES attempt_form_item(form_id,id,quiz_revision_id,source_item_id),
    CONSTRAINT fk_item_option_order_question FOREIGN KEY (quiz_revision_id,source_item_id,question_revision_id)
        REFERENCES quiz_revision_items(quiz_revision_id,id,question_revision_id),
    CONSTRAINT fk_item_option_order_contract FOREIGN KEY
        (question_revision_id,response_contract_id,response_contract_version,scoring_contract_id,scoring_contract_version)
        REFERENCES question_revision_contract_bindings
        (question_revision_id,response_contract_id,response_contract_version,scoring_contract_id,scoring_contract_version),
    CONSTRAINT chk_item_option_count CHECK (option_count>=0)
) ENGINE=InnoDB;

CREATE TABLE attempt_form_option (
    form_id BINARY(16) NOT NULL,
    item_id BINARY(16) NOT NULL,
    question_revision_id BINARY(16) NOT NULL,
    option_id BINARY(16) NOT NULL,
    position INT NOT NULL,
    PRIMARY KEY (form_id,item_id,option_id),
    UNIQUE KEY uq_form_option_position (form_id,item_id,position),
    CONSTRAINT fk_form_option_item FOREIGN KEY (form_id,item_id,question_revision_id)
        REFERENCES attempt_form_item_option_order(form_id,item_id,question_revision_id),
    CONSTRAINT fk_form_option_identity FOREIGN KEY (question_revision_id,option_id)
        REFERENCES question_option_revisions(question_revision_id,id),
    CONSTRAINT chk_form_option_position CHECK (position>=0)
) ENGINE=InnoDB;
