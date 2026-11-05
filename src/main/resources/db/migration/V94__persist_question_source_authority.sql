-- No attribution from mutable memberships: unverified legacy rows remain unknown.
ALTER TABLE questions ADD COLUMN source_owner_id BINARY(16) NULL;
ALTER TABLE questions ADD CONSTRAINT uk_question_source_identity UNIQUE (id, source_owner_id);

-- A committed authority references the exact owner pair. RESTRICT prevents changing/clearing
-- the question's owner without requiring CREATE TRIGGER/SUPER or trusting routine creators.
CREATE TABLE question_source_authorities (
    question_id BINARY(16) PRIMARY KEY,
    owner_id BINARY(16) NOT NULL,
    CONSTRAINT fk_question_source_identity FOREIGN KEY (question_id, owner_id)
        REFERENCES questions(id, source_owner_id) ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE question_source_confirmations (
    question_id BINARY(16) PRIMARY KEY,
    owner_id BINARY(16) NOT NULL,
    confirmed_by BINARY(16) NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    evidence VARCHAR(500) NOT NULL
) ENGINE=InnoDB;
-- No cascading ownership/evidence deletion with authoring associations or accounts.
