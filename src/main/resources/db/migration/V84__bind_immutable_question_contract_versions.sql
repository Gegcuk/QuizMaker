-- No inferred/backfilled versions. Existing unbound revisions remain explicit legacy/unconfigured input.
CREATE TABLE question_revision_contract_bindings (
    question_revision_id BINARY(16) NOT NULL PRIMARY KEY,
    response_contract_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_contract_version INT NOT NULL,
    scoring_contract_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scoring_contract_version INT NOT NULL,
    binding_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    CONSTRAINT fk_question_contract_revision FOREIGN KEY (question_revision_id) REFERENCES question_revisions(id),
    CONSTRAINT chk_response_contract_version CHECK (response_contract_version > 0),
    CONSTRAINT chk_scoring_contract_version CHECK (scoring_contract_version > 0)
) ENGINE=InnoDB;
